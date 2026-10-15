"""Portable Nerqo binary delta format (NRQDEL01), using only Python stdlib.

Android's DeltaPatch.java implements the exact same byte stream format.
Untrusted patches are strictly bounded and every reconstructed file is SHA-256 checked.
"""
from __future__ import annotations

import hashlib
import mmap
import os
import struct
import zlib
from pathlib import Path

MAGIC = b'NRQDEL01'
BLOCK = 8192
LITERAL_LIMIT = 1024 * 1024
MAX_OUTPUT = 8 * 1024**3
HEAD = struct.Struct('>8sQQ32s32s')
MOD = 65521


class DeltaError(ValueError):
    pass


def file_digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for buf in iter(lambda: f.read(1024 * 1024), b''):
            h.update(buf)
    return h.digest()


def _literal(out, data):
    for start in range(0, len(data), LITERAL_LIMIT):
        raw = data[start:start + LITERAL_LIMIT]
        compressed = zlib.compress(raw, 6)
        out.write(b'D' + struct.pack('>II', len(raw), len(compressed)))
        out.write(compressed)


def make_delta(base_path, target_path, patch_path):
    """Build patch with rolling Adler32, chunk reuse and compressed literals.

    Only the compact base-block checksums are indexed. File data is memory mapped,
    so even large files do not need to be copied into the Python heap.
    """
    base_path, target_path, patch_path = map(Path, (base_path, target_path, patch_path))
    old_size, new_size = base_path.stat().st_size, target_path.stat().st_size
    if max(old_size, new_size) > MAX_OUTPUT:
        raise DeltaError('File exceeds supported patch limit')
    old_hash, new_hash = file_digest(base_path), file_digest(target_path)
    with base_path.open('rb') as old_file, target_path.open('rb') as new_file, patch_path.open('wb') as out:
        out.write(HEAD.pack(MAGIC, old_size, new_size, old_hash, new_hash))
        if not new_size:
            out.write(b'E')
            return patch_path.stat().st_size
        if not old_size or old_size < BLOCK or new_size < BLOCK:
            # Tiny/empty files use compressed data rather than binary reuse.
            with mmap.mmap(new_file.fileno(), 0, access=mmap.ACCESS_READ) as new:
                for off in range(0, new_size, LITERAL_LIMIT):
                    _literal(out, new[off:off + LITERAL_LIMIT])
            out.write(b'E')
            return patch_path.stat().st_size
        with mmap.mmap(old_file.fileno(), 0, access=mmap.ACCESS_READ) as old, \
                mmap.mmap(new_file.fileno(), 0, access=mmap.ACCESS_READ) as new:
            index = {}
            for off in range(0, old_size - BLOCK + 1, BLOCK):
                checksum = zlib.adler32(old[off:off + BLOCK])
                index.setdefault(checksum, []).append(off)
            pos, literal_start = 0, 0
            checksum = zlib.adler32(new[:BLOCK])
            max_pos = new_size - BLOCK
            while pos <= max_pos:
                matched = None
                for base_off in index.get(checksum, ()):
                    if old[base_off:base_off + BLOCK] == new[pos:pos + BLOCK]:
                        matched = base_off
                        break
                if matched is not None:
                    if literal_start < pos:
                        for start in range(literal_start, pos, LITERAL_LIMIT):
                            _literal(out, new[start:min(pos,start + LITERAL_LIMIT)])
                    size = BLOCK
                    # Extend a matching range even if it is not aligned to blocks.
                    while (matched + size < old_size and pos + size < new_size and
                           old[matched + size] == new[pos + size]):
                        size += 1
                    remaining = size
                    while remaining:
                        take = min(remaining, 0xFFFFFFFF)
                        out.write(b'C' + struct.pack('>QI', matched + (size - remaining), take))
                        remaining -= take
                    pos += size
                    literal_start = pos
                    if pos <= max_pos:
                        checksum = zlib.adler32(new[pos:pos + BLOCK])
                else:
                    if pos < max_pos:
                        old_byte, new_byte = new[pos], new[pos + BLOCK]
                        a = (checksum & 0xffff)
                        b = checksum >> 16
                        next_a = (a - old_byte + new_byte) % MOD
                        next_b = (b - BLOCK * old_byte + next_a - 1) % MOD
                        checksum = (next_b << 16) | next_a
                    pos += 1
            if literal_start < new_size:
                for start in range(literal_start, new_size, LITERAL_LIMIT):
                    _literal(out, new[start:min(new_size, start + LITERAL_LIMIT)])
        out.write(b'E')
    return patch_path.stat().st_size


def _read_exact(f, n):
    data = f.read(n)
    if len(data) != n:
        raise DeltaError('Truncated patch')
    return data


def apply_delta(base_path, patch_path, target_path):
    """Safely reconstruct and verify a file; never replace existing target in place."""
    base_path, patch_path, target_path = map(Path, (base_path, patch_path, target_path))
    if target_path.exists():
        raise DeltaError('Destination already exists')
    created = False
    try:
        with base_path.open('rb') as base, patch_path.open('rb') as patch:
            magic, old_size, new_size, old_hash, new_hash = HEAD.unpack(_read_exact(patch, HEAD.size))
            if magic != MAGIC or max(old_size, new_size) > MAX_OUTPUT:
                raise DeltaError('Invalid patch header')
            if base_path.stat().st_size != old_size or file_digest(base_path) != old_hash:
                raise DeltaError('Original installation does not match this delta')
            written = 0
            digest = hashlib.sha256()
            with target_path.open('xb') as output:
                created = True
                while True:
                    tag = _read_exact(patch, 1)
                    if tag == b'E':
                        if patch.read(1):
                            raise DeltaError('Trailing patch data')
                        break
                    if tag == b'C':
                        offset, size = struct.unpack('>QI', _read_exact(patch, 12))
                        if not size or offset > old_size or size > old_size - offset:
                            raise DeltaError('Copy operation exceeds original file')
                        base.seek(offset)
                        while size:
                            buf = _read_exact(base, min(size, LITERAL_LIMIT))
                            output.write(buf)
                            digest.update(buf)
                            written += len(buf)
                            size -= len(buf)
                    elif tag == b'D':
                        raw_size, compressed_size = struct.unpack('>II', _read_exact(patch, 8))
                        if not (0 < raw_size <= LITERAL_LIMIT and 0 < compressed_size <= LITERAL_LIMIT + 1024):
                            raise DeltaError('Oversized literal')
                        raw_input = _read_exact(patch, compressed_size)
                        obj = zlib.decompressobj()
                        try:
                            raw = obj.decompress(raw_input, raw_size + 1)
                        except zlib.error as exc:
                            raise DeltaError('Corrupt compressed literal') from exc
                        if len(raw) != raw_size or not obj.eof or obj.unused_data or obj.unconsumed_tail:
                            raise DeltaError('Malformed compressed literal')
                        output.write(raw)
                        digest.update(raw)
                        written += raw_size
                    else:
                        raise DeltaError('Unknown patch operation')
                    if written > new_size:
                        raise DeltaError('Patch exceeds advertised output size')
            if written != new_size or digest.digest() != new_hash:
                raise DeltaError('Reconstructed file hash mismatch')
        return target_path
    except Exception:
        if created:
            target_path.unlink(missing_ok=True)
        raise
