import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from management import execute
from update_supply import unpack


class ManagementTests(unittest.TestCase):
    def test_rejects_unknown_actions_and_unconfirmed_operations(self):
        with self.assertRaises(ValueError):
            execute('maintenance/start', {'operationId': 'fixture-operation-id', 'confirmed': True,
                                          'action': 'run-command'})
        with self.assertRaises(ValueError):
            execute('torr/start', {'operationId': 'fixture-operation-id'})
        with self.assertRaises(ValueError):
            execute('maintenance/status', {'operationId': '../fixture-id'})
        with self.assertRaises(ValueError):
            execute('jacred/exec', {'command': 'synthetic'})

    def test_bundle_rejects_modified_payload_with_valid_manifest_signature(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            private, public, manifest, signature = [root / name for name in
                                                     ('private.pem', 'public.pem', 'manifest.json', 'manifest.sig')]
            payload = b'signed deployment test'
            manifest.write_text(json.dumps({'version': '0.1.23', 'channel': 'release', 'files': [
                {'path': 'management.py', 'size': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()},
            ]}))
            subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048',
                            '-out', str(private)], check=True, capture_output=True)
            subprocess.run(['openssl', 'pkey', '-in', str(private), '-pubout', '-out', str(public)],
                           check=True, capture_output=True)
            subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(private), '-out', str(signature), str(manifest)],
                           check=True, capture_output=True)
            path = root / 'candidate.zip'
            for valid in (True, False):
                with zipfile.ZipFile(path, 'w') as archive:
                    archive.write(manifest, 'manifest.json')
                    archive.write(signature, 'manifest.sig')
                    archive.writestr('management.py', payload if valid else b'x' * len(payload))
                destination = root / str(valid)
                destination.mkdir()
                if valid:
                    unpack(path, destination, '0.1.23', public)
                    self.assertEqual(payload, (destination / 'management.py').read_bytes())
                else:
                    with self.assertRaisesRegex(RuntimeError, 'supply_integrity'):
                        unpack(path, destination, '0.1.23', public)


if __name__ == '__main__':
    unittest.main()
