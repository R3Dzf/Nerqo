"""Disposable TLS peer for Android emulator transport tests; no real user data."""
import argparse
import hashlib
import json
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs

DATA = bytes(range(256)) * (9 * 4096) + b'nerqo'
UI_DATA = bytes(range(256)) * (32 * 4096) + b'ui'
SHA = hashlib.sha256(DATA).hexdigest()
UI_SHA = hashlib.sha256(UI_DATA).hexdigest()
SINGLE, FOLDER, UI = '1' * 32, '2' * 32, '3' * 32
TOKEN = 'nerqo-emulator-fixture-token'
lock = threading.Lock()
state = {'offset': 0, 'published': 0, 'range_offsets': [], 'finish_dropped': False, 'upload_chunks': []}
upload = bytearray()


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *_):
        pass
    def json(self, data, status=200):
        data = json.dumps(data).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def authenticated(self):
        if self.headers.get('Authorization') == 'Bearer ' + TOKEN:
            return True
        self.json({'error': 'Unauthorized'}, 401)
        return False
    def body(self):
        return self.rfile.read(int(self.headers.get('Content-Length', '0')))
    def do_GET(self):
        route = urlsplit(self.path)
        if route.path == '/api/status':
            with lock: self.json({'name': 'CI Windows fixture', 'port': 44888, 'version': '1.0.2', 'transferProtocol': 2,
                                  'published': state['published'], 'ranges': state['range_offsets'], 'uploadChunks': state['upload_chunks']})
            return
        if not self.authenticated(): return
        if route.path == '/api/items':
            self.json({'items': [{'id': UI, 'kind': 'file', 'sender': 'CI Windows fixture', 'content': 'Native-resume-fixture.bin', 'size': len(UI_DATA), 'sha256': UI_SHA}]})
            return
        if route.path == f'/api/outgoing-folders/{FOLDER}/manifest':
            self.json({'name': 'fixture-folder', 'directories': ['nested', 'empty'], 'files': [
                {'path': 'nested/large.bin', 'size': len(DATA), 'sha256': SHA},
                {'path': 'small.txt', 'size': 5, 'sha256': hashlib.sha256(b'hello').hexdigest()}]})
            return
        if route.path in (f'/api/files/{SINGLE}', f'/api/files/{UI}'):
            data = UI_DATA if route.path.endswith(UI) else DATA
        elif route.path == f'/api/outgoing-folders/{FOLDER}/file':
            data = DATA if parse_qs(route.query)['path'][0] == 'nested/large.bin' else b'hello'
        else:
            self.json({}, 404); return
        offset = 0
        if self.headers.get('Range'):
            offset = int(self.headers['Range'].removeprefix('bytes=').removesuffix('-'))
            with lock: state['range_offsets'].append(offset)
        self.send_response(206 if offset else 200)
        if offset: self.send_header('Content-Range', f'bytes {offset}-{len(data)-1}/{len(data)}')
        self.send_header('Content-Length', str(len(data)-offset))
        self.end_headers()
        try:
            for pos in range(offset, len(data), 65536):
                self.wfile.write(data[pos:pos+65536]); self.wfile.flush()
                if route.path.endswith(UI): time.sleep(.04)
        except (OSError, ssl.SSLError):
            pass
    def do_POST(self):
        if not self.authenticated(): return
        request = json.loads(self.body() or b'{}')
        route = urlsplit(self.path)
        if route.path == '/api/transfers/begin':
            with lock:
                if state['published']:
                    self.json({'id': 'a'*32, 'complete': True, 'sha256': SHA, 'size': len(DATA)})
                else: self.json({'id': 'a'*32, 'offset': state['offset'], 'size': len(DATA)})
            return
        if route.path == '/api/transfers/' + 'a'*32 + '/finish':
            with lock:
                if bytes(upload) != DATA or parse_qs(route.query).get('sha256') != [SHA]:
                    self.json({'error': 'wrong payload'}, 422); return
                if not state['published']: state['published'] = 1
                if not state['finish_dropped']:
                    state['finish_dropped'] = True
                    self.close_connection = True
                    self.connection.shutdown(2)
                    self.connection.close()
                    return
            self.json({'id': 'a'*32, 'sha256': SHA, 'size': len(DATA)}); return
        self.json({}, 404)
    def do_PUT(self):
        if not self.authenticated(): return
        block = self.body()
        with lock:
            if int(self.headers.get('X-Offset', '-1')) != state['offset'] or hashlib.sha256(block).hexdigest() != self.headers.get('X-SHA256'):
                self.json({}, 409); return
            state['upload_chunks'].append(state['offset'])
            upload.extend(block); state['offset'] = len(upload)
            self.json({'offset': len(upload)})
    def do_DELETE(self):
        if not self.authenticated(): return
        self.json({'complete': bool(state['published'])})


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--cert', required=True); parser.add_argument('--key', required=True)
    args = parser.parse_args()
    server = ThreadingHTTPServer(('127.0.0.1', 44888), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(args.cert, args.key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print('TLS emulator fixture ready', flush=True)
    server.serve_forever()
