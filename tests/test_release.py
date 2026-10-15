import json
import tempfile
import unittest
from pathlib import Path
from tools.build_release import create_release
from nerqo.version import VERSION

class ReleaseTests(unittest.TestCase):
    def test_release_builds_both_platforms_with_patches(self):
        with tempfile.TemporaryDirectory() as directory:
            d = Path(directory)
            old = d/'base.exe'; new=d/'new.exe';apk=d/'new.apk';old_apk=d/'old.apk'
            old.write_bytes(b'a'*(1024*1024));new.write_bytes(b'a'*(1024*1024)+b'changed')
            old_apk.write_bytes(b'b'*(1024*1024));apk.write_bytes(b'b'*(1024*1024)+b'patch')
            f=create_release(VERSION,new,apk,d/'release','0.1.0',old,old_apk)
            obj=json.loads(f.read_text())
            self.assertEqual(set(obj['assets']),{'windows','android'})
            self.assertTrue(obj['assets']['windows']['deltas'])
            self.assertTrue(obj['assets']['android']['deltas'])
            self.assertTrue((d/'release'/'Nerqo-Android.apk').exists())

    def test_requires_matching_version(self):
        with self.assertRaises(ValueError):
            create_release('0.2.5','missing','missing','missing')
