"""Privately sign a CI-built unsigned Android APK using Nerqo's pinned release key.

This tool never uploads the keystore, reads recovery passwords only from a local
private file, and refuses to publish packages signed by another certificate.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys

PROJECT = Path(__file__).resolve().parents[1]
EXPECTED = (PROJECT / 'android' / 'RELEASE_SIGNING_CERT.sha256').read_text().strip().lower()
ALIAS = 'nerqo-release'


def signing_passwords(recovery: Path) -> tuple[str, str]:
    data = recovery.read_text(encoding='utf-8')
    def value(label: str) -> str:
        match = re.search(r'^' + re.escape(label) + r'\s*:\s*(\S+)\s*$', data, re.M | re.I)
        if not match: raise ValueError(f'Missing {label} in recovery record')
        return match.group(1)
    return value('Store password'), value('Key password')


def certificate_fingerprint(keystore: Path, storepass: str) -> str:
    result = subprocess.run([
        'keytool', '-list', '-v', '-storetype', 'PKCS12', '-keystore', str(keystore),
        '-alias', ALIAS, '-storepass:env', 'NERQO_PRIVATE_SIGN_PASS'
    ], capture_output=True, text=True, env={**os.environ, 'NERQO_PRIVATE_SIGN_PASS': storepass}, check=True)
    match = re.search(r'SHA256:\s*([0-9A-F:]{95})', result.stdout, re.I)
    if not match: raise RuntimeError('Keystore certificate SHA-256 not found')
    return match.group(1).replace(':','').lower()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--unsigned', required=True, type=Path)
    ap.add_argument('--apksigner-jar', required=True, type=Path)
    ap.add_argument('--keystore', required=True, type=Path)
    ap.add_argument('--recovery', required=True, type=Path)
    ap.add_argument('--output', required=True, type=Path)
    args = ap.parse_args()
    for p in (args.unsigned, args.apksigner_jar, args.keystore, args.recovery):
        if not p.is_file(): raise FileNotFoundError(f'Missing signing input: {p}')
    if args.unsigned.resolve() == args.output.resolve():
        raise ValueError('Unsigned and signed APK must have different paths')
    storepass, keypass = signing_passwords(args.recovery)
    fingerprint = certificate_fingerprint(args.keystore, storepass)
    if fingerprint != EXPECTED: raise RuntimeError('Keystore certificate differs from project-pinned signing identity')
    env = {**os.environ, 'NERQO_PRIVATE_SIGN_PASS': storepass, 'NERQO_PRIVATE_KEY_PASS': keypass}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    if args.output.exists(): raise FileExistsError('Refusing to overwrite existing APK')
    signer = ['java', '-jar', str(args.apksigner_jar)]
    subprocess.run(signer + [
        'sign','--ks',str(args.keystore),'--ks-type','PKCS12','--ks-key-alias',ALIAS,
        '--ks-pass','env:NERQO_PRIVATE_SIGN_PASS', '--key-pass','env:NERQO_PRIVATE_KEY_PASS',
        '--v1-signing-enabled','true','--v2-signing-enabled','true','--v3-signing-enabled','true',
        '--out', str(args.output), str(args.unsigned)
    ], env=env, check=True, capture_output=True)
    validation = subprocess.run(signer + ['verify','--verbose','--print-certs',str(args.output)],
                                check=True, capture_output=True, text=True)
    digest_match = re.search(r'^Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})', validation.stdout, re.M)
    if not digest_match or digest_match.group(1).lower() != EXPECTED:
        args.output.unlink(missing_ok=True)
        raise RuntimeError('Signed APK certificate did not match pinned identity')
    if 'Verified using v2 scheme (APK Signature Scheme v2): true' not in validation.stdout:
        args.output.unlink(missing_ok=True)
        raise RuntimeError('Signed APK missing required v2 signature')
    print('Android APK signed and verified with the pinned permanent certificate.')
    print('Certificate SHA-256:', EXPECTED)
    print('APK SHA-256:', hashlib.sha256(args.output.read_bytes()).hexdigest())
    print('Output:', args.output)
    return 0

if __name__ == '__main__':
    try: sys.exit(main())
    except (OSError,ValueError,subprocess.CalledProcessError,RuntimeError) as e:
        print(f'Signing failed: {type(e).__name__}: {str(e) if not isinstance(e,subprocess.CalledProcessError) else "signer process failed"}',file=sys.stderr)
        sys.exit(1)
