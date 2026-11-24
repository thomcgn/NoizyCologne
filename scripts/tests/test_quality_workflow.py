"""Isolated workflow/hook tests. Tool failures here are deliberate simulations."""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('repository_check', ROOT / 'scripts/check-repository.py')
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


def foreign_repository_env():
    # Git hooks export repository-local variables. Git's documented list must be
    # cleared only when entering another repository, never in the active gate.
    local_names = set(subprocess.check_output(['git', 'rev-parse', '--local-env-vars'], text=True).splitlines())
    return {name: value for name, value in os.environ.items()
            if name not in local_names and name != 'QUALITY_BASE_REF'
            and not name.startswith(('GIT_CONFIG_KEY_', 'GIT_CONFIG_VALUE_'))}


class RepositoryTests(unittest.TestCase):
    def test_conflicts_and_high_confidence_secrets_are_detected(self):
        cases = [('conflict.txt', '<' * 7 + ' branch\ncontent\n' + '=' * 7 + '\n'),
                 ('key.txt', '-----BEGIN ' + 'PRIVATE KEY-----'),
                 ('token.txt', 'ghp_' + 'a' * 36), ('.env', 'PASSWORD=private')]
        for name, content in cases:
            with self.subTest(name=name):
                self.assertTrue(scanner.findings(name, content.encode()))
        self.assertFalse(scanner.findings('.env.example', b'PASSWORD=${DEVELOPMENT_PASSWORD}'))

    def test_staged_secret_cannot_be_hidden_by_a_clean_worktree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = foreign_repository_env()
            subprocess.run(['git', 'init', '-q', directory], env=env, check=True)
            file = root / 'config.txt'
            file.write_text('ghp_' + 'b' * 36)
            subprocess.run(['git', '-C', directory, 'add', 'config.txt'], env=env, check=True)
            file.write_text('clean worktree')
            result = subprocess.run(['python3', str(ROOT / 'scripts/check-repository.py')], cwd=root, env=env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('index: config.txt: GitHub token', result.stderr)
            self.assertNotIn('b' * 36, result.stderr)


class WorkflowFixture:
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for directory in ['scripts/tests', '.githooks', 'backend/src/main/java', 'frontend/node_modules/.bin', 'config', 'bin']:
            (self.root / directory).mkdir(parents=True)
        for file in ['scripts/check-quality.sh', 'scripts/check-repository.py', 'scripts/install-hooks.sh',
                     'scripts/quality-scope.py', 'scripts/quality-report.py', '.githooks/pre-commit']:
            shutil.copy2(ROOT / file, self.root / file)
        (self.root / 'scripts/check-browser.sh').write_text('#!/bin/sh\nprintf "browser-check %s\\n" "$*" >> "$FIXTURE_ROOT/calls"\ntest "$(cat "$FIXTURE_ROOT/fail-stage")" != browser-test\n')
        (self.root / 'scripts/check-browser.sh').chmod(0o755)
        (self.root / 'scripts/test-api-boundary.py').write_text('from pathlib import Path\nimport sys\nwith Path("calls").open("a") as out: out.write("api-boundary-fixture\\n")\nsys.exit(1 if Path("fail-stage").read_text() == "api-boundary-test" else 0)\n')
        (self.root / 'scripts/test-backup-integration.py').write_text('from pathlib import Path\nimport sys\nwith Path("calls").open("a") as out: out.write("backup-restore-fixture\\n")\nsys.exit(1 if Path("fail-stage").read_text() == "backup-test" else 0)\n')
        (self.root / 'scripts/check-java.py').write_text('from pathlib import Path\nimport sys\nprint("Simulated Java analysis fixture")\nsys.exit(1 if Path("fail-stage").read_text() == "java-null-warning" else 0)\n')
        # Do not recursively invoke this workflow suite inside its miniature fixture repository.
        (self.root / 'scripts/tests/test_quality_fixture.py').write_text('import unittest\nclass Fixture(unittest.TestCase):\n def test_fixture(self): self.assertTrue(True)\n')
        (self.root / 'scripts/tests/test_java_analysis.py').write_text('import unittest\nclass Fixture(unittest.TestCase):\n def test_fixture(self): self.assertTrue(True)\n')
        (self.root / 'scripts/tests/test_frontend_analysis.py').write_text('import unittest\nclass Fixture(unittest.TestCase):\n def test_fixture(self): self.assertTrue(True)\n')
        unit = self.root / 'backend/src/test/java/fixture/ProbeTests.java'
        unit.parent.mkdir(parents=True)
        unit.write_text('package fixture; class ProbeTests { Probe value; }\n')
        stub = '''#!/bin/sh
set -eu
name=$(basename "$0")
fail=$(cat "$FIXTURE_ROOT/fail-stage" 2>/dev/null || true)
printf '%s %s\\n' "$name" "$*" >> "$FIXTURE_ROOT/calls"
case "$name:$*" in
 docker:run*) printf '%s\\n' fixture-container; exit 0 ;;
 docker:port*) printf '%s\\n' 127.0.0.1:55435; exit 0 ;;
 mvnw:*surefire:test*|mvnw:*verify*) printf '%s\\n' "$DB_URL" "$SPRING_DATASOURCE_URL" "$SPRING_FLYWAY_URL" > "$FIXTURE_ROOT/test-connection" ;;
esac
case "$name:$*:$fail" in
 mvnw:*test-compile*:java-error|mvnw:*test-compile*:java-warning|mvnw:*verify*:test-failure|mvnw:*surefire:test*:test-failure|tsc:*:typescript|npm:*lint*:eslint-warning|npm:*test*:frontend-test|npm:*build*:build|docker:rm*:cleanup) exit 1 ;;
esac
exit 0
'''
        for file in ['backend/mvnw', 'bin/docker', 'bin/java', 'bin/node', 'bin/npm',
                     'frontend/node_modules/.bin/next', 'frontend/node_modules/.bin/tsc', 'frontend/node_modules/.bin/eslint']:
            path = self.root / file
            path.write_text(stub)
            path.chmod(0o755)
        self.env = {**foreign_repository_env(), 'PATH': str(self.root / 'bin') + os.pathsep + os.environ['PATH'], 'FIXTURE_ROOT': str(self.root)}
        self.git('init', '-q')
        self.git('config', 'user.name', 'Quality Fixture')
        self.git('config', 'user.email', 'quality@example.invalid')
        (self.root / '.gitignore').write_text('.quality/\n__pycache__/\ncalls\nfail-stage\ntest-connection\nbin/\nfrontend/node_modules/\nbackend/target/\n')
        (self.root / 'change.txt').write_text('fixture base\n')
        # Infrastructure is a clean baseline: QUICK must not mistake the test's
        # untracked tool stubs for changes to every application component.
        self.git('add', '.')
        self.git('commit', '-m', 'fixture infrastructure')
        (self.root / 'change.txt').write_text('fixture change\n')
        self.git('add', 'change.txt')

    def change_code(self, kind):
        name, content = {'backend': ('backend/src/main/java/fixture/Probe.java', 'package fixture; class Probe {}\n'),
                         'frontend': ('frontend/src/app/Probe.tsx', 'export const value = "valid";\n')}[kind]
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        self.git('add', name)

    def git(self, *args):
        return subprocess.run(['git', *args], cwd=self.root, env=self.env, capture_output=True, text=True, check=True)

    def run_gate(self, failure='', mode='--full'):
        (self.root / 'fail-stage').write_text(failure)
        return subprocess.run([str(self.root / 'scripts/check-quality.sh'), mode], cwd=self.root, env=self.env, capture_output=True, text=True)


class WorkflowTests(WorkflowFixture, unittest.TestCase):
    def test_success_runs_all_required_stages(self):
        result = self.run_gate()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = (self.root / 'calls').read_text()
        for command in ['clean test-compile', 'verify', 'next typegen', 'tsc --noEmit', '--max-warnings=0', 'npm test', 'npm run build', 'docker rm -f fixture-container', 'browser-check --full', 'api-boundary-fixture', 'backup-restore-fixture']:
            self.assertIn(command, calls)

    def test_failures_propagate_and_never_report_success(self):
        stages = {'java-error': 'backend-compile', 'java-warning': 'backend-compile', 'java-null-warning': 'java-analysis', 'test-failure': 'backend-tests', 'typescript': 'typescript', 'eslint-warning': 'eslint', 'frontend-test': 'frontend-tests', 'build': 'frontend-build', 'browser-test': 'browser-tests', 'api-boundary-test': 'api-boundary-fixture', 'backup-test': 'backup-restore-fixture'}
        for failure, stage in stages.items():
            with self.subTest(failure=failure):
                result = self.run_gate(failure)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('[quality] PASSED', result.stdout)
                self.assertIn(f'FAILED at {stage}.', result.stderr)

    def test_missing_required_dependency_fails(self):
        (self.root / 'frontend/node_modules/.bin/tsc').unlink()
        result = self.run_gate(mode='--frontend')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Missing frontend dependency: tsc', result.stderr)

    def test_installer_preserves_existing_hooks_and_is_idempotent(self):
        installer = self.root / 'scripts/install-hooks.sh'
        self.git('config', 'core.hooksPath', '/custom/hooks')
        refused = subprocess.run([str(installer)], cwd=self.root, env=self.env, capture_output=True, text=True)
        self.assertNotEqual(refused.returncode, 0)
        self.assertEqual(self.git('config', '--get', 'core.hooksPath').stdout.strip(), '/custom/hooks')
        self.git('config', '--unset', 'core.hooksPath')
        legacy = self.root / '.git/hooks/commit-msg'
        legacy.write_text('# Existing user hook\n')
        self.assertNotEqual(subprocess.run([str(installer)], cwd=self.root, env=self.env, capture_output=True).returncode, 0)
        self.assertEqual(legacy.read_text(), '# Existing user hook\n')
        legacy.unlink()
        for _ in range(2):
            installed = subprocess.run([str(installer)], cwd=self.root, env=self.env, capture_output=True, text=True)
            self.assertEqual(installed.returncode, 0, installed.stderr)
        self.assertEqual(self.git('config', '--get', 'core.hooksPath').stdout.strip(), '.githooks')

    def test_hook_actually_blocks_and_then_allows_commit(self):
        self.change_code('backend')
        self.change_code('frontend')
        self.git('config', 'core.hooksPath', '.githooks')
        original_head = self.git('rev-parse', 'HEAD').stdout
        for failure in ('java-null-warning', 'typescript'):
            with self.subTest(failure=failure):
                (self.root / 'fail-stage').write_text(failure)
                failed = subprocess.run(['git', 'commit', '-m', 'blocked fixture'], cwd=self.root, env=self.env, capture_output=True, text=True)
                self.assertNotEqual(failed.returncode, 0)
                self.assertEqual(self.git('rev-parse', 'HEAD').stdout, original_head)
        before = self.git('write-tree').stdout
        (self.root / 'fail-stage').write_text('')
        passed = self.git('commit', '-m', 'allowed fixture')
        self.assertEqual(passed.returncode, 0)
        self.assertEqual(self.git('rev-parse', 'HEAD^{tree}').stdout, before)


    def test_partial_commit_runs_hook_and_preserves_unrelated_staged_blob(self):
        self.git('commit', '-m', 'fixture base')
        unrelated = self.root / 'unrelated.txt'
        # The commit index must exclude this unrelated staged token. Inspecting
        # the ordinary index instead would correctly fail the repository check.
        unrelated.write_text('ghp_' + 'c' * 36)
        self.git('add', 'unrelated.txt')
        before = self.git('ls-files', '--stage', '--', 'unrelated.txt').stdout
        original_contents = unrelated.read_bytes()
        (self.root / 'change.txt').write_text('task edit\n')
        (self.root / 'new-task.txt').write_text('new task file\n')
        self.git('add', 'new-task.txt')
        self.git('config', 'core.hooksPath', '.githooks')
        (self.root / 'fail-stage').write_text('')

        committed = self.git('commit', '--only', '-m', 'partial fixture', '--', 'change.txt', 'new-task.txt')

        self.assertIn('[quality] PASSED --quick', committed.stdout + committed.stderr)
        calls = (self.root / 'calls').read_text() if (self.root / 'calls').exists() else ''
        self.assertNotIn('npm run build', calls)
        self.assertNotIn('docker run', calls)
        self.assertEqual(self.git('ls-files', '--stage', '--', 'unrelated.txt').stdout, before)
        self.assertEqual(unrelated.read_bytes(), original_contents)
        self.assertEqual(self.git('diff', '--cached', '--name-only').stdout.splitlines(), ['unrelated.txt'])
        self.assertEqual(sorted(self.git('show', '--format=', '--name-only', 'HEAD').stdout.splitlines()), ['change.txt', 'new-task.txt'])


class SupplyChainContractTests(unittest.TestCase):
    """Static contract checks; no network access and no real CVE scan."""

    def test_dependabot_config_covers_all_ecosystems_without_auto_merge(self):
        config = (ROOT / '.github/dependabot.yml').read_text()
        self.assertIn('version: 2', config)
        for ecosystem in ('maven', 'npm', 'github-actions', 'docker'):
            self.assertIn(f'package-ecosystem: {ecosystem}', config)
        self.assertIn('interval: weekly', config)
        self.assertIn('groups:', config)
        self.assertNotIn('auto-merge', config)

    def test_security_workflow_is_independent_and_fails_on_findings(self):
        workflow = (ROOT / '.github/workflows/security.yml').read_text()
        self.assertIn('workflow_dispatch:', workflow)
        self.assertIn('schedule:', workflow)
        self.assertNotIn('\n  push:', workflow)
        self.assertNotIn('\n  pull_request:', workflow)
        self.assertNotIn('workflow_run:', workflow)
        self.assertIn('npm audit --omit=dev', workflow)
        self.assertIn('osv-scanner-action@v2.7.1', workflow)
        self.assertIn('--lockfile=backend/pom.xml', workflow)
        self.assertNotIn('continue-on-error', workflow)
        self.assertNotIn('secrets.', workflow)
        self.assertNotIn('upload-artifact', workflow)


class EnvironmentIsolationTests(unittest.TestCase):
    def test_foreign_hook_environment_cannot_redirect_fixture_repositories(self):
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            clean_env = foreign_repository_env()
            subprocess.run(['git', 'init', '-q', directory], env=clean_env, check=True)
            (parent / 'parent.txt').write_text('parent index must stay unchanged\n')
            subprocess.run(['git', '-C', directory, 'add', 'parent.txt'], env=clean_env, check=True)
            index = parent / '.git/index'
            before = index.read_bytes()
            inherited = {**os.environ,
                         'GIT_INDEX_FILE': str(index),
                         'GIT_DIR': str(parent / '.git'),
                         'GIT_WORK_TREE': str(parent),
                         'GIT_COMMON_DIR': str(parent / '.git'),
                         'GIT_OBJECT_DIRECTORY': str(parent / '.git/objects'),
                         'GIT_PREFIX': 'foreign-parent/',
                         'GIT_CONFIG_COUNT': '1',
                         'GIT_CONFIG_KEY_0': 'core.hooksPath',
                         'GIT_CONFIG_VALUE_0': str(parent / 'foreign-hooks'),
                         'QUALITY_BASE_REF': 'f' * 40}
            result = subprocess.run([sys.executable, str(Path(__file__).resolve()),
                                     'RepositoryTests.test_staged_secret_cannot_be_hidden_by_a_clean_worktree',
                                     'WorkflowTests.test_success_runs_all_required_stages',
                                     'WorkflowTests.test_partial_commit_runs_hook_and_preserves_unrelated_staged_blob'],
                                    cwd=ROOT, env=inherited, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(index.read_bytes(), before)
            self.assertEqual((parent / 'parent.txt').read_text(), 'parent index must stay unchanged\n')


if __name__ == '__main__':
    unittest.main()
