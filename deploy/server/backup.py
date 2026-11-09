#!/usr/bin/env python3
"""Quiescent, encrypted backups; restores create isolated resources, never reuse a target."""
import argparse
import contextlib
import datetime as dt
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import shutil
import signal
import subprocess
import tarfile
import tempfile
import time
import uuid

PAYLOADS = ('database.dump', 'media.tar.gz', 'configuration.tar.gz')
MAX_ARCHIVE_BYTES = 32 * 1024**3


class BackupError(Exception):
    pass


def run(args, *, data=None, output=None, environment=None, input_stream=None):
    try:
        result = subprocess.run(args, input=data, stdout=output or subprocess.PIPE,
                                stderr=subprocess.PIPE, env=environment, stdin=input_stream, check=True, timeout=1800)
    except (OSError, subprocess.SubprocessError) as error:
        # Docker/Compose/GPG errors may include configuration or source content.
        raise BackupError(f'Command failed: {Path(args[0]).name}; confidential output withheld') from error
    return result.stdout


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def private_directory(path):
    if path.is_symlink():
        raise BackupError('Symlink directory refused')
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.stat().st_mode & 0o077:
        raise BackupError('Directory must have permissions 0700')


def json_command(args):
    return json.loads(run(args))


def media_inventory(archive):
    result = {}
    total = 0
    with tarfile.open(archive, 'r:gz') as source:
        for member in source:
            name = str(PurePosixPath(member.name))
            if member.isdir():
                if name.startswith('/') or '..' in PurePosixPath(name).parts:
                    raise BackupError('Unsafe media directory')
                continue
            if not member.isfile() or name.startswith('/') or '..' in PurePosixPath(name).parts or name in result:
                raise BackupError('Unsafe or duplicate media entry')
            total += member.size
            if total > MAX_ARCHIVE_BYTES or len(result) >= 100000:
                raise BackupError('Media archive exceeds safety budget')
            with source.extractfile(member) as stream:
                result[name] = hashlib.file_digest(stream, 'sha256').hexdigest()
    return result


def db_exec(container, command, *, data=None, output=None):
    return run(['docker', 'exec', '-i', container, 'sh', '-ec', command], data=data, output=output)


def facts(container):
    sql = r"""\set ON_ERROR_STOP on
BEGIN READ ONLY;
SELECT format('SELECT json_build_object(''table'', %L, ''count'', count(*)) FROM %I.%I;',
 tablename,schemaname,tablename) FROM pg_tables WHERE schemaname='public' ORDER BY tablename
\gexec
SELECT json_build_object('media',coalesce(json_agg(json_build_object('path',storage_path,'sha256',checksum_sha256) ORDER BY storage_path),'[]'::json)) FROM media_assets;
SELECT json_build_object('flyway',coalesce(json_agg(json_build_object('version',version,'checksum',checksum,'success',success) ORDER BY installed_rank),'[]'::json)) FROM flyway_schema_history;
COMMIT;
"""
    rows = [json.loads(line) for line in db_exec(container,
            'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
            data=sql.encode()).decode().splitlines() if line.startswith('{')]
    tables = {row['table']: row['count'] for row in rows if 'table' in row}
    metadata = {key: row[key] for row in rows for key in ('media', 'flyway') if key in row}
    if not tables or not metadata.get('flyway') or any(not row['success'] for row in metadata['flyway']):
        raise BackupError('Missing or unsuccessful schema history; empty/unrecognized database refused')
    return {'tables': tables, **metadata}


def verify_references(metadata, inventory):
    for asset in metadata['media']:
        if inventory.get(asset['path']) != asset['sha256']:
            raise BackupError('Referenced media missing or checksum mismatch')


class Source:
    def __init__(self, directory, project="noizycologne"):
        self.directory = directory.resolve()
        envfile = self.directory / '.env'
        if not envfile.is_file() or envfile.is_symlink() or envfile.stat().st_mode & 0o077:
            raise BackupError('A private regular persistent .env (0600) is required')
        tag = self.directory / '.previous-image-tag'
        self.release_sha = tag.read_text().strip() if tag.is_file() and not tag.is_symlink() else ''
        if not re.fullmatch(r'[0-9a-f]{40}', self.release_sha):
            raise BackupError('Recorded immutable application release SHA is required')
        marker = self.directory / '.active-release'
        self.release = Path(marker.read_text().strip()).resolve() if marker.exists() else self.directory
        if self.release != self.directory and not self.release.is_relative_to(self.directory / 'releases'):
            raise BackupError('Release manifest must stay within the persistent server directory')
        self.compose = ['docker', 'compose', '--env-file', str(envfile), '-f', str(self.release / 'compose.yaml'), '-p', project]
        self.config = json_command([*self.compose, 'config', '--format', 'json'])
        self.containers = {}
        for service in ('db', 'api', 'frontend'):
            ids = run([*self.compose, 'ps', '-aq', service]).decode().split()
            if len(ids) != 1:
                raise BackupError(f'Expected one existing {service} container')
            self.containers[service] = json_command(['docker', 'inspect', ids[0]])[0]
        if any(self.containers[name]['State']['Running'] or self.containers[name]['State'].get('Restarting')
               or self.containers[name]['HostConfig'].get('AutoRemove') for name in ('api', 'frontend')):
            raise BackupError('Maintenance required: API/frontend must be stopped, not paused/restarting')
        database = self.containers['db']
        if not database['State']['Running']:
            raise BackupError('Database is not running')
        self.database = database['Id']
        self.image = database['Image']
        media = self.config['volumes']['media_data']['name']
        mounts = [mount for mount in self.containers['api']['Mounts'] if mount['Destination'] == '/app/uploads']
        if len(mounts) != 1 or mounts[0].get('Name') != media or mounts[0]['Type'] != 'volume':
            raise BackupError('API media mount does not match the explicit Compose volume')
        self.media = media
        self.assert_quiescent()

    def assert_quiescent(self):
        ids = run(['docker', 'ps', '-q']).decode().split()
        for container in json_command(['docker', 'inspect', *ids]) if ids else []:
            if container['Id'] in (self.containers['api']['Id'], self.containers['frontend']['Id']):
                raise BackupError('Application restarted during backup')
            if any(mount.get('Name') == self.media and mount.get('RW') for mount in container['Mounts']):
                raise BackupError('A running container can write the media volume')

    def archive_media(self, target):
        with target.open('xb') as output:
            run(['docker', 'run', '--rm', '--pull', 'never', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
                 '--security-opt', 'no-new-privileges:true', '--memory', '256m', '--pids-limit', '64',
                 '--mount', f'type=volume,src={self.media},dst=/media,readonly', self.image,
                 'tar', '-C', '/media', '-czf', '-', '.'], output=output)


def offsite_copy(archive, directory):
    private_directory(directory)
    target = directory / archive.name
    if target.exists():
        raise BackupError('Offsite destination already exists')
    with tempfile.NamedTemporaryFile(dir=directory, prefix='.upload-', delete=False) as output:
        temp = Path(output.name)
    try:
        shutil.copyfile(archive, temp)
        if digest(temp) != digest(archive):
            raise BackupError('Offsite checksum mismatch')
        temp.replace(target)
    finally:
        temp.unlink(missing_ok=True)


def backup(args):
    if not re.fullmatch(r'[A-Fa-f0-9]{40,64}', args.recipient):
        raise BackupError('An explicit encryption key fingerprint is required')
    source = Source(args.server_dir, args.project)
    private_directory(args.output)
    name = 'noizycologne-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex
    archive = args.output / (name + '.tar.gpg')
    with tempfile.TemporaryDirectory(prefix='.backup-', dir=args.output) as directory:
        work = Path(directory)
        before = facts(source.database)
        with (work / 'database.dump').open('xb') as output:
            db_exec(source.database, 'exec pg_dump --format=custom -U "$POSTGRES_USER" -d "$POSTGRES_DB"', output=output)
        if (work / 'database.dump').stat().st_size < 128:
            raise BackupError('Empty database dump refused')
        source.archive_media(work / 'media.tar.gz')
        inventory = media_inventory(work / 'media.tar.gz')
        if not inventory and not args.allow_empty_media:
            raise BackupError('Empty media backup requires explicit --allow-empty-media')
        verify_references(before, inventory)
        source.assert_quiescent()
        # Detect metadata/DB changes too; a second media snapshot detects concurrent filesystem mutation.
        if facts(source.database) != before:
            raise BackupError('Database changed during maintenance backup')
        source.archive_media(work / 'media-check.tar.gz')
        if media_inventory(work / 'media-check.tar.gz') != inventory:
            raise BackupError('Media changed during maintenance backup')
        with tarfile.open(work / 'configuration.tar.gz', 'w:gz') as config:
            config.add(source.directory / '.env', arcname='persistent.env', recursive=False)
            for filename in ('compose.yaml', 'Caddyfile', 'compose.browser.yaml', 'seccomp_profile.json', 'deploy.sh'):
                path = source.release / filename
                if filename in ('compose.yaml', 'Caddyfile') and (not path.is_file() or path.is_symlink()):
                    raise BackupError('Required release configuration missing or unsafe')
                if path.is_file() and not path.is_symlink():
                    config.add(path, arcname=filename, recursive=False)
        manifest = {'version': 1, 'created_at': dt.datetime.now(dt.timezone.utc).isoformat(),
                    'image': source.image, 'release_sha': source.release_sha, 'facts': before, 'media': inventory,
                    'payloads': {name: digest(work / name) for name in PAYLOADS}}
        (work / 'manifest.json').write_text(json.dumps(manifest, sort_keys=True))
        with tarfile.open(work / 'bundle.tar', 'w') as bundle:
            for filename in (*PAYLOADS, 'manifest.json'):
                bundle.add(work / filename, arcname=filename, recursive=False)
        run(['gpg', '--batch', '--no-auto-key-retrieve', '--no-auto-key-locate', '--trust-model', 'always',
             '--recipient', args.recipient, '--output', str(work / 'sealed.gpg'), '--encrypt', str(work / 'bundle.tar')])
        source.assert_quiescent()
        os.chmod(work / 'sealed.gpg', 0o600)
        (work / 'sealed.gpg').replace(archive)
    state = {'file': archive.name, 'sha256': digest(archive), 'bytes': archive.stat().st_size,
             'media_count': len(inventory), 'offsite': 'NOT_CONFIGURED'}
    archive.with_suffix('.json').write_text(json.dumps(state))
    if args.offsite_dir:
        offsite_copy(archive, args.offsite_dir)
        state['offsite'] = 'VERIFIED_COPY'
        archive.with_suffix('.json').write_text(json.dumps(state))
    print(json.dumps(state))


@contextlib.contextmanager
def decrypted(archive):
    with tempfile.TemporaryDirectory(prefix='noizy-restore-') as directory:
        work = Path(directory)
        with (work / 'bundle.tar').open('xb') as output:
            run(['gpg', '--batch', '--no-auto-key-retrieve', '--no-auto-key-locate', '--decrypt', str(archive)], output=output)
        if (work / 'bundle.tar').stat().st_size > MAX_ARCHIVE_BYTES:
            raise BackupError('Restore archive exceeds safety budget')
        with tarfile.open(work / 'bundle.tar', 'r:') as bundle:
            members = bundle.getmembers()
            if {item.name for item in members} != {*PAYLOADS, 'manifest.json'} or len(members) != 4:
                raise BackupError('Unexpected restore payload')
            for item in members:
                if not item.isfile() or item.size > MAX_ARCHIVE_BYTES:
                    raise BackupError('Unsafe restore payload')
                with bundle.extractfile(item) as source, (work / item.name).open('xb') as target:
                    shutil.copyfileobj(source, target)
        manifest = json.loads((work / 'manifest.json').read_text())
        if manifest['version'] != 1 or not re.fullmatch(r'sha256:[0-9a-f]{64}', manifest['image']):
            raise BackupError('Unrecognized restore manifest')
        if any(digest(work / name) != manifest['payloads'][name] for name in PAYLOADS):
            raise BackupError('Backup checksum mismatch')
        if media_inventory(work / 'media.tar.gz') != manifest['media']:
            raise BackupError('Media checksum mismatch')
        verify_references(manifest['facts'], manifest['media'])
        with tarfile.open(work / 'configuration.tar.gz', 'r:gz') as config:
            entries = config.getmembers()
            if not {'persistent.env', 'compose.yaml', 'Caddyfile'}.issubset({item.name for item in entries}):
                raise BackupError('Recovery configuration missing')
            if any(not item.isfile() or '/' in item.name or item.size > 4 * 1024**2 for item in entries):
                raise BackupError('Unsafe recovery configuration')
        yield work, manifest


def restore_test(args):
    # Names cannot be supplied by the caller or by the archive. No connection settings are inherited.
    name = 'noizy-restore-' + uuid.uuid4().hex
    volumes = [name + '-db', name + '-media']
    created = []
    container_created = False
    with decrypted(args.archive) as (work, manifest):
        try:
            run(['docker', 'image', 'inspect', manifest['image']])  # Never pull an unreviewed image.
            for volume in volumes:
                if run(['docker', 'volume', 'ls', '-q', '--filter', 'name=^' + volume + '$']).strip():
                    raise BackupError('Restore target must not exist')
                run(['docker', 'volume', 'create', volume]); created.append(volume)
            environment = {**os.environ, 'POSTGRES_PASSWORD': secrets.token_urlsafe(32)}
            container_created = True
            run(['docker', 'run', '-d', '--pull', 'never', '--name', name, '--network', 'none',
                 '--memory', '512m', '--pids-limit', '128', '-e', 'POSTGRES_PASSWORD', '-e', 'POSTGRES_USER=noizy_restore',
                 '-e', 'POSTGRES_DB=noizy_restore', '--mount', f'type=volume,src={volumes[0]},dst=/var/lib/postgresql/data',
                 manifest['image']], environment=environment)
            container_created = True
            for _ in range(60):
                try:
                    db_exec(name, 'exec pg_isready -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"')
                    break
                except BackupError:
                    time.sleep(.5)
            else:
                raise BackupError('Isolated restore database did not become ready')
            with (work / 'database.dump').open('rb') as stream:
                # No --clean / DROP; target is a freshly generated empty database.
                run(['docker', 'exec', '-i', name, 'sh', '-ec',
                     'exec pg_restore --exit-on-error --no-owner --no-privileges -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], input_stream=stream)
            if facts(name) != manifest['facts']:
                raise BackupError('Restored row counts/schema/media metadata differ')
            with (work / 'media.tar.gz').open('rb') as stream:
                run(['docker', 'run', '--rm', '-i', '--pull', 'never', '--network', 'none', '--read-only',
                     '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--memory', '256m', '--pids-limit', '64',
                     '--mount', f'type=volume,src={volumes[1]},dst=/media', manifest['image'],
                     'tar', '-C', '/media', '-xzf', '-', '--no-same-owner', '--no-same-permissions'], input_stream=stream)
            with (work / 'restored-media.tar.gz').open('xb') as output:
                run(['docker', 'run', '--rm', '--pull', 'never', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
                     '--mount', f'type=volume,src={volumes[1]},dst=/media,readonly', manifest['image'], 'tar', '-C', '/media', '-czf', '-', '.'], output=output)
            if media_inventory(work / 'restored-media.tar.gz') != manifest['media']:
                raise BackupError('Restored media bytes differ')
            print(json.dumps({'restore': 'VERIFIED_FIXTURE_TARGET', 'tables': len(manifest['facts']['tables']),
                              'media_count': len(manifest['media']), 'source_sha256': digest(args.archive)}))
        finally:
            cleanup_failed = False
            commands = ([['docker', 'rm', '-f', name]] if container_created else []) + [['docker', 'volume', 'rm', volume] for volume in created]
            for command in commands:
                try:
                    run(command)
                except BackupError:
                    cleanup_failed = True
            if cleanup_failed:
                raise BackupError('Isolated restore resource cleanup failed; inspect noizy-restore resources')


def prune(args):
    # Only verified local generations, always retain at least the newest two.
    private_directory(args.output)
    generations = []
    for status in args.output.glob('noizycologne-*.tar.json'):
        archive = status.with_suffix('.gpg')
        if not status.is_symlink() and archive.is_file() and not archive.is_symlink():
            metadata = json.loads(status.read_text())
            if metadata.get('sha256') == digest(archive) and metadata.get('offsite') == 'VERIFIED_COPY':
                generations.append(archive)
    protected = set(sorted(generations, key=lambda path: path.stat().st_mtime, reverse=True)[:2])
    cutoff = time.time() - 14 * 86400
    removed = 0
    for archive in generations:
        if archive not in protected and archive.stat().st_mtime < cutoff:
            archive.unlink(); archive.with_suffix('.json').unlink(); removed += 1
    print(json.dumps({'pruned': removed, 'retention_days': 14, 'protected_generations': len(protected)}))


def main():
    os.umask(0o077)
    def interrupted(_number, _frame):
        raise BackupError('Interrupted; no complete new generation may be assumed')
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    default_server = Path(__file__).resolve().parent
    if default_server.parent.name == 'releases':
        default_server = default_server.parent.parent
    save = commands.add_parser('backup')
    save.add_argument('--server-dir', type=Path, default=Path(os.environ.get('NOIZY_SERVER_DIR', default_server)))
    save.add_argument('--project', default='noizycologne')
    save.add_argument('--output', type=Path, required=True)
    save.add_argument('--recipient', required=True)
    save.add_argument('--allow-empty-media', action='store_true')
    save.add_argument('--offsite-dir', type=Path)
    restore = commands.add_parser('restore-test')
    restore.add_argument('archive', type=Path)
    retention = commands.add_parser('prune')
    retention.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        {'backup': backup, 'restore-test': restore_test, 'prune': prune}[args.command](args)
    except (BackupError, ValueError, KeyError, OSError, tarfile.TarError) as error:
        # Detailed exception objects may contain user-controlled paths, keys or database data.
        print(json.dumps({'status': 'FAILED', 'reason': str(error) if isinstance(error, BackupError) else type(error).__name__}))
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
