#!/usr/bin/env python3
import importlib.util
import io
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('notify',Path(__file__).resolve().parents[1]/'mysql-backup-notify.py')
n=importlib.util.module_from_spec(spec);spec.loader.exec_module(n)
BASE='https://hc-ping.com/00000000-0000-0000-0000-000000000000'
class Response(io.BytesIO): status=200
class Opener:
    def __init__(self,replies): self.replies=list(replies);self.requests=[]
    def open(self,request,timeout):
        self.requests.append((request,timeout))
        response=self.replies.pop(0)
        if isinstance(response,Exception): raise response
        return Response(response)
class Tests(unittest.TestCase):
    def test_success_and_failure_paths(self):
        for mode,suffix in [('success',''),('fail','/fail')]:
            o=Opener([b'OK'])
            n.send_ping(BASE,mode,o)
            req,timeout=o.requests[0]
            self.assertEqual(req.full_url,BASE+suffix)
            self.assertEqual(req.get_method(),'POST')
            self.assertEqual(timeout,5)
            self.assertIn(b'Yeogidot DB',req.data)
    def test_transient_failure_retries(self):
        o=Opener([OSError('private URL must not escape'),b'OK'])
        n.send_ping(BASE,'success',o,pause=lambda _:None)
        self.assertEqual(len(o.requests),2)
    def test_http_200_with_unknown_or_rate_limited_check_is_failure(self):
        for body in [b'OK (not found)',b'OK (rate limited)']:
            o=Opener([body]*3)
            with self.assertRaisesRegex(RuntimeError,'Monitoring delivery failed'):
                n.send_ping(BASE,'success',o,pause=lambda _:None)
            self.assertEqual(len(o.requests),3)
    def test_reject_untrusted_endpoint_and_mode(self):
        for url in [BASE.replace('https:','http:'),BASE+'?secret=1',BASE.replace('hc-ping.com','evil.invalid'),BASE+'/fail']:
            with self.assertRaises(ValueError): n.send_ping(url,'success',Opener([]))
        with self.assertRaises(ValueError): n.send_ping(BASE,'unknown',Opener([]))
    def test_photo_payload_is_distinct_and_rejects_unknown_kind(self):
        for mode in ('success','fail'):
            opener=Opener([b'OK'])
            n.send_ping(BASE,mode,opener,kind='photos')
            req,_=opener.requests[0]
            self.assertIn(b'photo backup',req.data)
            self.assertNotIn(b'DB',req.data)
        with self.assertRaises(ValueError):
            n.send_ping(BASE,'success',Opener([]),kind='unknown')
    def test_no_redirect(self):
        self.assertIsNone(n.NoRedirect().redirect_request(None,None,302,'',{},'https://evil.invalid'))
if __name__=='__main__': unittest.main()
