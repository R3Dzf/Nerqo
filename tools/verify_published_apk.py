"""Verify privately signed APK preserves unsigned app payloads.

Signing metadata in root META-INF/ is excluded; application resources under
META-INF/com/ remain in scope and must match.
"""
from __future__ import annotations

from pathlib import Path
from zipfile import ZipFile
import hashlib
import sys


def assert_same_payload(unsigned: Path, signed: Path) -> None:
    with ZipFile(unsigned) as a, ZipFile(signed) as b:
        def application_entry(name: str) -> bool:
            # APK/JAR signer adds only these root-level metadata entries.
            upper = name.upper()
            if upper == 'META-INF/MANIFEST.MF': return False
            if upper.startswith('META-INF/') and upper.count('/') == 1:
                return not upper.endswith(('.SF', '.RSA', '.DSA', '.EC'))
            return True
        names_a = sorted(n for n in a.namelist() if application_entry(n))
        names_b = sorted(n for n in b.namelist() if application_entry(n))
        if names_a != names_b:
            raise ValueError('Unsigned and signed APK contain different ZIP entry names')
        for name in names_a:
            if hashlib.sha256(a.read(name)).digest() != hashlib.sha256(b.read(name)).digest():
                raise ValueError(f'APK payload differs: {name}')
    print('APK archive contents match independently built unsigned release.')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit('Usage: python tools/verify_published_apk.py unsigned.apk signed.apk')
    assert_same_payload(Path(sys.argv[1]),Path(sys.argv[2]))
