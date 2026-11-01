#!/usr/bin/env python3
"""Encrypt completed MySQL backup bundles with age and verify their private R2 copies."""
import datetime as dt
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile

BUNDLE = re.compile(r'mysql-yeogidot-(\d{8}T\d{6}Z)-\d+')
PREFIX = 'mysql/age-v1/'

def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()

def verify_remote(client, bucket, key, expected):
    response = client.get_object(Bucket=bucket, Key=key)
    value = hashlib.sha256()
    try:
        for chunk in iter(lambda: response['Body'].read(1024 * 1024), b''):
            value.update(chunk)
    finally:
        response['Body'].close()
    if value.hexdigest() != expected:
        raise RuntimeError('Remote ciphertext checksum mismatch')

def encrypt_bundle(bundle, recipient, output):
    with output.open('wb') as target:
        proc = subprocess.Popen(['age', '-r', recipient], stdin=subprocess.PIPE, stdout=target, stderr=subprocess.DEVNULL)
        try:
            with tarfile.open(fileobj=proc.stdin, mode='w|') as archive:
                for name in ('backup.sql.gz', 'SHA256SUMS'):
                    archive.add(bundle / name, arcname=name, recursive=False)
        except BaseException:
            proc.stdin.close()
            proc.wait()
            raise
        proc.stdin.close()
        if proc.wait():
            raise RuntimeError('Encryption failed')

def upload_bundle(client, bucket, bundle, recipient):
    dump, sums = bundle / 'backup.sql.gz', bundle / 'SHA256SUMS'
    if not all(p.is_file() and not p.is_symlink() for p in (dump, sums)):
        raise RuntimeError('Incomplete or linked backup bundle')
    fields = sums.read_text().split()
    plain_hash = digest(dump)
    if fields != [plain_hash, 'backup.sql.gz']:
        raise RuntimeError('Local dump checksum mismatch')
    key = PREFIX + bundle.name + '.tar.age'
    marker = bundle / '.r2-uploaded.json'
    if marker.is_file() and not marker.is_symlink():
        state = json.loads(marker.read_text())
        if state.get('plaintext_sha256') == plain_hash and state.get('recipient') == recipient and state.get('key') == key:
            try:
                verify_remote(client, bucket, key, state['ciphertext_sha256'])
                return
            except Exception:
                # Deleted/damaged remote copies are repaired from the retained local bundle.
                pass
    with tempfile.TemporaryDirectory(prefix='.r2-partial-', dir=bundle.parent) as directory:
        encrypted = Path(directory) / 'backup.tar.age'
        encrypt_bundle(bundle, recipient, encrypted)
        cipher_hash = digest(encrypted)
        client.upload_file(str(encrypted), bucket, key, ExtraArgs={'ContentType': 'application/octet-stream', 'Metadata': {'sha256': cipher_hash}})
        verify_remote(client, bucket, key, cipher_hash)
        state = {'key': key, 'recipient': recipient, 'plaintext_sha256': plain_hash, 'ciphertext_sha256': cipher_hash}
        temporary_marker = bundle / '.r2-uploaded.tmp'
        temporary_marker.write_text(json.dumps(state))
        temporary_marker.replace(marker)

def prune_remote(client, bucket, days, now=None):
    now = now or dt.datetime.now(dt.timezone.utc)
    cutoff = now - dt.timedelta(days=days)
    for page in client.get_paginator('list_objects_v2').paginate(Bucket=bucket, Prefix=PREFIX):
        for entry in page.get('Contents', []):
            key = entry['Key']
            if not key.startswith(PREFIX) or not key.endswith('.tar.age'):
                continue
            name = key[len(PREFIX):-len('.tar.age')]
            match = BUNDLE.fullmatch(name)
            if not match:
                continue
            stamped = dt.datetime.strptime(match[1], '%Y%m%dT%H%M%SZ').replace(tzinfo=dt.timezone.utc)
            if stamped < cutoff and entry['LastModified'] < cutoff:
                client.delete_object(Bucket=bucket, Key=key)

def main():
    import boto3
    from botocore.config import Config
    os.umask(0o077)
    if len(sys.argv) != 3:
        raise RuntimeError('Usage: mysql-backup-r2.py BACKUP_DIR AGE_RECIPIENT_FILE')
    root, recipient_path = Path(sys.argv[1]), Path(sys.argv[2])
    if root.is_symlink() or not root.is_dir() or root.stat().st_uid != os.geteuid() or root.stat().st_mode & 0o077:
        raise RuntimeError('Unsafe backup directory')
    if recipient_path.is_symlink() or not recipient_path.is_file():
        raise RuntimeError('Invalid recipient file')
    recipient = recipient_path.read_text().strip()
    if not re.fullmatch(r'age1[0-9a-z]{58}', recipient):
        raise RuntimeError('Invalid age public recipient')
    endpoint, bucket = os.environ['R2_ENDPOINT_URL'].rstrip('/'), os.environ['R2_BUCKET']
    if not re.fullmatch(r'https://[0-9a-f]{32}\.r2\.cloudflarestorage\.com', endpoint) or bucket != 'yeogidot-db-backups':
        raise RuntimeError('Unexpected endpoint or bucket')
    days = int(os.environ.get('R2_RETENTION_DAYS', '30'))
    if not 1 <= days <= 3650:
        raise RuntimeError('Invalid retention')
    client = boto3.client('s3', endpoint_url=endpoint, region_name='auto',
        aws_access_key_id=os.environ['AWS_ACCESS_KEY_ID'], aws_secret_access_key=os.environ['AWS_SECRET_ACCESS_KEY'],
        config=Config(signature_version='s3v4',connect_timeout=10,read_timeout=60,retries={'max_attempts':3}))
    # Explicit credentials; no EC2 metadata discovery or unrelated application R2 configuration.
    with (root / '.r2-upload.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        bundles = sorted(p for p in root.iterdir() if BUNDLE.fullmatch(p.name) and p.is_dir() and not p.is_symlink())
        if not bundles:
            raise RuntimeError('No completed local backups')
        for bundle in bundles:
            upload_bundle(client, bucket, bundle, recipient)
        # Only prune after all local retained bundles have confirmed remote copies.
        prune_remote(client, bucket, days)
        print('R2_BACKUP_SUCCESS verified_bundles=%d retention_days=%d' % (len(bundles),days))

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # SDK errors may contain URLs or credentials. Report only the class, never raw text.
        print('R2_BACKUP_FAILED type=' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
