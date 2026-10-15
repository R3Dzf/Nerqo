import hashlib
import os
import tempfile
import unittest
from pathlib import Path
from nerqo.delta import DeltaError, make_delta, apply_delta, HEAD


class DeltaTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.base, self.target, self.patch, self.result = [Path(self.dir.name) / x for x in ('base.bin','new.bin','patch.nrqd','rebuilt.bin')]

    def go(self, before, after):
        self.base.write_bytes(before)
        self.target.write_bytes(after)
        make_delta(self.base, self.target, self.patch)
        apply_delta(self.base, self.patch, self.result)
        self.assertEqual(self.result.read_bytes(), after)
        return self.patch.stat().st_size

    def test_change_and_shift(self):
        before = os.urandom(2 * 1024 * 1024)
        after = b'insert' + before[:100000] + b'changed' + before[100100:]
        size = self.go(before,after)
        self.assertLess(size, 40_000)

    def test_same(self):
        before = os.urandom(110_000)
        self.assertLess(self.go(before,before),1000)

    def test_empty(self):
        self.go(b'',b'')
        self.result.unlink()
        self.go(b'',b'hello')

    def test_changed_base_rejected(self):
        self.go(b'abc' * 10000, b'abd' * 10000)
        self.result.unlink()
        self.base.write_bytes(b'spoofed' * 5000)
        with self.assertRaises(DeltaError): apply_delta(self.base,self.patch,self.result)
        self.assertFalse(self.result.exists())

    def test_corruption_rejected(self):
        self.go(b'z' * 100000, b'hello' * 20000)
        self.result.unlink()
        bits = bytearray(self.patch.read_bytes());bits[-2] ^= 1
        self.patch.write_bytes(bits)
        with self.assertRaises(DeltaError):apply_delta(self.base,self.patch,self.result)
        self.assertFalse(self.result.exists())

    def test_target_cannot_be_overwritten(self):
        self.go(b'old' * 1000,b'new' * 1000)
        with self.assertRaises(DeltaError): apply_delta(self.base,self.patch,self.result)

    def test_truncated(self):
        self.go(b'abc' * 10000,b'abcd' * 10000)
        self.result.unlink()
        self.patch.write_bytes(self.patch.read_bytes()[:HEAD.size+3])
        with self.assertRaises(DeltaError):apply_delta(self.base,self.patch,self.result)
        self.assertFalse(self.result.exists())


if __name__ == '__main__':unittest.main()
