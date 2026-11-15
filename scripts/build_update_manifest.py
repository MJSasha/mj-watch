#!/usr/bin/env python3
"""Create an immutable, public testing-channel descriptor from signed APKs."""
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--dist', type=Path, required=True)
p.add_argument('--tag', required=True)
p.add_argument('--version-code', type=int, required=True)
p.add_argument('--version-name', required=True)
a = p.parse_args()
if not a.tag.startswith('test-') or not a.tag[5:].isdigit() or not 0 < a.version_code < 2**31:
    p.error('Invalid testing version')
base = 'https://github.com/MJSasha/mj-watch/releases'
packages = {}
checksums = []
for platform in ('mobile', 'tv'):
    apk = a.dist / f'MJWatch-{platform}.apk'
    hasher = hashlib.sha256()
    with apk.open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            hasher.update(chunk)
    digest = hasher.hexdigest()
    packages[platform] = dict(packageName=f'dev.mjwatch.{platform}', url=f'{base}/download/{a.tag}/{apk.name}', sha256=digest, size=apk.stat().st_size)
    checksums.append(f'{digest}  {apk.name}')
manifest = dict(schemaVersion=1, channel='testing', versionCode=a.version_code, versionName=a.version_name,
                publishedAt=datetime.now(timezone.utc).isoformat(), releaseUrl=f'{base}/tag/{a.tag}', packages=packages)
(a.dist / 'update.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
(a.dist / 'SHA256SUMS.txt').write_text('\n'.join(checksums) + '\n')
