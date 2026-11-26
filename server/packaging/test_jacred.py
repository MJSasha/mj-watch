import io
import hashlib
import json
import pathlib
import re
import runpy
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
import zipfile

import jacred

restore = runpy.run_path(str(pathlib.Path(__file__).resolve().parents[2] / 'core/src/main/assets/jacred-repair.py'))['restore']


class JacredTest(unittest.TestCase):
    def test_admin_session_requires_cookie_and_preserves_managed_config(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            config = root / 'init.conf'
            previous = jacred.merged_config({}, jacred.DEFAULTS)
            config.write_text(json.dumps(previous))
            (root / 'wwwroot').mkdir()
            (root / 'wwwroot/index.html').write_text('JacRed editor')

            def upstream(handler, path, body=None):
                if path == jacred.WEB_CONFIG:
                    return 200, json.dumps({'data': json.loads(config.read_text())}).encode()
                self.assertEqual(jacred.WEB_CONFIG + '/format', path)
                validated = json.loads(body)['data']
                self.assertEqual('127.0.0.1', validated['listenip'])
                return 200, json.dumps({'ok': True, 'data': validated}).encode()

            with patch.multiple(jacred, CONTROL=root, RELEASE=root, CONFIG=config,
                                operation_active=lambda: False, atomic=lambda path, value, *_: path.write_text(value)), \
                 patch.object(jacred.grp, 'getgrnam'), patch.object(jacred.AdminWebHandler, 'upstream', upstream), \
                 jacred.ThreadingHTTPServer(('127.0.0.1', 0), jacred.AdminWebHandler) as server:
                server.token = 'test-secret'
                server.cookie_name = 'admin'
                server.config_revision = config.stat().st_mtime_ns
                origin = f'http://127.0.0.1:{server.server_port}'
                headers = {'Cookie': 'admin=test-secret', 'Origin': origin}
                worker = threading.Thread(target=server.serve_forever, daemon=True)
                worker.start()

                def request(path, request_headers=None, data=None):
                    body = json.dumps({'data': data}).encode() if data is not None else None
                    req = urllib.request.Request(origin + path, body, request_headers or {})
                    return urllib.request.urlopen(req, timeout=5)

                try:
                    with self.assertRaises(urllib.error.HTTPError) as rejected:
                        request('/settings')
                    self.assertEqual(403, rejected.exception.code)
                    with request('/settings', headers) as response:
                        self.assertEqual(b'JacRed editor', response.read())
                    with self.assertRaises(urllib.error.HTTPError) as cross_origin:
                        request(jacred.WEB_CONFIG, dict(headers, Origin='http://127.0.0.1:5555'), {'logFdb': False})
                    self.assertEqual(403, cross_origin.exception.code)
                    with self.assertRaises(urllib.error.HTTPError) as managed:
                        request(jacred.WEB_CONFIG, headers, {'ListenIP': 'any'})
                    self.assertEqual(400, managed.exception.code)
                    with request(jacred.WEB_CONFIG, headers, {'futureOption': 3, 'logFdb': False}) as response:
                        self.assertTrue(json.load(response)['ok'])
                    advanced = jacred.merged_config(json.loads(config.read_text()), dict(jacred.DEFAULTS, interval=60))
                    self.assertFalse(advanced['logFdb'])
                    self.assertEqual(3, advanced['futureOption'])
                    self.assertEqual(previous['apikey'], advanced['apikey'])
                    self.assertEqual(60, advanced['timeSync'])
                finally:
                    server.shutdown()
                    worker.join()

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

    def test_tracker_proxy_round_trips_and_preserves_other_rules(self):
        other = dict(pattern=r'\.example/', list=['http://127.0.0.1:8080'])
        previous = dict(GlobalProxy=[other], cffetch=dict(enable=False))
        proxy = dict(url='socks5://127.0.0.1:40000', hosts=['tracker.example', 'tracker-mirror.example'])
        draft = jacred.validate(dict(jacred.DEFAULTS, trackerProxy=proxy))
        config = jacred.merged_config(previous, draft)
        self.assertEqual(proxy, jacred.tracker_proxy(config))
        pattern = re.compile(config['GlobalProxy'][0]['pattern'])
        self.assertTrue(pattern.search('https://TRACKER.example:443/forum'))
        self.assertTrue(pattern.search('https://tracker-mirror.example/forum'))
        self.assertFalse(pattern.search('https://tracker.example.evil.example/forum'))
        self.assertFalse(pattern.search('https://tracker.example:80@evil.example/forum'))
        self.assertEqual([other], config['GlobalProxy'][1:])
        self.assertEqual(config['GlobalProxy'], jacred.merged_config(config, jacred.DEFAULTS)['GlobalProxy'])
        cleared = jacred.merged_config(config, dict(draft, trackerProxy=dict(url='', hosts=[])))
        self.assertEqual([other], cleared['GlobalProxy'])
        self.assertEqual(previous['cffetch'], cleared['cffetch'])
        for change in (dict(url='socks5://192.0.2.1:40000'), dict(url='http://127.0.0.1:65536'),
                       dict(hosts=['tracker.example|evil.example']), dict(hosts=[])):
            with self.subTest(change=change), self.assertRaises(ValueError):
                jacred.validate(dict(draft, trackerProxy=dict(proxy, **change)))

    def test_unreachable_tracker_proxy_leaves_existing_config_untouched(self):
        proxy = dict(url='http://127.0.0.1:40000', hosts=['tracker.example'])
        with patch.object(jacred, 'report'), patch.object(jacred, 'load') as load, \
             patch.object(jacred.socket, 'create_connection', side_effect=ConnectionRefusedError):
            with self.assertRaisesRegex(RuntimeError, 'tracker_proxy_unreachable'):
                jacred.apply(dict(jacred.DEFAULTS, trackerProxy=proxy))
        load.assert_not_called()

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
