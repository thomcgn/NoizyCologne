"""Backup safety failures use disposable files and mocked process boundaries; no real data."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('backup_tool', ROOT / 'deploy/server/backup.py')
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


class BackupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def archive(self, kind=tarfile.REGTYPE, name='flyer.png'):
        path = self.root / 'media.tar.gz'
        with tarfile.open(path, 'w:gz') as target:
            member = tarfile.TarInfo(name)
            member.type = kind
            member.linkname = '/etc/passwd'
            member.size = 3 if kind == tarfile.REGTYPE else 0
            target.addfile(member, io.BytesIO(b'png') if member.size else None)
        return path

    def test_missing_env_fails_before_docker(self):
        with patch.object(backup, 'run') as command:
            with self.assertRaises(backup.BackupError):
                backup.Source(self.root)
            command.assert_not_called()

    def test_running_or_restarting_application_refuses_backup(self):
        (self.root / '.env').write_text('fixture')
        (self.root / '.env').chmod(0o600)
        (self.root / '.previous-image-tag').write_text('a' * 40)
        for state in ({'Running': True}, {'Running': False, 'Restarting': True}):
            database = {'State': {'Running': True}, 'Id': 'db', 'Image': 'fixture'}
            api = {'State': state, 'HostConfig': {}}
            frontend = {'State': {'Running': False}, 'HostConfig': {}}
            config = {'volumes': {'media_data': {'name': 'isolated'}}}
            with patch.object(backup, 'run', return_value=b'fixture'), patch.object(backup, 'json_command', side_effect=[config, [database], [api], [frontend]]):
                with self.assertRaisesRegex(backup.BackupError, 'Maintenance required'):
                    backup.Source(self.root, 'isolated-test')

    def test_private_directory_and_symlink_guards(self):
        (self.root / 'public').mkdir(mode=0o755)
        with self.assertRaises(backup.BackupError):
            backup.private_directory(self.root / 'public')
        (self.root / 'link').symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(backup.BackupError):
            backup.private_directory(self.root / 'link')

    def test_media_hash_and_unsafe_archive_entries(self):
        self.assertEqual(len(backup.media_inventory(self.archive())), 1)
        for kind, name in [(tarfile.SYMTYPE, 'link'), (tarfile.REGTYPE, '../outside'), (tarfile.REGTYPE, '/absolute')]:
            with self.subTest(kind=kind, name=name), self.assertRaises(backup.BackupError):
                backup.media_inventory(self.archive(kind, name))

    def test_referenced_missing_or_changed_media_fails(self):
        for inventory in ({}, {'flyer.png': 'different'}):
            with self.assertRaises(backup.BackupError):
                backup.verify_references({'media': [{'path': 'flyer.png', 'sha256': 'expected'}]}, inventory)

    def test_command_failure_does_not_disclose_stderr(self):
        import subprocess
        with patch.object(backup.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, ['pg_dump'], stderr=b'private secret')):
            with self.assertRaises(backup.BackupError) as raised:
                backup.run(['pg_dump'])
            self.assertNotIn('private secret', str(raised.exception))

    def fake_source(self):
        from unittest.mock import Mock
        source = Mock(spec=backup.Source)
        for name in ('.env', 'compose.yaml', 'Caddyfile'):
            (self.root / name).write_text('fixture')
        source.directory = self.root
        source.release = self.root
        source.database = 'isolated'
        source.image = 'sha256:' + 'a' * 64
        source.release_sha = 'a' * 40
        return source

    def test_dump_media_and_encryption_failures_never_publish_generation(self):
        from unittest.mock import Mock
        metadata = {'tables': {'fixture': 1}, 'media': [], 'flyway': [{'success': True}]}
        offsite = self.root / 'offsite'
        offsite.mkdir(mode=0o700)
        args = SimpleNamespace(server_dir=self.root, project='fixture', output=self.root / 'backup',
                               recipient='a' * 40, allow_empty_media=False, offsite_dir=offsite)
        for failure in ('dump', 'empty-dump', 'media', 'empty-media', 'changed-db', 'encrypt'):
            with self.subTest(failure=failure):
                source = self.fake_source()
                def dump(_container, _command, *, output):
                    if failure == 'dump':
                        raise backup.BackupError('pg_dump failed')
                    output.write(b'' if failure == 'empty-dump' else b'PGDMP' * 100)
                def media(target):
                    if failure == 'media':
                        raise backup.BackupError('media archive failed')
                    with tarfile.open(target, 'w:gz') as stream:
                        if failure != 'empty-media':
                            item = tarfile.TarInfo('fixture.png'); item.size = 3
                            stream.addfile(item, io.BytesIO(b'png'))
                source.archive_media.side_effect = media
                read_facts = Mock(return_value=metadata)
                if failure == 'changed-db':
                    read_facts.side_effect = [metadata, {**metadata, 'tables': {'fixture': 2}}]
                with patch.object(backup, 'Source', return_value=source), patch.object(backup, 'facts', read_facts), \
                     patch.object(backup, 'db_exec', side_effect=dump), patch.object(backup, 'run', side_effect=backup.BackupError('encryption failed')):
                    with self.assertRaises(backup.BackupError):
                        backup.backup(args)
                self.assertFalse(list(args.output.glob('*.gpg')))
                self.assertFalse(list(args.output.glob('.backup-*')))

    def test_retention_protects_two_verified_generations_and_local_only_backups(self):
        for index in range(5):
            archive = self.root / f'noizycologne-{index}.tar.gpg'
            archive.write_bytes(b'fixture')
            os.utime(archive, (time.time() - (30 + index) * 86400,) * 2)
            archive.with_suffix('.json').write_text(json.dumps({'sha256': backup.digest(archive),
                'offsite': 'NOT_CONFIGURED' if index == 4 else 'VERIFIED_COPY'}))
        backup.prune(SimpleNamespace(output=self.root))
        self.assertEqual(len(list(self.root.glob('*.gpg'))), 3)
        self.assertTrue((self.root / 'noizycologne-4.tar.gpg').exists())

    def test_unwritable_output_aborts_without_generation(self):
        offsite = self.root / 'offsite'
        offsite.mkdir(mode=0o700)
        args = SimpleNamespace(server_dir=self.root, project='fixture', output=self.root / 'locked',
                               recipient='a' * 40, offsite_dir=offsite)
        with patch.object(backup, 'Source', return_value=self.fake_source()), patch.object(backup.tempfile, 'TemporaryDirectory', side_effect=PermissionError('fixture denied')):
            with self.assertRaises(PermissionError):
                backup.backup(args)
        self.assertFalse(list(args.output.glob('*.gpg')))

    def test_encrypted_offsite_copy_is_verified_and_does_not_overwrite(self):
        archive = self.root / 'fixture.tar.gpg'; archive.write_bytes(b'encrypted fixture')
        target = self.root / 'offsite'
        target.mkdir(mode=0o700)
        backup.offsite_copy(archive, target)
        self.assertEqual(backup.digest(archive), backup.digest(target / archive.name))
        with self.assertRaises(backup.BackupError):
            backup.offsite_copy(archive, target)

    def prepared_offsite(self):
        offsite = self.root / 'offsite'
        offsite.mkdir(mode=0o700)
        return offsite

    def backup_args(self, offsite):
        return SimpleNamespace(server_dir=self.root, project='fixture', output=self.root / 'backups',
                               recipient='a' * 40, allow_empty_media=False, offsite_dir=offsite)

    def successful_environment(self, *, offsite_failure=False):
        """Patch process boundaries for a sealed, never decrypted fixture generation."""
        metadata = {'tables': {'fixture': 1}, 'media': [], 'flyway': [{'success': True}]}
        source = self.fake_source()

        def dump(_container, _command, *, output):
            output.write(b'PGDMP' * 100)

        def media(target):
            with tarfile.open(target, 'w:gz') as stream:
                item = tarfile.TarInfo('fixture.png'); item.size = 3
                stream.addfile(item, io.BytesIO(b'png'))

        def run(args, **_kwargs):
            if args[0] == 'gpg':
                Path(args[args.index('--output') + 1]).write_bytes(b'sealed fixture')
            return b''

        source.archive_media.side_effect = media
        patches = [patch.object(backup, 'Source', return_value=source),
                   patch.object(backup, 'facts', return_value=metadata),
                   patch.object(backup, 'db_exec', side_effect=dump),
                   patch.object(backup, 'run', side_effect=run)]
        if offsite_failure:
            patches.append(patch.object(backup, 'offsite_copy', side_effect=backup.BackupError('offsite copy failed')))
        for item in patches:
            item.start()
            self.addCleanup(item.stop)

    def test_cli_requires_offsite_dir(self):
        result = subprocess.run([sys.executable, str(ROOT / 'deploy/server/backup.py'), 'backup',
                                 '--server-dir', str(self.root), '--output', str(self.root / 'out'),
                                 '--recipient', 'a' * 40], capture_output=True, check=False)
        self.assertEqual(result.returncode, 2)
        self.assertIn(b'--offsite-dir', result.stderr)
        self.assertFalse((self.root / 'out').exists())

    def test_entry_point_without_offsite_refuses_before_docker(self):
        args = SimpleNamespace(server_dir=self.root, project='fixture', output=self.root / 'out',
                               recipient='a' * 40, offsite_dir=None)
        with patch.object(backup, 'Source') as source:
            with self.assertRaisesRegex(backup.BackupError, 'offsite'):
                backup.backup(args)
            source.assert_not_called()

    def test_offsite_target_must_exist_and_be_private(self):
        archive = self.root / 'fixture.tar.gpg'
        archive.write_bytes(b'sealed fixture')
        missing = self.root / 'absent'
        with self.assertRaises(backup.BackupError):
            backup.offsite_copy(archive, missing)
        self.assertFalse(missing.exists())
        loose = self.root / 'loose'
        loose.mkdir(mode=0o755)
        with self.assertRaises(backup.BackupError):
            backup.offsite_copy(archive, loose)

    def test_generation_manifest_records_verified_offsite(self):
        for name in backup.PAYLOADS:
            (self.root / name).write_bytes(b'fixture payload')
        manifest = backup.generation_manifest(self.fake_source(), self.root, {}, {})
        self.assertEqual(manifest['offsite'], 'VERIFIED_COPY')
        self.assertEqual(set(manifest['payloads']), set(backup.PAYLOADS))
        self.assertIn('created_at', manifest)

    def test_verified_generation_is_published_with_offsite_state(self):
        offsite = self.prepared_offsite()
        args = self.backup_args(offsite)
        self.successful_environment()
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            backup.backup(args)
        state = json.loads(output.getvalue().strip())
        archive = args.output / state['file']
        self.assertEqual(state['offsite'], 'VERIFIED_COPY')
        self.assertEqual(state['sha256'], backup.digest(archive))
        self.assertEqual((offsite / archive.name).read_bytes(), archive.read_bytes())
        status = json.loads(archive.with_suffix('.json').read_text())
        self.assertEqual(status['offsite'], 'VERIFIED_COPY')
        self.assertIn('created_at', status)

    def test_offsite_copy_failure_publishes_no_generation(self):
        offsite = self.prepared_offsite()
        args = self.backup_args(offsite)
        self.successful_environment(offsite_failure=True)
        with self.assertRaises(backup.BackupError):
            backup.backup(args)
        self.assertFalse(list(args.output.glob('*.gpg')))
        self.assertFalse(list(args.output.glob('*.json')))

    def test_local_only_generations_have_bounded_retention(self):
        for days in (10, 20, 30, 40, 1):
            archive = self.root / f'noizycologne-local-{days}.tar.gpg'
            archive.write_bytes(b'fixture')
            os.utime(archive, (time.time() - days * 86400,) * 2)
            archive.with_suffix('.json').write_text(json.dumps({'sha256': backup.digest(archive),
                                                                'offsite': 'NOT_CONFIGURED'}))
        backup.prune(SimpleNamespace(output=self.root, local_retention_days=3))
        self.assertEqual(sorted(path.name for path in self.root.glob('*.gpg')),
                         ['noizycologne-local-1.tar.gpg'])
        with self.assertRaises(backup.BackupError):
            backup.prune(SimpleNamespace(output=self.root, local_retention_days=0))


if __name__ == '__main__':
    unittest.main()
