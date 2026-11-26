"""Restore a search helper omitted by an older server updater."""
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import tempfile

MAX_HELPER = 256 * 1024


def installed_file(path, limit):
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as file:
        info = os.fstat(file.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022:
            raise ValueError("Unsafe installation file")
        data = file.read(limit + 1)
        if len(data) > limit:
            raise ValueError("Installation file is too large")
        return data


def restore(current, releases, staged, supplied, helper='jacred.py'):
    if helper not in ('jacred.py', 'prowlarr.py'):
        return 'invalid_installation'
    release = current.resolve(strict=True)
    if release.parent != releases.resolve(strict=True) or not (release / '.mjwatch-owned').is_file():
        return 'invalid_installation'
    # The installer copied this manifest into a root-owned release directory
    # after verifying its signature and file hashes.
    info = release.stat()
    if info.st_uid != os.geteuid() or info.st_mode & 0o022:
        return 'invalid_installation'
    manifest = json.loads(installed_file(release / 'manifest.json', 128 * 1024))
    if manifest.get('version') != release.name:
        return 'invalid_installation'
    entry = next((item for item in manifest['files'] if item.get('path') == helper), None)
    if entry is None:
        return 'update_required'

    # An interrupted upgrade can leave the original verified upload on the host.
    # The client can supply the identical file if the upload has since been cleared.
    try:
        original = installed_file(staged, MAX_HELPER)
    except (OSError, ValueError):
        original = b''
    for data in (original, supplied):
        if not data or len(data) > MAX_HELPER or len(data) != entry.get('size'):
            continue
        if hashlib.sha256(data).hexdigest() != entry.get('sha256'):
            continue
        with tempfile.NamedTemporaryFile(dir=release, delete=False) as file:
            temporary = Path(file.name)
            try:
                os.fchmod(file.fileno(), 0o700)
                file.write(data)
                file.flush()
                os.replace(temporary, release / helper)
            finally:
                temporary.unlink(missing_ok=True)
        return 'ready' if current.resolve() == release else 'release_changed'
    return 'helper_unavailable'


if __name__ == '__main__':
    if os.geteuid() != 0:
        raise SystemExit(1)
    try:
        helper = sys.argv[1] if len(sys.argv) > 1 else 'jacred.py'
        print(restore(Path('/opt/mjwatch/current'), Path('/opt/mjwatch/releases'),
                      Path('/var/lib/mjwatch-install/upload') / helper,
                      sys.stdin.buffer.read(MAX_HELPER + 1), helper))
    except (OSError, ValueError, KeyError, TypeError):
        print('invalid_installation')
