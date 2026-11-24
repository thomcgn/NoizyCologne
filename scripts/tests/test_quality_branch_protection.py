"""Protection acceptance cannot be claimed from workflow files alone."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('branch_protection', ROOT / 'scripts/check-branch-protection.py')
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class BranchProtectionTests(unittest.TestCase):
    def setUp(self):
        self.response = {
            'required_status_checks': {'strict': True, 'checks': [
                {'context': 'Full quality gate', 'app_id': 15368}]},
            'enforce_admins': {'enabled': True},
            'required_pull_request_reviews': {'required_approving_review_count': 0},
            'allow_force_pushes': {'enabled': False}, 'allow_deletions': {'enabled': False}}

    def test_required_full_check_accepts_exact_github_app(self):
        self.assertEqual([], MODULE.problems(self.response))

    def test_missing_wrong_or_bypassable_controls_rejected(self):
        mutations = [
            lambda value: value.clear(),
            lambda value: value['required_status_checks'].update(strict=False),
            lambda value: value['required_status_checks']['checks'][0].update(context='Targeted quality checks'),
            lambda value: value['required_status_checks']['checks'][0].update(app_id=-1),
            lambda value: value['enforce_admins'].update(enabled=False),
            lambda value: value.update(required_pull_request_reviews=None),
            lambda value: value['required_pull_request_reviews'].update(bypass_pull_request_allowances={'users': ['admin']}),
            lambda value: value['allow_force_pushes'].update(enabled=True),
            lambda value: value['allow_deletions'].update(enabled=True)]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                response = copy.deepcopy(self.response)
                mutate(response)
                self.assertTrue(MODULE.problems(response))

    def test_reviewable_configuration_matches_checker(self):
        config = json.loads((ROOT / '.github/branch-protection-main.json').read_text())
        response = copy.deepcopy(config)
        for key in ('enforce_admins', 'allow_force_pushes', 'allow_deletions'):
            response[key] = {'enabled': config[key]}
        self.assertEqual([], MODULE.problems(response))


class RulesetProtectionTests(unittest.TestCase):
    """Mocked read-only API answers for the classic/ruleset fallback path."""

    def setUp(self):
        self.summary = {
            'id': 26688192, 'name': 'Full quality gate', 'target': 'branch',
            'enforcement': 'active',
            'conditions': {'ref_name': {'include': ['~DEFAULT_BRANCH'], 'exclude': []}}}
        self.ruleset = copy.deepcopy(self.summary)
        self.ruleset['bypass_actors'] = []
        self.ruleset['rules'] = [
            {'type': 'required_status_checks', 'parameters': {
                'strict_required_status_checks_policy': False,
                'required_status_checks': [
                    {'context': 'Full quality gate', 'app_id': 15368}]}},
            {'type': 'pull_request', 'parameters': {
                'required_approving_review_count': 0,
                'dismiss_stale_reviews_on_push': False}}]
        self.calls = []

    def api(self, classic=None, rulesets=None, details=None):
        def fake(repo, path):
            self.assertEqual('thomcgn/NoizyCologne', repo)
            self.calls.append(path)
            if path == 'branches/main/protection':
                return classic if classic is not None else (False, None, 'HTTP 404: Not Found')
            if path == 'rulesets':
                return rulesets if rulesets is not None else (True, [], '')
            if path.startswith('rulesets/'):
                return details.get(path, (False, None, 'HTTP 404: Not Found'))
            raise AssertionError(f'unexpected API path: {path}')
        return fake

    def test_active_ruleset_with_required_gate_is_accepted(self):
        status, success, errors = MODULE.verify(
            'thomcgn/NoizyCologne',
            self.api(rulesets=(True, [self.summary], ''),
                     details={'rulesets/26688192': (True, self.ruleset, '')}))
        self.assertEqual(0, status)
        self.assertEqual([], errors)
        self.assertTrue(any('active ruleset' in line for line in success))
        self.assertTrue(any('strict=false' in line for line in success))
        self.assertTrue(any('zero approving reviews' in line for line in success))
        self.assertIn('branches/main/protection', self.calls)

    def test_missing_ruleset_and_protection_fails_closed(self):
        status, success, errors = MODULE.verify('thomcgn/NoizyCologne', self.api())
        self.assertEqual(1, status)
        self.assertEqual([], success)
        self.assertTrue(any('No active ruleset applies to main.' in line for line in errors))
        self.assertTrue(any('No merge authorization can be inferred.' in line for line in errors))

    def test_classic_protection_is_still_accepted(self):
        classic = {
            'required_status_checks': {'strict': True, 'checks': [
                {'context': 'Full quality gate', 'app_id': 15368}]},
            'enforce_admins': {'enabled': True},
            'required_pull_request_reviews': {'required_approving_review_count': 0},
            'allow_force_pushes': {'enabled': False}, 'allow_deletions': {'enabled': False}}
        status, success, errors = MODULE.verify('thomcgn/NoizyCologne', self.api(classic=(True, classic, '')))
        self.assertEqual(0, status)
        self.assertEqual([], errors)
        self.assertTrue(any('including for administrators' in line for line in success))
        self.assertEqual(['branches/main/protection'], self.calls)

    def test_no_api_access_fails_closed(self):
        status, success, errors = MODULE.verify(
            'thomcgn/NoizyCologne',
            self.api(classic=(False, None, 'HTTP 403: Forbidden'),
                     rulesets=(False, None, 'HTTP 403: Forbidden')))
        self.assertEqual(1, status)
        self.assertEqual([], success)
        self.assertTrue(any('Cannot verify main rulesets (missing API access or plan support): '
                            'HTTP 403: Forbidden' in line for line in errors))
        self.assertTrue(any('No merge authorization can be inferred.' in line for line in errors))

    def test_unreadable_ruleset_detail_fails_closed(self):
        status, _, errors = MODULE.verify(
            'thomcgn/NoizyCologne',
            self.api(rulesets=(True, [self.summary], ''),
                     details={'rulesets/26688192': (False, None, 'HTTP 403: Forbidden')}))
        self.assertEqual(1, status)
        self.assertTrue(any('Cannot verify main ruleset 26688192' in line for line in errors))

    def test_unsafe_rulesets_are_rejected(self):
        mutations = [
            lambda value: value.update(enforcement='disabled'),
            lambda value: value.update(target='tag'),
            lambda value: value.update(bypass_actors=[{'actor_type': 'repository_admin'}]),
            lambda value: value['conditions']['ref_name'].update(exclude=['~DEFAULT_BRANCH']),
            lambda value: value['conditions']['ref_name'].update(include=['feature/*']),
            lambda value: value['rules'][0]['parameters']['required_status_checks'][0].update(app_id=-1),
            lambda value: value['rules'][0]['parameters']['required_status_checks'][0].update(
                context='Targeted quality checks'),
            lambda value: value.pop('rules'),
            lambda value: value['rules'].pop(),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                ruleset = copy.deepcopy(self.ruleset)
                mutate(ruleset)
                status, success, errors = MODULE.verify(
                    'thomcgn/NoizyCologne',
                    self.api(rulesets=(True, [self.summary], ''),
                             details={'rulesets/26688192': (True, ruleset, '')}))
                self.assertEqual(1, status)
                self.assertEqual([], success)
                self.assertTrue(any('Ruleset check failed:' in line for line in errors))


if __name__ == '__main__':
    unittest.main()
