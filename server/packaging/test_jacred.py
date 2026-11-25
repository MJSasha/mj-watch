import io
import pathlib
import tempfile
import unittest
import zipfile

import jacred


class JacredTest(unittest.TestCase):
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


if __name__ == '__main__':
    unittest.main()
