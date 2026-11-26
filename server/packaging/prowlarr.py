#!/usr/bin/env python3
"""Install and select the managed Prowlarr service over owner SSH."""
import grp
import hashlib
import json
import os
from pathlib import Path
import platform
import pwd
import secrets
import socket
import subprocess
import sys
import tempfile
import time
from urllib.request import ProxyHandler, Request, build_opener

from jacred import atomic, load

VERSION = '3.1.0.5799'
ASSETS = {
    'x86_64': ('x64', 'e9c66fd3d2c5e94ca51e2495eb9e99ee16004dc396cac4e74905d1408fe31767'),
    'aarch64': ('arm64', '1e74262cf955ee60b8091d100a109d66402e5786ede5f3b68fa77347d562174a'),
}
USER = 'mjwatch-prowlarr'
PORT = 19696
CONTROL = Path('/var/lib/mjwatch-install/prowlarr')
ROOT = Path('/opt/mjwatch-prowlarr')
BINARY = ROOT / VERSION / 'Prowlarr'
UNIT = Path(f'/etc/systemd/system/{USER}.service')
ENV = CONTROL / 'environment'
OPERATION = CONTROL / 'status.json'
ACCESS = Path('/etc/mjwatch/search-provider.json')
JOB = 'mjwatch-prowlarr-install.service'


def systemctl(*args):
    return subprocess.run(('systemctl', *args), capture_output=True, timeout=40)


def active(unit):
    return systemctl('is-active', '--quiet', str(unit)).returncode == 0


def report(state, code=''):
    atomic(OPERATION, json.dumps(dict(state=state, code=code)))


def key():
    return ENV.read_text().strip().split('=', 1)[1]


def indexers():
    request = Request(f'http://127.0.0.1:{PORT}/api/v1/indexer', headers={'X-Api-Key': key()})
    with build_opener(ProxyHandler({})).open(request, timeout=3) as response:
        return json.loads(response.read(1 << 20))


def status():
    installed = BINARY.is_file() and UNIT.is_file()
    running = installed and active(UNIT.name)
    ready = False
    configured = False
    if running:
        try:
            trackers = indexers()
            ready = True
            configured = any(item.get('enable') and item.get('supportsSearch')
                             and item.get('protocol') == 'torrent' for item in trackers)
        except (OSError, ValueError):
            pass
    operation = load(OPERATION, {})
    if systemctl('show', '-p', 'ActiveState', '--value', JOB).stdout.strip() in (b'active', b'activating'):
        operation = dict(state='running')
    elif operation.get('state') == 'running':
        operation = dict(state='failed', code='interrupted')
    return dict(installed=installed, running=running, ready=ready, configured=configured,
                provider=load(ACCESS, {}).get('provider', 'jacred'), operation=operation)


def install():
    report('running')
    try:
        asset = ASSETS.get(platform.machine())
        if not asset:
            raise RuntimeError('unsupported_architecture')
        if not ENV.exists():
            if any(os.path.lexists(path) for path in (UNIT, ROOT, Path(f'/var/lib/{USER}'))):
                raise RuntimeError('existing_service_conflict')
            try:
                pwd.getpwnam(USER)
            except KeyError:
                pass
            else:
                raise RuntimeError('existing_service_conflict')
            with socket.socket() as listener:
                try:
                    listener.bind(('127.0.0.1', PORT))
                except OSError as error:
                    raise RuntimeError('port_in_use') from error
            atomic(ENV, 'PROWLARR__AUTH__APIKEY=' + secrets.token_hex(32) + '\n')
        try:
            pwd.getpwnam(USER)
        except KeyError:
            subprocess.run(('useradd', '--system', '--user-group', '--no-create-home',
                            '--shell', '/usr/sbin/nologin', USER), check=True, timeout=15)
        if not Path('/usr/bin/sqlite3').is_file():
            try:
                subprocess.run(('apt-get', 'install', '-y', 'sqlite3'), check=True, timeout=90,
                               env={**os.environ, 'DEBIAN_FRONTEND': 'noninteractive'})
            except subprocess.SubprocessError as error:
                raise RuntimeError('dependency_install_failed') from error
        ROOT.mkdir(mode=0o755, exist_ok=True)
        if not BINARY.is_file():
            arch, checksum = asset
            name = f'Prowlarr.master.{VERSION}.linux-core-{arch}.tar.gz'
            url = f'https://github.com/Prowlarr/Prowlarr/releases/download/v{VERSION}/{name}'
            with tempfile.TemporaryDirectory(dir=ROOT) as temp:
                staging = Path(temp)
                archive = staging / name
                try:
                    subprocess.run(('curl', '--fail', '--silent', '--show-error', '--location',
                                    '--proto', '=https', '--max-time', '240', '--max-filesize', str(128 << 20),
                                    '--output', archive, url), check=True, timeout=245)
                except subprocess.SubprocessError as error:
                    raise RuntimeError('download_failed') from error
                digest = hashlib.sha256()
                with archive.open('rb') as stream:
                    for chunk in iter(lambda: stream.read(1 << 20), b''):
                        digest.update(chunk)
                if digest.hexdigest() != checksum:
                    raise RuntimeError('checksum_mismatch')
                subprocess.run(('tar', '-xzf', archive, '-C', staging, '--no-same-owner',
                                '--no-same-permissions'), check=True, timeout=35)
                (staging / 'Prowlarr').rename(BINARY.parent)
        atomic(UNIT, f"""[Unit]
Description=MJWatch Prowlarr
After=network-online.target
[Service]
User={USER}
Group={USER}
StateDirectory={USER}
StateDirectoryMode=0700
EnvironmentFile={ENV}
Environment=PROWLARR__SERVER__BINDADDRESS=127.0.0.1
Environment=PROWLARR__SERVER__PORT={PORT}
Environment=PROWLARR__SERVER__ENABLESSL=false
Environment=PROWLARR__SERVER__URLBASE=
Environment=PROWLARR__AUTH__REQUIRED=Enabled
ExecStart={BINARY} -nobrowser -data=/var/lib/{USER}
Restart=on-failure
UMask=0077
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
[Install]
WantedBy=multi-user.target
""", 0o644)
        systemctl('daemon-reload').check_returncode()
        systemctl('enable', '--now', UNIT.name).check_returncode()
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            try:
                indexers()
                report('complete')
                return
            except (OSError, ValueError):
                time.sleep(1)
        raise RuntimeError('service_unavailable')
    except RuntimeError as error:
        report('failed', str(error))
    except (OSError, ValueError, subprocess.SubprocessError):
        report('failed', 'install_failed')


def select(provider):
    current = load(ACCESS, {})
    if provider == 'prowlarr':
        if not status()['configured']:
            return dict(error='indexer_required')
        current['apiKey'] = key()
    elif not active('mjwatch-jacred.service') or not load(ACCESS.with_name('jacred-access.json'), {}).get('apiKey'):
        return dict(error='jacred_unavailable')
    current['provider'] = provider
    atomic(ACCESS, json.dumps(current), 0o640, grp.getgrnam('mjwatch').gr_gid)
    return status()


def main(action):
    if action == 'install':
        CONTROL.mkdir(mode=0o700, exist_ok=True)
        if not active(JOB):
            result = subprocess.run(('systemd-run', '--unit=' + JOB, '--collect',
                                     '--property=RuntimeMaxSec=480', '/usr/bin/python3', '-B',
                                     str(Path(__file__).resolve()), 'run'), capture_output=True)
            if result.returncode and not active(JOB):
                return dict(error='install_failed')
        return dict(operation=dict(state='running'))
    if action == 'run':
        install()
        return {}
    if action in ('prowlarr', 'jacred'):
        return select(action)
    return status()


if __name__ == '__main__':
    print(json.dumps(main(sys.argv[1])))
