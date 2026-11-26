#!/usr/bin/env python3
"""One-time local upgrade from a release without the HTTPS management socket."""
from getpass import getpass
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import uuid

from update_supply import stage_latest


def main():
    if os.geteuid() != 0 or len(sys.argv) != 2 or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', sys.argv[1]):
        raise SystemExit('Run as root with the published MJWatch server version.')
    root = Path('/var/lib/mjwatch-install')
    if root.is_symlink() or root.stat().st_uid != 0 or not (root / 'owned').is_file():
        raise SystemExit('No managed MJWatch installation found.')
    version = sys.argv[1]
    passphrase = getpass('Backup passphrase (16 or more characters): ')
    if len(passphrase) < 16 or len(passphrase) > 1024 or passphrase != getpass('Repeat backup passphrase: '):
        raise SystemExit('Backup passphrase is invalid or does not match.')
    if input(f'Install signed server {version}? Current playback can briefly stop. [yes/no] ') != 'yes':
        return
    with tempfile.TemporaryDirectory(prefix='https-bootstrap-', dir=root) as stage:
        supply = Path(stage)
        stage_latest(supply, version)
        if not (supply / 'management.py').is_file() or not (supply / 'update_supply.py').is_file():
            raise SystemExit('The signed release does not provide HTTPS management.')
        request = {'action': 'update', 'confirmed': True, 'operationId': str(uuid.uuid4()),
                   'passphrase': passphrase}
        subprocess.run(['/usr/bin/python3', '-B', str(supply / 'maintenance.py')],
                       input=json.dumps(request), text=True, check=True,
                       env={**os.environ, 'MJWATCH_BOOTSTRAP_SUPPLY': stage})


if __name__ == '__main__':
    main()
