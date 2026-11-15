"""Exercise the real status writer/error handler without any host installation."""
import json, pathlib, subprocess, tempfile, unittest
from types import SimpleNamespace
from unittest.mock import patch

class InstallStatusTest(unittest.TestCase):
 def run_failure(self, stage, failure, cancel=False):
  source=pathlib.Path(__file__).with_name('install.py').read_text()
  definitions,handler=source.rsplit('try:main()',1)
  scope={}
  exec(definitions,scope)
  with tempfile.TemporaryDirectory() as folder:
   root=pathlib.Path(folder)
   scope['ROOT']=root
   if cancel:(root/'cancel').touch()
   def fail():
    scope['status'](stage)
    raise failure
   scope['main']=fail
   with self.assertRaises(SystemExit):exec('try:main()'+handler,scope)
   return json.loads((root/'status.json').read_text())
 def test_keeps_failed_stage_and_safe_command_name(self):
  state=self.run_failure('securing',subprocess.CalledProcessError(1,['systemctl','secret-argument']))
  self.assertEqual(('securing','failed','command_failed_systemctl'),(state['stage'],state['state'],state['code']))
  self.assertNotIn('secret',json.dumps(state))
 def test_cancellation_stays_cancelled(self):
  state=self.run_failure('installing_mjwatch',RuntimeError('unused'),True)
  self.assertEqual(('installing_mjwatch','cancelled','cancelled_at_safe_boundary'),(state['stage'],state['state'],state['code']))
 def test_preflight_failure_preserves_reason(self):
  state=self.run_failure('checking',RuntimeError('unsupported_os'))
  self.assertEqual('unsupported_os',state['code'])

class InstallPlatformTest(unittest.TestCase):
 def setUp(self):
  self.scope={}
  source=pathlib.Path(__file__).with_name('install.py').read_text().rsplit('try:main()',1)[0]
  exec(source,self.scope)
  self.scope['sys']=SimpleNamespace(version_info=(3,9))
  self.scope['platform']=SimpleNamespace(machine=lambda:'x86_64')
  self.scope['shutil']=SimpleNamespace(which=lambda command:'/usr/bin/'+command)
  self.systemd=patch.object(pathlib.Path,'is_dir',return_value=True)
  self.systemd.start();self.addCleanup(self.systemd.stop)
 def check(self,distribution,version):
  return self.scope['check_platform'](dict(ID=distribution,VERSION_ID=version))
 def test_debian_and_ubuntu_versions_use_capabilities(self):
  for distribution,versions in [('ubuntu',['20.04','22.04','24.04','25.10','26.04','future']),('debian',['11','12','13','testing'])]:
   for version in versions:
    with self.subTest(distribution=distribution,version=version):self.assertEqual('amd64',self.check(distribution,version))
  self.scope['platform']=SimpleNamespace(machine=lambda:'aarch64')
  self.assertEqual('arm64',self.check('ubuntu','26.04'))
 def test_rejects_other_distributions_even_with_debian_id_like(self):
  with self.assertRaisesRegex(RuntimeError,'unsupported_os'):
   self.scope['check_platform'](dict(ID='linuxmint',ID_LIKE='ubuntu debian',VERSION_ID='22'))
 def test_checks_python_architecture_systemd_and_tools(self):
  self.scope['sys']=SimpleNamespace(version_info=(3,8))
  with self.assertRaisesRegex(RuntimeError,'unsupported_python'):self.check('ubuntu','20.04')
  self.scope['sys']=SimpleNamespace(version_info=(3,14))
  self.scope['platform']=SimpleNamespace(machine=lambda:'riscv64')
  with self.assertRaisesRegex(RuntimeError,'unsupported_system'):self.check('ubuntu','26.04')
  self.scope['platform']=SimpleNamespace(machine=lambda:'x86_64')
  with patch.object(pathlib.Path,'is_dir',return_value=False):
   with self.assertRaisesRegex(RuntimeError,'unsupported_system'):self.check('debian','13')
  self.scope['shutil']=SimpleNamespace(which=lambda command:None if command=='apt-get' else '/usr/bin/'+command)
  with self.assertRaisesRegex(RuntimeError,'missing_system_tools'):self.check('ubuntu','26.04')

class InstallResourcesTest(unittest.TestCase):
 def test_ram_boundary_and_disk_minimum(self):
  scope={}
  source=pathlib.Path(__file__).with_name('install.py').read_text().rsplit('try:main()',1)[0]
  exec(source,scope)
  for ram,disk,accepted in [(499_999_999,2*1024**3,False),(500_000_000,2*1024**3,True),(512*1024**2,2*1024**3,True),(1_000_000_000,2*1024**3,True),(2_000_000_000,2*1024**3-1,False)]:
   with self.subTest(ram=ram,disk=disk):
    scope['os']=SimpleNamespace(sysconf=lambda name:ram if name=='SC_PHYS_PAGES' else 1)
    scope['shutil']=SimpleNamespace(disk_usage=lambda path:SimpleNamespace(free=disk))
    if accepted:scope['check_resources']()
    else:
     with self.assertRaisesRegex(RuntimeError,'insufficient_resources'):scope['check_resources']()

if __name__=='__main__':unittest.main()
