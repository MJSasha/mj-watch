"""Exercise real TLS for legacy installed CAs and newly generated strict CAs."""
import http.server, io, json, pathlib, ssl, subprocess, tempfile, threading, unittest, urllib.request
from unittest import mock

BASE=pathlib.Path(__file__).parent

def definitions(name):
 scope={}
 exec((BASE/name).read_text().rsplit('try:main()',1)[0],scope)
 return scope

class Handler(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  self.send_response(200);self.end_headers();self.wfile.write(b'{"ok":true}')
 def log_message(self,*args):pass

class HealthTlsTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.tmp=tempfile.TemporaryDirectory();cls.root=pathlib.Path(cls.tmp.name)
  cls.context=staticmethod(definitions('maintenance.py')['health_tls_context'])
  cls.openssl('req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=Legacy MJWatch CA','-addext','basicConstraints=critical,CA:TRUE','-addext','subjectKeyIdentifier=hash','-keyout','ca.key','-out','ca.crt')
  cls.openssl('req','-new','-newkey','rsa:2048','-nodes','-subj','/CN=MJWatch','-keyout','leaf.key','-out','leaf.csr')
  (cls.root/'san.cnf').write_text('subjectAltName=IP:127.0.0.1\nextendedKeyUsage=serverAuth\nauthorityKeyIdentifier=keyid,issuer\nsubjectKeyIdentifier=hash\n')
  cls.sign('leaf.crt','1')
  (cls.root/'index.txt').touch();(cls.root/'serial').write_text('01\n')
  (cls.root/'ca.cnf').write_text('[ca]\ndefault_ca=local\n[local]\ndatabase=index.txt\nserial=serial\nnew_certs_dir=.\ncertificate=ca.crt\nprivate_key=ca.key\ndefault_md=sha256\npolicy=names\n[names]\ncommonName=supplied\n')
  cls.openssl('ca','-batch','-config','ca.cnf','-in','leaf.csr','-out','expired.crt','-startdate','20000101000000Z','-enddate','20000102000000Z','-notext','-extfile','san.cnf')
  cls.new=cls.root/'new';cls.new.mkdir()
  definitions('install.py')['create_ca'](cls.new)
  cls.openssl('x509','-req','-in','leaf.csr','-CA',str(cls.new/'ca.crt'),'-CAkey',str(cls.new/'ca.key'),'-CAcreateserial','-days','1','-extfile','san.cnf','-out','new-leaf.crt')
 @classmethod
 def tearDownClass(cls):cls.tmp.cleanup()
 @classmethod
 def openssl(cls,*args):
  return subprocess.run(['openssl',*args],cwd=cls.root,check=True,capture_output=True).stdout
 @classmethod
 def sign(cls,out,days):
  cls.openssl('x509','-req','-in','leaf.csr','-CA','ca.crt','-CAkey','ca.key','-CAcreateserial','-days',days,'-extfile','san.cnf','-out',out)
 def request(self,context,certificate='leaf.crt',host='127.0.0.1'):
  server=http.server.HTTPServer(('127.0.0.1',0),Handler)
  tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(self.root/certificate,self.root/'leaf.key')
  server.socket=tls.wrap_socket(server.socket,server_side=True)
  thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
  try:
   opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=context))
   with opener.open(f'https://{host}:{server.server_port}/',timeout=3) as response:return json.load(response)
  finally:server.shutdown();server.server_close();thread.join(timeout=3)
 @unittest.skipIf(ssl.OPENSSL_VERSION_INFO<(3,0),"Missing CA key usage is rejected by OpenSSL 3 strict verification")
 def test_legacy_ca_missing_key_usage_reproduces_strict_failure(self):
  ctx=ssl.create_default_context(cafile=str(self.root/'ca.crt'));ctx.verify_flags|=ssl.VERIFY_X509_STRICT
  with self.assertRaises(urllib.error.URLError) as caught:self.request(ctx)
  self.assertIsInstance(caught.exception.reason,ssl.SSLCertVerificationError)
  self.assertIn("key usage",caught.exception.reason.verify_message.lower())
 def test_compatible_context_authenticates_legacy_ca(self):
  ctx=self.context(str(self.root/'ca.crt'))
  self.assertEqual(ssl.CERT_REQUIRED,ctx.verify_mode);self.assertTrue(ctx.check_hostname)
  self.assertGreaterEqual(ctx.minimum_version,ssl.TLSVersion.TLSv1_2)
  self.assertEqual({'ok':True},self.request(ctx))
 def test_wrong_ca_hostname_and_expiry_remain_rejected(self):
  cases=[(self.context(str(self.new/'ca.crt')),'leaf.crt','127.0.0.1'),(self.context(str(self.root/'ca.crt')),'leaf.crt','localhost'),(self.context(str(self.root/'ca.crt')),'expired.crt','127.0.0.1')]
  for ctx,cert,host in cases:
   with self.subTest(cert=cert,host=host),self.assertRaises(urllib.error.URLError) as caught:self.request(ctx,cert,host)
   self.assertIsInstance(caught.exception.reason,ssl.SSLCertVerificationError)
 def test_new_installer_ca_supports_strict_verification(self):
  self.openssl('verify','-x509_strict','-CAfile',str(self.new/'ca.crt'),str(self.root/'new-leaf.crt'))

class RecoveryTest(unittest.TestCase):
 def test_health_uses_scoped_token_without_bootstrap_or_device_receipts(self):
  scope=definitions('maintenance.py');commands=[]
  def run(*args,input=None):
   commands.append((args,input))
   if args[0]=='systemctl':return ''
   self.assertEqual('health-token',args[1]);self.assertIsNone(input)
   return json.dumps({'accessToken':'status-only-token'})
  with tempfile.TemporaryDirectory() as folder:
   root=pathlib.Path(folder);(root/'plan.json').write_text('{"host":"127.0.0.1"}')
   scope.update(ROOT=root,run=run,health_tls_context=lambda path:'verified-ca-context')
   def response(req,context,timeout):
    self.assertEqual('Bearer status-only-token',req.get_header('Authorization'))
    self.assertEqual('verified-ca-context',context)
    return io.StringIO('{"databaseHealthy":true,"torrserverHealthy":true}')
   with mock.patch('urllib.request.urlopen',side_effect=response):scope['healthy']({'listen':':443'})
   self.assertEqual(1,sum(args[1]=='health-token' for args,_ in commands))
   self.assertEqual(['plan.json'],[p.name for p in root.iterdir()])
 def test_patch_is_atomic_idempotent_and_preserves_backup_and_mode(self):
  import runpy
  recovery=runpy.run_path(str(BASE/'fix-python314-health.py'))
  with tempfile.TemporaryDirectory() as folder:
   helper=pathlib.Path(folder)/'maintenance.py'
   original=(BASE/'maintenance.py').read_text().replace("ctx=health_tls_context('/etc/mjwatch/ca.crt')", "ssl=__import__('ssl');"+recovery['OLD'])
   helper.write_text(original);helper.chmod(0o700)
   self.assertTrue(recovery['patch_helper'](helper))
   self.assertFalse(recovery['patch_helper'](helper))
   self.assertEqual(original,helper.with_name('maintenance.py.before-python314').read_text())
   self.assertEqual(0o700,helper.stat().st_mode&0o777)
   self.assertIn(recovery['NEW'],helper.read_text())
   helper.write_text('unknown_version=True')
   with self.assertRaises(RuntimeError):recovery['patch_helper'](helper)
   self.assertEqual('unknown_version=True',helper.read_text())

if __name__=='__main__':unittest.main()
