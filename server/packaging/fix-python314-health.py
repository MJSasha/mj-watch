#!/usr/bin/env python3
"""One-time repair for pre-0.1.3 maintenance on Python 3.13+, without CA rotation."""
import fcntl, json, os, pathlib, shutil, ssl, stat, tempfile, urllib.error, urllib.request

OLD="ctx=ssl.create_default_context(cafile='/etc/mjwatch/ca.crt')"
NEW="ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT);ctx.minimum_version=ssl.TLSVersion.TLSv1_2;ctx.load_verify_locations(cafile='/etc/mjwatch/ca.crt')"

def patch_helper(path):
 original=path.read_text()
 if NEW in original or 'ctx=health_tls_context(' in original:return False
 if original.count(OLD)!=1:raise RuntimeError('Неизвестная версия помощника. Файл не изменён.')
 updated=original.replace(OLD,NEW)
 compile(updated,str(path),'exec')
 backup=path.with_name(path.name+'.before-python314')
 if not backup.exists():shutil.copy2(path,backup)
 mode=stat.S_IMODE(path.stat().st_mode)
 fd,name=tempfile.mkstemp(prefix='.maintenance-',dir=path.parent)
 try:
  with os.fdopen(fd,'w') as output:output.write(updated);output.flush();os.fsync(output.fileno())
  os.chmod(name,mode);os.replace(name,path)
 finally:
  if os.path.exists(name):os.unlink(name)
 return True

def main():
 if os.geteuid()!=0:raise RuntimeError('Запустите команду от root.')
 root=pathlib.Path('/var/lib/mjwatch-install')
 with (root/'lock').open('a') as lock:
  try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
  except BlockingIOError:raise RuntimeError('На сервере идёт операция. Дождитесь её завершения.')
  helper=pathlib.Path('/opt/mjwatch/current/maintenance.py').resolve(strict=True)
  if not helper.is_relative_to('/opt/mjwatch/releases') or helper.stat().st_uid!=0 or helper.stat().st_mode&0o022:raise RuntimeError('Неожиданный путь или права помощника. Файл не изменён.')
  config=json.loads(pathlib.Path('/etc/mjwatch/config.json').read_text())
  host=json.loads((root/'plan.json').read_text())['host'];host='['+host+']' if ':' in host else host
  ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT);ctx.minimum_version=ssl.TLSVersion.TLSv1_2;ctx.load_verify_locations(cafile='/etc/mjwatch/ca.crt')
  # Read-only: no bootstrap session, invite, credentials or password are sent.
  try:
   with urllib.request.urlopen('https://'+host+config['listen']+'/api/v1/me',context=ctx,timeout=10):
    raise RuntimeError('Сервер не требует входа. Исправление не применено.')
  except urllib.error.HTTPError as error:
   if error.code!=401:raise RuntimeError('Сервер вернул HTTP '+str(error.code)+'. Исправление не применено.')
  changed=patch_helper(helper)
  print('Проверка HTTPS пройдена. '+('Исправление установлено.' if changed else 'Исправление уже установлено.'))
  print('Сертификаты, пользователи и настройки сохранены. Теперь можно повторить обновление сервера в MJWatch.')

if __name__=='__main__':
 try:main()
 except Exception as error:raise SystemExit(str(error))
