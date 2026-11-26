"""Retrieve a signed MJWatch supply in a private staging directory."""
import hashlib
import json
import pathlib
import re
import subprocess
import time
import urllib.request
import zipfile

MAX_SUPPLY = 128 << 20
GITHUB = 'https://github.com/MJSasha/mj-watch/releases/latest/download/'
PUBLIC_KEY = '/etc/mjwatch/release-public.pem'


def stage_latest(directory, version):
    path = directory / 'supply.zip'
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version):
        raise RuntimeError('incompatible_release')
    limit = MAX_SUPPLY
    deadline = time.monotonic() + 180
    try:
        with urllib.request.urlopen(GITHUB + f'mjwatch-{version}-supply.zip', timeout=15) as source, path.open('wb') as target:
            while block := source.read(1 << 20):
                limit -= len(block)
                if limit < 0 or time.monotonic() > deadline:
                    raise RuntimeError('supply_limit')
                target.write(block)
        unpack(path, directory, version, PUBLIC_KEY)
    except (OSError, zipfile.BadZipFile, KeyError, ValueError, subprocess.SubprocessError) as error:
        raise RuntimeError('supply_unavailable_or_invalid') from error
    finally:
        path.unlink(missing_ok=True)


def unpack(path, directory, version, public_key):
    with zipfile.ZipFile(path) as archive:
        for name, limit in (('manifest.json', 65536), ('manifest.sig', 4096)):
            if archive.getinfo(name).file_size > limit:
                raise RuntimeError('supply_integrity')
            (directory / name).write_bytes(archive.read(name))
        subprocess.run(['openssl', 'dgst', '-sha256', '-verify', str(public_key), '-signature',
                        str(directory / 'manifest.sig'), str(directory / 'manifest.json')],
                       check=True, capture_output=True, timeout=10)
        manifest = json.loads((directory / 'manifest.json').read_text())
        if manifest.get('version') != version or manifest.get('channel') != 'release':
            raise RuntimeError('incompatible_release')
        size = 0
        for entry in manifest['files']:
            name = entry['path']
            relative = pathlib.PurePosixPath(name)
            if relative.is_absolute() or '..' in relative.parts or str(relative) != name:
                raise RuntimeError('supply_integrity')
            info = archive.getinfo(name)
            size += info.file_size
            if info.file_size != entry['size'] or size > MAX_SUPPLY:
                raise RuntimeError('supply_integrity')
            output = directory / name
            output.parent.mkdir(parents=True, exist_ok=True)
            digest = hashlib.sha256()
            with archive.open(info) as source, output.open('xb') as target:
                while block := source.read(1 << 20):
                    digest.update(block)
                    target.write(block)
            if digest.hexdigest() != entry['sha256']:
                raise RuntimeError('supply_integrity')
