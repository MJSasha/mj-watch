#!/usr/bin/env python3
"""Managed JacRed service. Invoked by the owner over SSH and by systemd."""
import fcntl
import grp
import hashlib
import json
import os
import pathlib
import platform
import re
import secrets
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

VERSION = '3.20.0-oss'
ASSETS = {
    'x86_64': ('amd64', 'b56993270ab55ba457a9a50b87406b10fa9e3327165a1baff20c8d6ecbe47f10'),
    'aarch64': ('arm64', '21735831da33f7cb9e5deab34a7eb07073505fb1f8fa3144d1b04ee645e872e7'),
}
TRACKERS = ('anibelka', 'anidub', 'anifilm', 'aniliberty', 'animelayer', 'anistar', 'bitru',
            'kinozal', 'knaben', 'korsars', 'leproduction', 'lostfilm', 'mazepa', 'megapeer',
            'nnmclub', 'rudub', 'rutor', 'rutracker', 'selezen', 'subsplease', 'toloka',
            'torrentby', 'ultradox', 'viruseproject')
BASE = pathlib.Path('/var/lib/mjwatch-jacred')
CONTROL = pathlib.Path('/var/lib/mjwatch-install/jacred')
RELEASE = pathlib.Path('/opt/mjwatch-jacred') / VERSION
ACCESS = pathlib.Path('/etc/mjwatch/jacred-access.json')
INDEX_STATUS = ACCESS.with_name('jacred-index.json')
INDEX_REQUEST = pathlib.Path('/var/lib/mjwatch/jacred-collect.request')
CONFIG = BASE / 'init.conf'
UNIT = 'mjwatch-jacred.service'
TIMER = 'mjwatch-jacred-index.timer'
JOB = 'mjwatch-jacred-operation'
ROOT_HELPER = '/opt/mjwatch/current/jacred.py'
ADDR = 'http://127.0.0.1:19117'
DEFAULTS = dict(syncUrl='', trackers=['rutor', 'nnmclub'], collect=True, interval=120)


def run(*args, **options):
    return subprocess.run(args, check=True, capture_output=True, timeout=90, **options)


def atomic(path, content, mode=0o600, group=0):
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as f:
        temp = pathlib.Path(f.name)
        try:
            os.fchmod(f.fileno(), mode)
            os.fchown(f.fileno(), 0, group)
            f.write(content.encode())
            f.flush()
            os.replace(temp, path)
        finally:
            temp.unlink(missing_ok=True)


def load(path, default):
    try:
        return json.loads(path.read_text())
    except FileNotFoundError:
        return default


def report(state, stage, code=''):
    atomic(CONTROL / 'status.json', json.dumps(dict(state=state, stage=stage, code=code)))


def systemd_active(unit):
    return subprocess.run(['systemctl', 'is-active', '--quiet', unit], timeout=10).returncode == 0


def operation_active():
    state = subprocess.run(['systemctl', 'show', JOB, '--property=ActiveState', '--value'],
                           capture_output=True, text=True, timeout=10).stdout.strip()
    return state in ('active', 'activating', 'deactivating', 'reloading')


def settings(config):
    if not config:
        return DEFAULTS.copy()
    return dict(syncUrl=config.get('syncapi', ''), trackers=config.get('synctrackers', []),
                collect=config.get('collect', False), interval=config.get('timeSync', 120))


def validate(raw):
    url, trackers, collect, interval = (raw.get(k) for k in ('syncUrl', 'trackers', 'collect', 'interval'))
    if not isinstance(url, str) or len(url) > 512 or any(c.isspace() for c in url):
        raise ValueError()
    if url:
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != 'https' or not parsed.hostname or parsed.username is not None or parsed.query or parsed.fragment:
            raise ValueError()
    if (not isinstance(trackers, list) or len(trackers) > len(TRACKERS)
            or any(t not in TRACKERS for t in trackers) or len(set(trackers)) != len(trackers)):
        raise ValueError()
    if type(collect) is not bool or type(interval) is not int or not 30 <= interval <= 1440:
        raise ValueError()
    if (collect or url) and not trackers:
        raise ValueError()
    return dict(syncUrl=url, trackers=trackers, collect=collect, interval=interval)


def status():
    config = load(CONFIG, {})
    operation = load(CONTROL / 'status.json', {})
    if operation.get('state') == 'running' and not operation_active():
        # Re-read after systemd responds to avoid masking a receipt written in the meantime.
        operation = load(CONTROL / 'status.json', {})
        if operation.get('state') == 'running':
            operation = dict(state='failed', code='interrupted')
    running = systemd_active(UNIT)
    catalog = None
    if running and config and operation.get('state') != 'running':
        try:
            with local_request('/stats/torrents', 'apikey', config['apikey'], 2) as response:
                rows = json.loads(response.read(128 * 1024))
                if isinstance(rows, list) and rows:
                    catalog = sum(row['alltorrents'] for row in rows
                                  if isinstance(row, dict) and type(row.get('alltorrents')) is int)
        except (OSError, ValueError):
            pass
    return dict(installed=bool(config) and (RELEASE / 'JacRed').is_file(),
                running=running, version=VERSION, catalogCount=catalog,
                manualIndex=load(ACCESS, {}).get('manual', False),
                settings=settings(config), trackers=TRACKERS,
                operation=operation,
                indexing=load(CONTROL / 'index.json', {}))


def extract(archive, destination):
    with zipfile.ZipFile(archive) as source:
        members = source.infolist()
        if sum(entry.file_size for entry in members) > 350 * 1024 * 1024:
            raise RuntimeError('archive_size')
        for entry in members:
            path = pathlib.PurePosixPath(entry.filename)
            kind = stat.S_IFMT(entry.external_attr >> 16)
            if path.is_absolute() or '..' in path.parts or '\\' in entry.filename or kind not in (0, stat.S_IFREG, stat.S_IFDIR):
                raise RuntimeError('archive_layout')
        source.extractall(destination, members)


def install_release():
    RELEASE.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
    RELEASE.parent.chmod(0o755)
    if (RELEASE / 'JacRed').is_file():
        return
    asset = ASSETS.get(platform.machine())
    if asset is None:
        raise RuntimeError('unsupported_arch')
    arch, expected = asset
    with tempfile.TemporaryDirectory(dir=RELEASE.parent) as temp:
        archive = pathlib.Path(temp) / 'release.zip'
        url = f'https://github.com/jacred-fdb/jacred/releases/download/{VERSION}/jacred-linux-{arch}.zip'
        digest = hashlib.sha256()
        with urllib.request.urlopen(url, timeout=25) as source, archive.open('wb') as out:
            size = 0
            while chunk := source.read(65536):
                size += len(chunk)
                if size > 64 * 1024 * 1024:
                    raise RuntimeError('archive_size')
                digest.update(chunk)
                out.write(chunk)
        if digest.hexdigest() != expected:
            raise RuntimeError('archive_digest')
        folder = pathlib.Path(temp) / 'release'
        folder.mkdir(mode=0o755)
        extract(archive, folder)
        binary = folder / 'JacRed'
        if not binary.is_file():
            raise RuntimeError('archive_layout')
        binary.chmod(0o755)
        # The SSH operation inherits UMask=0077. Runtime libraries must be readable by the service user.
        for item in folder.rglob('*'):
            item.chmod(0o755 if item.is_dir() or item == binary else 0o644)
        folder.chmod(0o755)
        folder.rename(RELEASE)


def local_request(path, param, key, timeout):
    url = ADDR + path + '?' + urllib.parse.urlencode({param: key})
    return urllib.request.urlopen(url, timeout=timeout)


def verify(key):
    for attempt in range(15):
        try:
            with local_request('/api/v2.0/indexers/all/results', 'apikey', key, 2) as response:
                result = json.loads(response.read(65536))
                if isinstance(result.get('Results', result.get('results')), list):
                    return
        except (OSError, ValueError):
            pass
        if attempt < 14:
            time.sleep(1)
    raise RuntimeError('startup_failed')


def write_units(interval):
    directory = pathlib.Path('/etc/systemd/system')
    atomic(directory / UNIT, f'''[Unit]
Description=JacRed for MJWatch
After=network-online.target
Wants=network-online.target
[Service]
User=mjwatch-jacred
Group=mjwatch-jacred
WorkingDirectory={BASE}
ExecStart={RELEASE}/JacRed
Environment=DOTNET_BUNDLE_EXTRACT_BASE_DIR={BASE}/Data/.dotnet
Restart=on-failure
RestartSec=4
UMask=0077
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths={BASE}/Data
Nice=5
[Install]
WantedBy=multi-user.target
''', 0o644)
    atomic(directory / 'mjwatch-jacred-index.service', f'''[Unit]
Description=Update the MJWatch JacRed catalog
After={UNIT}
[Service]
Type=oneshot
ExecStart=/usr/bin/python3 {ROOT_HELPER} index
TimeoutStartSec=45min
UMask=0077
''', 0o644)
    atomic(directory / TIMER, f'''[Unit]
Description=Schedule JacRed catalog updates
[Timer]
OnActiveSec=1min
OnUnitInactiveSec={interval}min
[Install]
WantedBy=timers.target
''', 0o644)
    atomic(directory / 'mjwatch-jacred-index.path', f'''[Unit]
Description=Watch for a JacRed collection request from MJWatch
[Path]
PathExists={INDEX_REQUEST}
Unit=mjwatch-jacred-index.service
[Install]
WantedBy=multi-user.target
''', 0o644)


def publish_access(config):
    active = settings(config)
    atomic(ACCESS, json.dumps(dict(apiKey=config['apikey'], collect=active['collect'],
                                  synchronized=bool(active['syncUrl']), interval=active['interval'],
                                  manual=True)),
           0o640, grp.getgrnam('mjwatch').gr_gid)


def enable_observability():
    config = load(CONFIG, {})
    if not config or not (CONTROL / 'owned').is_file():
        return
    write_units(settings(config)['interval'])
    run('systemctl', 'daemon-reload')
    run('systemctl', 'enable', '--now', 'mjwatch-jacred-index.path')
    publish_access(config)


def apply(raw):
    draft = validate(raw)
    marker = CONTROL / 'owned'
    if not marker.exists():
        managed_paths = (RELEASE.parent, BASE, ACCESS, pathlib.Path('/etc/systemd/system', UNIT),
                         pathlib.Path('/etc/systemd/system', TIMER))
        if any(path.exists() or path.is_symlink() for path in managed_paths):
            raise RuntimeError('existing_unit')
        marker.touch()
    previous = load(CONFIG, {})
    report('running', 'download')
    install_release()
    report('running', 'configure')
    libs = run('ldconfig', '-p', text=True).stdout
    if 'libicuuc.so' not in libs or 'libicui18n.so' not in libs:
        run('apt-get', 'update')
        packages = run('apt-cache', 'pkgnames', 'libicu', text=True).stdout.splitlines()
        available = sorted((p for p in packages if re.fullmatch(r'libicu\d+', p)), key=lambda p: int(p[6:]))
        if not available:
            raise RuntimeError('libicu_unavailable')
        run('apt-get', 'install', '-y', '--no-install-recommends', available[-1])
    try:
        jacred_group = grp.getgrnam('mjwatch-jacred')
    except KeyError:
        run('useradd', '--system', '--user-group', '--no-create-home', '--shell', '/usr/sbin/nologin', 'mjwatch-jacred')
        jacred_group = grp.getgrnam('mjwatch-jacred')
    BASE.mkdir(mode=0o755, exist_ok=True)
    BASE.chmod(0o755)
    data = BASE / 'Data'
    data.mkdir(mode=0o700, exist_ok=True)
    shutil.chown(data, user='mjwatch-jacred', group='mjwatch-jacred')
    key = previous.get('apikey') or secrets.token_urlsafe(32)
    config = dict(listenip='127.0.0.1', listenport=19117, apikey=key,
                  devkey=previous.get('devkey') or secrets.token_urlsafe(32),
                  web=False, openstats=True, opensync=False, syncapi=draft['syncUrl'],
                  synctrackers=draft['trackers'], timeSync=draft['interval'],
                  collect=draft['collect'], syncsport=False, syncspidr=False,
                  flaresolverr=dict(enable=False), cffetch=dict(enable=False))

    def configure(value):
        atomic(CONFIG, json.dumps(value), 0o640, jacred_group.gr_gid)
        current = settings(value)
        write_units(current['interval'])
        run('systemctl', 'daemon-reload')
        run('systemctl', 'enable', UNIT)
        run('systemctl', 'restart', UNIT)
        verify(value['apikey'])
        if current['collect']:
            run('systemctl', 'enable', TIMER)
            run('systemctl', 'restart', TIMER)
        else:
            run('systemctl', 'disable', '--now', TIMER)
        run('systemctl', 'enable', '--now', 'mjwatch-jacred-index.path')

    report('running', 'start')
    try:
        configure(config)
        publish_access(config)
    except Exception:
        if previous:
            configure(previous)
        else:
            run('systemctl', 'disable', '--now', UNIT, TIMER, 'mjwatch-jacred-index.path')
            CONFIG.unlink(missing_ok=True)
        raise


def index():
    config = load(CONFIG, {})
    if not config or not config.get('collect'):
        INDEX_REQUEST.unlink(missing_ok=True)
        return
    trigger = 'manual' if INDEX_REQUEST.exists() else 'scheduled'
    successes = []
    failures = []
    rows = [dict(tracker=tracker, state='pending') for tracker in settings(config)['trackers'] if tracker in TRACKERS]
    started = int(time.time())
    group = grp.getgrnam('mjwatch').gr_gid
    previous = load(INDEX_STATUS, {})
    history = previous.get('history', [])
    if previous.get('state') == 'running':
        history = [dict(state='interrupted', startedAt=previous.get('startedAt', 0),
                        updatedAt=previous.get('updatedAt', 0), trigger=previous.get('trigger', 'scheduled')),
                   *history][:12]

    def publish(state):
        now = int(time.time())
        recent = history
        if state == 'complete':
            recent = [dict(state=state, startedAt=started, updatedAt=now, trigger=trigger,
                           total=len(rows), failed=len(failures)), *history][:12]
        atomic(INDEX_STATUS, json.dumps(dict(state=state, startedAt=started, updatedAt=now,
                                           trigger=trigger, results=rows, history=recent)),
               0o640, group)

    try:
        publish('running')
    finally:
        INDEX_REQUEST.unlink(missing_ok=True)
    for row in rows:
        tracker = row['tracker']
        row['state'] = 'running'
        publish('running')
        try:
            with local_request(f'/cron/{tracker}/parse', 'devkey', config['devkey'], 90) as response:
                body = response.read(4096).decode(errors='replace')
            row['state'] = parser_result(body)
        except urllib.error.HTTPError as error:
            row.update(state='http_error', httpCode=error.code)
        except TimeoutError:
            row['state'] = 'timeout'
        except urllib.error.URLError as error:
            row['state'] = 'timeout' if isinstance(error.reason, TimeoutError) else 'connection_error'
        except (OSError, ValueError):
            row['state'] = 'connection_error'
        if row['state'] in ('responded', 'busy', 'disabled'):
            successes.append(tracker)
        else:
            failures.append(tracker)
        publish('running')
    atomic(CONTROL / 'index.json', json.dumps(dict(updated=int(time.time()), succeeded=successes, failed=failures)))
    publish('complete')


def parser_result(body):
    text = body.strip().lower()
    if text in ('work', 'running'):
        return 'busy'
    if text == 'disabled':
        return 'disabled'
    if text.startswith(('error', 'failed', 'exception')) or re.search(r'/\s*false\b', text):
        return 'parser_error'
    return 'responded'


def main():
    command = sys.argv[1]
    if os.geteuid() != 0:
        raise SystemExit(1)
    CONTROL.mkdir(mode=0o700, exist_ok=True)
    if CONTROL.is_symlink() or CONTROL.stat().st_uid != 0 or CONTROL.stat().st_mode & 0o077:
        raise SystemExit(64)
    if command == 'status':
        print(json.dumps(status()))
        return
    if command == 'index':
        index()
        return
    if command == 'enable-observability':
        enable_observability()
        return
    if command == 'start':
        try:
            value = validate(json.loads(sys.stdin.read(8192)))
        except (TypeError, ValueError):
            print(json.dumps(dict(state='failed', code='invalid_settings')))
            return
        with (CONTROL / 'start.lock').open('w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            if operation_active():
                print(json.dumps(dict(state='busy')))
                return
            atomic(CONTROL / 'request.json', json.dumps(value))
            report('running', 'queue')
            run('systemd-run', '--unit=' + JOB, '--collect', '--property=UMask=0077',
                '--property=RuntimeMaxSec=300', '/usr/bin/python3', ROOT_HELPER, 'apply')
        print(json.dumps(load(CONTROL / 'status.json', {})))
        return
    if command == 'apply':
        try:
            apply(load(CONTROL / 'request.json', {}))
            report('complete', 'ready')
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
            report('failed', 'error', str(error) if isinstance(error, RuntimeError) else type(error).__name__)
        return
    raise SystemExit(64)


if __name__ == '__main__':
    main()
