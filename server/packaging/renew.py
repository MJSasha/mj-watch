#!/usr/bin/env python3
"""Refresh the server leaf without replacing its pinned CA or restarting streams."""
import fcntl,json,os,pathlib,shutil,subprocess,time
ROOT=pathlib.Path('/var/lib/mjwatch-install')
TLS=pathlib.Path('/etc/mjwatch')
def run(*args):subprocess.run(args,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
with (ROOT/'lock').open('w') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 if subprocess.run(['openssl','x509','-checkend','2592000','-noout','-in',str(TLS/'tls.crt')],stdout=subprocess.DEVNULL).returncode==0:raise SystemExit(0)
 temporary=TLS/('leaf-'+str(time.time_ns()));temporary.mkdir(mode=0o700)
 try:
  run('openssl','req','-new','-newkey','rsa:3072','-nodes','-subj','/CN=MJWatch','-keyout',str(temporary/'tls.key'),'-out',str(temporary/'tls.csr'))
  run('openssl','x509','-req','-in',str(temporary/'tls.csr'),'-CA',str(TLS/'ca.crt'),'-CAkey',str(TLS/'ca.key'),'-CAcreateserial','-days','365','-extfile',str(TLS/'san.cnf'),'-out',str(temporary/'tls.crt'))
  for name in ['tls.key','tls.crt']:shutil.chown(temporary/name,'root','mjwatch');(temporary/name).chmod(0o640 if name=='tls.key' else 0o644)
  os.replace(temporary/'tls.crt',TLS/'tls.crt');os.replace(temporary/'tls.key',TLS/'tls.key')
 finally:shutil.rmtree(temporary)
