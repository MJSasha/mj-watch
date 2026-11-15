#!/usr/bin/env python3
"""Root-only resumable installation. All input is JSON, never shell text."""
import base64, fcntl, hashlib, ipaddress, json, os, pathlib, platform, secrets, shutil, socket, subprocess, sys, time, urllib.request
ROOT=pathlib.Path('/var/lib/mjwatch-install')
UPLOAD=ROOT/'upload'
def run(*args,**kw): return subprocess.run(args,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kw).stdout.decode().strip()
def atomic(path,value,mode=0o600):
 path=pathlib.Path(path);tmp=path.with_suffix('.tmp');tmp.write_text(json.dumps(value));tmp.chmod(mode);os.replace(tmp,path)
CURRENT_STAGE='checking'
def status(stage,state='running',code=''):
 global CURRENT_STAGE
 CURRENT_STAGE=stage
 atomic(ROOT/'status.json',dict(stage=stage,state=state,code=code,updated=int(time.time())))
 if state=='running' and (ROOT/'cancel').exists():
  atomic(ROOT/'status.json',dict(stage=stage,state='cancelled',code='cancelled_at_safe_boundary',updated=int(time.time())))
  raise RuntimeError('cancelled_at_safe_boundary')
def free_port(start):
 for port in range(start,start+100):
  try:
   with socket.socket() as s, socket.socket(type=socket.SOCK_DGRAM) as udp:s.bind(('0.0.0.0',port));udp.bind(('0.0.0.0',port))
   return port
  except OSError: pass
 raise RuntimeError('no_free_port')
def owner(path,user,mode):run('chown',user,str(path));pathlib.Path(path).chmod(mode)
def unit(name,user,command,writable):
 text=f"""[Unit]
Description=MJWatch {name}
After=network-online.target
Wants=network-online.target
[Service]
User={user}
Group={user}
ExecStart={command}
Restart=on-failure
RestartSec=3
UMask=0077
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths={writable}
LimitNOFILE=65536
StandardOutput=null
StandardError=null
[Install]
WantedBy=multi-user.target
"""
 path=pathlib.Path('/etc/systemd/system')/name
 if path.exists() and not (ROOT/'owned').exists(): raise RuntimeError('existing_unit_conflict')
 path.write_text(text);path.chmod(0o644)
def main():
 if os.geteuid()!=0:raise RuntimeError('root_required')
 ROOT.mkdir(mode=0o700,exist_ok=True)
 if ROOT.stat().st_uid!=0 or ROOT.is_symlink():raise RuntimeError('unsafe_install_directory')
 ROOT.chmod(0o700)
 with (ROOT/'lock').open('w') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
  status('checking')
  os_release={k:v.strip('"') for k,v in (line.strip().split('=',1) for line in pathlib.Path('/etc/os-release').read_text().splitlines() if '=' in line)}
  if (os_release.get('ID'),os_release.get('VERSION_ID')) not in [('debian','12'),('ubuntu','24.04')]:raise RuntimeError('unsupported_os')
  arch={'x86_64':'amd64','aarch64':'arm64'}.get(platform.machine())
  if not arch or not pathlib.Path('/run/systemd/system').is_dir():raise RuntimeError('unsupported_system')
  if os.sysconf('SC_PHYS_PAGES')*os.sysconf('SC_PAGE_SIZE')<2_000_000_000 or shutil.disk_usage('/var/lib').free<2*1024**3:raise RuntimeError('insufficient_resources')
  request=json.loads((ROOT/'request.json').read_text());host=request['host']
  if len(host)>253 or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.:-' for c in host):raise RuntimeError('invalid_host')
  run('apt-get','update');run('apt-get','install','-y','openssl','ca-certificates','curl')
  run('openssl','dgst','-sha256','-verify',str(UPLOAD/'release-public.pem'),'-signature',str(UPLOAD/'manifest.sig'),str(UPLOAD/'manifest.json'))
  manifest=json.loads((UPLOAD/'manifest.json').read_text())
  if not __import__('re').fullmatch('[0-9]+\.[0-9]+\.[0-9]+',manifest['version']) or manifest.get('apiVersion',1)!=1 or manifest.get('schemaVersion')!=1 or manifest['torrserverVersion']!='MatriX.145.2':raise RuntimeError('manifest_version')
  for entry in manifest['files']:
   path=UPLOAD/entry['path']
   if path.is_symlink() or not path.resolve().is_relative_to(UPLOAD.resolve()) or hashlib.sha256(path.read_bytes()).hexdigest()!=entry['sha256']:raise RuntimeError('supply_integrity')
  existing=pathlib.Path('/opt/mjwatch/current')
  if existing.is_symlink() and existing.resolve().name!=manifest['version']:raise RuntimeError('use_signed_update_for_version_change')
  config_path=pathlib.Path('/etc/mjwatch/config.json')
  if config_path.exists() and not (ROOT/'owned').exists():raise RuntimeError('existing_config_conflict')
  if (ROOT/'plan.json').exists():plan=json.loads((ROOT/'plan.json').read_text())
  else:
   plan=dict(apiPort=free_port(8443),torrPort=free_port(18090),peerPort=free_port(32000),host=host,arch=arch)
   atomic(ROOT/'plan.json',plan)
  if plan['host']!=host or plan['arch']!=arch:raise RuntimeError('installation_identity_changed')
  if not (ROOT/'owned').exists():
   if pathlib.Path('/opt/mjwatch').exists():raise RuntimeError('existing_release_conflict')
   if any((pathlib.Path('/etc/systemd/system')/name).exists() for name in ['mjwatch.service','mjwatch-torrserver.service']):raise RuntimeError('existing_unit_conflict')
   import pwd
   for account in ['mjwatch','mjwatch-torr']:
    try:pwd.getpwnam(account);raise RuntimeError('existing_account_conflict')
    except KeyError:pass
  (ROOT/'owned').touch(mode=0o600)
  for user in ['mjwatch','mjwatch-torr']:
   try:run('id','-u',user)
   except subprocess.CalledProcessError:run('useradd','--system','--no-create-home','--shell','/usr/sbin/nologin',user)
  for path,user in [('/etc/mjwatch','root:mjwatch'),('/var/lib/mjwatch','mjwatch:mjwatch'),('/var/lib/torrserver-mjwatch','mjwatch-torr:mjwatch-torr'),('/var/backups/mjwatch','mjwatch:mjwatch')]:
   pathlib.Path(path).mkdir(mode=0o750,parents=True,exist_ok=True);owner(path,user,0o750 if path=='/etc/mjwatch' else 0o700)
  release=pathlib.Path('/opt/mjwatch/releases')/manifest['version'];release.mkdir(parents=True,exist_ok=True)
  for directory in [pathlib.Path('/opt/mjwatch'),pathlib.Path('/opt/mjwatch/releases'),release]:directory.chmod(0o755)
  (release/'.mjwatch-owned').touch(mode=0o600)
  status('installing_torrserver')
  for name,source in [('torrserver',f'linux-{arch}/TorrServer'),('mjwatch',f'linux-{arch}/mjwatch')]:
   tmp=release/(name+'.new');shutil.copyfile(UPLOAD/source,tmp);tmp.chmod(0o755);os.replace(tmp,release/name)
  shutil.copyfile(UPLOAD/'manifest.json',release/'manifest.json');(release/'manifest.json').chmod(0o644)
  status('installing_mjwatch')
  if not config_path.exists():
   password=secrets.token_urlsafe(32)
   atomic('/var/lib/torrserver-mjwatch/accs.db',{'mjwatch':password});owner('/var/lib/torrserver-mjwatch/accs.db','mjwatch-torr:mjwatch-torr',0o600)
   config=dict(database='/var/lib/mjwatch/state.db',listen=f":{plan['apiPort']}",certificate='/etc/mjwatch/tls.crt',privateKey='/etc/mjwatch/tls.key',torrUrl=f"http://127.0.0.1:{plan['torrPort']}",torrUser='mjwatch',torrPassword=password,secretKey='/etc/mjwatch/secret.key',backupDir='/var/backups/mjwatch')
   atomic(config_path,config,0o640);owner(config_path,'root:mjwatch',0o640)
  config=json.loads(config_path.read_text())
  status('securing')
  tls=pathlib.Path('/etc/mjwatch')
  if not (tls/'ca.crt').exists():run('openssl','req','-x509','-newkey','rsa:3072','-nodes','-days','3650','-subj','/CN=MJWatch Server CA','-keyout',str(tls/'ca.key'),'-out',str(tls/'ca.crt'))
  if not (tls/'tls.crt').exists():
   run('openssl','req','-new','-newkey','rsa:3072','-nodes','-subj','/CN=MJWatch','-keyout',str(tls/'tls.key'),'-out',str(tls/'tls.csr'))
   try:ipaddress.ip_address(host);san='IP:'+host
   except ValueError:san='DNS:'+host
   (tls/'san.cnf').write_text('subjectAltName='+san+'\nextendedKeyUsage=serverAuth\n')
   run('openssl','x509','-req','-in',str(tls/'tls.csr'),'-CA',str(tls/'ca.crt'),'-CAkey',str(tls/'ca.key'),'-CAcreateserial','-days','365','-extfile',str(tls/'san.cnf'),'-out',str(tls/'tls.crt'))
  for name in ['ca.key','tls.key']:owner(tls/name,'root:mjwatch' if name=='tls.key' else 'root:root',0o640 if name=='tls.key' else 0o600)
  for name in ['ca.crt','tls.crt']:owner(tls/name,'root:mjwatch',0o644)
  run(str(release/'mjwatch'),'init-key','/etc/mjwatch/secret.key');owner('/etc/mjwatch/secret.key','root:mjwatch',0o640)
  current=pathlib.Path('/opt/mjwatch/current')
  if current.exists() and not current.is_symlink():raise RuntimeError('release_path_conflict')
  temporary=pathlib.Path('/opt/mjwatch/current.next');temporary.unlink(missing_ok=True);temporary.symlink_to(release);os.replace(temporary,current)
  unit('mjwatch-torrserver.service','mjwatch-torr',f"/opt/mjwatch/current/torrserver --ip 127.0.0.1 --port {plan['torrPort']} --path /var/lib/torrserver-mjwatch --httpauth",'/var/lib/torrserver-mjwatch')
  unit('mjwatch.service','mjwatch','/opt/mjwatch/current/mjwatch serve /etc/mjwatch/config.json','/var/lib/mjwatch /var/backups/mjwatch')
  for helper in ['maintenance.py','renew.py']:shutil.copyfile(UPLOAD/helper,release/helper);(release/helper).chmod(0o700)
  shutil.copyfile(UPLOAD/'operation.py','/opt/mjwatch/operation.py');pathlib.Path('/opt/mjwatch/operation.py').chmod(0o700)
  shutil.copyfile(UPLOAD/'release-public.pem','/etc/mjwatch/release-public.pem');pathlib.Path('/etc/mjwatch/release-public.pem').chmod(0o644)
  shutil.copyfile(UPLOAD/'mjwatchctl','/usr/local/sbin/mjwatchctl');pathlib.Path('/usr/local/sbin/mjwatchctl').chmod(0o700)
  (ROOT/'owned').touch(mode=0o600)
  pathlib.Path('/etc/systemd/system/mjwatch-renew.service').write_text('[Unit]\nDescription=Renew MJWatch leaf certificate\n[Service]\nType=oneshot\nExecStart=/usr/bin/python3 /opt/mjwatch/current/renew.py\nUMask=0077\n')
  pathlib.Path('/etc/systemd/system/mjwatch-renew.timer').write_text('[Unit]\nDescription=Renew MJWatch TLS certificate\n[Timer]\nOnCalendar=daily\nPersistent=true\n[Install]\nWantedBy=timers.target\n')
  for name in ['mjwatch-renew.service','mjwatch-renew.timer']:pathlib.Path('/etc/systemd/system',name).chmod(0o644)
  run('systemctl','daemon-reload');run('systemctl','enable','--now','mjwatch-renew.timer');run('systemctl','enable','--now','mjwatch-torrserver.service')
  # Initial safe defaults are applied only once; retries preserve user settings.
  if not (ROOT/'torr-initialized').exists():
   auth='Basic '+base64.b64encode(f"{config['torrUser']}:{config['torrPassword']}".encode()).decode()
   def torr(action,sets=None):
    data=dict(action=action)
    if sets is not None:data['sets']=sets
    req=urllib.request.Request(config['torrUrl']+'/settings',json.dumps(data).encode(),{'Content-Type':'application/json','Authorization':auth})
    for attempt in range(30):
     try:
      with urllib.request.urlopen(req,timeout=10) as response:return response.read()
     except OSError:
      if attempt==29:raise
      time.sleep(1)
   settings=json.loads(torr('get'));settings.update(EnableDLNA=False,EnableBonjour=False,EnableRutorSearch=False,EnableTorznabSearch=False,TrackTimecode=False,EnableLPD=False,UseDisk=False,TorrentsSavePath="/var/lib/torrserver-mjwatch/cache",DisableUPNP=True,PeersListenPort=plan['peerPort'],CacheSize=64*1024**2)
   torr('set',settings);(ROOT/'torr-initialized').touch(mode=0o600)
  # Existing firewall policy is never flushed, enabled or disabled.
  if shutil.which('ufw') and 'Status: active' in run('ufw','status'):
   run('ufw','allow',str(plan['apiPort'])+'/tcp','comment','MJWatch HTTPS')
   for proto in ['tcp','udp']:run('ufw','allow',str(plan['peerPort'])+'/'+proto,'comment','MJWatch peers')
  run('systemctl','enable','--now','mjwatch.service')
  status('verifying')
  for name in ['mjwatch.service','mjwatch-torrserver.service']:run('systemctl','is-active','--quiet',name);run('systemctl','is-enabled','--quiet',name)
  for attempt in range(30):
   try:
    code=run('curl','--silent','--show-error','--output','/dev/null','--write-out','%{http_code}','--cacert','/etc/mjwatch/ca.crt',f"https://{host}:{plan['apiPort']}/api/v1/me")
    if code=='401':break
    raise RuntimeError('auth_health_failed')
   except subprocess.CalledProcessError as error:
    # Authenticated verification is completed by the app; unauthenticated /me must reject.
    if attempt==29:raise RuntimeError('https_unreachable')
    time.sleep(1)
  else:raise RuntimeError('auth_not_enforced')
  status('awaiting_app_verification','installed')
try:main()
except Exception as error:
 code=str(error) if isinstance(error,RuntimeError) else type(error).__name__
 if isinstance(error,subprocess.CalledProcessError):
  command=pathlib.Path(error.cmd[0]).name
  if command in ['apt-get','openssl','curl','systemctl','useradd','id','chown','ufw']:
   code='command_failed_'+command.replace('-','_')
 try:status(CURRENT_STAGE,'cancelled' if code=='cancelled_at_safe_boundary' else 'failed',code)
 except Exception:pass
 sys.exit(1)
