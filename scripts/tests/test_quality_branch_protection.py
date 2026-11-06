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


if __name__ == '__main__':
    unittest.main()
