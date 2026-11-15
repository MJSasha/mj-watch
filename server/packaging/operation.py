#!/usr/bin/env python3
"""Stable SSH v1 transport. Never execute commands or paths supplied by clients."""
import json,os,pathlib,re,subprocess,sys
ROOT=pathlib.Path('/var/lib/mjwatch-install')
if os.geteuid()!=0:raise SystemExit(1)
request=json.loads(sys.stdin.read(65536));operation=request.get('operationId','')
if not re.fullmatch('[A-Za-z0-9_-]{16,128}',operation):raise SystemExit(64)
command=sys.argv[1]
if command=='start':
 kind=request.get('kind')
 if kind not in ['torr','maintenance']:raise SystemExit(64)
 path=ROOT/'requests'/(operation+'.json');info=path.lstat()
 if path.is_symlink() or info.st_uid!=0 or info.st_mode&0o077:raise SystemExit(64)
 unit='mjwatch-'+kind+'-'+operation
 active=subprocess.run(['systemctl','is-active','--quiet',unit]).returncode==0
 if not active:
  if kind=='torr':
   (ROOT/'torrserver'/(operation+'.error.json')).unlink(missing_ok=True)
   args=['/opt/mjwatch/current/mjwatch','torr-apply-file','/etc/mjwatch/config.json',str(path)]
  else:
   (ROOT/(operation+'.maintenance.error.json')).unlink(missing_ok=True)
   args=['/usr/bin/python3','/opt/mjwatch/current/maintenance.py',str(path)]
  subprocess.run(['systemd-run','--unit='+unit,'--collect','--property=UMask=0077',*args],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
 print(json.dumps(dict(operationId=operation,state='running')))
else:
 if command=='torr-status':receipt=ROOT/'torrserver'/(operation+'.json');error=ROOT/'torrserver'/(operation+'.error.json')
 elif command=='maintenance-status':receipt=ROOT/(operation+'.maintenance.json');error=ROOT/(operation+'.maintenance.error.json')
 else:raise SystemExit(64)
 if receipt.exists():
  result=json.loads(receipt.read_text());out=dict(state='complete',result=result)
  if command=='maintenance-status':out.update(result);out['state']='complete'
  print(json.dumps(out))
 elif error.exists():print(error.read_text())
 else:print(json.dumps(dict(operationId=operation,state='pending')))
