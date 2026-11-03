#!/usr/bin/env python3
"""Daily encrypted photo snapshots to a separate private R2 bucket. Linux only."""
import argparse
import datetime as dt
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import uuid

PREFIX = 'photos/age-v1/'
NAME = re.compile(r'photos-(\d{8}T\d{6}Z)-[0-9a-f]{12}\.tar\.age')
CHUNK = 1024 * 1024
MAX_MANIFEST = 8 * 1024 * 1024
STAGE = 'configuration'


def sha256_file(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def inventory(client, bucket, max_objects, max_bytes):
    entries = []
    total = 0
    keys = set()
    for page in client.get_paginator('list_objects_v2').paginate(Bucket=bucket):
        for obj in page.get('Contents', []):
            key, size, etag = obj['Key'], obj['Size'], obj['ETag']
            if not isinstance(key, str) or not key or key in keys:
                raise ValueError('Invalid source key')
            if not isinstance(size, int) or size < 0 or not etag:
                raise ValueError('Invalid source metadata')
            keys.add(key)
            total += size
            entries.append({'key': key, 'size': size, 'etag': etag,
                            'modified': obj['LastModified'].isoformat()})
            if len(entries) > max_objects or total > max_bytes:
                raise ValueError('Source exceeds configured limit')
    # An empty listing may indicate a wrong source or mass deletion. Never publish/prune.
    if not entries:
        raise ValueError('Empty source')
    return sorted(entries, key=lambda row: row['key'])


class HashReader:
    def __init__(self, body):
        self.body = body
        self.digest = hashlib.sha256()
        self.count = 0

    def read(self, size=-1):
        data = self.body.read(size)
        self.digest.update(data)
        self.count += len(data)
        return data


def add_bytes(archive, name, data):
    entry = tarfile.TarInfo(name)
    entry.size, entry.mode, entry.mtime = len(data), 0o600, 0
    archive.addfile(entry, io.BytesIO(data))


def encrypt_snapshot(source, bucket, entries, recipient, output, snapshot):
    # Only the encrypted stream is persisted. Keys are recorded in the encrypted manifest;
    # tar paths are numeric, never untrusted source object names.
    with output.open('xb') as cipher:
        process = subprocess.Popen(['age', '-r', recipient], stdin=subprocess.PIPE,
                                   stdout=cipher, stderr=subprocess.DEVNULL)
        try:
            records = []
            with tarfile.open(fileobj=process.stdin, mode='w|', format=tarfile.USTAR_FORMAT) as archive:
                for index, entry in enumerate(entries):
                    response = source.get_object(Bucket=bucket, Key=entry['key'], IfMatch=entry['etag'])
                    body = response['Body']
                    try:
                        if response['ContentLength'] != entry['size'] or response['ETag'] != entry['etag']:
                            raise ValueError('Source changed during download')
                        stream = HashReader(body)
                        path = 'objects/%08d' % index
                        member = tarfile.TarInfo(path)
                        member.size, member.mode, member.mtime = entry['size'], 0o600, 0
                        archive.addfile(member, stream)
                        if stream.count != entry['size'] or stream.read(1):
                            raise ValueError('Source size mismatch')
                        metadata = {name: response[name] for name in ('ContentType', 'CacheControl', 'ContentDisposition', 'ContentEncoding', 'ContentLanguage') if name in response}
                        records.append(dict(entry, path=path, sha256=stream.digest.hexdigest(), http_metadata=metadata))
                    finally:
                        body.close()
                manifest = {'version': 1, 'snapshot': snapshot, 'source_bucket': bucket,
                            'objects': records, 'count': len(records),
                            'total_bytes': sum(row['size'] for row in records)}
                data = json.dumps(manifest, ensure_ascii=True, separators=(',', ':')).encode()
                if len(data) > MAX_MANIFEST:
                    raise ValueError('Manifest too large')
                add_bytes(archive, 'manifest.json', data)
            process.stdin.close()
            if process.wait() != 0:
                raise RuntimeError('Encryption failed')
        except BaseException:
            if process.poll() is None:
                process.terminate()
            process.wait()
            if not process.stdin.closed:
                process.stdin.close()
            raise


def verify_remote(client, bucket, key, expected):
    response = client.get_object(Bucket=bucket, Key=key)
    digest = hashlib.sha256()
    try:
        for part in iter(lambda: response['Body'].read(CHUNK), b''):
            digest.update(part)
    finally:
        response['Body'].close()
    if digest.hexdigest() != expected:
        raise RuntimeError('Remote checksum mismatch')


def prune_snapshots(client, bucket, days, now):
    cutoff = now - dt.timedelta(days=days)
    expired = []
    for page in client.get_paginator('list_objects_v2').paginate(Bucket=bucket, Prefix=PREFIX):
        for obj in page.get('Contents', []):
            key = obj['Key']
            if not key.startswith(PREFIX):
                continue
            match = NAME.fullmatch(key[len(PREFIX):])
            if not match:
                continue
            stamped = dt.datetime.strptime(match[1], '%Y%m%dT%H%M%SZ').replace(tzinfo=dt.timezone.utc)
            if stamped < cutoff and obj['LastModified'] < cutoff:
                expired.append(key)
    # List first: a listing failure must not leave a partially pruned inventory.
    for key in expired:
        client.delete_object(Bucket=bucket, Key=key)


def backup(source, destination, root, recipient, config, now=None):
    global STAGE
    now = now or dt.datetime.now(dt.timezone.utc)
    with (root / '.photo-backup.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        STAGE = 'source-list'
        entries = inventory(source, config['source_bucket'], config['max_objects'], config['max_bytes'])
        total = sum(row['size'] for row in entries)
        needed = int(total * 1.1) + len(entries) * 2048 + 64 * 1024 * 1024
        if shutil.disk_usage(root).free < needed:
            raise RuntimeError('Insufficient encrypted staging space')
        snapshot = 'photos-' + now.strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:12]
        key = PREFIX + snapshot + '.tar.age'
        with tempfile.TemporaryDirectory(prefix='.photo-partial-', dir=root) as directory:
            encrypted = Path(directory) / 'snapshot.tar.age'
            STAGE = 'encrypt'
            encrypt_snapshot(source, config['source_bucket'], entries, recipient, encrypted, snapshot)
            STAGE = 'source-recheck'
            if inventory(source, config['source_bucket'], config['max_objects'], config['max_bytes']) != entries:
                raise RuntimeError('Source changed; retry snapshot later')
            digest = sha256_file(encrypted)
            STAGE = 'upload'
            # TransferConfig is supplied only by the real CLI; memory tests use no SDK.
            extra = {'ExtraArgs': {'ContentType': 'application/octet-stream', 'Metadata': {'sha256': digest}}}
            if config.get('transfer_config') is not None:
                extra['Config'] = config['transfer_config']
            destination.upload_file(str(encrypted), config['destination_bucket'], key, **extra)
            STAGE = 'remote-verify'
            verify_remote(destination, config['destination_bucket'], key, digest)
            # Retention is independent of source deletion and runs only after a new verified snapshot.
            STAGE = 'retention'
            prune_snapshots(destination, config['destination_bucket'], config['days'], now)
            STAGE = 'receipt'
            receipt = {'snapshot': snapshot, 'completed_at': dt.datetime.now(dt.timezone.utc).isoformat(),
                       'objects': len(entries), 'total_bytes': total, 'ciphertext_sha256': digest}
            temporary = root / '.last-success.tmp'
            temporary.write_text(json.dumps(receipt), encoding='utf-8')
            temporary.replace(root / 'last-success.json')
        return receipt


def check_directory(root):
    if root.is_symlink() or not root.is_dir():
        raise ValueError('Invalid work directory')
    stat = root.stat()
    if stat.st_uid != os.geteuid() or stat.st_mode & 0o077:
        raise ValueError('Work directory must be owned by runner with mode 700')


def positive_integer(env, name, default, maximum):
    raw = env.get(name, str(default))
    if not re.fullmatch(r'[1-9][0-9]*', raw) or int(raw) > maximum:
        raise ValueError('Invalid numeric setting')
    return int(raw)


def read_config(env):
    source = env['PHOTO_SOURCE_ENDPOINT_URL'].rstrip('/')
    destination = env['PHOTO_BACKUP_ENDPOINT_URL'].rstrip('/')
    region = env.get('PHOTO_BACKUP_REGION', 'auto')
    endpoint_pattern = r'https://[0-9a-f]{32}\.r2\.cloudflarestorage\.com'
    if not re.fullmatch(endpoint_pattern, source):
        raise ValueError('Unexpected source endpoint')
    if not re.fullmatch(endpoint_pattern, destination):
        raise ValueError('Unexpected backup endpoint')
    if region != 'auto':
        raise ValueError('R2 backup region must be auto')
    if env['PHOTO_SOURCE_BUCKET'] != 'yeogidot-storage' or env['PHOTO_BACKUP_BUCKET'] != 'yeogidot-photo-backups':
        raise ValueError('Unexpected source/destination bucket')
    for name in ('PHOTO_SOURCE_ACCESS_KEY_ID', 'PHOTO_SOURCE_SECRET_ACCESS_KEY',
                 'PHOTO_BACKUP_ACCESS_KEY_ID', 'PHOTO_BACKUP_SECRET_ACCESS_KEY'):
        if not env.get(name):
            raise ValueError('Missing explicit credential')
    if env['PHOTO_SOURCE_ACCESS_KEY_ID'] == env['PHOTO_BACKUP_ACCESS_KEY_ID']:
        raise ValueError('Use separate source and backup credentials')
    return {'source_endpoint': source, 'destination_endpoint': destination, 'region': region,
            'source_bucket': env['PHOTO_SOURCE_BUCKET'], 'destination_bucket': env['PHOTO_BACKUP_BUCKET'],
            'days': positive_integer(env, 'PHOTO_RETENTION_DAYS', 30, 3650),
            'max_objects': positive_integer(env, 'PHOTO_MAX_OBJECTS', 10000, 10000),
            'max_bytes': positive_integer(env, 'PHOTO_MAX_BYTES', 256 * 1024**2, 1024**4)}


def scan_plain_tar(stream, output=None):
    observed = {}
    manifest = None
    total = 0
    with tarfile.open(fileobj=stream, mode='r|') as archive:
        for member in archive:
            if not member.isfile():
                raise ValueError('Non-regular archive member')
            if member.name == 'manifest.json':
                if manifest is not None or member.size > MAX_MANIFEST:
                    raise ValueError('Invalid manifest')
                manifest = json.loads(archive.extractfile(member).read())
                continue
            if manifest is not None or not re.fullmatch(r'objects/[0-9]{8}', member.name) or member.name in observed:
                raise ValueError('Unsafe or duplicate archive member')
            if len(observed) >= 10000:
                raise ValueError('Too many objects')
            digest = hashlib.sha256()
            body = archive.extractfile(member)
            target = None
            if output is not None:
                (output / 'objects').mkdir(exist_ok=True, mode=0o700)
                target = (output / member.name).open('xb')
            try:
                count = 0
                for part in iter(lambda: body.read(CHUNK), b''):
                    count += len(part)
                    digest.update(part)
                    if target is not None:
                        target.write(part)
            finally:
                if target is not None:
                    target.close()
            if count != member.size:
                raise ValueError('Truncated object')
            total += count
            observed[member.name] = (count, digest.hexdigest())
    if not isinstance(manifest, dict) or manifest.get('version') != 1 or manifest.get('source_bucket') != 'yeogidot-storage':
        raise ValueError('Invalid manifest schema')
    records = manifest.get('objects')
    if not isinstance(records, list) or manifest.get('count') != len(observed) or len(records) != len(observed) or manifest.get('total_bytes') != total:
        raise ValueError('Manifest count mismatch')
    paths, keys = set(), set()
    for row in records:
        path, key = row['path'], row['key']
        if path in paths or key in keys or not isinstance(key, str) or not key:
            raise ValueError('Duplicate manifest mapping')
        paths.add(path)
        keys.add(key)
        if observed.get(path) != (row['size'], row['sha256']):
            raise ValueError('Restored checksum mismatch')
    if not observed:
        raise ValueError('Empty restored snapshot')
    if output is not None:
        (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=True), encoding='utf-8')
    return {'objects': len(observed), 'total_bytes': total}


def decrypt_and_verify(archive, identity, output=None):
    process = subprocess.Popen(['age', '-d', '-i', str(identity), str(archive)],
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    try:
        result = scan_plain_tar(process.stdout, output)
        # Drain to EOF so age checks the authenticated end of the encrypted stream.
        for _ in iter(lambda: process.stdout.read(CHUNK), b''):
            pass
        process.stdout.close()
        if process.wait() != 0:
            raise RuntimeError('Decryption failed')
        return result
    except BaseException:
        if process.poll() is None:
            process.terminate()
        process.wait()
        process.stdout.close()
        raise


def restore(archive, identity, output):
    if output.exists() or output.is_symlink():
        raise ValueError('Restore destination must not exist')
    check_directory(output.parent)
    # Atomic publish: a corrupt archive must not leave a seemingly successful restore directory.
    with tempfile.TemporaryDirectory(prefix='.photo-restore-', dir=output.parent) as temporary:
        root = Path(temporary) / 'restored'
        root.mkdir(mode=0o700)
        result = decrypt_and_verify(archive, identity, root)
        root.rename(output)
    return result


def main():
    global STAGE
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    run = commands.add_parser('backup')
    run.add_argument('work_dir', type=Path)
    run.add_argument('recipient_file', type=Path)
    for mode in ('verify', 'restore'):
        cmd = commands.add_parser(mode)
        cmd.add_argument('archive', type=Path)
        cmd.add_argument('identity_file', type=Path)
        if mode == 'restore':
            cmd.add_argument('output_dir', type=Path)
    args = parser.parse_args()
    if args.command != 'backup':
        STAGE = args.command
        result = restore(args.archive, args.identity_file, args.output_dir) if args.command == 'restore' else decrypt_and_verify(args.archive, args.identity_file)
        print('PHOTO_' + args.command.upper() + '_SUCCESS objects=%d bytes=%d' % (result['objects'], result['total_bytes']))
        return
    check_directory(args.work_dir)
    if args.recipient_file.is_symlink() or not args.recipient_file.is_file():
        raise ValueError('Invalid public recipient file')
    recipient = args.recipient_file.read_text().strip()
    if not re.fullmatch(r'age1[0-9a-z]{58}', recipient):
        raise ValueError('Invalid age recipient')
    config = read_config(os.environ)
    import boto3
    from botocore.config import Config
    from boto3.s3.transfer import TransferConfig
    sdk_config = Config(signature_version='s3v4', connect_timeout=10, read_timeout=60,
                        retries={'max_attempts': 3}, s3={'addressing_style': 'path'},
                        request_checksum_calculation='when_required', response_checksum_validation='when_required')
    def client(endpoint, region, key_prefix):
        return boto3.client('s3', endpoint_url=endpoint, region_name=region, config=sdk_config,
                            aws_access_key_id=os.environ[key_prefix + '_ACCESS_KEY_ID'],
                            aws_secret_access_key=os.environ[key_prefix + '_SECRET_ACCESS_KEY'])
    source = client(config['source_endpoint'], 'auto', 'PHOTO_SOURCE')
    destination = client(config['destination_endpoint'], config['region'], 'PHOTO_BACKUP')
    config['transfer_config'] = TransferConfig(max_concurrency=1, use_threads=False)
    result = backup(source, destination, args.work_dir, recipient, config)
    print('PHOTO_BACKUP_SUCCESS objects=%d bytes=%d retention_days=%d' % (result['objects'], result['total_bytes'], config['days']))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # No object names, endpoints, credentials or raw SDK exceptions in journal/email.
        print('PHOTO_BACKUP_FAILED stage=%s type=%s' % (STAGE, type(error).__name__), file=sys.stderr)
        sys.exit(1)

