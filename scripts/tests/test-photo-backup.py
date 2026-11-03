#!/usr/bin/env python3
"""Linux tests: synthetic photos, memory S3, real age; no production credentials."""
import datetime as dt
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
spec = importlib.util.spec_from_file_location('photos', Path(__file__).resolve().parents[1] / 'photo-backup.py')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
NOW = dt.datetime(2026, 11, 2, tzinfo=dt.timezone.utc)

class Source:
    def __init__(self):
        self.data = {'여행/사진.webp': b'synthetic photo\x00\xff', '../untrusted.jpg': b'synthetic other'}
        self.calls, self.list_count = [], 0
        self.mutate_on_second_list = self.mutate_on_get = self.truncate = False
    def get_paginator(self, operation):
        assert operation == 'list_objects_v2'
        return self
    def paginate(self, **kwargs):
        self.calls.append(('list', kwargs))
        self.list_count += 1
        data = dict(self.data)
        if self.mutate_on_second_list and self.list_count >= 2:
            data['new.webp'] = b'changed'
        rows = [{'Key': k, 'Size': len(v), 'ETag': '"' + hashlib.md5(v).hexdigest() + '"',
                 'LastModified': NOW} for k, v in data.items()]
        return [{'Contents': rows[:1]}, {}, {'Contents': rows[1:]}]
    def get_object(self, **kwargs):
        self.calls.append(('get', kwargs))
        data = self.data[kwargs['Key']]
        etag = '"' + hashlib.md5(data).hexdigest() + '"'
        if self.mutate_on_get:
            raise RuntimeError('412 synthetic failure: NEVER PRINT THIS PRIVATE TEXT')
        assert kwargs['IfMatch'] == etag
        return {'Body': io.BytesIO(data[:-1] if self.truncate else data),
                'ContentLength': len(data), 'ETag': etag, 'ContentType': 'image/webp'}

class Destination:
    def __init__(self):
        self.data, self.deleted, self.extra, self.uploaded = {}, [], [], []
        self.corrupt = self.fail_upload = False
    def upload_file(self, path, bucket, key, **kwargs):
        self.uploaded.append(key)
        if self.fail_upload:
            raise RuntimeError('private synthetic upload failure')
        self.data[key] = Path(path).read_bytes()
    def get_object(self, **kwargs):
        return {'Body': io.BytesIO(b'corrupt' if self.corrupt else self.data[kwargs['Key']])}
    def get_paginator(self, operation):
        return self
    def paginate(self, **kwargs):
        return [{'Contents': self.extra}]
    def delete_object(self, **kwargs):
        self.deleted.append(kwargs['Key'])

class PhotoTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary_key = tempfile.TemporaryDirectory()
        cls.identity = Path(cls.temporary_key.name) / 'age.key'
        subprocess.run(['age-keygen', '-o', str(cls.identity)], stderr=subprocess.DEVNULL, check=True)
        cls.recipient = subprocess.check_output(['age-keygen', '-y', str(cls.identity)]).decode().strip()
    @classmethod
    def tearDownClass(cls):
        cls.temporary_key.cleanup()
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.root.chmod(0o700)
        self.source, self.destination = Source(), Destination()
        self.config = {'source_bucket': 'yeogidot-storage', 'destination_bucket': 'yeogidot-photo-backups',
                       'days': 30, 'max_objects': 10000, 'max_bytes': 1024**3}
    def tearDown(self):
        self.temporary.cleanup()
    def run_backup(self):
        return p.backup(self.source, self.destination, self.root, self.recipient, self.config, NOW)
    def archive(self):
        path = self.root / 'download.tar.age'
        path.write_bytes(next(iter(self.destination.data.values())))
        return path

    def test_round_trip_preserves_names_bytes_metadata_and_rejects_path_traversal(self):
        self.assertEqual(self.run_backup()['objects'], 2)
        self.assertEqual(self.destination.deleted, [])
        self.assertTrue(all(call[0] in ('get', 'list') for call in self.source.calls))
        self.assertFalse(any(x.name.startswith('.photo-partial-') for x in self.root.iterdir()))
        self.assertNotIn(b'synthetic photo', next(iter(self.destination.data.values())))
        archive, restored = self.archive(), self.root / 'restored'
        self.assertEqual(p.restore(archive, self.identity, restored)['objects'], 2)
        for row in json.loads((restored / 'manifest.json').read_text())['objects']:
            self.assertEqual((restored / row['path']).read_bytes(), self.source.data[row['key']])
            self.assertEqual(row['http_metadata']['ContentType'], 'image/webp')
        self.assertFalse((self.root / 'untrusted.jpg').exists())
        cli = subprocess.run(['python3', p.__file__, 'verify', str(archive), str(self.identity)],
                             capture_output=True, text=True)
        self.assertEqual(cli.returncode, 0, cli.stderr)
        self.assertIn('PHOTO_VERIFY_SUCCESS objects=2', cli.stdout)

    def test_restore_never_overwrites_existing_directory(self):
        self.run_backup()
        target = self.root / 'existing'
        target.mkdir()
        (target / 'keep').write_text('keep')
        with self.assertRaises(ValueError):
            p.restore(self.archive(), self.identity, target)
        self.assertEqual((target / 'keep').read_text(), 'keep')

    def test_tampered_ciphertext_fails_without_publishing_plaintext(self):
        self.run_backup()
        path = self.archive()
        data = bytearray(path.read_bytes())
        data[-1] ^= 1
        path.write_bytes(data)
        target = self.root / 'restored'
        with self.assertRaises(Exception):
            p.restore(path, self.identity, target)
        self.assertFalse(target.exists())
        self.assertFalse(any(x.name.startswith('.photo-restore-') for x in self.root.iterdir()))

    def test_changing_missing_or_truncated_source_never_uploads_or_prunes(self):
        for attribute in ('mutate_on_second_list', 'mutate_on_get', 'truncate'):
            self.source = Source()
            setattr(self.source, attribute, True)
            with self.assertRaises(Exception):
                self.run_backup()
            self.assertEqual(self.destination.uploaded, [])
            self.assertEqual(self.destination.deleted, [])
            self.assertFalse((self.root / 'last-success.json').exists())

    def test_empty_or_oversized_source_is_rejected(self):
        self.source.data.clear()
        with self.assertRaises(ValueError):
            self.run_backup()
        self.source = Source()
        self.config['max_bytes'] = 1
        with self.assertRaises(ValueError):
            self.run_backup()
        self.assertFalse(self.destination.uploaded)

    def test_upload_failure_or_corruption_preserves_previous_success(self):
        self.run_backup()
        receipt = (self.root / 'last-success.json').read_bytes()
        for attribute in ('corrupt', 'fail_upload'):
            setattr(self.destination, attribute, True)
            with self.assertRaises(Exception):
                self.run_backup()
            setattr(self.destination, attribute, False)
            self.assertEqual((self.root / 'last-success.json').read_bytes(), receipt)
            self.assertEqual(self.destination.deleted, [])

    def test_retention_deletes_only_expired_snapshot_keys_after_verified_upload(self):
        old = NOW - dt.timedelta(days=40)
        expired = p.PREFIX + 'photos-20260901T000000Z-aabbccddeeff.tar.age'
        self.destination.extra = [
            {'Key': expired, 'LastModified': old},
            {'Key': p.PREFIX + 'photos-20260901T000000Z-112233445566.tar.age', 'LastModified': NOW},
            {'Key': p.PREFIX + 'photos-20261102T000000Z-112233445566.tar.age', 'LastModified': old},
            {'Key': 'mysql/age-v1/unrelated.tar.age', 'LastModified': old},
            {'Key': p.PREFIX + 'keep.tar.age', 'LastModified': old}]
        self.run_backup()
        self.assertEqual(self.destination.deleted, [expired])

    def test_source_deletion_keeps_previous_snapshot(self):
        self.run_backup()
        first_key = self.destination.uploaded[-1]
        del self.source.data['여행/사진.webp']
        self.run_backup()
        self.assertIn(first_key, self.destination.data)
        self.assertEqual(self.destination.deleted, [])

    def test_low_disk_and_concurrent_job_are_rejected(self):
        with patch.object(p.shutil, 'disk_usage', return_value=type('Usage', (), {'free': 1})()):
            with self.assertRaises(RuntimeError):
                self.run_backup()
        with (self.root / '.photo-backup.lock').open('a') as lock:
            p.fcntl.flock(lock, p.fcntl.LOCK_EX | p.fcntl.LOCK_NB)
            with self.assertRaises(BlockingIOError):
                self.run_backup()
        self.assertFalse(self.destination.uploaded)

    def test_restore_rejects_links_traversal_and_wrong_manifest(self):
        import tarfile
        for name, link in [('../escape', False), ('objects/00000000', True), ('objects/00000000', False)]:
            data = io.BytesIO()
            with tarfile.open(fileobj=data, mode='w') as archive:
                member = tarfile.TarInfo(name)
                if link:
                    member.type, member.linkname = tarfile.SYMTYPE, '/tmp/escape'
                    archive.addfile(member)
                else:
                    member.size = 3
                    archive.addfile(member, io.BytesIO(b'abc'))
                manifest = {'version': 1, 'source_bucket': 'yeogidot-storage', 'count': 1, 'total_bytes': 3,
                            'objects': [{'path': name, 'key': 'key', 'size': 3, 'sha256': '0' * 64}]}
                p.add_bytes(archive, 'manifest.json', json.dumps(manifest).encode())
            data.seek(0)
            with self.assertRaises(ValueError):
                p.scan_plain_tar(data)

    def test_r2_endpoints_bucket_and_separate_credentials_validation(self):
        endpoint = 'https://' + 'a' * 32 + '.r2.cloudflarestorage.com'
        env = {'PHOTO_SOURCE_ENDPOINT_URL': endpoint, 'PHOTO_BACKUP_ENDPOINT_URL': endpoint,
               'PHOTO_BACKUP_REGION': 'auto', 'PHOTO_SOURCE_BUCKET': 'yeogidot-storage',
               'PHOTO_BACKUP_BUCKET': 'yeogidot-photo-backups',
               'PHOTO_SOURCE_ACCESS_KEY_ID': 'source-key', 'PHOTO_SOURCE_SECRET_ACCESS_KEY': 'test',
               'PHOTO_BACKUP_ACCESS_KEY_ID': 'backup-key', 'PHOTO_BACKUP_SECRET_ACCESS_KEY': 'test'}
        config = p.read_config(env)
        self.assertEqual(config['days'], 30)
        self.assertEqual(config['region'], 'auto')
        self.assertEqual(config['max_bytes'], 256 * 1024**2)
        # A separately owned R2 account also works; same account is not a prerequisite.
        other = 'https://' + 'b' * 32 + '.r2.cloudflarestorage.com'
        self.assertEqual(p.read_config(dict(env, PHOTO_BACKUP_ENDPOINT_URL=other))['destination_endpoint'], other)
        without_region = dict(env)
        del without_region['PHOTO_BACKUP_REGION']
        self.assertEqual(p.read_config(without_region)['region'], 'auto')
        for key, value in [('PHOTO_BACKUP_ENDPOINT_URL', endpoint.replace('https:', 'http:')),
                           ('PHOTO_BACKUP_ENDPOINT_URL', endpoint + '/bucket'),
                           ('PHOTO_BACKUP_ENDPOINT_URL', 'https://example.compat.objectstorage.ap-osaka-1.oraclecloud.com'),
                           ('PHOTO_BACKUP_ENDPOINT_URL', 'https://photos.example.com'),
                           ('PHOTO_BACKUP_REGION', 'ap-osaka-1'),
                           ('PHOTO_BACKUP_BUCKET', 'yeogidot-storage'),
                           ('PHOTO_BACKUP_BUCKET', 'yeogidot-db-backups'),
                           ('PHOTO_SOURCE_BUCKET', 'wrong'), ('PHOTO_SOURCE_ACCESS_KEY_ID', ''),
                           ('PHOTO_BACKUP_ACCESS_KEY_ID', env['PHOTO_SOURCE_ACCESS_KEY_ID']),
                           ('PHOTO_RETENTION_DAYS', '0'), ('PHOTO_MAX_BYTES', '-1')]:
            with self.subTest(setting=key, value=value):
                with self.assertRaises(ValueError):
                    p.read_config(dict(env, **{key: value}))

    def test_cli_failure_does_not_log_raw_secrets(self):
        public = self.root / 'public.txt'
        public.write_text(self.recipient)
        env = dict(os.environ, PHOTO_SOURCE_ENDPOINT_URL='https://private-secret.invalid')
        result = subprocess.run(['python3', p.__file__, 'backup', str(self.root), str(public)],
                                env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('PHOTO_BACKUP_FAILED stage=configuration type=', result.stderr)
        self.assertNotIn('private-secret', result.stderr)

if __name__ == '__main__':
    unittest.main()

