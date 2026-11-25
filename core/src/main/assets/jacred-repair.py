"""Restore the helper omitted by the 0.1.13 updater when installing 0.1.14."""
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


def restore(current, releases, staged, supplied):
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
    entry = next((item for item in manifest['files'] if item.get('path') == 'jacred.py'), None)
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
                os.replace(temporary, release / 'jacred.py')
            finally:
                temporary.unlink(missing_ok=True)
        return 'ready' if current.resolve() == release else 'release_changed'
    return 'helper_unavailable'


if __name__ == '__main__':
    if os.geteuid() != 0:
        raise SystemExit(1)
    try:
        print(restore(Path('/opt/mjwatch/current'), Path('/opt/mjwatch/releases'),
                      Path('/var/lib/mjwatch-install/upload/jacred.py'), sys.stdin.buffer.read(MAX_HELPER + 1)))
    except (OSError, ValueError, KeyError, TypeError):
        print('invalid_installation')
