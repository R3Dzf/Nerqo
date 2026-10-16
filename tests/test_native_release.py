import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from tools.build_native_release import check_versions, prepare
from nerqo.delta import apply_delta


class NativeReleaseTests(unittest.TestCase):
    def test_version_lockstep(self):
        project = Path(__file__).resolve().parents[1]
        check_versions(project, "1.0.6")
        with self.assertRaises(ValueError):
            check_versions(project, "0.5.0")

    def test_native_manifest_and_reconstruct_delta(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            old, new, apk = root / "old.exe", root / "new.exe", root / "app.apk"
            old.write_bytes(b"MZ" + b"\x00" * 65536)
            new.write_bytes(old.read_bytes() + b"changed")
            with ZipFile(apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"fixture")
                archive.writestr("classes.dex", b"fixture")
            output = root / "release"
            manifest_path = prepare("0.5.0", new, apk, output, "0.4.0", old, None)
            metadata = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(metadata["schema"], 1)
            self.assertEqual(metadata["version"], "0.5.0")
            self.assertEqual(metadata["assets"]["windows"]["full"]["name"], "Nerqo-Windows-Setup.exe")
            self.assertEqual(metadata["assets"]["android"]["full"]["name"], "Nerqo-Android.apk")
            patch = metadata["assets"]["windows"]["deltas"][0]
            self.assertEqual(patch["from_version"], "0.4.0")
            rebuilt = root / "reassembled.exe"
            apply_delta(old, output / patch["name"], rebuilt)
            self.assertEqual(rebuilt.read_bytes(), new.read_bytes())

    def test_stable_pipeline_uses_native_installers_only(self):
        root = Path(__file__).resolve().parents[1]
        workflow = (root / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn("dotnet publish desktop/Nerqo.Desktop", workflow)
        self.assertIn("Nerqo-Windows-Setup.exe", workflow)
        self.assertIn("Nerqo-Android.apk", workflow)
        self.assertIn("verify_published_apk.py", workflow)
        self.assertIn("github.ref == 'refs/heads/main'", workflow)
        self.assertNotIn("pyinstaller", workflow.lower())
        self.assertNotIn("app.py", workflow)
        self.assertFalse((root / ".github/workflows/build.yml").exists())
        self.assertFalse((root / ".github/workflows/publish-locally-signed.yml").exists())

    def test_reject_invalid_assets_and_versions(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            exe, apk = root / "setup.exe", root / "app.apk"
            exe.write_bytes(b"not an exe")
            apk.write_bytes(b"not an apk")
            with self.assertRaises(ValueError):
                prepare("0.5.0-preview", exe, apk, root / "release")
            with self.assertRaises(ValueError):
                prepare("0.5.0", exe, apk, root / "release")
            exe.write_bytes(b"MZ" + b"\x00" * 512)
            with self.assertRaises(ValueError):
                prepare("0.5.0", exe, apk, root / "release")


if __name__ == "__main__":
    unittest.main()
