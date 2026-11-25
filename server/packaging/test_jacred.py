import io
import hashlib
import json
import pathlib
import runpy
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import jacred

restore = runpy.run_path(str(pathlib.Path(__file__).resolve().parents[2] / 'core/src/main/assets/jacred-repair.py'))['restore']


class JacredTest(unittest.TestCase):
    def test_manual_run_consumes_request_and_records_progress_and_history(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            config, request, report = (root / name for name in ('config.json', 'request', 'report.json'))
            config.write_text(json.dumps(dict(collect=True, synctrackers=['rutor', 'nnmclub'], devkey='private')))
            request.touch()

            def request_tracker(path, *_):
                active = json.loads(report.read_text())
                running = [row['tracker'] for row in active['results'] if row['state'] == 'running']
                self.assertEqual([path.split('/')[2]], running)
                self.assertFalse(request.exists())
                return io.BytesIO(b'page / False' if running == ['rutor'] else b'ok')

            with patch.multiple(jacred, CONFIG=config, CONTROL=root, INDEX_STATUS=report, INDEX_REQUEST=request,
                                atomic=lambda path, value, *_: path.write_text(value), local_request=request_tracker), \
                 patch.object(jacred.grp, 'getgrnam'):
                jacred.index()
            result = json.loads(report.read_text())
            self.assertEqual('complete', result['state'])
            self.assertEqual('manual', result['trigger'])
            self.assertEqual(['parser_error', 'responded'], [r['state'] for r in result['results']])
            self.assertEqual(1, result['history'][0]['failed'])
            self.assertNotIn('private', report.read_text())

    def test_parser_status_uses_known_markers_instead_of_http_200(self):
        for body, expected in [('work', 'busy'), ('disabled', 'disabled'),
                               ('films - 0 / False', 'parser_error'), ('error: refused', 'parser_error'),
                               ('films - 0 / True', 'responded')]:
            with self.subTest(body=body):
                self.assertEqual(expected, jacred.parser_result(body))

    def test_settings_can_enable_collection_and_validate_upstream(self):
        ready = dict(jacred.DEFAULTS, collect=True, syncUrl='https://catalog.example/sync')
        self.assertEqual(ready, jacred.validate(ready))
        for change in (dict(syncUrl='http://catalog.example'),
                       dict(syncUrl='https://user:secret@catalog.example'),
                       dict(trackers=[]), dict(trackers=['../stats']),
                       dict(interval=True)):
            with self.subTest(change=change), self.assertRaises(ValueError):
                jacred.validate(dict(ready, **change))

    def test_zip_cannot_write_outside_the_staging_directory(self):
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, 'w') as output:
            output.writestr('JacRed', 'binary')
            output.writestr('../unsafe', 'staged')
        with tempfile.TemporaryDirectory() as folder, self.assertRaisesRegex(RuntimeError, 'archive_layout'):
            jacred.extract(archive, pathlib.Path(folder) / 'release')
            self.assertFalse((pathlib.Path(folder) / 'unsafe').exists())

    def test_repair_requires_the_installed_manifest_hash(self):
        helper = b'#!/usr/bin/env python3\n'
        entry = dict(path='jacred.py', size=len(helper), sha256=hashlib.sha256(helper).hexdigest())
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            releases = root / 'releases'
            release = releases / '0.1.14'
            release.mkdir(parents=True)
            (release / '.mjwatch-owned').touch()
            manifest = release / 'manifest.json'
            current = root / 'current'
            current.symlink_to(release)
            staged = root / 'upload.py'
            manifest.write_text(json.dumps(dict(version=release.name, files=[])))
            self.assertEqual('update_required', restore(current, releases, staged, helper))
            manifest.write_text(json.dumps(dict(version=release.name, files=[entry])))
            self.assertEqual('helper_unavailable', restore(current, releases, staged, b'altered'))
            self.assertFalse((current / 'jacred.py').exists())

            self.assertEqual('ready', restore(current, releases, staged, helper))
            restored = release / 'jacred.py'
            self.assertEqual(helper, restored.read_bytes())
            self.assertEqual(0o700, restored.stat().st_mode & 0o777)
            restored.unlink()

            staged.write_bytes(helper)
            self.assertEqual('ready', restore(current, releases, staged, b''))
            self.assertEqual(helper, restored.read_bytes())


if __name__ == '__main__':
    unittest.main()
