"""Nerqo localhost/LAN server. Python 3.10+ standard library only."""
from __future__ import annotations
import argparse
import ipaddress
import json
import mimetypes
import os
import re
import secrets
import socket
import threading
import time
import urllib.parse
import webbrowser
from collections import defaultdict, deque
from http import HTTPStatus
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from .core import Store, AppError, CHUNK_LIMIT

BASE = Path(__file__).resolve().parent.parent
WEB = BASE / 'web'
ATTEMPTS = defaultdict(deque)
ATTEMPT_LOCK = threading.Lock()


def local_addresses():
    ips = set()
    try:
        for entry in socket.getaddrinfo(socket.gethostname(), None, family=socket.AF_INET):
            if not entry[4][0].startswith('127.'): ips.add(entry[4][0])
    except OSError: pass
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(('192.0.2.1', 80))
            if not sock.getsockname()[0].startswith('127.'): ips.add(sock.getsockname()[0])
    except OSError: pass
    # Windows laptop can have multiple active interfaces; display all likely IPs.
    return sorted(ips)


class TransferHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    def __init__(self, bind, store: Store, name: str):
        super().__init__(bind, Handler)
        self.store = store
        self.device_name = name
        self.pin = f'{secrets.randbelow(1000000):06d}'


class Handler(BaseHTTPRequestHandler):
    server: TransferHTTPServer
    protocol_version = 'HTTP/1.1'

    def log_message(self, fmt, *args):
        # Avoid logging secrets, file names or URLs supplied by clients.
        print(f'{self.client_address[0]} [{self.log_date_time_string()}] {self.command} {self.path.split("?")[0][:100]} {args[-1] if args else ""}')

    def _reply(self, code, raw: bytes, content_type='application/json; charset=utf-8', extra=None):
        self.send_response(code)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(raw)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Referrer-Policy', 'no-referrer')
        self.send_header('X-Frame-Options', 'DENY')
        if extra:
            for k,v in extra.items(): self.send_header(k,v)
        self.end_headers()
        if self.command != 'HEAD': self.wfile.write(raw)

    def _json(self, code, data, extra=None):
        self._reply(code, json.dumps(data, ensure_ascii=False).encode('utf-8'), extra=extra)

    def _body(self, limit=65536):
        length = self.headers.get('Content-Length', '')
        if not length.isdecimal() or int(length) > limit:
            raise AppError('Invalid request size', 413)
        return self.rfile.read(int(length))

    def _payload(self):
        if 'application/json' not in self.headers.get('Content-Type', ''):
            raise AppError('Expected application/json')
        try:
            doc=json.loads(self._body())
            if not isinstance(doc,dict): raise ValueError()
            return doc
        except (ValueError,UnicodeDecodeError): raise AppError('Invalid JSON')

    def _is_local(self):
        return self.client_address[0] in ('127.0.0.1','::1')

    def _client(self):
        if self._is_local(): return {'name':'This computer', 'identity':'HOST'}
        token = self.headers.get('X-Nerqo-Token', '')
        if not token:
            cookies = SimpleCookie()
            try: cookies.load(self.headers.get('Cookie',''))
            except Exception: pass
            if 'nerqo_session' in cookies: token = cookies['nerqo_session'].value
        return self.server.store.identify(token)

    def _authorized(self):
        user = self._client()
        if not user: raise AppError('Pair this device with the PIN shown on your computer', 401)
        return user

    def _origin_check(self):
        hostname = self.headers.get('Host', '').rsplit(':',1)[0]
        if hostname != 'localhost':
            try:
                if not ipaddress.ip_address(hostname).is_private:
                    raise ValueError('Public address')
            except ValueError:
                raise AppError('Host not allowed',403)
        origin = self.headers.get('Origin')
        if not origin: return
        parsed = urllib.parse.urlsplit(origin)
        # Reject browser cross-origin requests to prevent LAN CSRF.
        if parsed.scheme != 'http' or parsed.netloc != self.headers.get('Host', ''):
            raise AppError('Origin rejected', 403)

    def _dispatch(self):
        self._origin_check()
        path = urllib.parse.urlsplit(self.path).path
        if self.command in ('GET','HEAD') and path in ('/','/style.css','/app.js','/favicon.svg'):
            name = {'/':'index.html','/style.css':'style.css','/app.js':'app.js','/favicon.svg':'favicon.svg'}[path]
            content_type = {'index.html':'text/html; charset=utf-8','style.css':'text/css; charset=utf-8', 'app.js':'application/javascript; charset=utf-8','favicon.svg':'image/svg+xml'}[name]
            raw = (WEB / name).read_bytes()
            return self._reply(200,raw,content_type,{'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"})
        if self.command == 'GET' and path == '/api/status':
            return self._json(200, {'name':self.server.device_name,'authorized':bool(self._client()),
                'local':self._is_local(), 'pin':self.server.pin if self._is_local() else None,
                'version':'0.1.0', 'port':self.server.server_port, 'addresses':local_addresses() if self._is_local() else []})
        if self.command == 'POST' and path == '/api/pair':
            data = self._payload()
            ip = self.client_address[0]
            with ATTEMPT_LOCK:
                history = ATTEMPTS[ip]
                while history and history[0] < time.time() - 600: history.popleft()
                if len(history) >= 6: raise AppError('Too many attempts; retry in 10 minutes', 429)
                history.append(time.time())
            pin = data.get('pin')
            if not isinstance(pin,str) or not secrets.compare_digest(pin,self.server.pin):
                raise AppError('Wrong pairing PIN', 403)
            name = data.get('name','Mobile device')
            if not isinstance(name,str) or not name.strip(): name='Mobile device'
            token = self.server.store.pair(name.strip())
            with ATTEMPT_LOCK: ATTEMPTS.pop(ip,None)
            # HttpOnly cookie lets native downloads work without loading entire files into memory.
            return self._json(200,{'ok':True}, {'Set-Cookie':f'nerqo_session={token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=2592000'})
        if self.command == 'GET' and path == '/api/items':
            self._authorized()
            return self._json(200,{'items':self.server.store.all_items()})
        if self.command == 'POST' and path == '/api/items':
            sender=self._authorized(); data=self._payload()
            ident=self.server.store.add_note(data.get('kind'),sender['name'],data.get('content'))
            return self._json(201,{'id':ident})
        if self.command == 'POST' and path == '/api/transfers/begin':
            sender=self._authorized(); data=self._payload()
            info=self.server.store.begin(sender['identity'],data.get('name'),data.get('size'),data.get('resumeId'))
            return self._json(200,info)
        match=re.fullmatch(r'/api/transfers/([a-f0-9]{32})/(chunk|finish)',path)
        if match:
            sender=self._authorized(); ident,action=match.groups()
            if self.command == 'PUT' and action == 'chunk':
                try: offset=int(self.headers.get('X-Offset','-1'))
                except ValueError: raise AppError('Bad offset')
                data=self._body(CHUNK_LIMIT)
                return self._json(200,self.server.store.append(ident,sender['identity'],offset,data,self.headers.get('X-SHA256')))
            if self.command == 'POST' and action == 'finish':
                self._body(1024)
                return self._json(200,self.server.store.finish(ident,sender['identity'],sender['name']))
        match=re.fullmatch(r'/api/files/([a-f0-9]{32})',path)
        if self.command in ('GET','HEAD') and match:
            self._authorized()
            return self._download(match.group(1))
        match=re.fullmatch(r'/api/items/([a-f0-9]{32})',path)
        if self.command == 'DELETE' and match:
            if not self._is_local(): raise AppError('Only the host can delete history',403)
            self.server.store.delete_item(match.group(1))
            return self._json(200,{'ok':True})
        if self.command == 'POST' and path == '/api/reset':
            self._body(1024)
            if not self._is_local(): raise AppError('Only the host can revoke access',403)
            self.server.store.clear_sessions()
            self.server.pin = f'{secrets.randbelow(1000000):06d}'
            return self._json(200,{'ok':True,'pin':self.server.pin})
        raise AppError('Not found',404)

    def _download(self, ident):
        item=self.server.store.get_item(ident)
        if item is None or item['kind']!='file': raise AppError('File not found',404)
        path=self.server.store.files / f'{ident}.bin'
        if not path.is_file(): raise AppError('File missing from storage',404)
        size=path.stat().st_size
        start,end=0,size-1
        ranged=self.headers.get('Range')
        if ranged:
            m=re.fullmatch(r'bytes=(\d+)-(\d*)',ranged)
            if not m: raise AppError('Invalid Range',416)
            start=int(m.group(1)); end=int(m.group(2)) if m.group(2) else size-1
            if start >= size or end < start or end >= size: raise AppError('Invalid Range',416)
        filename=item['content'].split('/')[-1]
        encoded=urllib.parse.quote(filename,safe='')
        self.send_response(206 if ranged else 200)
        self.send_header('Content-Type','application/octet-stream')
        self.send_header('Content-Disposition',f"attachment; filename*=UTF-8''{encoded}")
        self.send_header('Content-Length',str(max(0,end-start+1)))
        self.send_header('Accept-Ranges','bytes')
        self.send_header('Cache-Control','no-store')
        self.send_header('X-Content-Type-Options','nosniff')
        if ranged:self.send_header('Content-Range',f'bytes {start}-{end}/{size}')
        self.end_headers()
        if self.command=='HEAD':return
        with path.open('rb') as f:
            f.seek(start); remaining=end-start+1
            while remaining > 0:
                block=f.read(min(256*1024,remaining))
                if not block:break
                try:self.wfile.write(block)
                except (ConnectionError,BrokenPipeError):break
                remaining-=len(block)

    def do_GET(self): self._safe()
    def do_HEAD(self): self._safe()
    def do_POST(self): self._safe()
    def do_PUT(self): self._safe()
    def do_DELETE(self): self._safe()
    def do_OPTIONS(self): self._reply(405,b'')

    def _safe(self):
        try: self._dispatch()
        except AppError as e:
            self.close_connection = True
            self._json(e.status,{'error':str(e)})
        except (BrokenPipeError,ConnectionResetError): pass
        except Exception as e:
            print('Server error:',type(e).__name__,str(e))
            try:self._json(500,{'error':'Internal server error'})
            except OSError: pass


class DiscoveryResponder(threading.Thread):
    """Optional UDP discovery on trusted LAN. PIN still required for any transfer."""
    def __init__(self, port, name):
        super().__init__(daemon=True)
        self.port, self.name = port, name
        self.active = True
        self.sock = None
    def stop(self):
        self.active = False
        if self.sock:
            try:self.sock.close()
            except OSError:pass
    def run(self):
        try:
            with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
                self.sock=sock
                sock.bind(('0.0.0.0',44889))
                sock.settimeout(1)
                while self.active:
                    try:data,addr=sock.recvfrom(128)
                    except socket.timeout:continue
                    except OSError:break
                    if data == b'NERQO_DISCOVER_V1':
                        response=json.dumps({'app':'Nerqo','port':self.port,'name':self.name},ensure_ascii=False).encode()
                        sock.sendto(response[:256],addr)
        except OSError as e:
            print('LAN discovery unavailable:',e)


def serve(port=44888, root=None, open_browser=True, host='0.0.0.0'):
    root = Path(root) if root else Path.home()/ 'Nerqo-Received'
    store = Store(root)
    server = TransferHTTPServer((host,port),store,socket.gethostname())
    print('\nNerqo — offline Wi-Fi file and link transfer')
    print('Stored files:',root)
    print('Local desktop: http://127.0.0.1:%s'%server.server_port)
    for ip in local_addresses(): print(f'Phone (same Wi-Fi / hotspot): http://{ip}:{server.server_port}')
    print('Pairing PIN:',server.pin)
    print('Keep this window running during transfers. Press Ctrl+C to stop.\n')
    discovery = DiscoveryResponder(server.server_port, server.device_name)
    discovery.start()
    if open_browser:
        threading.Timer(1.0, lambda:webbrowser.open(f'http://127.0.0.1:{server.server_port}')).start()
    try:server.serve_forever(poll_interval=.4)
    except KeyboardInterrupt:pass
    finally:
        discovery.stop()
        server.server_close()


def main():
    ap=argparse.ArgumentParser(description='Nerqo offline LAN transfer host')
    ap.add_argument('--port',type=int,default=44888)
    ap.add_argument('--data-dir',default=None)
    ap.add_argument('--no-browser',action='store_true')
    ap.add_argument('--host',default='0.0.0.0')
    a=ap.parse_args()
    serve(a.port,a.data_dir,not a.no_browser,a.host)

if __name__ == '__main__':main()
