"""Measure actual native payload changes using the delta-friendly Windows packaging.

The old 1.0.7 payload is repackaged for this test only. This is NOT the published
1.0.7 installer, and does not imply a small migration delta from its old format.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from nerqo.delta import make_delta, apply_delta, file_digest


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--baseline-source', required=True, type=Path)
    ap.add_argument('--target', required=True, type=Path)
    ap.add_argument('--iscc', type=Path, default=Path(r'C:\Program Files (x86)\Inno Setup 6\ISCC.exe'))
    args = ap.parse_args()
    if not (args.baseline_source / 'Nerqo.exe').is_file():
        raise FileNotFoundError('Missing previous native Windows payload')
    script = (ROOT / 'installer/Nerqo.iss').read_text()
    old_source = r'{#SourcePath}..\dist\Windows\*'
    if script.count(old_source) != 1:
        raise ValueError('Installer input pattern changed; review the delta measurement')
    script = script.replace(old_source, str(args.baseline_source.resolve() / '*'))
    generated = None
    try:
        # Stay alongside Nerqo.iss so its relative icon/output paths retain their meaning.
        with tempfile.NamedTemporaryFile('w', suffix='.iss', prefix='delta-qa-',
                                         dir=ROOT / 'installer', delete=False) as f:
            f.write(script); generated = Path(f.name)
        with tempfile.TemporaryDirectory(prefix='nerqo-delta-budget-') as name:
            work = Path(name)
            subprocess.run([str(args.iscc), '/Qp', f'/O{work}', '/Fbaseline', str(generated)], check=True)
            base, patch, rebuilt = work / 'baseline.exe', work / 'change.nrqd', work / 'rebuilt.exe'
            start = time.monotonic()
            make_delta(base, args.target, patch)
            apply_delta(base, patch, rebuilt)
            if file_digest(rebuilt) != file_digest(args.target):
                raise ValueError('Installer delta reconstruction differs from target')
            ratio = patch.stat().st_size / args.target.stat().st_size
            report = {
                'baseline_payload_version': '1.0.7',
                'baseline_repackaged_for_test': True,
                'target_installer_bytes': args.target.stat().st_size,
                'delta_bytes': patch.stat().st_size,
                'delta_fraction': round(ratio, 6),
                'reconstruction_sha256': file_digest(rebuilt).hex(),
                'seconds': round(time.monotonic() - start, 2),
                'max_delta_fraction': 0.05,
            }
            destination = ROOT / 'dist/qa/windows-delta-budget.json'
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(json.dumps(report, indent=2) + '\n')
            print(json.dumps(report, indent=2))
            if ratio > .05:
                raise ValueError('Native patch exceeds the 5% packaging regression budget')
    finally:
        if generated: generated.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
