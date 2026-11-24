#!/usr/bin/env python3
"""Authorize deployments from immutable commits and actual FULL job results.

Only a completed CI push on main can be reused. PR runs report the PR head in
the Actions API although the workflow tests a merge commit; they are deliberately
ineligible. Manual deployments without reusable CI must complete their own FULL
job. No downloaded artifact or build cache is an authorization credential.
"""

import argparse
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request


CI_PATH = '.github/workflows/ci.yml'
DEPLOY_PATH = '.github/workflows/ghcr.yml'
FULL_JOB = 'Full validation / Full validation'
REQUIRED_CHECK = 'Full quality gate'
FULL_STEPS = ('Verify checked out commit', 'Full quality gate', 'Validate Caddy configuration')

# The guard job translates only this exact exit code into a clean skip. Every
# other error stays blocking and must never authorize or silently skip a deploy.
STALE_EXIT = 2
STALE_TRIGGER = 'STALE_TRIGGER: the triggering commit is no longer the current main head'


class Unqualified(RuntimeError):
    """The selected commit has no suitable successful FULL proof."""


def require(condition, message):
    if not condition:
        raise Unqualified(message)


def commit_sha(value):
    require(isinstance(value, str) and re.fullmatch(r'[0-9a-f]{40}', value),
            'The deployment target must be a full immutable commit SHA')
    return value


def positive_id(value):
    require(isinstance(value, int) and not isinstance(value, bool) and value > 0,
            'A positive run ID and attempt are required')
    return value


class GitHub:
    def __init__(self, repository, token, api_url='https://api.github.com'):
        require(bool(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository)),
                'Invalid repository name')
        require(bool(token), 'A read-only Actions token is required')
        require(api_url.startswith('https://'), 'GitHub API requests must use HTTPS')
        self.repository = repository
        self.token = token
        self.api_url = api_url.rstrip('/')

    def get(self, path):
        request = urllib.request.Request(
            f'{self.api_url}/repos/{self.repository}/{path}',
            headers={
                'Authorization': f'Bearer {self.token}',
                'Accept': 'application/vnd.github+json',
                'X-GitHub-Api-Version': '2022-11-28',
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except (urllib.error.URLError, ValueError) as error:
            # Do not print request headers, tokens or remote response bodies.
            raise Unqualified(f'Cannot verify GitHub Actions state for {path.split("?")[0]}') from error

    def collection(self, path, key):
        result = []
        separator = '&' if '?' in path else '?'
        for page in range(1, 11):
            response = self.get(f'{path}{separator}per_page=100&page={page}')
            items = response.get(key)
            require(isinstance(items, list), f'Missing Actions API collection: {key}')
            result.extend(items)
            if len(items) < 100:
                return result
        raise Unqualified('Actions history is too large to verify completely')


class DeploymentGuard:
    def __init__(self, client, current_run_id, current_attempt):
        self.client = client
        self.repository = client.repository
        self.current_run_id = positive_id(current_run_id)
        self.current_attempt = positive_id(current_attempt)

    def workflow_id(self, path):
        workflow = self.client.get(f'actions/workflows/{Path(path).name}')
        require(workflow.get('path') == path and workflow.get('state') == 'active',
                'The expected quality workflow is missing or inactive')
        return positive_id(workflow.get('id'))

    def identity(self, run, sha, path, event, branch=None):
        require(run.get('head_sha') == sha, 'The quality run tested a different commit')
        require(run.get('path') == path and run.get('event') == event,
                'The run is not from the expected workflow and event')
        require(run.get('repository', {}).get('full_name', '').lower() == self.repository.lower()
                and run.get('head_repository', {}).get('full_name', '').lower() == self.repository.lower(),
                'Foreign repositories and fork runs cannot authorize deployment')
        if branch is not None:
            require(run.get('head_branch') == branch, 'Only a main push CI run can be reused')
        positive_id(run.get('id'))
        positive_id(run.get('run_attempt'))

    def latest_ci_run(self, sha):
        query = urllib.parse.urlencode({'branch': 'main', 'event': 'push', 'head_sha': sha})
        runs = self.client.collection(f'actions/workflows/ci.yml/runs?{query}', 'workflow_runs')
        if not runs:
            return None
        # Include all conclusions: filtering for success could revive an older
        # green result while a newer run/attempt is pending or failing.
        # Completion time is unsuitable: an older run can finish after a newer
        # queued/failed run and must not take precedence over that newer run.
        return max(runs, key=lambda run: (run.get('created_at', ''), run.get('id', 0)))

    def full_job(self, run, require_check):
        jobs = self.client.collection(
            f'actions/runs/{run["id"]}/attempts/{run["run_attempt"]}/jobs', 'jobs')
        full = [job for job in jobs if job.get('name') == FULL_JOB]
        require(len(full) == 1, 'The run has no unique real FULL validation job')
        job = full[0]
        require(job.get('run_id') == run['id'] and job.get('head_sha') == run['head_sha'],
                'FULL job provenance does not match the run')
        require(job.get('status') == 'completed' and job.get('conclusion') == 'success',
                'FULL validation did not complete successfully')
        for name in FULL_STEPS:
            steps = [step for step in job.get('steps', []) if step.get('name') == name]
            require(len(steps) == 1 and steps[0].get('status') == 'completed'
                    and steps[0].get('conclusion') == 'success',
                    f'The required FULL step did not succeed: {name}')
        if require_check:
            checks = [job for job in jobs if job.get('name') == REQUIRED_CHECK]
            require(len(checks) == 1 and checks[0].get('status') == 'completed'
                    and checks[0].get('conclusion') == 'success',
                    'The required Full quality gate check did not succeed')

    def current_main(self, sha):
        reference = self.client.get('git/ref/heads/main')
        require(reference.get('object', {}).get('sha') == sha,
                'A newer main commit exists; refusing an automatic rollback')

    def is_current_main(self, sha):
        """Read-only staleness check for the guard job; it produces no proof.

        Malformed event data or API answers raise Unqualified and are treated as
        hard errors. The strict plan/verify authorization is unchanged and is
        still rechecked before publish and deploy.
        """
        sha = commit_sha(sha)
        reference = self.client.get('git/ref/heads/main')
        require(isinstance(reference, dict) and isinstance(reference.get('object'), dict),
                'Malformed main ref response')
        return commit_sha(reference['object'].get('sha')) == sha

    def verify_ci(self, sha, run_id, attempt, automatic):
        latest = self.latest_ci_run(sha)
        require(latest is not None and latest.get('id') == run_id
                and latest.get('run_attempt') == attempt,
                'The selected proof is not the latest CI attempt for this commit')
        run = self.client.get(f'actions/runs/{run_id}')
        self.identity(run, sha, CI_PATH, 'push', 'main')
        require(run.get('workflow_id') == self.workflow_id(CI_PATH), 'Unexpected CI workflow ID')
        require(run.get('id') == run_id and run.get('run_attempt') == attempt and run.get('status') == 'completed'
                and run.get('conclusion') == 'success',
                'CI is missing, running, rerun or unsuccessful')
        self.full_job(run, require_check=True)
        # Re-read after jobs: a newly requested rerun must not inherit old jobs.
        fresh = self.client.get(f'actions/runs/{run_id}')
        require(fresh.get('id') == run_id and fresh.get('head_sha') == sha
                and fresh.get('run_attempt') == attempt and fresh.get('status') == 'completed'
                and fresh.get('conclusion') == 'success', 'CI changed while its proof was verified')
        latest = self.latest_ci_run(sha)
        require(latest is not None and latest.get('id') == run_id and latest.get('run_attempt') == attempt,
                'A newer CI run appeared while its proof was verified')
        if automatic:
            self.current_main(sha)

    def verify_local(self, sha, run_id, attempt):
        require(run_id == self.current_run_id and attempt == self.current_attempt,
                'A fallback FULL proof must belong to this deployment attempt')
        run = self.client.get(f'actions/runs/{run_id}')
        self.identity(run, sha, DEPLOY_PATH, 'workflow_dispatch')
        require(run.get('workflow_id') == self.workflow_id(DEPLOY_PATH), 'Unexpected deployment workflow ID')
        require(run.get('id') == run_id and run.get('run_attempt') == attempt and run.get('status') == 'in_progress'
                and run.get('conclusion') is None, 'The manual deployment attempt is no longer active')
        self.full_job(run, require_check=False)

    def plan(self, event_name, event, dispatch_sha):
        if event_name == 'workflow_run':
            run = event.get('workflow_run', {})
            sha = commit_sha(run.get('head_sha'))
            self.identity(run, sha, CI_PATH, 'push', 'main')
            require(event.get('action') == 'completed' and run.get('status') == 'completed'
                    and run.get('conclusion') == 'success', 'The triggering CI did not succeed')
            self.verify_ci(sha, run['id'], run['run_attempt'], automatic=True)
            return self.proof(sha, 'ci', run['id'], run['run_attempt'], True, True)
        require(event_name == 'workflow_dispatch', 'Unsupported deployment trigger')
        sha = commit_sha(dispatch_sha)
        latest = self.latest_ci_run(sha)
        if latest is not None:
            try:
                self.verify_ci(sha, latest.get('id'), latest.get('run_attempt'), automatic=False)
            except Unqualified:
                pass
            else:
                return self.proof(sha, 'ci', latest['id'], latest['run_attempt'], True, False)
        return self.proof(sha, 'local', self.current_run_id, self.current_attempt, False, False)

    @staticmethod
    def proof(sha, kind, run_id, attempt, reuse, automatic):
        return {'sha': sha, 'kind': kind, 'run_id': str(run_id), 'attempt': str(attempt),
                'reuse': str(reuse).lower(), 'automatic': str(automatic).lower()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('plan', 'verify', 'check-current'))
    args = parser.parse_args()
    try:
        client = GitHub(os.environ['GITHUB_REPOSITORY'], os.environ['GITHUB_TOKEN'],
                        os.environ.get('GITHUB_API_URL', 'https://api.github.com'))
        guard = DeploymentGuard(client, int(os.environ['GITHUB_RUN_ID']), int(os.environ['GITHUB_RUN_ATTEMPT']))
        if args.command == 'check-current':
            event_name = os.environ['GITHUB_EVENT_NAME']
            if event_name == 'workflow_dispatch':
                print('Manual trigger: staleness guard not applicable; plan/verify remain authoritative')
                return 0
            require(event_name == 'workflow_run', 'Unsupported staleness check trigger')
            event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
            trigger = event.get('workflow_run')
            require(isinstance(trigger, dict), 'Malformed workflow_run event')
            if guard.is_current_main(trigger.get('head_sha')):
                print('CURRENT_TRIGGER: the triggering commit is still the current main head')
                return 0
            print(STALE_TRIGGER)
            return STALE_EXIT
        if args.command == 'plan':
            event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
            proof = guard.plan(os.environ['GITHUB_EVENT_NAME'], event, os.environ['GITHUB_SHA'])
            with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                for key, value in proof.items():
                    print(f'{key}={value}', file=output)
            print(f'Exact commit {proof["sha"]}: {"reuse CI FULL" if proof["reuse"] == "true" else "run FULL before deployment"}')
        else:
            sha = commit_sha(os.environ['QUALITY_TARGET_SHA'])
            run_id = positive_id(int(os.environ['QUALITY_PROOF_RUN_ID']))
            attempt = positive_id(int(os.environ['QUALITY_PROOF_ATTEMPT']))
            kind = os.environ['QUALITY_PROOF_KIND']
            require(os.environ['QUALITY_AUTOMATIC'] in ('true', 'false'), 'Invalid deployment trigger mode')
            if kind == 'ci':
                guard.verify_ci(sha, run_id, attempt, os.environ['QUALITY_AUTOMATIC'] == 'true')
            else:
                require(kind == 'local' and os.environ['QUALITY_AUTOMATIC'] == 'false', 'Invalid proof type')
                guard.verify_local(sha, run_id, attempt)
            print(f'FULL proof verified for exact commit {sha}')
    except (Unqualified, KeyError, TypeError, ValueError, OSError) as error:
        print(f'[deployment] BLOCKED: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
