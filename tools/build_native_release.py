"""Build native-only Windows/Android GitHub Release assets and delta manifest.

The Android APK must already be privately signed and certificate-verified.
Do not use this script to publish a release unless all acceptance checks pass.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
from pathlib import Path
from zipfile import ZipFile, BadZipFile

from nerqo.delta import file_digest, make_delta


def check_versions(root: Path, version: str) -> None:
    sources = (
        (root / "desktop/Nerqo.Desktop/Nerqo.Desktop.csproj",
         r"<Version>(\d+\.\d+\.\d+)</Version>"),
        (root / "installer/Nerqo.iss",
         r'#define MyAppVersion "(\d+\.\d+\.\d+)"'),
        (root / "android/app/build.gradle.kts",
         r'versionName\s*=\s*"(\d+\.\d+\.\d+)"'),
    )
    for path, regex in sources:
        match = re.search(regex, path.read_text(encoding="utf-8"))
        if match is None or match.group(1) != version:
            raise ValueError(f"Native component version mismatch: {path}")


def describe(path: Path) -> dict:
    return {"name": path.name, "size": path.stat().st_size,
            "sha256": file_digest(path).hex()}


def prepare(version: str, windows: Path, android: Path, output: Path,
            previous: str | None = None, old_windows: Path | None = None,
            old_android: Path | None = None, project: Path | None = None) -> Path:
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("Invalid stable semantic version")
    if previous and (not re.fullmatch(r"\d+\.\d+\.\d+", previous) or previous == version):
        raise ValueError("Invalid previous version")
    if project is not None:
        check_versions(project, version)
    if not windows.is_file() or not android.is_file():
        raise FileNotFoundError("Both native platform assets are required")
    with windows.open("rb") as exe:
        if exe.read(2) != b"MZ":
            raise ValueError("Windows input must be a native PE installer")
    try:
        with ZipFile(android) as apk:
            if "AndroidManifest.xml" not in apk.namelist() or apk.testzip() is not None:
                raise ValueError("Invalid Android APK archive")
    except BadZipFile as e:
        raise ValueError("Invalid Android APK archive") from e
    output.mkdir(parents=True, exist_ok=True)
    manifest = {"schema": 1, "version": version, "assets": {}}
    for platform, filename, source, older in (
        ("windows", "Nerqo-Windows-Setup.exe", windows, old_windows),
        ("android", "Nerqo-Android.apk", android, old_android),
    ):
        target = output / filename
        if target.resolve() != source.resolve():
            shutil.copyfile(source, target)
        deltas = []
        if previous and older is not None and older.is_file():
            delta = output / f"Nerqo-{platform}-from-{previous}.nrqd"
            make_delta(older, target, delta)
            if delta.stat().st_size < target.stat().st_size * .9:
                deltas.append({**describe(delta), "from_version": previous,
                               "base_sha256": file_digest(older).hex()})
            else:
                delta.unlink()
        manifest["assets"][platform] = {"full": describe(target), "deltas": deltas}
    destination = output / "update-manifest.json"
    destination.write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n",
                           encoding="utf-8")
    return destination


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--windows", type=Path, required=True)
    parser.add_argument("--android", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--old-version")
    parser.add_argument("--old-windows", type=Path)
    parser.add_argument("--old-android", type=Path)
    args = parser.parse_args()
    print(prepare(args.version, args.windows, args.android, args.output,
                  args.old_version, args.old_windows, args.old_android,
                  Path(__file__).resolve().parents[1]))
