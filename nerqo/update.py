"""Explicit, opt-in GitHub Releases delta updater for frozen Windows app.

This module never requires Internet for LAN transfers. The EXE is replaced only
when the user explicitly chooses Update on the LOCAL Windows web interface.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import urllib.request
import urllib.error
from pathlib import Path

from .delta import DeltaError, apply_delta, file_digest
from .version import REPO, VERSION

API = f'https://api.github.com/repos/{REPO}/releases/latest'
MAX_MANIFEST = 128 * 1024
MAX_DOWNLOAD = 3 * 1024**3


class UpdateError(Exception):
    pass


def newer(available, installed):
    def parts(v):
        if not isinstance(v, str) or not re.fullmatch(r'\d+\.\d+\.\d+', v):
            raise UpdateError('Invalid update version')
        return tuple(int(x) for x in v.split('.'))
    return parts(available) > parts(installed)


def sha256(path):
    return file_digest(path).hex()


def _get(url, max_bytes):
    req = urllib.request.Request(url, headers={'User-Agent':'Nerqo-Updater/0.2','Accept':'application/vnd.github+json'})
    with urllib.request.urlopen(req,timeout=20) as r:
        data = r.read(max_bytes + 1)
        if len(data) > max_bytes:
            raise UpdateError('Oversized update response')
        return data


def latest():
    try:
        release = json.loads(_get(API, MAX_MANIFEST * 8))
    except urllib.error.HTTPError as e:
        if e.code == 404: return None  # No published stable release yet.
        raise
    version = release.get('tag_name', '').removeprefix('v')
    if not newer(version, VERSION):
        return None
    prefix = f'https://github.com/{REPO}/releases/download/v{version}/'
    assets = {a['name']:a['browser_download_url'] for a in release.get('assets',[])
              if isinstance(a.get('name'),str) and isinstance(a.get('browser_download_url'),str)}
    url = assets.get('update-manifest.json')
    if url != prefix + 'update-manifest.json':
        raise UpdateError('Official release has no signed-off update manifest')
    manifest = json.loads(_get(url, MAX_MANIFEST))
    if manifest.get('schema') != 1 or manifest.get('version') != version:
        raise UpdateError('Release metadata mismatch')
    for platform in ('windows','android'):
        group = manifest.get('assets',{}).get(platform,{})
        candidates = [group.get('full',{})] + group.get('deltas',[])
        for a in candidates:
            name, digest = a.get('name'),a.get('sha256')
            if not isinstance(name,str) or not re.fullmatch('[A-Za-z0-9._-]{1,90}',name):
                raise UpdateError('Invalid asset name')
            if not isinstance(digest,str) or not re.fullmatch('[0-9a-f]{64}',digest):
                raise UpdateError('Invalid asset checksum')
            if not isinstance(a.get('size'),int) or not 0 < a['size'] <= MAX_DOWNLOAD:
                raise UpdateError('Invalid asset size')
            if assets.get(name) != prefix + name:
                raise UpdateError('Release asset missing/mismatched')
    return manifest, assets


def choose_asset(group, installed_hash):
    full = group['full']
    matches = [x for x in group.get('deltas',[])
               if x.get('from_version') == VERSION and x.get('base_sha256') == installed_hash
               and x.get('size', MAX_DOWNLOAD + 1) < int(full['size']) * .9]
    return min(matches,key=lambda x:x['size']) if matches else full


def download_asset(url, expected, destination, progress=None):
    dest = Path(destination)
    size = expected['size']
    h = hashlib.sha256()
    req = urllib.request.Request(url,headers={'User-Agent':'Nerqo-Updater/0.2'})
    total = 0
    try:
        with urllib.request.urlopen(req,timeout=45) as src, dest.open('xb') as out:
            while True:
                block = src.read(256 * 1024)
                if not block: break
                total += len(block)
                if total > size: raise UpdateError('Asset larger than manifest')
                out.write(block)
                h.update(block)
                if progress: progress(total, size)
        if total != size or h.hexdigest() != expected['sha256']:
            raise UpdateError('Downloaded file failed SHA-256 verification')
        return dest
    except Exception:
        dest.unlink(missing_ok=True)
        raise


class WindowsUpdater:
    def __init__(self):
        self.lock = threading.Lock()
        self.state = {'status':'idle','version':VERSION}
        self.pending_dir = None
        self.staged = None
        self.expected_sha = None

    def status(self):
        with self.lock: return dict(self.state)

    def _set(self, **fields):
        with self.lock:self.state.update(fields)

    def check(self):
        try:
            offer=latest()
            if not offer:return {'status':'up-to-date','version':VERSION}
            manifest,_ = offer
            group=manifest['assets']['windows']
            installed_hash = sha256(sys.executable) if getattr(sys,'frozen',False) else None
            choice=choose_asset(group,installed_hash)
            return {'status':'available','version':manifest['version'],
                    'mode':'delta' if choice is not group['full'] else 'full',
                    'download_bytes':choice['size'], 'full_bytes':group['full']['size'],
                    'install_supported':bool(getattr(sys,'frozen',False) and os.name=='nt')}
        except Exception as e:
            return {'status':'error','error':str(e)[:180]}

    def start(self):
        if not getattr(sys,'frozen',False) or os.name!='nt':
            raise UpdateError('Self-update requires the installed Windows EXE')
        with self.lock:
            if self.state['status'] in ('downloading','installing','ready'):
                raise UpdateError('Update already in progress')
            self.state={'status':'downloading','version':VERSION,'received':0}
        threading.Thread(target=self._prepare,daemon=True).start()

    def _prepare(self):
        try:
            offer=latest()
            if not offer:
                self._set(status='up-to-date');return
            manifest,urls=offer
            group=manifest['assets']['windows']
            exe=Path(sys.executable).resolve()
            full=group['full']
            existing_sha=sha256(exe)
            choice=choose_asset(group,existing_sha)
            directory=Path(tempfile.mkdtemp(prefix='nerqo-update-',dir=exe.parent))
            self.pending_dir=directory
            def progress(received,total): self._set(received=received,total=total)
            def obtain(meta):
                return download_asset(urls[meta['name']],meta,directory / meta['name'],progress)
            staged=directory / 'Nerqo.next.exe'
            if choice is not full:
                try:
                    self._set(mode='delta',total=choice['size'])
                    patch=obtain(choice)
                    apply_delta(exe,patch,staged)
                except (DeltaError,UpdateError,OSError,ValueError):
                    # A valid release can be installed without the delta if an
                    # otherwise legitimate base APK/EXE differs locally.
                    self._set(mode='full',received=0,total=full['size'])
                    download_asset(urls[full['name']],full,staged,progress)
            else:
                self._set(mode='full',total=full['size'])
                download_asset(urls[full['name']],full,staged,progress)
            if sha256(staged) != full['sha256'] or staged.stat().st_size != full['size']:
                raise UpdateError('Reconstructed executable failed SHA-256 verification')
            self.staged = staged
            self.expected_sha = full['sha256']
            self._set(status='ready',version=manifest['version'], mode=self.status().get('mode','full'), received=self.status().get('total', full['size']))
        except Exception as e:
            self._set(status='error',error=str(e)[:180])

    def install(self):
        if self.status()['status'] != 'ready' or self.staged is None:
            raise UpdateError('No verified update ready to install')
        exe=Path(sys.executable).resolve()
        helper=Path(__file__).resolve().parent / 'replace_windows.ps1'
        path = self.pending_dir / 'replace_windows.ps1'
        path.write_bytes(helper.read_bytes())
        kwargs={'creationflags':subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS}
        subprocess.Popen(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(path),
                          '-Existing',str(exe),'-Staged',str(self.staged),
                          '-ExpectedSha',self.expected_sha,'-ProcessId',str(os.getpid())],
                         stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,**kwargs)
        self._set(status='installing')


updater = WindowsUpdater()
