#!/usr/bin/env python3
"""Read-only verification of the main branch's required FULL quality check."""
import argparse
import json
import re
import subprocess
import sys


def problems(protection):
    """API response, not the PUT configuration schema. Fail on missing controls."""
    if not isinstance(protection, dict):
        return ['Branch protection response is not an object.']
    errors = []
    checks = protection.get('required_status_checks') or {}
    if checks.get('strict') is not True:
        errors.append('Require up-to-date status checks before merging.')
    if not any(check.get('context') == 'Full quality gate' and check.get('app_id') == 15368
               for check in checks.get('checks', []) if isinstance(check, dict)):
        errors.append('Require Full quality gate from the GitHub Actions app (15368).')
    if (protection.get('enforce_admins') or {}).get('enabled') is not True:
        errors.append('Administrators must also be subject to branch protection.')
    reviews = protection.get('required_pull_request_reviews')
    if not isinstance(reviews, dict):
        errors.append('Require a pull request before merging.')
    elif any((reviews.get('bypass_pull_request_allowances') or {}).get(kind)
             for kind in ('users', 'teams', 'apps')):
        errors.append('Remove pull-request bypass allowances.')
    for name in ('allow_force_pushes', 'allow_deletions'):
        if (protection.get(name) or {}).get('enabled') is not False:
            errors.append(f'{name} must be explicitly disabled.')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', default='thomcgn/NoizyCologne', help='GitHub owner/repository')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repo):
        parser.error('Expected owner/repository.')
    try:
        result = subprocess.run(['gh', 'api', f'repos/{args.repo}/branches/main/protection'],
                                check=False, capture_output=True, text=True)
    except OSError:
        print('Cannot run gh; install/authenticate the GitHub CLI for this read-only check.', file=sys.stderr)
        return 1
    if result.returncode:
        print('Cannot verify main protection (missing protection, access or plan support). '
              'No merge authorization can be inferred.', file=sys.stderr)
        return 1
    try:
        errors = problems(json.loads(result.stdout))
    except (ValueError, AttributeError, TypeError):
        errors = ['Malformed branch protection response.']
    if errors:
        print('\n'.join(f'Protection check failed: {error}' for error in errors), file=sys.stderr)
        return 1
    print('main requires the GitHub Actions Full quality gate, including for administrators.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
