"""Isolated backup-wrapper tests: fake docker/python/hook, no services or data touched."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
WRAPPER = ROOT / 'deploy/server/backup-wrapper.sh'

DOCKER_STUB = '''#!/bin/sh
printf '%s\\n' "$*" >> "$FAKE_DOCKER_LOG"
case "$*" in
  *" ps -aq api"*) printf '%s\\n' "${FAKE_API_ID:-api-container}"; exit 0 ;;
  *" ps -aq frontend"*) printf '%s\\n' "${FAKE_FRONTEND_ID:-frontend-container}"; exit 0 ;;
  *"inspect --format"*)
    case "$*" in
      *api-container*) printf '%s\\n' "${FAKE_API_RUNNING:-true}" ;;
      *) printf '%s\\n' "${FAKE_FRONTEND_RUNNING:-true}" ;;
    esac
    exit 0 ;;
  *" stop "*) exit "${FAKE_STOP_STATUS:-0}" ;;
  *" start "*) exit "${FAKE_START_STATUS:-0}" ;;
esac
exit 0
'''

PYTHON_STUB = '''#!/bin/sh
printf '%s\\n' "$*" >> "$FAKE_PY_LOG"
case "$2" in
  backup)
    if [ -n "${FAKE_BACKUP_JSON:-}" ]; then printf '%s\\n' "$FAKE_BACKUP_JSON"
    else printf '%s\\n' '{"file":"fixture.tar.gpg","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","offsite":"VERIFIED_COPY"}'; fi
    exit "${FAKE_BACKUP_STATUS:-0}" ;;
  prune)
    printf '%s\\n' '{"pruned":0}'
    exit "${FAKE_PRUNE_STATUS:-0}" ;;
esac
exit 0
'''

HOOK_STUB = '''#!/bin/sh
cat > "$FAKE_HOOK_LOG"
exit "${FAKE_HOOK_STATUS:-0}"
'''


class WrapperTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir(mode=0o700)
        for name, content in (('docker', DOCKER_STUB), ('python3', PYTHON_STUB), ('alarm-hook', HOOK_STUB)):
            path = self.bin / name
            path.write_text(content)
            path.chmod(0o700)
        self.server = self.root / 'server'
        self.server.mkdir(mode=0o700)
        envfile = self.server / '.env'
        envfile.write_text('POSTGRES_PASSWORD=fixture-secret\n')
        envfile.chmod(0o600)
        (self.server / 'compose.yaml').write_text('fixture compose')
        self.output = self.root / 'backups'
        self.output.mkdir(mode=0o700)
        self.offsite = self.root / 'offsite'
        self.offsite.mkdir(mode=0o700)
        self.docker_log = self.root / 'docker.log'
        self.python_log = self.root / 'python.log'
        self.hook_log = self.root / 'hook.log'
        self.env = dict(os.environ)
        self.env.update(PATH=str(self.bin) + os.pathsep + self.env['PATH'],
                        FAKE_DOCKER_LOG=str(self.docker_log), FAKE_PY_LOG=str(self.python_log),
                        FAKE_HOOK_LOG=str(self.hook_log),
                        NOIZY_SERVER_DIR=str(self.server), NOIZY_BACKUP_OUTPUT=str(self.output),
                        NOIZY_OFFSITE_DIR=str(self.offsite), NOIZY_BACKUP_RECIPIENT='a' * 40,
                        NOIZY_BACKUP_ALARM_HOOK=str(self.bin / 'alarm-hook'))

    def run_wrapper(self, updates=None):
        environment = dict(self.env)
        environment.update(updates or {})
        return subprocess.run(['bash', str(WRAPPER)], env=environment, capture_output=True, check=False)

    def lines(self, path):
        return path.read_text().splitlines() if path.exists() else []

    def event(self):
        return json.loads(self.hook_log.read_text().strip())

    def test_missing_environment_fails_closed_before_docker(self):
        environment = dict(self.env)
        for name in ('NOIZY_SERVER_DIR', 'NOIZY_BACKUP_OUTPUT', 'NOIZY_OFFSITE_DIR', 'NOIZY_BACKUP_RECIPIENT'):
            environment.pop(name, None)
        result = subprocess.run(['bash', str(WRAPPER)], env=environment, capture_output=True, check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.lines(self.docker_log), [])
        self.assertEqual(self.lines(self.python_log), [])
        self.assertEqual(self.event()['reason_code'], 'MISSING_CONFIG')

    def test_unsafe_env_file_fails_before_any_stop(self):
        (self.server / '.env').chmod(0o644)
        self.assertNotEqual(self.run_wrapper().returncode, 0)
        self.assertEqual(self.lines(self.docker_log), [])
        self.assertEqual(self.event()['reason_code'], 'MISSING_CONFIG')

    def test_stop_failure_restarts_and_skips_backup(self):
        result = self.run_wrapper({'FAKE_STOP_STATUS': '1'})
        self.assertNotEqual(result.returncode, 0)
        docker_calls = self.lines(self.docker_log)
        self.assertTrue(any(' stop api frontend' in call for call in docker_calls))
        self.assertTrue(any(' start api frontend' in call for call in docker_calls))
        self.assertEqual(self.lines(self.python_log), [])
        self.assertEqual(self.event()['reason_code'], 'STOP_FAILED')

    def test_backup_failure_restarts_services_and_alerts(self):
        result = self.run_wrapper({'FAKE_BACKUP_STATUS': '3'})
        self.assertEqual(result.returncode, 3)
        self.assertTrue(any(' start api frontend' in call for call in self.lines(self.docker_log)))
        self.assertTrue(any('backup.py backup ' in call for call in self.lines(self.python_log)))
        event = self.event()
        self.assertEqual(event['event'], 'BACKUP_FAILED')
        self.assertEqual(event['reason_code'], 'BACKUP_FAILED')

    def test_success_restarts_prunes_and_reports_sha256(self):
        sha = 'b' * 64
        generation = 'noizycologne-fixture.tar.gpg'
        result = self.run_wrapper({'FAKE_BACKUP_JSON': json.dumps({'file': generation, 'sha256': sha,
                                                                   'offsite': 'VERIFIED_COPY'})})
        self.assertEqual(result.returncode, 0)
        self.assertTrue(any(' start api frontend' in call for call in self.lines(self.docker_log)))
        self.assertTrue(any('backup.py prune ' in call for call in self.lines(self.python_log)))
        event = self.event()
        self.assertEqual(event['event'], 'BACKUP_OK')
        self.assertEqual(event['sha256'], sha)
        self.assertEqual(event['generation'], generation)
        self.assertEqual(event['offsite'], 'VERIFIED_COPY')
        for log in (self.docker_log, self.python_log, self.hook_log):
            self.assertNotIn('fixture-secret', log.read_text())

    def test_services_already_stopped_are_not_restarted(self):
        result = self.run_wrapper({'FAKE_API_RUNNING': 'false', 'FAKE_FRONTEND_RUNNING': 'false'})
        self.assertEqual(result.returncode, 0)
        docker_calls = self.lines(self.docker_log)
        self.assertFalse(any(' stop ' in call or ' start ' in call for call in docker_calls))
        self.assertTrue(any('backup.py backup ' in call for call in self.lines(self.python_log)))

    def test_offsite_and_fingerprint_are_passed_without_secrets(self):
        self.assertEqual(self.run_wrapper().returncode, 0)
        backup_call = next(call for call in self.lines(self.python_log) if 'backup.py backup ' in call)
        self.assertIn('--offsite-dir ' + str(self.offsite), backup_call)
        self.assertIn('--recipient ' + 'a' * 40, backup_call)
        self.assertNotIn('POSTGRES_PASSWORD', backup_call)


if __name__ == '__main__':
    unittest.main()
