#!/usr/bin/env python3
"""Linux tests: real age encryption; in-memory S3, never production credentials."""
import datetime as dt
import gzip
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('uploader',Path(__file__).resolve().parents[1]/'mysql-backup-r2.py')
u=importlib.util.module_from_spec(spec); spec.loader.exec_module(u)
class MemoryS3:
    def __init__(self): self.objects={}; self.deleted=[]; self.corrupt=False
    def upload_file(self,path,bucket,key,**kwargs): self.objects[key]=Path(path).read_bytes()
    def get_object(self,**kwargs): return {'Body':io.BytesIO(b'corrupt' if self.corrupt else self.objects[kwargs['Key']])}
    def get_paginator(self,name): return self
    def paginate(self,**kwargs): return [{'Contents':getattr(self,'entries',[])}]
    def delete_object(self,**kwargs): self.deleted.append(kwargs['Key'])
class UploadTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.root=Path(self.temp.name)
        self.key=self.root/'key'
        subprocess.run(['age-keygen','-o',str(self.key)],stderr=subprocess.DEVNULL,check=True)
        self.recipient=subprocess.check_output(['age-keygen','-y',str(self.key)]).decode().strip()
        self.bundle=self.root/'mysql-yeogidot-20261101T052112Z-1'; self.bundle.mkdir()
        with gzip.open(self.bundle/'backup.sql.gz','wb') as f: f.write(b'synthetic SQL only')
        (self.bundle/'SHA256SUMS').write_text(u.digest(self.bundle/'backup.sql.gz')+'  backup.sql.gz\n')
        self.s3=MemoryS3()
    def tearDown(self): self.temp.cleanup()
    def test_real_encryption_decryption_and_remote_repair(self):
        u.upload_bundle(self.s3,'test',self.bundle,self.recipient)
        cipher=next(iter(self.s3.objects.values()))
        plain=subprocess.run(['age','-d','-i',str(self.key)],input=cipher,capture_output=True,check=True).stdout
        with tarfile.open(fileobj=io.BytesIO(plain)) as t:
            self.assertEqual(sorted(t.getnames()),['SHA256SUMS','backup.sql.gz'])
            self.assertEqual(gzip.decompress(t.extractfile('backup.sql.gz').read()),b'synthetic SQL only')
        # Remote loss is repaired even though the local upload marker exists.
        self.s3.objects.clear()
        u.upload_bundle(self.s3,'test',self.bundle,self.recipient)
        self.assertEqual(len(self.s3.objects),1)
    def test_corrupt_remote_does_not_mark_success(self):
        self.s3.corrupt=True
        with self.assertRaises(RuntimeError): u.upload_bundle(self.s3,'test',self.bundle,self.recipient)
        self.assertFalse((self.bundle/'.r2-uploaded.json').exists())
    def test_damaged_local_dump_blocks_upload(self):
        (self.bundle/'backup.sql.gz').write_bytes(b'corrupt')
        with self.assertRaises(RuntimeError): u.upload_bundle(self.s3,'test',self.bundle,self.recipient)
        self.assertFalse(self.s3.objects)
    def test_retention_protects_recent_and_unrelated_objects(self):
        now=dt.datetime(2026,11,1,tzinfo=dt.timezone.utc); old=now-dt.timedelta(days=40)
        expired=u.PREFIX+'mysql-yeogidot-20260901T000000Z-1.tar.age'
        self.s3.entries=[{'Key':expired,'LastModified':old},
            {'Key':u.PREFIX+'mysql-yeogidot-20260901T000000Z-2.tar.age','LastModified':now},
            {'Key':u.PREFIX+'mysql-yeogidot-20261101T000000Z-3.tar.age','LastModified':old},
            {'Key':u.PREFIX+'unrelated.tar.age','LastModified':old},
            {'Key':'photos/other.tar.age','LastModified':old}]
        u.prune_remote(self.s3,'test',30,now)
        self.assertEqual(self.s3.deleted,[expired])
if __name__=='__main__': unittest.main()
