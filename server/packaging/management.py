#!/usr/bin/env python3
"""Fixed local operations for the unprivileged MJWatch HTTPS service."""
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path('/var/lib/mjwatch-install')
CURRENT = Path('/opt/mjwatch/current')
CTL = '/usr/local/sbin/mjwatchctl'
MAX_REQUEST = 32 * 1024
ID = re.compile(r'[A-Za-z0-9_-]{16,128}')
VERSION = re.compile(r'[0-9]+\.[0-9]+\.[0-9]+')


def command(args, payload=None):
    result = subprocess.run(args, input=json.dumps(payload) if payload is not None else '',
                            text=True, capture_output=True, timeout=20, check=True)
    return json.loads(result.stdout)


def operation_status(kind, operation):
    result = command([CTL, 'torrserver' if kind == 'torr' else 'maintenance', 'operation'],
                     {'operationId': operation})
    if kind == 'torr' and result.get('state') == 'complete':
        return torr_snapshot(result)
    return result


def torr_snapshot(snapshot):
    fields = {field['name'] for field in snapshot.get('fields', [])}
    if 'settings' in snapshot:
        snapshot['settings'] = {name: value for name, value in snapshot['settings'].items() if name in fields}
    if 'result' in snapshot:
        torr_snapshot(snapshot['result'])
    return snapshot


def unit_running(kind, operation):
    unit = f'mjwatch-{kind}-{operation}'
    state = subprocess.run(['systemctl', 'show', '--property=ActiveState', '--value', unit], timeout=5,
                           capture_output=True, text=True).stdout.strip()
    return state in ('active', 'activating', 'reloading', 'deactivating')


def start_operation(kind, payload):
    operation = payload.get('operationId', '')
    if not isinstance(operation, str) or not ID.fullmatch(operation) or payload.get('confirmed') is not True:
        raise ValueError('invalid_request')
    if kind == 'maintenance':
        action = payload.get('action')
        if action not in ('restart', 'clear-cache', 'restore', 'update'):
            raise ValueError('invalid_request')
        if action in ('restore', 'update'):
            secret = payload.get('passphrase')
            if not isinstance(secret, str) or not 16 <= len(secret) <= 1024:
                raise ValueError('invalid_request')
        if action == 'update':
            version = payload.get('version')
            if not isinstance(version, str) or not VERSION.fullmatch(version):
                raise ValueError('invalid_request')
            payload['httpsSupply'] = True
    requests = ROOT / 'requests'
    requests.mkdir(mode=0o700, exist_ok=True)
    if requests.is_symlink() or requests.stat().st_uid != 0 or requests.stat().st_mode & 0o077:
        raise RuntimeError('unsafe_request_directory')
    with (ROOT / 'management.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        previous = operation_status(kind, operation)
        if previous.get('state') in ('complete', 'failed'):
            return previous
        # A running job owns its operation ID even if it has already consumed the request file.
        for other in ('torr', 'maintenance'):
            if unit_running(other, operation):
                if kind != other:
                    raise ValueError('invalid_request')
                return {'state': 'running'}
        request = requests / (operation + '.json')
        temporary = requests / (operation + '.part')
        with temporary.open('w') as file:
            json.dump(payload, file)
        os.replace(temporary, request)
        return command([CTL, 'start', 'operation'], {'operationId': operation, 'kind': kind})


def execute(route, payload):
    if route == 'status':
        return {'available': True}
    if route == 'torr/read':
        return torr_snapshot(command([CTL, 'torrserver', 'settings', 'read']))
    if route in ('torr/start', 'maintenance/start'):
        return start_operation(route.split('/')[0], payload)
    if route in ('torr/status', 'maintenance/status'):
        operation = payload.get('operationId', '')
        if not isinstance(operation, str) or not ID.fullmatch(operation):
            raise ValueError('invalid_request')
        kind = route.split('/')[0]
        if unit_running(kind, operation):
            return {'state': 'running'}
        result = operation_status(kind, operation)
        if result.get('state') == 'pending':
            return {'state': 'failed', 'code': 'operation_not_found'}
        return result
    if route in ('prowlarr/status', 'prowlarr/install', 'prowlarr/prowlarr', 'prowlarr/jacred',
                 'jacred/status', 'jacred/start'):
        service, action = route.split('/')
        return command(['/usr/bin/python3', '-B', str(CURRENT / (service + '.py')), action], payload)
    raise ValueError('invalid_request')


def install_units():
    units = Path('/etc/systemd/system')
    socket = units / 'mjwatch-management.socket'
    socket.write_text('''[Unit]
Description=MJWatch local management socket
[Socket]
ListenStream=/run/mjwatch-management.sock
SocketMode=0660
SocketUser=root
SocketGroup=mjwatch
Accept=yes
MaxConnections=8
RemoveOnStop=yes
[Install]
WantedBy=sockets.target
''')
    service = units / 'mjwatch-management@.service'
    service.write_text('''[Unit]
Description=MJWatch local management operation
[Service]
ExecStart=/usr/bin/python3 -B /opt/mjwatch/current/management.py
StandardInput=socket
StandardOutput=socket
StandardError=journal
UMask=0077
RuntimeMaxSec=1850
''')
    for unit in (socket, service):
        unit.chmod(0o644)
    subprocess.run(['systemctl', 'daemon-reload'], check=True)
    subprocess.run(['systemctl', 'enable', '--now', socket.name], check=True)


def main():
    if os.geteuid() != 0:
        raise SystemExit(1)
    if sys.argv[1:] == ['install']:
        install_units()
        return
    try:
        raw = sys.stdin.buffer.readline(MAX_REQUEST + 1)
        if len(raw) > MAX_REQUEST:
            raise ValueError('invalid_request')
        request = json.loads(raw)
        payload = request.get('payload', {})
        if not isinstance(payload, dict):
            raise ValueError('invalid_request')
        if request.get('route') == 'jacred/web':
            subprocess.run(['/usr/bin/python3', '-B', str(CURRENT / 'jacred.py'), 'admin-session'],
                           check=True, timeout=1810)
            return
        result = execute(request.get('route'), payload)
    except (ValueError, TypeError, AttributeError):
        result = {'error': 'invalid_request'}
    except (RuntimeError, OSError, subprocess.SubprocessError):
        result = {'error': 'management_operation_failed'}
    print(json.dumps(result))


if __name__ == '__main__':
    main()
