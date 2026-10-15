import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile
from tools.verify_published_apk import assert_same_payload

class APKPublishedPayloadTests(unittest.TestCase):
    def test_added_signature_metadata_is_ignored(self):
        with tempfile.TemporaryDirectory() as d:
            unsigned=Path(d)/'unsigned.apk'
            signed=Path(d)/'signed.apk'
            with ZipFile(unsigned,'w') as z:
                z.writestr('classes.dex',b'a')
                z.writestr('META-INF/com/android/build/gradle/app-metadata.properties',b'b')
            with ZipFile(signed,'w') as z:
                z.writestr('classes.dex',b'a')
                z.writestr('META-INF/com/android/build/gradle/app-metadata.properties',b'b')
                z.writestr('META-INF/MANIFEST.MF',b'signature')
                z.writestr('META-INF/NERQO-RE.RSA',b'signature')
            assert_same_payload(unsigned,signed)

    def test_tampered_app_payload_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            unsigned=Path(d)/'unsigned.apk'
            signed=Path(d)/'signed.apk'
            with ZipFile(unsigned,'w') as z:z.writestr('classes.dex',b'a')
            with ZipFile(signed,'w') as z:z.writestr('classes.dex',b'b')
            with self.assertRaisesRegex(ValueError,'payload differs'):
                assert_same_payload(unsigned,signed)

if __name__ == '__main__':unittest.main()
