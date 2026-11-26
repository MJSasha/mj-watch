#!/usr/bin/env python3
"""Check the release bundle and bind it to the current workflow run and SHA."""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server' / 'packaging'))
from update_supply import MAX_DOWNLOAD_SIZE, MAX_UNPACKED_SIZE


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def digest(path):
    with path.open('rb') as data:
        return hashlib.file_digest(data, 'sha256').hexdigest()


def verify_supply(out, supply, channel):
    require((out / supply).stat().st_size <= MAX_DOWNLOAD_SIZE, 'Server ZIP exceeds the HTTPS download limit')
    with ZipFile(out / supply) as archive, TemporaryDirectory() as tmp:
        names = ('manifest.json', 'manifest.sig', 'release-public.pem')
        for name in names:
            (Path(tmp) / name).write_bytes(archive.read(name))
        subprocess.run(['openssl', 'dgst', '-sha256', '-verify', names[2], '-signature', names[1], names[0]],
                       check=True, cwd=tmp, stdout=sys.stderr)
        manifest = json.loads(archive.read(names[0]))
        require(manifest['channel'] == channel and supply == f"mjwatch-{manifest['version']}-supply.zip",
                'Wrong server supply channel or version')
        entries = {entry['path']: (entry['size'], entry['sha256']) for entry in manifest['files']}
        require(len(entries) == len(manifest['files']), 'Duplicate server manifest entry')
        require(sum(size for size, _ in entries.values()) <= MAX_UNPACKED_SIZE,
                'Server payload exceeds the HTTPS unpack limit')
        for name in names:
            data = archive.read(name)
            entries[name] = (len(data), hashlib.sha256(data).hexdigest())
        require(sorted(archive.namelist()) == sorted(entries), 'Wrong server ZIP file set')
        for name, (size, checksum) in entries.items():
            with archive.open(name) as data:
                require(archive.getinfo(name).file_size == size and hashlib.file_digest(data, 'sha256').hexdigest() == checksum,
                        f'Invalid server supply entry: {name}')
    prefix = 'assets/server/'
    for platform in ('mobile', 'tv'):
        with ZipFile(out / f'MJWatch-{platform}.apk') as apk:
            embedded = [name[len(prefix):] for name in apk.namelist() if name.startswith(prefix) and not name.endswith('/')]
            require(sorted(embedded) == sorted(entries), f'{platform}: wrong embedded server file set')
            for name, (size, checksum) in entries.items():
                with apk.open(prefix + name) as data:
                    require(apk.getinfo(prefix + name).file_size == size and hashlib.file_digest(data, 'sha256').hexdigest() == checksum,
                            f'{platform}: incorrect embedded server file {name}')


out, channel = Path(sys.argv[1]), sys.argv[2]
run_id, sha = os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_SHA']
metadata = out / 'release-identity.json'
if sys.argv[3:] == ['--prepare']:
    manifest = json.loads(Path('core/src/main/assets/server/manifest.json').read_text())
    require(re.fullmatch(r'\d+\.\d+\.\d+', manifest['version']), 'Invalid server version')
    supply = f"mjwatch-{manifest['version']}-supply.zip"
    shutil.copyfile(Path('dist/server') / supply, out / supply)
    with (out / 'SHA256SUMS.txt').open('a') as sums:
        sums.write(f'{digest(out / supply)}  {supply}\n')
    verify_supply(out, supply, channel)
    metadata.write_text(json.dumps(dict(commit=sha, runId=run_id, channel=channel, serverSupply=supply)) + '\n')

identity = json.loads(metadata.read_text())
require(identity['commit'] == sha and identity['runId'] == run_id, 'Bundle belongs to another commit or workflow run')
require(identity['channel'] == channel, 'Unexpected release signing channel')
supply = identity['serverSupply']
require(re.fullmatch(r'mjwatch-\d+\.\d+\.\d+-supply\.zip', supply), 'Invalid server supply name')
assets = {f'MJWatch-{platform}.apk' for platform in ('mobile', 'tv')} | {supply}
require({p.name for p in out.iterdir()} == assets | {'update.json', 'SHA256SUMS.txt', metadata.name},
        'Unexpected release file set')
sums = {}
for line in (out / 'SHA256SUMS.txt').read_text().splitlines():
    checksum, name = line.split('  ', 1)
    require(name in assets and name not in sums and digest(out / name) == checksum, f'Invalid checksum for {name}')
    sums[name] = checksum
require(set(sums) == assets, 'Missing asset checksums')

run = int(os.environ['GITHUB_RUN_NUMBER'])
base = f'https://github.com/{os.environ["GITHUB_REPOSITORY"]}/releases'
version = next(line.split('=', 1)[1] for line in Path('gradle.properties').read_text().splitlines()
               if line.startswith('mjwatchVersionName='))
update = json.loads((out / 'update.json').read_text())
descriptor = dict(schemaVersion=1, channel='testing', versionCode=1000 + run, versionName=version,
                  releaseUrl=f'{base}/tag/test-{run}')
require(all(update.get(key) == value for key, value in descriptor.items()), 'Update metadata mismatch')
require(set(update['packages']) == {'mobile', 'tv'}, 'Unexpected update packages')
for platform, package in update['packages'].items():
    apk = f'MJWatch-{platform}.apk'
    require(package == dict(packageName=f'dev.mjwatch.{platform}', url=f'{base}/download/test-{run}/{apk}',
                            sha256=sums[apk], size=(out / apk).stat().st_size), f'Update mismatch for {platform}')
print(supply)
