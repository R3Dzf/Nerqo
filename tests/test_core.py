import hashlib
import tempfile
import unittest
from pathlib import Path
from nerqo.core import Store, AppError, normalize_path, CHUNK_LIMIT

class CoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.store=Store(Path(self.tmp.name))
    def tearDown(self):self.tmp.cleanup()

    def test_safe_filenames(self):
        self.assertEqual(normalize_path('pics/flowers.jpg'),'pics/flowers.jpg')
        self.assertEqual(normalize_path('CON.txt'),'_CON.txt')
        for bad in ('../secrets','/root/a','C:\\Users\\x','a//b','a/./b','a/../b','a/',''):
            with self.subTest(bad=bad),self.assertRaises(AppError):normalize_path(bad)

    def test_pair_session(self):
        t=self.store.pair('Android')
        info=self.store.identify(t)
        self.assertEqual(info['name'],'Android')
        self.assertIsNone(self.store.identify(t+'x'))
        self.store.clear_sessions()
        self.assertIsNone(self.store.identify(t))

    def test_note_and_link_validation(self):
        self.store.add_note('link','android','https://example.com/path')
        with self.assertRaises(AppError):self.store.add_note('link','android','file:///etc/shadow')
        with self.assertRaises(AppError):self.store.add_note('text','android','A'*40000)
        self.assertEqual(len(self.store.all_items()),1)

    def test_upload_resume_and_hash_and_access(self):
        payload=b'A'*256000+b'B'*1325
        identity='ID_123'
        start=self.store.begin(identity,'folder/test.bin',len(payload))
        xid=start['id']
        with self.assertRaises(AppError):self.store.append(xid,'OTHER',0,payload[:20])
        with self.assertRaises(AppError):self.store.append(xid,identity,0,b'bad','0'*64)
        self.store.append(xid,identity,0,payload[:256000],hashlib.sha256(payload[:256000]).hexdigest())
        resume=self.store.begin(identity,'folder/test.bin',len(payload),xid)
        self.assertEqual(resume['offset'],256000)
        with self.assertRaises(AppError):self.store.finish(xid,identity,'android')
        self.store.append(xid,identity,256000,payload[256000:])
        info=self.store.finish(xid,identity,'android')
        self.assertEqual(info['name'],'folder/test.bin')
        self.assertEqual((self.store.files / f'{xid}.bin').read_bytes(),payload)
        self.assertEqual(self.store.get_item(xid)['sender'],'android')
        self.store.delete_item(xid)
        self.assertFalse((self.store.files / f'{xid}.bin').exists())

    def test_oversized_chunk(self):
        tr=self.store.begin('owner','huge',CHUNK_LIMIT+100)
        with self.assertRaises(AppError):self.store.append(tr['id'],'owner',0,b'X'*(CHUNK_LIMIT+1))

if __name__=='__main__':unittest.main()
