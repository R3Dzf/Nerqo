"""Guard the shipping UI surface and pinned signing identity."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]

class UISigningTests(unittest.TestCase):
    def test_browser_ui_keeps_transfer_controls(self):
        html = (ROOT/'web/index.html').read_text(encoding='utf-8')
        js = (ROOT/'web/app.js').read_text(encoding='utf-8')
        for item in ['pickFiles','pickFolder','filesInput','folderInput','dropZone','noteContent',
                     'sendNote','pairForm','pinInput','items','checkUpdates','prepareUpdate','installUpdate',
                     'seeConnect','updateProgress','refresh','themeToggle']:
            with self.subTest(element=item):
                self.assertIn(f'id="{item}"',html)
        used=set(re.findall(r"\$\('([A-Za-z][\w-]*)'\)",js))
        provided=set(re.findall(r'id="([A-Za-z][\w-]*)"',html))
        self.assertEqual(used-provided,set())
    def test_cert_fingerprint_is_public_not_private_key(self):
        cert=(ROOT/'android/RELEASE_SIGNING_CERT.sha256').read_text().strip()
        self.assertRegex(cert,r'^[0-9a-f]{64}$')
        self.assertFalse(list(ROOT.rglob('*.p12')))
        self.assertFalse(list(ROOT.rglob('*.jks')))
        self.assertFalse(list(ROOT.rglob('*.keystore')))
        self.assertFalse(list(ROOT.rglob('*signing-recovery*.txt')))
    def test_versions_match(self):
        from nerqo.version import VERSION
        self.assertEqual(VERSION,'0.3.0')
        gradle=(ROOT/'android/app/build.gradle.kts').read_text()
        self.assertIn('versionName = "0.5.0"',gradle)
        self.assertIn('versionCode = 5',gradle)
    def test_signed_release_verifies_identity(self):
        wf=(ROOT/'.github/workflows/release.yml').read_text()
        self.assertIn('Confirm permanent Android signing identity',wf)
        self.assertIn('Verify signed Android APK',wf)
        self.assertIn('NERQO_ANDROID_KEYSTORE_BASE64',wf)
        self.assertIn('apksigner',wf)
        self.assertIn('workflow_dispatch:',wf)
        ci=(ROOT/'.github/workflows/build.yml').read_text()
        self.assertIn('assembleRelease',ci)
        self.assertIn('app-release-unsigned.apk',ci)
        gradle=(ROOT/'android/app/build.gradle.kts').read_text()
        self.assertIn('if (!signingFile.isNullOrBlank())',gradle)

if __name__ == '__main__':
    unittest.main()
