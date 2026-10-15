"""Prepare two platform release assets and deterministic delta patches.

Used by GitHub tag pipeline. If a previous signed release's binaries are present,
include a patch only when it is at least 10% smaller than the full installer.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from nerqo.delta import make_delta, file_digest
from nerqo.version import VERSION


def create_release(version, windows, android, target, old_version=None, previous_windows=None, previous_android=None):
    if version != VERSION or not re.fullmatch(r'\d+\.\d+\.\d+',version):
        raise ValueError('Release tag and application version do not match')
    folder=Path(target);folder.mkdir(parents=True,exist_ok=True)
    platforms={
        'windows':('Nerqo-Windows.exe',windows,previous_windows),
        'android':('Nerqo-Android.apk',android,previous_android),
    }
    result={'schema':1,'version':version,'assets':{}}
    for platform,(name,source,previous) in platforms.items():
        original=Path(source)
        if not original.is_file():raise FileNotFoundError(original)
        dest=folder/name
        shutil.copyfile(original,dest)
        full={'name':name,'sha256':file_digest(dest).hex(),'size':dest.stat().st_size}
        deltas=[]
        if old_version and previous and Path(previous).is_file():
            patch_name=f'Nerqo-{platform}-from-{old_version}.nrqd'
            patch=folder/patch_name
            make_delta(previous,dest,patch)
            if patch.stat().st_size < dest.stat().st_size * .9:
                deltas.append({'name':patch_name,'from_version':old_version,
                               'base_sha256':file_digest(previous).hex(),
                               'sha256':file_digest(patch).hex(),'size':patch.stat().st_size})
            else:patch.unlink()
        result['assets'][platform]={'full':full,'deltas':deltas}
    path=folder/'update-manifest.json'
    path.write_text(json.dumps(result,indent=2,sort_keys=True)+'\n',encoding='utf-8')
    return path


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--version',required=True)
    p.add_argument('--windows',required=True)
    p.add_argument('--android',required=True)
    p.add_argument('--target',required=True)
    p.add_argument('--old-version')
    p.add_argument('--old-windows')
    p.add_argument('--old-android')
    x=p.parse_args()
    print(create_release(x.version,x.windows,x.android,x.target,x.old_version,x.old_windows,x.old_android))
