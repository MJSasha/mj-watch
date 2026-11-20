#!/usr/bin/env python3
"""Move a managed installation between two root SSH aliases from an operator workstation."""
import argparse
import json
import pathlib
import secrets
import shlex
import subprocess
import sys

WORKER = pathlib.Path(__file__).with_name('migration_host.py')
if not WORKER.is_file():  # repository checkout
    WORKER = WORKER.parent / 'packaging' / WORKER.name


def command(host, action, options):
    remote = shlex.join(['python3', '-c', WORKER.read_text(), action, json.dumps(options)])
    return ['ssh', '-T', '-o', 'StrictHostKeyChecking=yes', '-o', 'BatchMode=yes', '--', host, remote]


def invoke(host, action, **options):
    return json.loads(subprocess.check_output(command(host, action, options), stdin=subprocess.DEVNULL))


def migrate(source, target, replace_bootstrap=False, abort=False):
    old = invoke(source, 'inspect')
    new = invoke(target, 'inspect')
    if old['baseUrl'] == new['baseUrl'] or old['ca'] == new['ca']:
        raise ValueError('Source and destination must be separate managed installations.')
    move = old.get('relocation') or new.get('reservation') or dict(
        serverId=old['serverId'], serverEpoch=old['serverEpoch'], baseUrl=new['baseUrl'],
        ca=new['ca'], migrationId=secrets.token_urlsafe(32), code='migration_in_progress')
    if not old.get('relocation') and any(old.get(k) != move[k] for k in ('serverId', 'serverEpoch')):
        raise ValueError('Destination is reserved for a different source.')
    if move['ca'] != new['ca'] or move['baseUrl'] != new['baseUrl']:
        raise ValueError('The source is already moving to another destination.')
    if abort:
        invoke(target, 'cancel', move=move)
        invoke(source, 'thaw', move=move)
        invoke(target, 'release', move=move)
        print('Migration cancelled. The original server is active.')
        return
    # Reserve the destination before interrupting the source. A concurrent migration
    # or a populated destination fails before the first source service is stopped.
    invoke(target, 'reserve', move=move, replaceBootstrap=replace_bootstrap)
    invoke(source, 'freeze', move=move)
    status = invoke(target, 'status', move=move)
    if status['phase'] == 'reserved':
        print('Copying the frozen installation over SSH.', file=sys.stderr)
        with subprocess.Popen(command(source, 'export', dict(move=move)), stdout=subprocess.PIPE,
                              stdin=subprocess.DEVNULL) as sender:
            try:
                subprocess.run(command(target, 'receive', dict(move=move)), stdin=sender.stdout, check=True)
            finally:
                sender.stdout.close()
            if sender.wait():
                raise RuntimeError('Source export interrupted. Run the same command to resume.')
        invoke(target, 'commit-transfer', move=move)
    invoke(target, 'activate', move=move)
    invoke(source, 'publish', move=move)
    print('Migration verified. Keep the old HTTPS address reachable for returning devices.')
    print(move['baseUrl'])


if __name__ == '__main__':
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument('source', help='root SSH alias for the current installation')
    args.add_argument('destination', help='root SSH alias for the newly installed server')
    args.add_argument('--replace-bootstrap', action='store_true',
                      help='allow a fresh destination with its single initial owner and device')
    args.add_argument('--abort', action='store_true', help='cancel before destination installation begins')
    opts = args.parse_args()
    try:
        migrate(opts.source, opts.destination, opts.replace_bootstrap, opts.abort)
    except (ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        # A remote process command line contains the whole helper. Keep diagnostics readable.
        sys.exit('Migration stopped. Read the preceding host error and retry the same command.'
                 if isinstance(error, subprocess.CalledProcessError) else str(error))
