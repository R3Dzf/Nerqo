"""Filesystem and persistence primitives. No third-party dependencies."""
from __future__ import annotations
import hashlib
from contextlib import contextmanager
import os
import re
import secrets
import sqlite3
import threading
import time
from pathlib import Path

CHUNK_LIMIT = 4 * 1024 * 1024
MAX_NOTE = 32 * 1024
MAX_FILE = (1 << 63) - 1
_INVALID = re.compile(r'[<>:"\\|?*\x00-\x1f]')
_LOCK = threading.RLock()

class AppError(Exception):
    def __init__(self, message: str, status=400):
        super().__init__(message)
        self.status = status


def normalize_path(name: str) -> str:
    """Retain safe folder structure; prevent traversal and Windows reserved names."""
    if not isinstance(name, str) or not name or len(name) > 2048:
        raise AppError('Invalid filename')
    if name.startswith(('/', '\\')) or re.match(r'^[A-Za-z]:', name):
        raise AppError('Absolute paths are not allowed')
    parts = name.replace('\\', '/').split('/')
    if len(parts) > 32 or any(x in ('', '.', '..') for x in parts):
        raise AppError('Unsafe path')
    result = []
    for part in parts:
        safe = _INVALID.sub('_', part).rstrip(' .')[:180]
        if safe.upper().split('.')[0] in {'CON','PRN','AUX','NUL',*(f'COM{i}' for i in range(1,10)),*(f'LPT{i}' for i in range(1,10))}:
            safe = '_' + safe
        if not safe:
            raise AppError('Invalid filename')
        result.append(safe)
    return '/'.join(result)


class Store:
    def __init__(self, root: Path):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.files = self.root / 'files'
        self.files.mkdir(exist_ok=True)
        self.dbfile = self.root / 'state.sqlite3'
        self._initialize()

    @contextmanager
    def _db(self):
        connection = sqlite3.connect(self.dbfile, timeout=30)
        connection.row_factory = sqlite3.Row
        try:
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def _initialize(self):
        with self._db() as db:
            db.execute('PRAGMA journal_mode=WAL')
            db.executescript('''
                CREATE TABLE IF NOT EXISTS sessions (
                    token_hash TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    created REAL NOT NULL,
                    expires REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS transfers (
                    id TEXT PRIMARY KEY,
                    owner TEXT NOT NULL,
                    name TEXT NOT NULL,
                    size INTEGER NOT NULL,
                    offset INTEGER NOT NULL DEFAULT 0,
                    state TEXT NOT NULL DEFAULT 'uploading',
                    created REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS items (
                    id TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    sender TEXT NOT NULL,
                    content TEXT NOT NULL,
                    size INTEGER NOT NULL DEFAULT 0,
                    created REAL NOT NULL
                );
            ''')

    def pair(self, name: str) -> str:
        token = secrets.token_urlsafe(32)
        with _LOCK, self._db() as db:
            db.execute('INSERT INTO sessions VALUES (?,?,?,?)',
                (hashlib.sha256(token.encode()).hexdigest(), name[:80], time.time(), time.time() + 30 * 86400))
        return token

    def identify(self, token: str | None) -> str | None:
        if not token or len(token) > 256:
            return None
        digest = hashlib.sha256(token.encode()).hexdigest()
        with self._db() as db:
            row = db.execute('SELECT name FROM sessions WHERE token_hash=? AND expires>?', (digest, time.time())).fetchone()
        return {'name':row['name'], 'identity':digest} if row else None

    def clear_sessions(self):
        with _LOCK, self._db() as db:
            db.execute('DELETE FROM sessions')

    def add_note(self, kind: str, sender: str, content: str):
        if kind not in ('text','link') or not isinstance(content, str) or not content.strip() or len(content.encode()) > MAX_NOTE:
            raise AppError('Invalid text or link (max 32 KB)')
        if kind == 'link' and not re.match(r'^https?://[^\s]+$', content.strip(), re.I):
            raise AppError('Links must begin with http:// or https://')
        item_id = secrets.token_hex(16)
        with _LOCK, self._db() as db:
            db.execute('INSERT INTO items VALUES (?,?,?,?,?,?)',
                       (item_id, kind, sender, content.strip(), 0, time.time()))
        return item_id

    def begin(self, owner: str, name: str, size: int, resume_id: str | None = None):
        name = normalize_path(name)
        if type(size) is not int or not 0 <= size <= MAX_FILE:
            raise AppError('Invalid file size')
        with _LOCK, self._db() as db:
            if resume_id:
                row = db.execute('SELECT * FROM transfers WHERE id=?', (resume_id,)).fetchone()
                if row is not None:
                    if row['owner'] != owner or row['name'] != name or row['size'] != size or row['state'] != 'uploading':
                        raise AppError('Resume metadata mismatch', 409)
                    path = self.files / f"{resume_id}.part"
                    physical_size = path.stat().st_size if path.exists() else 0
                    offset = min(row['offset'], physical_size)
                    # Crash safety: never claim uncommitted data as complete.
                    if path.exists() and physical_size > offset:
                        with path.open('r+b') as f: f.truncate(offset)
                    db.execute('UPDATE transfers SET offset=? WHERE id=?', (offset, resume_id))
                    return {'id': resume_id, 'offset': offset, 'size': size, 'name': name}
            ident = secrets.token_hex(16)
            (self.files / f'{ident}.part').touch(exist_ok=False)
            db.execute('INSERT INTO transfers (id,owner,name,size,created) VALUES (?,?,?,?,?)',
                       (ident, owner, name, size, time.time()))
            return {'id': ident, 'offset': 0, 'size': size, 'name': name}

    def append(self, ident: str, owner: str, offset: int, data: bytes, checksum: str | None = None):
        if not re.fullmatch(r'[a-f0-9]{32}', ident): raise AppError('Bad transfer ID')
        if not data or len(data) > CHUNK_LIMIT: raise AppError('Chunk must be between 1 byte and 4 MiB')
        if checksum and not secrets.compare_digest(hashlib.sha256(data).hexdigest(), checksum.lower()):
            raise AppError('Chunk SHA-256 mismatch', 422)
        with _LOCK, self._db() as db:
            row = db.execute('SELECT * FROM transfers WHERE id=?', (ident,)).fetchone()
            if row is None or row['owner'] != owner or row['state'] != 'uploading':
                raise AppError('Transfer not found', 404)
            if offset != row['offset']:
                raise AppError(f"Offset conflict; expected {row['offset']}", 409)
            if offset + len(data) > row['size']: raise AppError('Chunk exceeds expected size')
            path = self.files / f'{ident}.part'
            with path.open('r+b') as file:
                file.seek(0, os.SEEK_END)
                if file.tell() < offset: raise AppError('Transfer data missing', 409)
                file.truncate(offset)
                file.write(data)
                file.flush()
                os.fsync(file.fileno())
            db.execute('UPDATE transfers SET offset=? WHERE id=?', (offset + len(data), ident))
            return {'offset': offset + len(data), 'size': row['size']}

    def finish(self, ident: str, owner: str, sender_name: str):
        if not re.fullmatch(r'[a-f0-9]{32}', ident): raise AppError('Bad transfer ID')
        with _LOCK, self._db() as db:
            row = db.execute('SELECT * FROM transfers WHERE id=?', (ident,)).fetchone()
            if row is None or row['owner'] != owner or row['state'] != 'uploading':
                raise AppError('Transfer not found', 404)
            path = self.files / f'{ident}.part'
            if row['offset'] != row['size'] or not path.exists() or path.stat().st_size != row['size']:
                raise AppError('Upload incomplete', 409)
            path.replace(self.files / f'{ident}.bin')
            db.execute('UPDATE transfers SET state=? WHERE id=?', ('complete', ident))
            db.execute('INSERT INTO items VALUES (?,?,?,?,?,?)',
                       (ident, 'file', sender_name, row['name'], row['size'], time.time()))
            return {'id':ident, 'size':row['size'], 'name':row['name']}

    def all_items(self, limit=250):
        with self._db() as db:
            rows = db.execute('SELECT * FROM items ORDER BY created DESC LIMIT ?', (limit,)).fetchall()
        return [dict(row) for row in rows]

    def get_item(self, ident):
        with self._db() as db:
            row = db.execute('SELECT * FROM items WHERE id=?', (ident,)).fetchone()
        return dict(row) if row else None

    def delete_item(self, ident):
        with _LOCK, self._db() as db:
            row = db.execute('SELECT kind FROM items WHERE id=?', (ident,)).fetchone()
            if not row: raise AppError('Item not found', 404)
            db.execute('DELETE FROM items WHERE id=?', (ident,))
            if row['kind'] == 'file':
                (self.files / f'{ident}.bin').unlink(missing_ok=True)
                db.execute('DELETE FROM transfers WHERE id=?', (ident,))
