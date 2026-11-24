"""Exercise the real rollout/rollback shell in an isolated fake Docker host."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
SHA='a'*40
OLD='b'*40

class BrowserDeploymentTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.server=self.root/'persistent'
        self.server.mkdir()
        self.release=self.root/SHA
        self.release.mkdir()
        for name in ('deploy.sh','compose.yaml','compose.browser.yaml','Caddyfile'):
            shutil.copy(ROOT/'deploy/server'/name,self.release/name)
        shutil.copy(ROOT/'browser-worker/seccomp_profile.json',self.release/'seccomp_profile.json')
        shutil.copy(ROOT/'deploy/server/compose.yaml',self.server/'compose.yaml')
        (self.server/'.previous-image-tag').write_text(OLD)
        self.original='TEAM_USER=keep-user\nTEAM_SESSION_SECRET=keep-session\nFACTORY_RESET_SECRET='+'f'*64+'\nDISCOVERY_BROWSER_ENABLED=true\n'
        (self.server/'.env').write_text(self.original)
        self.bin=self.root/'bin'
        self.bin.mkdir()
        self.log=self.root/'calls'
        self.log.touch()
        (self.bin/'docker').write_text('''#!/usr/bin/env python3
import json,os,sys
args=sys.argv[1:]
with open(os.environ['FAKE_LOG'],'a') as out:out.write(json.dumps({'args':args,'tag':os.environ.get('IMAGE_TAG'),'browser':os.environ.get('DISCOVERY_BROWSER_ENABLED')})+'\\n')
if 'volume' in args and 'inspect' in args and os.environ.get('MISSING_MEDIA_VOLUME'):sys.exit(1)
if 'pull' in args and os.environ.get('FAIL_PULL'):sys.exit(1)
if 'run' in args and 'browser-worker' in args and os.environ.get('FAIL_SANDBOX'):sys.exit(1)
if 'up' in args and os.environ.get('IMAGE_TAG')=='a'*40 and os.environ.get('FAIL_UP'):sys.exit(1)
''')
        (self.bin/'awk').write_text('''#!/bin/sh
case "$*" in *'/proc/meminfo'*) echo "${TEST_RAM_KIB:-2097152}";; *) exec /usr/bin/awk "$@";; esac
''')
        for executable in self.bin.iterdir():executable.chmod(0o755)
        self.env={**os.environ,'PATH':str(self.bin)+os.pathsep+os.environ['PATH'],'NOIZY_SERVER_DIR':str(self.server),'FAKE_LOG':str(self.log)}
        for key in ('BROWSER_WORKER_TOKEN','BROWSER_SECCOMP_PROFILE','DISCOVERY_BROWSER_ENABLED','FACTORY_RESET_SECRET','API_TRUST_SECRET','API_TRUST_PREVIOUS_SECRET','AUTH_SERVICE_SECRET'):
            self.env.pop(key,None)

    def run_deploy(self,argument=SHA,**environment):
        return subprocess.run(['bash',str(self.release/'deploy.sh'),argument],env={**self.env,**environment},text=True,capture_output=True)

    def calls(self):return [json.loads(line) for line in self.log.read_text().splitlines()]

    def test_enabled_rollout_pulls_both_sha_images_waits_for_sandbox_and_preserves_credentials(self):
        result=self.run_deploy()
        self.assertEqual(result.returncode,0,result.stderr)
        calls=self.calls()
        pull=next(c for c in calls if 'pull' in c['args'])
        self.assertIn('browser-worker',pull['args']);self.assertIn('browser-gateway',pull['args']);self.assertEqual(pull['tag'],SHA)
        sandbox=next(i for i,c in enumerate(calls) if 'run' in c['args'] and 'browser-worker' in c['args'])
        rollout=next(i for i,c in enumerate(calls) if 'up' in c['args'])
        self.assertLess(sandbox,rollout)
        self.assertTrue((self.server/'.env').read_text().startswith(self.original))
        token=next(line for line in (self.server/'.env').read_text().splitlines() if line.startswith('BROWSER_WORKER_TOKEN='))
        self.assertNotIn(token.split('=',1)[1],result.stdout+result.stderr)
        self.assertEqual((self.server/'.active-browser-enabled').read_text().strip(),'true')
        self.assertEqual((self.server/'.previous-image-tag').read_text().strip(),SHA)

    def test_auth_service_key_is_private_stable_and_never_logged(self):
        first=self.run_deploy()
        self.assertEqual(first.returncode,0,first.stderr)
        before=(self.server/'.env').read_text()
        key=next(line.split('=',1)[1] for line in before.splitlines() if line.startswith('AUTH_SERVICE_SECRET='))
        self.assertRegex(key,r'^[0-9a-f]{64}$')
        self.assertEqual((self.server/'.env').stat().st_mode & 0o777,0o600)
        second=self.run_deploy()
        self.assertEqual(second.returncode,0,second.stderr)
        self.assertEqual((self.server/'.env').read_text(),before)
        self.assertNotIn(key,first.stdout+first.stderr+second.stdout+second.stderr+self.log.read_text())
        self.assertTrue(before.startswith(self.original))

    def test_invalid_existing_auth_key_fails_before_rollout(self):
        for value in ('','short','a'*32+' invalid'):
            with self.subTest(value_length=len(value)):
                self.log.write_text('')
                (self.server/'.env').write_text(self.original+'AUTH_SERVICE_SECRET='+value+'\n')
                result=self.run_deploy()
                self.assertNotEqual(result.returncode,0)
                self.assertIn('AUTH_CONFIG_FAILED',result.stderr)
                self.assertFalse(any('up' in call['args'] or 'pull' in call['args'] for call in self.calls()))

    def test_missing_images_or_failed_sandbox_leave_active_release_untouched(self):
        for failure in ('FAIL_PULL','FAIL_SANDBOX'):
            self.log.write_text('')
            result=self.run_deploy(**{failure:'1'})
            self.assertNotEqual(result.returncode,0)
            self.assertFalse(any('up' in c['args'] for c in self.calls()))
            self.assertEqual((self.server/'.previous-image-tag').read_text(),OLD)

    def test_insufficient_ram_and_missing_profile_prevent_activation(self):
        result=self.run_deploy(TEST_RAM_KIB='1000')
        self.assertNotEqual(result.returncode,0);self.assertIn('BROWSER_RAM_INSUFFICIENT',result.stderr)
        result=self.run_deploy(BROWSER_SECCOMP_PROFILE='/missing/profile.json')
        self.assertNotEqual(result.returncode,0);self.assertIn('BROWSER_SECCOMP_MISSING',result.stderr)
        self.assertFalse(any('up' in c['args'] for c in self.calls()))

    def test_failed_rollout_restores_old_manifest_and_disabled_browser(self):
        result=self.run_deploy(FAIL_UP='1')
        self.assertNotEqual(result.returncode,0)
        restored=[c for c in self.calls() if 'up' in c['args'] and c['tag']==OLD]
        self.assertTrue(restored)
        self.assertTrue(all(c['browser']=='false' and str(self.server/'compose.yaml') in c['args'] and 'compose.browser.yaml' not in ' '.join(c['args']) for c in restored))
        self.assertEqual((self.server/'.previous-image-tag').read_text(),OLD)
        self.assertTrue(any('rm' in c['args'] and 'browser-worker' in c['args'] for c in self.calls()))

    def test_failed_rollout_restores_a_previously_enabled_browser_release(self):
        old=self.root/OLD
        old.mkdir()
        for name in ('compose.yaml','compose.browser.yaml','seccomp_profile.json'):
            shutil.copy(self.release/name,old/name)
        (self.server/'.active-release').write_text(str(old))
        (self.server/'.active-browser-enabled').write_text('true')
        result=self.run_deploy(FAIL_UP='1')
        self.assertNotEqual(result.returncode,0)
        restored=[c for c in self.calls() if 'up' in c['args'] and c['tag']==OLD]
        self.assertTrue(restored)
        self.assertTrue(all(c['browser']=='true' and str(old/'compose.browser.yaml') in c['args'] for c in restored))
        self.assertTrue(any('browser-worker' in c['args'] for c in restored))

    def test_external_smoke_failure_can_rollback_persisted_browser_and_release_selection(self):
        self.assertEqual(self.run_deploy().returncode,0)
        result=self.run_deploy('--rollback')
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual((self.server/'.previous-image-tag').read_text().strip(),OLD)
        self.assertEqual((self.server/'.active-browser-enabled').read_text().strip(),'false')
        self.assertTrue((self.server/'.env').read_text().startswith(self.original))

    def test_disabling_the_overlay_stops_only_browser_services_and_keeps_rollback(self):
        self.assertEqual(self.run_deploy().returncode,0)
        (self.server/'.env').write_text((self.server/'.env').read_text().replace('DISCOVERY_BROWSER_ENABLED=true','DISCOVERY_BROWSER_ENABLED=false'))
        self.log.write_text('')
        result=self.run_deploy()
        self.assertEqual(result.returncode,0,result.stderr)
        removals=[c['args'] for c in self.calls() if 'rm' in c['args']]
        self.assertTrue(any(args[-3:]==['-sf','browser-gateway','browser-worker'] for args in removals))
        self.assertFalse(any('db' in args or 'frontend' in args for args in removals))
        self.assertEqual((self.server/'.active-browser-enabled').read_text().strip(),'false')

    def test_disabled_mode_does_not_require_token_profile_or_browser_images(self):
        (self.server/'.env').write_text(self.original.replace('DISCOVERY_BROWSER_ENABLED=true','DISCOVERY_BROWSER_ENABLED=false'))
        (self.release/'seccomp_profile.json').unlink()
        result=self.run_deploy()
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertFalse(any('browser-worker' in c['args'] or 'browser-gateway' in c['args'] for c in self.calls()))
        self.assertNotIn('BROWSER_WORKER_TOKEN=',(self.server/'.env').read_text())

    def test_media_permissions_are_prepared_only_for_a_new_volume(self):
        first=self.run_deploy()
        self.assertEqual(first.returncode,0,first.stderr)
        self.assertFalse(any('-R' in c['args'] and '10001:10001' in c['args'] for c in self.calls()))
        self.log.write_text('')
        result=self.run_deploy(MISSING_MEDIA_VOLUME='1')
        self.assertEqual(result.returncode,0,result.stderr)
        chown=[c for c in self.calls() if '-R' in c['args'] and '10001:10001' in c['args']]
        self.assertEqual(len(chown),1)
        self.assertIn('/app/uploads',chown[0]['args'])
        self.assertTrue(any('volume' in c['args'] and 'create' in c['args'] for c in self.calls()))

if __name__=='__main__':unittest.main()
