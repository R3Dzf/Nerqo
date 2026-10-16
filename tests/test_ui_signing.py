"""Guard the Windows/Android native shipping surfaces and signing identity."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class NativeSigningTests(unittest.TestCase):
    def test_native_ui_is_independent_of_browser(self):
        windows = (ROOT / "desktop/Nerqo.Desktop/Nerqo.Desktop.csproj").read_text()
        android = (ROOT / "android/app/src/main/java/com/r3dzf/nerqo/MainActivity.kt").read_text()
        xaml = (ROOT / "desktop/Nerqo.Desktop/MainWindow.xaml").read_text()
        self.assertIn("<OutputType>WinExe</OutputType>", windows)
        self.assertIn("<UseWPF>true</UseWPF>", windows)
        self.assertIn("class MainActivity : ComponentActivity()", android)
        self.assertIn("setContent { NativeScreen() }", android)
        self.assertNotIn("WebView", xaml)
        self.assertIn("Choose folder to share (ZIP)", xaml)
        self.assertIn("Send folder (ZIP)", android)

    def test_cert_fingerprint_public_without_private_key(self):
        cert = (ROOT / "android/RELEASE_SIGNING_CERT.sha256").read_text().strip()
        self.assertRegex(cert, r"^[0-9a-f]{64}$")
        for extension in ("*.p12", "*.jks", "*.keystore", "*.pfx"):
            self.assertFalse(list(ROOT.rglob(extension)), extension)
        self.assertFalse(list(ROOT.rglob("*signing-recovery*.txt")))

    def test_native_versions_match(self):
        windows = (ROOT / "desktop/Nerqo.Desktop/Nerqo.Desktop.csproj").read_text()
        installer = (ROOT / "installer/Nerqo.iss").read_text()
        gradle = (ROOT / "android/app/build.gradle.kts").read_text()
        w = re.search(r"<Version>(\d+\.\d+\.\d+)</Version>", windows)
        i = re.search(r'#define MyAppVersion "(\d+\.\d+\.\d+)"', installer)
        a = re.search(r'versionName\s*=\s*"(\d+\.\d+\.\d+)"', gradle)
        self.assertTrue(all((w, i, a)))
        self.assertEqual(w.group(1), i.group(1))
        self.assertEqual(w.group(1), a.group(1))
        self.assertIn("versionCode = 5", gradle)

    def test_native_signed_release_verification(self):
        wf = (ROOT / ".github/workflows/release.yml").read_text()
        self.assertIn("Verify private-release-signed APK against reproducible payload", wf)
        self.assertIn("android/RELEASE_SIGNING_CERT.sha256", wf)
        self.assertIn("tools/verify_published_apk.py", wf)
        self.assertIn("apksigner", wf)
        self.assertIn("workflow_dispatch:", wf)
        self.assertIn("github.ref == 'refs/heads/main'", wf)
        self.assertIn("Nerqo-Windows-Setup.exe", wf)
        self.assertNotIn("pyinstaller", wf.lower())
        self.assertFalse((ROOT / ".github/workflows/build.yml").exists())
        self.assertFalse((ROOT / ".github/workflows/publish-locally-signed.yml").exists())
        gradle = (ROOT / "android/app/build.gradle.kts").read_text()
        self.assertIn("if (!signingFile.isNullOrBlank())", gradle)


if __name__ == "__main__":
    unittest.main()
