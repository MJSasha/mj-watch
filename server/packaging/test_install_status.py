"""Exercise the real status writer/error handler without any host installation."""
import json, pathlib, subprocess, tempfile, unittest

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

if __name__=='__main__':unittest.main()
