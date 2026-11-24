#!/usr/bin/env python3
"""Real encrypted fixture backup/restore; fresh Docker project, no inherited service settings."""
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]


def command(args, *, env=None, data=None):
    result = subprocess.run(args, input=data, capture_output=True, env=env, timeout=180, check=False)
    if result.returncode:
        raise RuntimeError(f'Fixture command failed: {args[0]}; output withheld')
    return result.stdout


def main():
    os.umask(0o077)
    project = 'noizy-backup-test-' + uuid.uuid4().hex
    with tempfile.TemporaryDirectory(prefix='noizy-backup-fixture-') as directory:
        work = Path(directory)
        (work / 'keys').mkdir(mode=0o700)
        (work / 'offsite').mkdir(mode=0o700)
        env = {key: value for key, value in os.environ.items() if not key.startswith(('POSTGRES_', 'DB_', 'SPRING_', 'COMPOSE_', 'TEAM_', 'NOIZY_', 'MEDIA_'))}
        env['GNUPGHOME'] = str(work / 'keys')
        (work / '.env').write_text('POSTGRES_PASSWORD=' + secrets.token_urlsafe(32) + '\nTEAM_USER=fixture\nTEAM_SESSION_SECRET=isolated-fixture\n')
        (work / '.previous-image-tag').write_text('a' * 40)
        (work / 'Caddyfile').write_text(':80 {\nrespond "fixture"\n}\n')
        (work / 'compose.yaml').write_text('''services:
  db:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: fixture
      POSTGRES_USER: fixture
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?required}
    volumes: [database:/var/lib/postgresql/data]
    network_mode: none
    mem_limit: 512m
    pids_limit: 128
  api:
    image: postgres:17-alpine
    command: ["true"]
    volumes: [media_data:/app/uploads]
    network_mode: none
  frontend:
    image: postgres:17-alpine
    command: ["true"]
    network_mode: none
volumes:
  database: {}
  media_data: {}
''')
        compose = ['docker', 'compose', '--env-file', str(work / '.env'), '-f', str(work / 'compose.yaml'), '-p', project]
        created = False
        try:
            command(['gpg', '--batch', '--pinentry-mode', 'loopback', '--passphrase', '', '--quick-generate-key',
                     'Disposable Backup Fixture <backup@example.invalid>', 'rsa2048', 'encr', '1d'], env=env)
            listing = command(['gpg', '--batch', '--with-colons', '--list-keys'], env=env).decode()
            recipient = next(line.split(':')[9] for line in listing.splitlines() if line.startswith('fpr:'))
            created = True
            command([*compose, 'create'], env=env)
            command([*compose, 'start', 'db'], env=env)
            database = command([*compose, 'ps', '-q', 'db'], env=env).decode().strip()
            for _ in range(60):
                ready = subprocess.run(['docker', 'exec', database, 'pg_isready', '-h', '127.0.0.1', '-U', 'fixture'], capture_output=True, check=False)
                if ready.returncode == 0:
                    break
                time.sleep(.5)
            else:
                raise RuntimeError('Fixture database not ready')
            image = command(['docker', 'inspect', '--format', '{{.Image}}', database], env=env).decode().strip()
            config = json.loads(command([*compose, 'config', '--format', 'json'], env=env))
            media = config['volumes']['media_data']['name']
            content = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a6f8AAAAASUVORK5CYII=')
            checksum = hashlib.sha256(content).hexdigest()
            command(['docker', 'run', '--rm', '-i', '--network', 'none', '--mount', f'type=volume,src={media},dst=/media',
                     image, 'sh', '-ec', 'cat > /media/fixture.png'], env=env, data=content)
            sql = f"""CREATE TABLE flyway_schema_history(installed_rank int,version text,checksum int,success bool);
INSERT INTO flyway_schema_history VALUES(1,'fixture',123,true);
CREATE TABLE media_assets(storage_path text,checksum_sha256 text);
INSERT INTO media_assets VALUES('fixture.png','{checksum}');
CREATE TABLE fixture_events(id int,title text);
INSERT INTO fixture_events VALUES(1,'Retained fixture event');
"""
            command(['docker', 'exec', '-i', database, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'fixture', '-d', 'fixture'], env=env, data=sql.encode())
            tool = ['python3', str(ROOT / 'deploy/server/backup.py')]
            saved = json.loads(command([*tool, 'backup', '--server-dir', str(work), '--project', project, '--output', str(work / 'backups'),
                                       '--recipient', recipient, '--offsite-dir', str(work / 'offsite')], env=env))
            archive = work / 'backups' / saved['file']
            if (work / 'offsite' / archive.name).read_bytes() != archive.read_bytes():
                raise RuntimeError('Offsite fixture bytes differ')
            restored = json.loads(command([*tool, 'restore-test', str(archive)], env=env))
            if restored['media_count'] != 1 or restored['tables'] != 3:
                raise RuntimeError('Restored fixture metadata differs')
            print('PASS: encrypted PostgreSQL + media + recovery configuration fixture; verified offsite copy and isolated restore')
        finally:
            errors = []
            if created:
                for args in ([*compose, 'rm', '-sf'], ['docker', 'network', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + project]):
                    try:
                        command(args, env=env)
                    except RuntimeError as error:
                        errors.append(error)
                volume_ids = command(['docker', 'volume', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + project], env=env).decode().split()
                for volume in volume_ids:
                    try:
                        command(['docker', 'volume', 'rm', volume], env=env)
                    except RuntimeError as error:
                        errors.append(error)
            command(['gpgconf', '--kill', 'gpg-agent'], env=env)
            if errors:
                raise RuntimeError('Fixture cleanup failed')


if __name__ == '__main__':
    main()
