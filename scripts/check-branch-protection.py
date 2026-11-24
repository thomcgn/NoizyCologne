#!/usr/bin/env python3
"""Read-only verification of main's FULL quality-gate protection.

Accepts either classic branch protection or an active, non-bypassable ruleset
that requires the Full quality gate from GitHub Actions. Every unreadable API
response fails closed; nothing is written to GitHub.
"""
import argparse
import fnmatch
import json
import re
import subprocess
import sys

GATE_CONTEXT = 'Full quality gate'
GATE_APP_ID = 15368


def problems(protection):
    """API response, not the PUT configuration schema. Fail on missing controls."""
    if not isinstance(protection, dict):
        return ['Branch protection response is not an object.']
    errors = []
    checks = protection.get('required_status_checks') or {}
    if checks.get('strict') is not True:
        errors.append('Require up-to-date status checks before merging.')
    if not any(check.get('context') == GATE_CONTEXT and check.get('app_id') == GATE_APP_ID
               for check in checks.get('checks', []) if isinstance(check, dict)):
        errors.append(f'Require {GATE_CONTEXT} from the GitHub Actions app ({GATE_APP_ID}).')
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


def gh_get(repo, path):
    """One read-only `gh api` call: (ok, decoded payload, diagnostic)."""
    try:
        result = subprocess.run(['gh', 'api', f'repos/{repo}/{path}'],
                                check=False, capture_output=True, text=True)
    except OSError as error:
        return False, None, str(error)
    if result.returncode:
        detail = (result.stderr or result.stdout or 'unknown API error').strip()
        return False, None, detail
    try:
        return True, json.loads(result.stdout), ''
    except ValueError:
        return False, None, 'malformed JSON response'


def ref_matches(pattern):
    """Conservative match of a ruleset ref pattern against main."""
    if pattern in ('~ALL', '*'):
        return True
    if pattern == '~DEFAULT_BRANCH':
        return True
    return any(fnmatch.fnmatchcase(ref, pattern) for ref in ('main', 'refs/heads/main'))


def applies_to_main(ruleset):
    """The ruleset must include main and must not exclude it."""
    conditions = ruleset.get('conditions') or {}
    ref = conditions.get('ref_name') or {}
    include = ref.get('include') or []
    exclude = ref.get('exclude') or []
    return (any(ref_matches(pattern) for pattern in include if isinstance(pattern, str))
            and not any(ref_matches(pattern) for pattern in exclude if isinstance(pattern, str)))


def ruleset_problems(ruleset):
    """Fail-closed validation of a ruleset detail response."""
    if not isinstance(ruleset, dict):
        return ['Ruleset response is not an object.']
    errors = []
    if ruleset.get('target') != 'branch':
        errors.append('Ruleset must target branches.')
    if ruleset.get('enforcement') != 'active':
        errors.append('Ruleset must be active, not evaluated or disabled.')
    if not applies_to_main(ruleset):
        errors.append('Ruleset must apply to main and must not exclude it.')
    if ruleset.get('bypass_actors'):
        errors.append('Ruleset must not define bypass actors.')
    rules = ruleset.get('rules')
    if not isinstance(rules, list):
        return errors + ['Ruleset must expose its rules.']
    by_type = {}
    for rule in rules:
        if isinstance(rule, dict) and isinstance(rule.get('type'), str):
            by_type[rule['type']] = rule
    gate = by_type.get('required_status_checks') or {}
    checks = (gate.get('parameters') or {}).get('required_status_checks') or []
    if not any(isinstance(check, dict) and check.get('context') == GATE_CONTEXT
               and check.get('app_id') == GATE_APP_ID for check in checks):
        errors.append(f'Require {GATE_CONTEXT} from the GitHub Actions app ({GATE_APP_ID}).')
    if 'pull_request' not in by_type:
        errors.append('Require a pull request before merging.')
    return errors


def ruleset_divergences(ruleset):
    """Report weaker ruleset details explicitly; never present them as strict."""
    notes = []
    by_type = {}
    for rule in ruleset.get('rules') or []:
        if isinstance(rule, dict) and isinstance(rule.get('type'), str):
            by_type[rule['type']] = rule
    gate = by_type.get('required_status_checks') or {}
    gate_parameters = gate.get('parameters') or {}
    if gate_parameters.get('strict_required_status_checks_policy') is not True:
        notes.append('Ruleset does not require up-to-date status checks (strict=false); '
                     'the versioned template is stricter.')
    review_parameters = (by_type.get('pull_request') or {}).get('parameters') or {}
    if review_parameters.get('required_approving_review_count', 0) == 0:
        notes.append('Ruleset requires zero approving reviews; no independent review is enforced.')
    return notes


def verify(repo, api):
    """Return (status, success lines, error lines) without mutating GitHub."""
    ok, classic, _ = api(repo, 'branches/main/protection')
    classic_errors = problems(classic) if ok else []
    if ok and not classic_errors:
        return 0, [f'main requires the GitHub Actions {GATE_CONTEXT} check, including for administrators.'], []

    errors = []
    if ok:
        errors += [f'Protection check failed: {error}' for error in classic_errors]
    else:
        errors.append('Cannot verify classic main protection (missing protection, access or plan support).')

    ok, rulesets, detail = api(repo, 'rulesets')
    if not ok:
        errors.append(f'Cannot verify main rulesets (missing API access or plan support): {detail}')
        return 1, [], errors + ['No merge authorization can be inferred.']
    if not isinstance(rulesets, list):
        return 1, [], errors + ['Malformed rulesets response.', 'No merge authorization can be inferred.']

    candidates = [ruleset for ruleset in rulesets
                  if isinstance(ruleset, dict)
                  and ruleset.get('enforcement') == 'active'
                  and ruleset.get('target') == 'branch'
                  and applies_to_main(ruleset)]
    for candidate in candidates:
        ruleset_id = candidate.get('id')
        if not isinstance(ruleset_id, int):
            errors.append('Active main ruleset has no readable id.')
            continue
        ok, ruleset, detail = api(repo, f'rulesets/{ruleset_id}')
        if not ok:
            errors.append(f'Cannot verify main ruleset {ruleset_id} '
                          f'(missing API access or plan support): {detail}')
            return 1, [], errors + ['No merge authorization can be inferred.']
        ruleset_errors = ruleset_problems(ruleset)
        if not ruleset_errors:
            success = [f'main has an active ruleset requiring the GitHub Actions {GATE_CONTEXT} '
                       'check without bypass actors.']
            success += [f'Note: {note}' for note in ruleset_divergences(ruleset)]
            return 0, success, []
        errors += [f'Ruleset check failed: {error}' for error in ruleset_errors]
    if not candidates:
        errors.append('No active ruleset applies to main.')
    return 1, [], errors + ['No merge authorization can be inferred.']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', default='thomcgn/NoizyCologne', help='GitHub owner/repository')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repo):
        parser.error('Expected owner/repository.')
    status, success, errors = verify(args.repo, gh_get)
    for line in success:
        print(line)
    for line in errors:
        print(line, file=sys.stderr)
    return status


if __name__ == '__main__':
    sys.exit(main())
