"""Root worker sent over each independently authenticated SSH connection by migrate.py."""
import base64
import contextlib
import fcntl
import http.client
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import sqlite3
import ssl
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.parse

ROOT = Path('/var/lib/mjwatch-install')
CONFIG = Path('/etc/mjwatch/config.json')
TORR = Path('/var/lib/torrserver-mjwatch')
STAGE = ROOT / 'migration'
RECEIPT = STAGE / 'destination.json'
API = 'mjwatch.service'
UPSTREAM = 'mjwatch-torrserver.service'


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    # The config must keep its service group. Persist both file and rename for reboot recovery.
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temp:
        previous = path.stat() if path.exists() else None
        if previous:
            os.fchown(temp.fileno(), previous.st_uid, previous.st_gid)
            os.fchmod(temp.fileno(), stat.S_IMODE(previous.st_mode))
        temp.write(json.dumps(value).encode())
        temp.flush()
        os.fsync(temp.fileno())
    os.replace(temp.name, path)
    fd = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def control(action, *units):
    subprocess.run(['systemctl', action, *units], check=True, stdout=subprocess.DEVNULL)


def settings(db):
    return dict(db.execute('SELECT key,value FROM settings'))


def identity(db):
    values = settings(db)
    return dict(serverId=values['server_id'], serverEpoch=values['server_epoch'],
                migrationId=values.get('migration_id', ''))


def endpoint():
    plan = read(ROOT / 'plan.json')
    host = plan['host']
    if ':' in host:
        host = '[' + host + ']'
    return dict(baseUrl='https://' + host + ':' + str(plan['apiPort']),
                ca=(CONFIG.parent / 'ca.crt').read_text())


def probe(endpoint, path, headers=None):
    url = urllib.parse.urlsplit(endpoint['baseUrl'])
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_verify_locations(cadata=endpoint['ca'])
    conn = http.client.HTTPSConnection(url.hostname, url.port, timeout=8, context=ctx)
    try:
        conn.request('GET', '/api/v1/' + path, headers=headers or {})
        reply = conn.getresponse()
        return reply.status, json.loads(reply.read(65536))
    finally:
        conn.close()


def ready(endpoint, path, check, headers=None):
    for attempt in range(12):
        try:
            if check(*probe(endpoint, path, headers)):
                return
        except (OSError, ValueError, http.client.HTTPException):
            pass
        if attempt != 11:
            time.sleep(1)
    raise ValueError('Verified HTTPS probe failed for ' + path)


def matches(a, b):
    return all(a.get(k) == b.get(k) for k in ('serverId', 'serverEpoch', 'migrationId', 'ca', 'baseUrl'))


def fresh(db, allow_owner):
    users = db.execute('SELECT role FROM users').fetchall()
    if users and (not allow_owner or users != [('admin',)]):
        return False
    # An owner created by the normal installer is allowed only by explicit opt-in.
    for table in ('devices', 'sessions'):
        if db.execute('SELECT COUNT(*) FROM '+table).fetchone()[0] > int(allow_owner):
            return False
    empty = ('entities', 'changes', 'mutations', 'invites', 'pairing', 'playback', 'remote_receipts')
    return (all(not db.execute('SELECT 1 FROM '+t+' LIMIT 1').fetchone() for t in empty) and
            set(settings(db)) <= {'server_id', 'server_epoch', 'maintenance_access'})


def empty_torr(config):
    url = urllib.parse.urlsplit(config['torrUrl'])
    if url.scheme != 'http' or url.hostname != '127.0.0.1':
        raise ValueError('Expected a managed loopback TorrServer')
    conn = http.client.HTTPConnection(url.hostname, url.port, timeout=10)
    credentials = (config['torrUser'] + ':' + config['torrPassword']).encode()
    try:
        conn.request('POST', '/torrents', body=b'{"action":"list"}',
                     headers={'Content-Type': 'application/json',
                              'Authorization': 'Basic ' + base64.b64encode(credentials).decode()})
        reply = conn.getresponse()
        return reply.status == 200 and json.loads(reply.read(2 << 20)) in ([], None)
    finally:
        conn.close()


def db_open(path):
    # Refuse to silently create a new database if a configured path went missing.
    return contextlib.closing(sqlite3.connect(path.resolve().as_uri() + '?mode=rw', uri=True))


def snapshot(source, dest):
    with db_open(source) as live, contextlib.closing(sqlite3.connect(dest)) as copy:
        live.backup(copy)


def integrity(db, move):
    values = identity(db)
    if (db.execute('PRAGMA user_version').fetchone() != (1,) or
            db.execute('PRAGMA integrity_check').fetchone() != ('ok',) or
            db.execute('PRAGMA foreign_key_check').fetchone() or
            any(values[k] != move[k] for k in ('serverId', 'serverEpoch'))):
        raise ValueError('Database integrity or source identity check failed.')


def export(config, move, out):
    # SQLite backup includes the final committed WAL, including refresh rotations.
    with tempfile.TemporaryDirectory(dir=ROOT) as tmp, tarfile.open(fileobj=out, mode='w|') as archive:
        database = Path(tmp) / 'state.db'
        snapshot(Path(config['database']), database)
        with db_open(database) as db:
            integrity(db, move)
        archive.add(database, 'state.db', recursive=False)
        archive.add(config['secretKey'], 'secret.key', recursive=False)
        for directory, prefix in [(TORR, 'torr'), (Path(config['backupDir']), 'backups')]:
            for root, dirs, files in os.walk(directory):
                if Path(root) == TORR and 'cache' in dirs:
                    dirs.remove('cache')
                if any((Path(root) / d).is_symlink() for d in dirs):
                    raise ValueError('Unsupported linked data directory')
                for name in files:
                    file = Path(root) / name
                    rel = file.relative_to(directory)
                    if prefix == 'torr' and rel == Path('accs.db'):
                        continue  # the target keeps its own loopback credentials
                    if not stat.S_ISREG(file.lstat().st_mode):
                        raise ValueError('Unsupported linked or special data file')
                    archive.add(file, str(Path(prefix) / rel), recursive=False)
        data = json.dumps(move['migrationId']).encode()
        end = tarfile.TarInfo('complete.json')
        end.size = len(data)
        archive.addfile(end, io.BytesIO(data))


def receive(folder, move, stream):
    if folder.exists():
        shutil.rmtree(folder)
    folder.mkdir(mode=0o700)
    finished = False
    with tarfile.open(fileobj=stream, mode='r|') as archive:
        for item in archive:
            rel = PurePosixPath(item.name)
            if (finished or not item.isfile() or rel.is_absolute() or '..' in rel.parts or not rel.parts or
                    (str(rel) not in ('state.db', 'secret.key', 'complete.json') and
                     not (len(rel.parts) > 1 and rel.parts[0] in ('torr', 'backups'))) or
                    str(rel) == 'torr/accs.db'):
                raise ValueError('Unexpected migration archive entry')
            body = archive.extractfile(item)
            if str(rel) == 'complete.json':
                finished = item.size < 128 and json.loads(body.read(128)) == move['migrationId']
                if not finished:
                    raise ValueError('Wrong migration archive')
                continue
            dest = folder / rel
            dest.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            with dest.open('xb') as f:
                shutil.copyfileobj(body, f)
    if not finished or (folder / 'secret.key').stat().st_size != 32:
        raise ValueError('Incomplete migration stream')
    with db_open(folder / 'state.db') as db:
        integrity(db, move)
        with db:
            db.execute("INSERT OR REPLACE INTO settings VALUES ('migration_id',?)", (move['migrationId'],))


def preserve(path, backup):
    # A crash between any two renames may leave the destination vacant.
    # The untouched destination is saved exactly once across retries.
    if path.exists() and not backup.exists():
        path.rename(backup)


def install(config, folder):
    original = STAGE / 'original'
    original.mkdir(mode=0o700, exist_ok=True)
    db = Path(config['database'])
    for name, path in [('db', db.parent), ('torr', TORR), ('backups', Path(config['backupDir']))]:
        preserve(path, original / name)
        if not path.exists():
            path.mkdir(mode=0o700)
        user = 'mjwatch-torr' if name == 'torr' else 'mjwatch'
        shutil.chown(path, user, user)
        if name == 'db':
            shutil.copyfile(folder / 'state.db', db)
            shutil.chown(db, user, user)
        else:
            if (folder / name).exists():
                shutil.copytree(folder / name, path, dirs_exist_ok=True)
            if name == 'torr':
                shutil.copyfile(original / name / 'accs.db', path / 'accs.db')
            subprocess.run(['chown', '-R', user+':'+user, str(path)], check=True)
    secret = Path(config['secretKey'])
    if not (original / 'secret.key').exists():
        shutil.copy2(secret, original / 'secret.key')
    # Writing through the existing inode keeps its group and mode.
    shutil.copyfile(folder / 'secret.key', secret)


def worker(action, args):
    if os.geteuid() != 0 or not (ROOT / 'owned').exists():
        raise ValueError('A root SSH connection to a managed installation is required')
    config = read(CONFIG)
    dbpath = Path(config['database'])
    if (dbpath != Path('/var/lib/mjwatch/state.db') or
            Path(config['secretKey']) != CONFIG.parent / 'secret.key' or
            Path(config['backupDir']) != Path('/var/backups/mjwatch')):
        raise ValueError('Unsupported nonstandard data paths')
    local = endpoint()
    fenced = config.get('relocation')
    journal = read(RECEIPT) if RECEIPT.exists() else None
    if action == 'inspect':
        if fenced:
            return dict(local, relocation=fenced, reservation=journal['move'] if journal else None)
        if journal and journal['phase'] != 'active':
            return dict(local, reservation=journal['move'])
        status, info = probe(local, 'server/identity')
        if status != 200 or not info.get('serverId'):
            raise ValueError('Install server version 0.1.9 or later on both hosts first.')
        return dict(local, **info)
    move = args['move']
    if action == 'reserve':
        if fenced and (not journal or not matches(fenced, move)):
            raise ValueError('Destination itself is moving to another host.')
        if journal:
            if journal['phase'] == 'cancelled':
                raise ValueError('Cancellation in progress. Resume with --abort.')
            if not matches(journal['move'], move):
                raise ValueError('Destination is reserved for a different migration.')
            return {}
        if fenced or local['baseUrl'] != move['baseUrl'] or local['ca'] != move['ca']:
            raise ValueError('Destination identity mismatch')
        with db_open(dbpath) as db:
            if identity(db)['serverId'] == move['serverId'] or not fresh(db, args.get('replaceBootstrap', False)):
                raise ValueError('Destination contains existing user state. A fresh installation is required.')
        if any(Path(config['backupDir']).iterdir()) or not empty_torr(config):
            raise ValueError('Destination already contains backups or torrents.')
        STAGE.mkdir(mode=0o700, exist_ok=True)
        write(RECEIPT, dict(move=move, phase='reserved', allowOwner=args.get('replaceBootstrap', False)))
        return {}
    if action == 'thaw':
        if fenced and (not matches(fenced, move) or fenced['code'] != 'migration_in_progress'):
            raise ValueError('Conflicting or already published move')
        with db_open(dbpath) as db:
            integrity(db, move)
        if config.pop('relocation', None):
            write(CONFIG, config)
        control('start', UPSTREAM)
        control('restart', API)
        ready(local, 'server/identity', lambda s,v: s == 200 and v.get('serverId') == move['serverId'])
        return {}
    if action in ('freeze', 'export', 'publish'):
        if fenced and not matches(fenced, move):
            raise ValueError('Conflicting source migration')
        if action == 'freeze':
            if not fenced:
                ready(move, 'server/identity', lambda s,v: s == 200 and v.get('serverId') and v['serverId'] != move['serverId'])
                with db_open(dbpath) as db:
                    integrity(db, move)
                    config['priorMigrationId'] = identity(db)['migrationId']
                config['relocation'] = dict(move, code='migration_in_progress')
                write(CONFIG, config)
            if not fenced or fenced['code'] != 'server_moved':
                control('stop', API, UPSTREAM)
                control('start', API)
                ready(local, 'server/identity', lambda s, v: s == 503 and v.get('code') == 'migration_in_progress')
        elif not fenced:
            raise ValueError('Source must be frozen first')
        elif action == 'export':
            control('stop', UPSTREAM)
            export(config, move, sys.stdout.buffer)
            return None
        else:
            ready(move, 'server/identity', lambda s, v: s == 200 and
                  all(move[k] == v.get(k) for k in ('serverId', 'serverEpoch', 'migrationId')))
            config['relocation'] = dict(move, code='server_moved')
            write(CONFIG, config)
            control('restart', API)
            ready(local, 'me', lambda s, v: s == 409 and matches(v, move))
        return {}
    if fenced and not matches(fenced, move):
        raise ValueError('Destination has a conflicting migration')
    if not journal or not matches(journal['move'], move):
        raise ValueError('Destination reservation mismatch')
    phase = journal['phase']
    folder = STAGE / 'incoming'
    if action == 'cancel':
        if phase not in ('reserved', 'received', 'cancelled'):
            raise ValueError('Destination installation already began. Resume forward without --abort.')
        journal['phase'] = 'cancelled'
        write(RECEIPT, journal)
        if config.pop('relocation', None):
            write(CONFIG, config)
        control('start', UPSTREAM)
        control('restart', API)
        return {}
    if action == 'release' and phase == 'cancelled':
        if folder.exists():
            shutil.rmtree(folder)
        RECEIPT.unlink()
        return {}
    if action == 'status':
        return dict(phase=phase)
    if action == 'receive' and phase == 'reserved':
        receive(folder, move, sys.stdin.buffer)
        return None
    if action == 'commit-transfer' and phase == 'reserved':
        # Only the coordinator that saw successful exit from *both* SSH processes commits.
        with db_open(folder / 'state.db') as db:
            integrity(db, move)
            if identity(db)['migrationId'] != move['migrationId']:
                raise ValueError('Unverified transfer')
        os.sync()
        journal['phase'] = 'received'
        write(RECEIPT, journal)
        return {}
    if action != 'activate' or phase not in ('received', 'installing', 'installed', 'active'):
        raise ValueError('Unexpected migration phase')
    if phase in ('received', 'installing'):
        if not fenced:
            config['relocation'] = dict(move, code='migration_in_progress')
            write(CONFIG, config)
        control('stop', API)
        if phase == 'received':
            control('start', UPSTREAM)
            if any(Path(config['backupDir']).iterdir()) or not empty_torr(config):
                config.pop('relocation')
                write(CONFIG, config)
                control('start', API)
                raise ValueError('Destination gained backups or torrents during staging.')
            with db_open(dbpath) as db:
                if not fresh(db, journal['allowOwner']):
                    config.pop('relocation')
                    write(CONFIG, config)
                    control('start', UPSTREAM, API)
                    raise ValueError('Destination gained user data during staging.')
            journal['phase'] = 'installing'
            write(RECEIPT, journal)
        control('stop', UPSTREAM)
        install(config, folder)
        os.sync()
        journal['phase'] = 'installed'
        write(RECEIPT, journal)
    # Never reinstall after first exposing the destination: clients may already have written.
    if config.pop('relocation', None):
        write(CONFIG, config)
    control('start', UPSTREAM)
    control('restart' if phase != 'active' else 'start', API)
    ready(local, 'server/identity', lambda s, v: s == 200 and
          all(move[k] == v.get(k) for k in ('serverId', 'serverEpoch', 'migrationId')))
    token = json.loads(subprocess.check_output(['/opt/mjwatch/current/mjwatch', 'health-token', str(CONFIG)]))
    ready(local, 'admin/status', lambda s,v: s == 200 and v.get('databaseHealthy') and v.get('torrserverHealthy'),
          {'Authorization': 'Bearer '+token['accessToken']})
    journal['phase'] = 'active'
    write(RECEIPT, journal)
    if folder.exists():
        shutil.rmtree(folder)
    return {}


if __name__ == '__main__':
    os.umask(0o077)
    with (ROOT / 'lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        result = worker(sys.argv[1], json.loads(sys.argv[2]))
        if result is not None:
            print(json.dumps(result))
