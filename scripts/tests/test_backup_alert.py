"""Read-only alarm-check tests; fixture files only, no Docker or live state."""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
ALERT = ROOT / 'deploy/server/backup-alert.py'
HOOK_STUB = '#!/bin/sh\ncat > "$FAKE_HOOK_LOG"\nexit "${FAKE_HOOK_STATUS:-0}"\n'


class AlertTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = self.root / 'backups'
        self.output.mkdir(mode=0o700)
        self.restore = self.root / 'restore-state.json'
        self.hook_log = self.root / 'hook.log'
        hook = self.root / 'alarm-hook'
        hook.write_text(HOOK_STUB)
        hook.chmod(0o700)
        self.env = dict(os.environ, FAKE_HOOK_LOG=str(self.hook_log))
        self.now = dt.datetime.now(dt.timezone.utc)

    def generation(self, *, age_hours=1, offsite='VERIFIED_COPY', sha256=None):
        archive = self.output / 'noizycologne-fixture.tar.gpg'
        archive.write_bytes(b'encrypted fixture')
        digest = sha256 or hashlib.sha256(archive.read_bytes()).hexdigest()
        created = self.now - dt.timedelta(hours=age_hours)
        status = {'file': archive.name, 'sha256': digest, 'offsite': offsite,
                  'created_at': created.isoformat()}
        archive.with_suffix('.json').write_text(json.dumps(status))
        return archive, status

    def restore_state(self, *, age_days=1):
        tested = self.now - dt.timedelta(days=age_days)
        self.restore.write_text(json.dumps({'tested_at': tested.isoformat(),
                                            'result': 'VERIFIED_FIXTURE_TARGET'}))

    def check(self, *, extra=None):
        args = [sys.executable, str(ALERT), '--output', str(self.output),
                '--restore-state', str(self.restore), '--hook', str(self.root / 'alarm-hook')]
        args.extend(extra or [])
        return subprocess.run(args, env=self.env, capture_output=True, check=False)

    def event(self):
        return json.loads(self.hook_log.read_text().strip())

    def codes(self, result):
        return [item['code'] for item in json.loads(result.stdout)['events']]

    def test_fresh_generation_and_restore_are_silent(self):
        self.generation()
        self.restore_state()
        result = self.check()
        self.assertEqual(result.returncode, 0)
        self.assertEqual(self.codes(result), [])
        self.assertEqual(self.event()['event'], 'OK')

    def test_stale_backup_raises_alarm(self):
        self.generation(age_hours=40)
        self.restore_state()
        result = self.check()
        self.assertEqual(result.returncode, 1)
        self.assertIn('BACKUP_STALE', self.codes(result))
        self.assertGreater(json.loads(result.stdout)['backup_age_hours'], 36)

    def test_sha256_and_offsite_alarms(self):
        self.generation(sha256='0' * 64, offsite='NOT_CONFIGURED')
        self.restore_state()
        result = self.check()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.codes(result), ['SHA256_MISMATCH', 'OFFSITE_NOT_VERIFIED'])
        self.assertEqual(self.event()['event'], 'SHA256_MISMATCH')

    def test_stale_and_missing_restore_state_raise_alarm(self):
        self.generation()
        self.restore_state(age_days=40)
        result = self.check()
        self.assertIn('RESTORE_STALE', self.codes(result))
        self.restore.unlink()
        result = self.check()
        self.assertIn('RESTORE_STALE', self.codes(result))

    def test_omitted_restore_state_is_reported_without_traceback(self):
        self.generation()
        args = [sys.executable, str(ALERT), '--output', str(self.output)]
        result = subprocess.run(args, env=self.env, capture_output=True, check=False)
        self.assertEqual(result.returncode, 1)
        self.assertIn('RESTORE_STALE', self.codes(result))
        self.assertNotIn(b'Traceback', result.stderr)

    def test_missing_generation_raises_alarm(self):
        self.restore_state()
        result = self.check()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.codes(result), ['NO_BACKUP'])

    def test_threshold_parameters_are_positive(self):
        result = self.check(extra=['--max-backup-age-hours', '0'])
        self.assertEqual(result.returncode, 2)
        self.assertIn(b'positive', result.stderr)

    def test_hook_delivery_failure_fails_closed_without_disclosing_content(self):
        self.generation()
        self.restore_state()
        result = self.check(extra=['--hook', str(self.root / 'missing-hook')])
        self.assertEqual(result.returncode, 1)
        self.assertIn(b'not executable', result.stderr)
        self.assertNotIn(b'encrypted fixture', result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
