#!/usr/bin/env python3
import contextlib, fcntl, hashlib, json, os, pathlib, re, shutil, ssl, subprocess, sys, tempfile, time
ROOT=pathlib.Path('/var/lib/mjwatch-install')
CURRENT=''
def run(*args,input=None):return subprocess.run(args,input=input,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True).stdout.strip()
def write_receipt(path,result):
 tmp=path.with_suffix('.part');tmp.write_text(json.dumps(result));tmp.chmod(0o600);os.replace(tmp,path)
def health_tls_context(cafile):
 # Trust only this installation's CA, including pre-0.1.3 CAs without keyUsage.
 # PROTOCOL_TLS_CLIENT still requires chain, validity and hostname verification.
 ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
 ctx.minimum_version=ssl.TLSVersion.TLSv1_2
 ctx.load_verify_locations(cafile=cafile)
 return ctx

def healthy(config):
 for service in ['mjwatch.service','mjwatch-torrserver.service']:run('systemctl','is-active','--quiet',service)
 for attempt in range(30):
  try:
   # A short-lived status-only credential; no user device or refresh session.
   token=json.loads(run('/opt/mjwatch/current/mjwatch','health-token','/etc/mjwatch/config.json'))['accessToken']
   host=json.loads((ROOT/'plan.json').read_text())['host'];host='['+host+']' if ':' in host else host
   request=__import__('urllib.request',fromlist=['Request']);ctx=health_tls_context('/etc/mjwatch/ca.crt')
   req=request.Request('https://'+host+config['listen']+'/api/v1/admin/status',headers={'Authorization':'Bearer '+token})
   with request.urlopen(req,context=ctx,timeout=10) as response:result=json.load(response)
   if not result['torrserverHealthy'] or not result['databaseHealthy']:raise RuntimeError('component_health')
   return
  except Exception:
   if attempt==29:raise RuntimeError('health_failed')
   time.sleep(1)
def main():
 global CURRENT
 if os.geteuid()!=0:raise RuntimeError('root_required')
 if len(sys.argv)>1:
  request_path=pathlib.Path(sys.argv[1])
  if (request_path!=ROOT/'maintenance-request.json' and request_path.parent!=ROOT/'requests') or request_path.is_symlink() or request_path.stat().st_uid!=0 or request_path.stat().st_mode&0o077:raise RuntimeError('unsafe_request_file')
  request=json.loads(request_path.read_text());request_path.unlink()
 else:request=json.load(sys.stdin)
 operation=request['operationId']
 if not re.fullmatch('[A-Za-z0-9_-]{16,128}',operation) or request.get('confirmed') is not True:raise RuntimeError('confirmation_required')
 CURRENT=operation
 receipt=ROOT/(operation+'.maintenance.json')
 write_receipt(ROOT/'maintenance-status.json',dict(state='running',operationId=operation,action=request['action']))
 with (ROOT/'lock').open('w') as lock, contextlib.ExitStack() as cleanup:
  fcntl.flock(lock,fcntl.LOCK_EX)
  if receipt.exists():print(receipt.read_text());return
  config=json.loads(pathlib.Path('/etc/mjwatch/config.json').read_text());action=request['action'];current=pathlib.Path('/opt/mjwatch/current');previous=current.resolve()
  if action=='restart':run('systemctl','restart','mjwatch-torrserver.service','mjwatch.service');healthy(config)
  elif action=='clear-cache':
   cache=pathlib.Path('/var/lib/torrserver-mjwatch/cache')
   if cache.is_symlink():raise RuntimeError('unsafe_cache_path')
   run('systemctl','stop','mjwatch-torrserver.service')
   try:
    if cache.exists():run('runuser','-u','mjwatch-torr','--','find',str(cache),'-mindepth','1','-delete')
   finally:run('systemctl','start','mjwatch-torrserver.service')
   healthy(config)
  elif action=='restore':
   archive=request['archive'];passphrase=request['passphrase']
   if not re.fullmatch('[A-Za-z0-9_-]+\.mjwb',archive):raise RuntimeError('archive_id')
   destination=pathlib.Path('/var/lib/mjwatch-restore')
   if destination.exists():raise RuntimeError('restore_staging_exists')
   run('/opt/mjwatch/current/mjwatch','restore','/etc/mjwatch/config.json',input=json.dumps(dict(Archive=str(pathlib.Path(config['backupDir'])/archive),Passphrase=passphrase,Destination=str(destination))))
   old=pathlib.Path('/var/lib/mjwatch-pre-restore-'+operation);old.mkdir(mode=0o700)
   run('systemctl','stop','mjwatch.service')
   try:
    # Keep the current network/loopback credentials, restore the DB secret key and TLS identity together.
    for name in ['state.db','state.db-wal','state.db-shm']:
     path=pathlib.Path(config['database']).parent/name
     if path.exists():shutil.move(str(path),old/name)
    for name in ['secret.key','tls.key','tls.crt','ca.key','ca.crt']:
     path=pathlib.Path('/etc/mjwatch')/name;shutil.copy2(path,old/name);shutil.copy2(destination/name,path);run('chown','root:mjwatch' if name!='ca.key' else 'root:root',str(path));path.chmod(0o640 if name in ['secret.key','tls.key'] else 0o600 if name=='ca.key' else 0o644)
    run('openssl','req','-new','-newkey','rsa:3072','-nodes','-subj','/CN=MJWatch','-keyout','/etc/mjwatch/tls.key','-out','/etc/mjwatch/tls.csr')
    run('openssl','x509','-req','-in','/etc/mjwatch/tls.csr','-CA','/etc/mjwatch/ca.crt','-CAkey','/etc/mjwatch/ca.key','-CAcreateserial','-days','365','-extfile','/etc/mjwatch/san.cnf','-out','/etc/mjwatch/tls.crt')
    run('chown','root:mjwatch','/etc/mjwatch/tls.key');pathlib.Path('/etc/mjwatch/tls.key').chmod(0o640)
    shutil.copy2(destination/'state.db',config['database']);run('chown','mjwatch:mjwatch',config['database']);run('systemctl','start','mjwatch.service');healthy(config)
   except Exception:
    run('systemctl','stop','mjwatch.service')
    for name in ['state.db','state.db-wal','state.db-shm']:
     path=pathlib.Path(config['database']).parent/name;path.unlink(missing_ok=True)
     if (old/name).exists():shutil.move(str(old/name),path)
    for name in ['secret.key','tls.key','tls.crt','ca.key','ca.crt']:shutil.copy2(old/name,pathlib.Path('/etc/mjwatch')/name)
    run('systemctl','start','mjwatch.service');raise RuntimeError('restore_failed_original_restored')
   finally:shutil.rmtree(destination)
  elif action=='update':
   bootstrap=os.environ.get('MJWATCH_BOOTSTRAP_SUPPLY')
   upload=pathlib.Path(bootstrap) if bootstrap else ROOT/'upload'
   if bootstrap and (upload.parent!=ROOT or upload.is_symlink() or upload.stat().st_uid!=0 or upload.stat().st_mode&0o077):
    raise RuntimeError('unsafe_supply_directory')
   if request.get('httpsSupply') is True:
    from update_supply import stage_latest
    upload=pathlib.Path(cleanup.enter_context(tempfile.TemporaryDirectory(dir=ROOT)))
    stage_latest(upload,request['version'])
   run('openssl','dgst','-sha256','-verify','/etc/mjwatch/release-public.pem','-signature',str(upload/'manifest.sig'),str(upload/'manifest.json'))
   manifest=json.loads((upload/'manifest.json').read_text());version=manifest['version']
   if not re.fullmatch('[0-9]+\.[0-9]+\.[0-9]+',version) or manifest.get('schemaVersion')!=1:raise RuntimeError('incompatible_release')
   arch=json.loads((ROOT/'plan.json').read_text())['arch'];release=pathlib.Path('/opt/mjwatch/releases')/version
   if release==previous:raise RuntimeError('already_installed')
   if tuple(map(int,version.split('.')))<=tuple(map(int,previous.name.split('.'))):raise RuntimeError('not_newer_release')
   for entry in manifest['files']:
    path=upload/entry['path']
    if not path.resolve().is_relative_to(upload.resolve()) or path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest()!=entry['sha256']:raise RuntimeError('supply_integrity')
   # A consistent encrypted backup is mandatory before switching binaries or schema.
   backup=pathlib.Path(config['backupDir'])/('pre-update-'+operation+'.mjwb')
   run('/opt/mjwatch/current/mjwatch','backup','/etc/mjwatch/config.json',input=json.dumps(dict(Passphrase=request['passphrase'],Destination=str(backup))))
   if release.exists():
    if not (release/'.mjwatch-owned').is_file() or release.is_symlink():raise RuntimeError('release_directory_conflict')
    shutil.rmtree(release)
   release.mkdir(mode=0o755,exist_ok=False);release.chmod(0o755);(release/'.mjwatch-owned').touch(mode=0o600)
   for name,source in [('mjwatch',f'linux-{arch}/mjwatch'),('torrserver',f'linux-{arch}/TorrServer')]:shutil.copy2(upload/source,release/name);(release/name).chmod(0o755)
   for helper in ['maintenance.py','renew.py']:shutil.copy2(upload/helper,release/helper);(release/helper).chmod(0o700)
   for helper in ('jacred.py','prowlarr.py','management.py','update_supply.py'):
    if any(entry['path']==helper for entry in manifest['files']):
     shutil.copy2(upload/helper,release/helper);(release/helper).chmod(0o700)
   shutil.copy2(upload/'manifest.json',release/'manifest.json');(release/'manifest.json').chmod(0o644)
   try:
    run('systemctl','stop','mjwatch.service','mjwatch-torrserver.service');temp=current.with_suffix('.next');temp.symlink_to(release);os.replace(temp,current)
    if (release/'management.py').is_file():run('/usr/bin/python3','-B',str(release/'management.py'),'install')
    run('systemctl','start','mjwatch-torrserver.service','mjwatch.service');healthy(config)
   except Exception:
    run('systemctl','stop','mjwatch.service','mjwatch-torrserver.service');temp=current.with_suffix('.rollback');temp.symlink_to(previous);os.replace(temp,current);run('systemctl','start','mjwatch-torrserver.service','mjwatch.service');healthy(config);raise RuntimeError('update_failed_binary_rollback_confirmed')
  else:raise RuntimeError('operation_not_allowed')
  result=dict(operationId=operation,action=action,verified=True);write_receipt(receipt,result);write_receipt(ROOT/'maintenance-status.json',dict(result,state='complete'));print(json.dumps(result))
try:main()
except Exception as error:
 result=dict(state='failed',verified=False,code=str(error) if isinstance(error,RuntimeError) else type(error).__name__)
 try:
  write_receipt(ROOT/'maintenance-status.json',result)
  if CURRENT:write_receipt(ROOT/(CURRENT+'.maintenance.error.json'),result)
 except Exception:pass
 print(json.dumps(result),file=sys.stderr);sys.exit(1)
