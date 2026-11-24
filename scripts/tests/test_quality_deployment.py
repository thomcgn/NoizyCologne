"""Offline authorization regressions; these tests never trigger GitHub or SSH."""

import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('deployment_quality', ROOT / 'scripts/quality-deployment.py')
deployment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deployment)

SHA = 'a' * 40
OTHER_SHA = 'b' * 40
REPOSITORY = 'thomcgn/NoizyCologne'


def run_fixture(run_id=100, attempt=1, sha=SHA):
    return {
        'id': run_id, 'run_attempt': attempt, 'head_sha': sha,
        'path': deployment.CI_PATH, 'workflow_id': 1, 'event': 'push',
        'repository': {'full_name': REPOSITORY},
        'head_repository': {'full_name': REPOSITORY}, 'head_branch': 'main',
        'status': 'completed', 'conclusion': 'success',
        'created_at': '2026-11-06T10:00:00Z', 'updated_at': '2026-11-06T10:10:00Z',
    }


def jobs_fixture(run):
    return [
        {'name': deployment.FULL_JOB, 'run_id': run['id'], 'head_sha': run['head_sha'],
         'status': 'completed', 'conclusion': 'success',
         'steps': [{'name': name, 'status': 'completed', 'conclusion': 'success'}
                   for name in deployment.FULL_STEPS]},
        {'name': deployment.REQUIRED_CHECK, 'status': 'completed', 'conclusion': 'success'},
    ]


class GitHubFixture:
    repository = REPOSITORY

    def __init__(self):
        self.run = run_fixture()
        self.runs = [self.run]
        self.jobs = jobs_fixture(self.run)
        self.main_sha = SHA
        self.calls = []
        self.after_jobs = None

    def get(self, path):
        self.calls.append(path)
        if path.startswith('actions/workflows/'):
            name = path.rsplit('/', 1)[1]
            return {'path': f'.github/workflows/{name}', 'id': 1 if name == 'ci.yml' else 2, 'state': 'active'}
        if path.startswith('actions/runs/'):
            run_id = int(path.rsplit('/', 1)[1])
            run = self.run if self.run['id'] == run_id else next(run for run in self.runs if run['id'] == run_id)
            return copy.deepcopy(run)
        if path == 'git/ref/heads/main':
            return {'object': {'sha': self.main_sha}}
        raise AssertionError(f'Unexpected API request: {path}')

    def collection(self, path, key):
        self.calls.append(path)
        if key == 'workflow_runs':
            return copy.deepcopy(self.runs)
        if key == 'jobs':
            self.last_jobs_path = path
            result = copy.deepcopy(self.jobs)
            if self.after_jobs is not None:
                self.after_jobs()
            return result
        raise AssertionError(f'Unexpected collection: {path}')


class DeploymentProofTests(unittest.TestCase):
    def setUp(self):
        self.client = GitHubFixture()
        self.guard = deployment.DeploymentGuard(self.client, current_run_id=200, current_attempt=1)

    def event(self):
        return {'action': 'completed', 'workflow_run': copy.deepcopy(self.client.run)}

    def verify_ci(self, automatic=False):
        self.guard.verify_ci(SHA, 100, 1, automatic)

    def test_automatic_deploy_uses_triggering_tested_sha_not_default_branch_github_sha(self):
        proof = self.guard.plan('workflow_run', self.event(), OTHER_SHA)
        self.assertEqual(proof, {'sha': SHA, 'kind': 'ci', 'run_id': '100', 'attempt': '1',
                                 'reuse': 'true', 'automatic': 'true'})
        self.assertEqual(self.client.last_jobs_path, 'actions/runs/100/attempts/1/jobs')

    def test_manual_exact_green_main_commit_reuses_full(self):
        proof = self.guard.plan('workflow_dispatch', {}, SHA)
        self.assertEqual(proof['reuse'], 'true')
        self.assertEqual(proof['kind'], 'ci')
        self.assertEqual(proof['sha'], SHA)

    def test_missing_proof_requires_new_full_for_manual_dispatch(self):
        self.client.runs = []
        proof = self.guard.plan('workflow_dispatch', {}, OTHER_SHA)
        self.assertEqual(proof, {'sha': OTHER_SHA, 'kind': 'local', 'run_id': '200', 'attempt': '1',
                                 'reuse': 'false', 'automatic': 'false'})

    def test_other_sha_pr_merge_sha_and_feature_quick_never_authorize_reuse(self):
        for changes in (
            {'head_sha': OTHER_SHA},
            {'event': 'pull_request'},
            {'head_branch': 'feature/something'},
            {'path': '.github/workflows/quality-quick.yml'},
        ):
            with self.subTest(changes=changes):
                self.setUp()
                self.client.run.update(changes)
                with self.assertRaises(deployment.Unqualified):
                    self.verify_ci()
                self.assertEqual(self.guard.plan('workflow_dispatch', {}, SHA)['reuse'], 'false')

    def test_untrusted_workflow_run_payloads_are_rejected(self):
        cases = (
            {'head_repository': {'full_name': 'someone/fork'}},
            {'repository': {'full_name': 'someone/fork'}},
            {'event': 'pull_request'}, {'head_branch': 'feature'},
            {'path': '.github/workflows/other.yml'},
            {'status': 'in_progress', 'conclusion': None},
            {'status': 'completed', 'conclusion': 'failure'},
            {'head_sha': 'main'}, {'id': 0}, {'run_attempt': 0},
        )
        for changes in cases:
            with self.subTest(changes=changes):
                event = self.event()
                event['workflow_run'].update(changes)
                with self.assertRaises(deployment.Unqualified):
                    self.guard.plan('workflow_run', event, SHA)

    def test_foreign_api_run_and_wrong_workflow_id_cannot_override_valid_payload(self):
        event = self.event()
        for changes in ({'workflow_id': 99}, {'head_repository': {'full_name': 'someone/fork'}}):
            with self.subTest(changes=changes):
                self.setUp()
                self.client.run.update(changes)
                with self.assertRaises(deployment.Unqualified):
                    self.guard.plan('workflow_run', event, SHA)

    def test_latest_failed_or_running_ci_blocks_old_green_proof(self):
        for status, conclusion in (('in_progress', None), ('queued', None), ('completed', 'failure'),
                                   ('completed', 'cancelled'), ('completed', 'neutral')):
            with self.subTest(status=status, conclusion=conclusion):
                self.setUp()
                newer = run_fixture(run_id=101)
                newer.update(status=status, conclusion=conclusion, updated_at='2026-11-06T11:00:00Z')
                self.client.runs.append(newer)
                with self.assertRaises(deployment.Unqualified):
                    self.verify_ci()
                self.assertEqual(self.guard.plan('workflow_dispatch', {}, SHA)['reuse'], 'false')
                self.assertTrue(all('status=success' not in call for call in self.client.calls))

    def test_latest_attempt_is_required_and_old_attempt_jobs_are_never_used(self):
        self.client.run['run_attempt'] = 2
        with self.assertRaises(deployment.Unqualified):
            self.verify_ci()
        self.assertFalse(any('/jobs' in call for call in self.client.calls))

    def test_older_success_completing_later_cannot_override_newer_pending_run(self):
        self.client.run['updated_at'] = '2026-11-06T12:00:00Z'
        newer = run_fixture(run_id=101)
        newer.update(status='queued', conclusion=None, created_at='2026-11-06T11:00:00Z',
                     updated_at='2026-11-06T11:00:00Z')
        self.client.runs.append(newer)
        with self.assertRaises(deployment.Unqualified):
            self.verify_ci()
        self.assertEqual(self.guard.plan('workflow_dispatch', {}, SHA)['reuse'], 'false')

    def test_rerun_requested_during_job_verification_invalidates_proof(self):
        self.client.after_jobs = lambda: self.client.run.update(run_attempt=2, status='queued', conclusion=None)
        with self.assertRaises(deployment.Unqualified):
            self.verify_ci()

    def test_new_ci_created_during_verification_invalidates_old_proof(self):
        newer = run_fixture(run_id=101)
        newer.update(status='queued', conclusion=None, updated_at='2026-11-06T11:00:00Z')
        self.client.after_jobs = lambda: self.client.runs.append(newer)
        with self.assertRaises(deployment.Unqualified):
            self.verify_ci()

    def test_missing_skipped_pending_or_failed_real_full_job_blocks_deployment(self):
        for status, conclusion in (('completed', 'skipped'), ('completed', 'neutral'),
                                   ('in_progress', None), ('completed', 'failure')):
            with self.subTest(status=status, conclusion=conclusion):
                self.setUp()
                self.client.jobs[0].update(status=status, conclusion=conclusion)
                with self.assertRaises(deployment.Unqualified):
                    self.verify_ci()
        self.client.jobs = []
        with self.assertRaises(deployment.Unqualified):
            self.verify_ci()

    def test_full_caddy_and_checkout_steps_must_all_have_run_successfully(self):
        for name in deployment.FULL_STEPS:
            for condition in ('missing', 'skipped', 'failure', 'in_progress'):
                with self.subTest(step=name, condition=condition):
                    self.setUp()
                    if condition == 'missing':
                        self.client.jobs[0]['steps'] = [step for step in self.client.jobs[0]['steps']
                                                       if step['name'] != name]
                    else:
                        step = next(step for step in self.client.jobs[0]['steps'] if step['name'] == name)
                        step['conclusion'] = condition
                        if condition == 'in_progress':
                            step['status'] = condition
                    with self.assertRaises(deployment.Unqualified):
                        self.verify_ci()

    def test_successful_aggregate_cannot_mask_missing_or_wrong_real_full_job(self):
        for changes in ({'head_sha': OTHER_SHA}, {'run_id': 99}, {'name': 'Targeted quality checks'}):
            with self.subTest(changes=changes):
                self.setUp()
                self.client.jobs[0].update(changes)
                with self.assertRaises(deployment.Unqualified):
                    self.verify_ci()
        self.setUp()
        self.client.jobs.append(copy.deepcopy(self.client.jobs[0]))
        with self.assertRaises(deployment.Unqualified):
            self.verify_ci()

    def test_required_context_skipped_neutral_or_missing_is_not_accepted(self):
        for conclusion in ('skipped', 'neutral', 'failure', None):
            with self.subTest(conclusion=conclusion):
                self.setUp()
                self.client.jobs[1]['conclusion'] = conclusion
                with self.assertRaises(deployment.Unqualified):
                    self.verify_ci()
        self.client.jobs = self.client.jobs[:1]
        with self.assertRaises(deployment.Unqualified):
            self.verify_ci()

    def test_stale_main_is_blocked_for_automatic_but_not_explicit_manual_commit(self):
        self.client.main_sha = OTHER_SHA
        with self.assertRaises(deployment.Unqualified):
            self.verify_ci(automatic=True)
        self.verify_ci(automatic=False)

    def test_later_failure_is_rechecked_before_publish_or_deploy(self):
        self.verify_ci()
        self.client.run.update(status='in_progress', conclusion=None, run_attempt=2)
        with self.assertRaises(deployment.Unqualified):
            self.verify_ci()

    def local_full(self):
        self.client.run = run_fixture(run_id=200)
        self.client.run.update(path=deployment.DEPLOY_PATH, workflow_id=2, event='workflow_dispatch',
                               status='in_progress', conclusion=None, head_branch='feature/manual')
        self.client.jobs = jobs_fixture(self.client.run)[:1]

    def test_manual_fallback_accepts_own_completed_full_without_claiming_overall_success(self):
        self.local_full()
        self.guard.verify_local(SHA, 200, 1)

    def test_manual_fallback_rejects_other_runs_attempts_commits_and_skipped_full(self):
        self.local_full()
        for run_id, attempt, sha in ((201, 1, SHA), (200, 2, SHA), (200, 1, OTHER_SHA)):
            with self.subTest(run_id=run_id, attempt=attempt, sha=sha):
                with self.assertRaises(deployment.Unqualified):
                    self.guard.verify_local(sha, run_id, attempt)
        for changes in ({'event': 'pull_request'}, {'workflow_id': 1}, {'conclusion': 'failure'},
                        {'status': 'completed'}, {'head_repository': {'full_name': 'someone/fork'}}):
            with self.subTest(changes=changes):
                self.local_full()
                self.client.run.update(changes)
                with self.assertRaises(deployment.Unqualified):
                    self.guard.verify_local(SHA, 200, 1)
        self.local_full()
        self.client.jobs[0]['conclusion'] = 'skipped'
        with self.assertRaises(deployment.Unqualified):
            self.guard.verify_local(SHA, 200, 1)

    def test_mutable_refs_and_unknown_events_are_never_deployment_targets(self):
        for sha in ('main', 'refs/heads/main', 'abc123', SHA + '\n', '$(touch bad)', ''):
            with self.subTest(sha=sha):
                with self.assertRaises(deployment.Unqualified):
                    self.guard.plan('workflow_dispatch', {}, sha)
        with self.assertRaises(deployment.Unqualified):
            self.guard.plan('push', {}, SHA)


class WorkflowContractTests(unittest.TestCase):
    def workflow(self, name):
        return (ROOT / '.github/workflows' / name).read_text()

    def test_required_full_context_only_exists_in_full_ci_and_cannot_pass_on_skipped_dependency(self):
        ci = self.workflow('ci.yml')
        self.assertIn('name: Full quality gate\n    if: always()\n    needs: full', ci)
        self.assertIn('run: test "$FULL_RESULT" = success', ci)
        self.assertIn('target-sha: ${{ github.sha }}', ci)
        quick = self.workflow('quality-quick.yml')
        self.assertNotIn('name: Full quality gate', quick)
        self.assertIn("branches: ['**', '!main']", quick)
        self.assertIn('QUALITY_BASE_REF: ${{ github.event.before }}', quick)
        self.assertIn('fetch-depth: 0', quick)
        self.assertIn("if: steps.scope.outputs.frontend == 'true'", quick)

    def test_common_full_workflow_keeps_real_full_caddy_and_immutable_checkout(self):
        full = self.workflow('quality-full.yml')
        self.assertIn('name: Full validation', full)
        self.assertIn('ref: ${{ inputs.target-sha }}', full)
        self.assertIn('test "$(git rev-parse HEAD)" = "$TARGET_SHA"', full)
        self.assertIn('scripts/check-quality.sh --full', full)
        self.assertIn('name: Validate Caddy configuration', full)
        self.assertIn('run: npm ci', full)
        self.assertNotIn('continue-on-error', full)

    def test_quality_workflows_bound_runtime_and_serialize_refs(self):
        for name in ('ci.yml', 'quality-quick.yml', 'quality-full.yml'):
            with self.subTest(workflow=name):
                workflow = self.workflow(name)
                self.assertIn('concurrency:', workflow)
                self.assertIn('timeout-minutes:', workflow)
        ci = self.workflow('ci.yml')
        self.assertIn('group: ci-${{ github.ref }}', ci)
        self.assertIn('cancel-in-progress: false', ci)
        self.assertIn('timeout-minutes: 10', ci)
        self.assertIn('name: Full quality gate\n    if: always()\n    needs: full', ci)
        quick = self.workflow('quality-quick.yml')
        self.assertIn('group: quick-${{ github.ref }}', quick)
        self.assertIn('cancel-in-progress: true', quick)
        full = self.workflow('quality-full.yml')
        self.assertIn('group: full-${{ inputs.target-sha }}', full)
        self.assertIn('cancel-in-progress: false', full)
        self.assertIn('timeout-minutes: 60', full)

    def test_deploy_never_uses_default_branch_sha_or_duplicate_push_trigger(self):
        deploy = self.workflow('ghcr.yml')
        self.assertIn('workflow_run:', deploy)
        self.assertIn('workflow_dispatch:', deploy)
        self.assertNotIn('\n  push:', deploy)
        self.assertNotIn('${{ github.sha }}', deploy)
        self.assertEqual(deploy.count('ref: ${{ needs.authorize.outputs.sha }}'), 2)
        self.assertEqual(deploy.count(':${{ needs.authorize.outputs.sha }}'), 5)
        self.assertIn("./deploy/server/releases/${{ needs.authorize.outputs.sha }}/deploy.sh '${{ needs.authorize.outputs.sha }}'", deploy)

    def test_quality_rechecked_before_registry_login_and_ssh_and_existing_smoke_rollback_remain(self):
        deploy = self.workflow('ghcr.yml')
        self.assertLess(deploy.index('name: Verify exact commit before publishing'), deploy.index('name: Login to GHCR'))
        self.assertLess(deploy.index('name: Verify exact commit before deployment'), deploy.index('name: Configure SSH'))
        self.assertIn('needs: [authorize, publish]', deploy)
        self.assertIn('needs.full.result == \'success\'', deploy)
        self.assertIn("if: ${{ !cancelled() && needs.authorize.result == 'success' }}", deploy)
        for marker in ('name: Verify live deployment', 'test "$unauthenticated_status" = 401',
                       'name: Remove legacy smoke service after successful cutover',
                       'name: Restore previous qualified release after failed smoke',
                       'StrictHostKeyChecking=yes', 'cancel-in-progress: false'):
            self.assertIn(marker, deploy)

    def test_layer_cache_keeps_image_scopes_separate_and_pulls_current_base_images(self):
        deploy = self.workflow('ghcr.yml')
        self.assertIn('uses: docker/setup-buildx-action@v3', deploy)
        self.assertEqual(deploy.count('pull: true'), 5)
        for image in ('backend', 'frontend', 'landing','browser-worker','browser-gateway'):
            self.assertIn(f'cache-from: type=gha,scope=noizycologne-{image}', deploy)
            self.assertIn(f'cache-to: type=gha,scope=noizycologne-{image},mode=max', deploy)


class DeploymentCommandTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.directory = Path(self.folder.name)
        self.output = self.directory / 'output'
        self.output.touch()
        self.event = self.directory / 'event.json'
        self.event.write_text('{}')
        self.client = GitHubFixture()
        self.environment = {
            'GITHUB_REPOSITORY': REPOSITORY, 'GITHUB_TOKEN': 'fixture-test-only',
            'GITHUB_RUN_ID': '200', 'GITHUB_RUN_ATTEMPT': '1',
            'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_EVENT_PATH': str(self.event),
            'GITHUB_SHA': SHA, 'GITHUB_OUTPUT': str(self.output),
        }

    def command(self, command):
        self.stdout = io.StringIO()
        self.stderr = io.StringIO()
        with patch.dict(os.environ, self.environment, clear=True), \
                patch.object(deployment, 'GitHub', return_value=self.client), \
                patch('sys.argv', ['quality-deployment.py', command]), \
                contextlib.redirect_stdout(self.stdout), contextlib.redirect_stderr(self.stderr):
            return deployment.main()

    def test_plan_writes_only_validated_sha_and_proof_for_workflow_outputs(self):
        self.assertEqual(self.command('plan'), 0)
        outputs = dict(line.split('=', 1) for line in self.output.read_text().splitlines())
        self.assertEqual(outputs['sha'], SHA)
        self.assertEqual(outputs['reuse'], 'true')
        self.assertEqual(outputs['run_id'], '100')
        self.assertNotIn('fixture-test-only', self.stdout.getvalue())

    def test_invalid_trigger_fails_without_leaving_authorizing_outputs(self):
        self.environment['GITHUB_EVENT_NAME'] = 'pull_request'
        self.assertNotEqual(self.command('plan'), 0)
        self.assertEqual(self.output.read_text(), '')
        self.assertIn('BLOCKED', self.stderr.getvalue())

    def test_verify_missing_or_failing_proof_is_a_nonzero_command(self):
        self.environment.update(QUALITY_TARGET_SHA=SHA, QUALITY_PROOF_KIND='ci', QUALITY_PROOF_RUN_ID='100',
                                QUALITY_PROOF_ATTEMPT='1', QUALITY_AUTOMATIC='true')
        self.assertEqual(self.command('verify'), 0)
        self.client.run.update(status='in_progress', conclusion=None, run_attempt=2)
        self.assertNotEqual(self.command('verify'), 0)
        self.assertIn('BLOCKED', self.stderr.getvalue())
        del self.environment['QUALITY_PROOF_RUN_ID']
        self.assertNotEqual(self.command('verify'), 0)

    def test_network_failure_is_closed_without_printing_token_or_response(self):
        client = deployment.GitHub(REPOSITORY, 'fixture-test-only')
        with patch.object(deployment.urllib.request, 'urlopen',
                          side_effect=urllib.error.URLError('fixture-test-only response body')):
            with self.assertRaisesRegex(deployment.Unqualified, '^Cannot verify GitHub Actions state') as error:
                client.get('actions/runs/100')
        self.assertNotIn('fixture-test-only', str(error.exception))

    def test_pagination_reads_all_attempt_jobs_instead_of_accepting_first_page(self):
        client = deployment.GitHub(REPOSITORY, 'fixture-test-only')
        first = [{'name': f'unrelated-{number}'} for number in range(100)]
        final = [{'name': deployment.FULL_JOB}]
        with patch.object(client, 'get', side_effect=[{'jobs': first}, {'jobs': final}]) as get:
            result = client.collection('actions/runs/100/attempts/1/jobs', 'jobs')
        self.assertEqual(len(result), 101)
        self.assertEqual(result[-1]['name'], deployment.FULL_JOB)
        self.assertIn('page=2', get.call_args_list[-1].args[0])


if __name__ == '__main__':
    unittest.main()
