import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path
from nerqo.core import Store
from nerqo.server import TransferHTTPServer

class TestHost(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory()
        cls.server=TransferHTTPServer(('127.0.0.1',0),Store(Path(cls.tmp.name)),'Test Laptop')
        cls.thread=threading.Thread(target=cls.server.serve_forever,daemon=True)
        cls.thread.start()
    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown();cls.server.server_close();cls.thread.join(5);cls.tmp.cleanup()
    def req(self, method, path, body=None, cookie=None, headers=None):
        c=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=10)
        hdr=headers or {}
        if isinstance(body,dict): body=json.dumps(body).encode();hdr['Content-Type']='application/json'
        if cookie:hdr['Cookie']=cookie
        c.request(method,path,body,headers=hdr)
        r=c.getresponse();payload=r.read();result=(r.status,r.getheaders(),payload)
        c.close();return result

    def test_roundtrip(self):
        code,headers,p=self.req('GET','/api/status')
        self.assertEqual(code,200)
        self.assertTrue(json.loads(p)['authorized'])
        code,headers,p=self.req('POST','/api/items',{'kind':'link','content':'https://example.com'})
        self.assertEqual(code,201)
        code,headers,p=self.req('GET','/api/items')
        self.assertTrue(any(it['content']=='https://example.com' for it in json.loads(p)['items']))
        code,headers,p=self.req('POST','/api/transfers/begin',{'name':'test.txt','size':5})
        self.assertEqual(code,200);ident=json.loads(p)['id']
        code,headers,p=self.req('PUT',f'/api/transfers/{ident}/chunk',b'hello',headers={'X-Offset':'0'})
        self.assertEqual(code,200)
        code,headers,p=self.req('POST',f'/api/transfers/{ident}/finish',{})
        self.assertEqual(code,200)
        code,headers,p=self.req('GET',f'/api/files/{ident}',headers={'Range':'bytes=1-3'})
        self.assertEqual(code,206);self.assertEqual(p,b'ell')
        code,headers,p=self.req('DELETE',f'/api/items/{ident}')
        self.assertEqual(code,200)

    def test_origin_rejection(self):
        code,_,_=self.req('POST','/api/items',{'kind':'text','content':'bad'},headers={'Origin':'https://evil.example'})
        self.assertEqual(code,403)

    def test_pairing_rate_limit(self):
        # Testing wrong attempts on loopback is still explicitly rate limited.
        for i in range(6):
            code,_,_=self.req('POST','/api/pair',{'pin':'wrong','name':'test'})
            self.assertEqual(code,403)
        code,_,_=self.req('POST','/api/pair',{'pin':self.server.pin,'name':'test'})
        self.assertEqual(code,429)

if __name__=='__main__': unittest.main()

class RemotePairTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.server=TransferHTTPServer(('0.0.0.0',0),Store(Path(self.tmp.name)),'My Laptop')
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start()
    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.thread.join(2);self.tmp.cleanup()
    def req(self,method,path,body=None,cookie=None):
        # A loopback alias mimics a different device's source address.
        c=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=5,source_address=('127.0.0.2',0))
        headers={'Host':f'127.0.0.1:{self.server.server_port}'}
        if isinstance(body,dict):body=json.dumps(body).encode();headers['Content-Type']='application/json'
        if cookie:headers['Cookie']=cookie
        c.request(method,path,body,headers)
        resp=c.getresponse()
        code,header,data=resp.status,dict(resp.getheaders()),resp.read()
        c.close();return code,header,data
    def test_pin_pair_share_and_revoke(self):
        self.assertEqual(self.req('GET','/api/status')[0],200)
        self.assertFalse(json.loads(self.req('GET','/api/status')[2])['authorized'])
        self.assertEqual(self.req('GET','/api/items')[0],401)
        self.assertEqual(self.req('POST','/api/pair',{'pin':'111111','name':'Android'})[0],403)
        code,h,p=self.req('POST','/api/pair',{'pin':self.server.pin,'name':'Phone'})
        self.assertEqual(code,200)
        cookie=h['Set-Cookie'].split(';')[0]
        self.assertEqual(self.req('GET','/api/items',cookie=cookie)[0],200)
        code,_,_=self.req('POST','/api/items',{'kind':'text','content':'Hi from Android'},cookie=cookie)
        self.assertEqual(code,201)
        self.server.store.clear_sessions()
        self.assertEqual(self.req('GET','/api/items',cookie=cookie)[0],401)
