"""Backup safety failures use disposable files and mocked process boundaries; no real data."""
import importlib.util
import io
import json
import os
from pathlib import Path
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
        args = SimpleNamespace(server_dir=self.root, project='fixture', output=self.root / 'backup',
                               recipient='a' * 40, allow_empty_media=False, offsite_dir=None)
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
        args = SimpleNamespace(server_dir=self.root, project='fixture', output=self.root / 'locked', recipient='a' * 40)
        with patch.object(backup, 'Source', return_value=self.fake_source()), patch.object(backup.tempfile, 'TemporaryDirectory', side_effect=PermissionError('fixture denied')):
            with self.assertRaises(PermissionError):
                backup.backup(args)
        self.assertFalse(list(args.output.glob('*.gpg')))

    def test_encrypted_offsite_copy_is_verified_and_does_not_overwrite(self):
        archive = self.root / 'fixture.tar.gpg'; archive.write_bytes(b'encrypted fixture')
        target = self.root / 'offsite'
        backup.offsite_copy(archive, target)
        self.assertEqual(backup.digest(archive), backup.digest(target / archive.name))
        with self.assertRaises(backup.BackupError):
            backup.offsite_copy(archive, target)


if __name__ == '__main__':
    unittest.main()
