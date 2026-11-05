"""Isolated workflow/hook tests. Tool failures here are deliberate simulations."""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('repository_check', ROOT / 'scripts/check-repository.py')
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


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
            subprocess.run(['git', 'init', '-q', directory], check=True)
            file = root / 'config.txt'
            file.write_text('ghp_' + 'b' * 36)
            subprocess.run(['git', '-C', directory, 'add', 'config.txt'], check=True)
            file.write_text('clean worktree')
            result = subprocess.run(['python3', str(ROOT / 'scripts/check-repository.py')], cwd=root, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('index: config.txt: GitHub token', result.stderr)
            self.assertNotIn('b' * 36, result.stderr)


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for directory in ['scripts/tests', '.githooks', 'backend/src/main/java', 'frontend/node_modules/.bin', 'config', 'bin']:
            (self.root / directory).mkdir(parents=True)
        for file in ['scripts/check-quality.sh', 'scripts/check-repository.py', 'scripts/install-hooks.sh', '.githooks/pre-commit']:
            shutil.copy2(ROOT / file, self.root / file)
        (self.root / 'scripts/check-java.py').write_text('from pathlib import Path\nimport sys\nprint("Simulated Java analysis fixture")\nsys.exit(1 if Path("fail-stage").read_text() == "java-null-warning" else 0)\n')
        # Do not recursively invoke this workflow suite inside its miniature fixture repository.
        (self.root / 'scripts/tests/test_quality_fixture.py').write_text('import unittest\nclass Fixture(unittest.TestCase):\n def test_fixture(self): self.assertTrue(True)\n')
        (self.root / 'scripts/tests/test_java_analysis.py').write_text('import unittest\nclass Fixture(unittest.TestCase):\n def test_fixture(self): self.assertTrue(True)\n')
        (self.root / 'scripts/tests/test_frontend_analysis.py').write_text('import unittest\nclass Fixture(unittest.TestCase):\n def test_fixture(self): self.assertTrue(True)\n')
        stub = '''#!/bin/sh
set -eu
name=$(basename "$0")
fail=$(cat "$FIXTURE_ROOT/fail-stage" 2>/dev/null || true)
printf '%s %s\\n' "$name" "$*" >> "$FIXTURE_ROOT/calls"
case "$name:$*" in
 docker:run*) printf '%s\\n' fixture-container; exit 0 ;;
 docker:port*) printf '%s\\n' 127.0.0.1:55435; exit 0 ;;
esac
case "$name:$*:$fail" in
 mvnw:*test-compile*:java-error|mvnw:*test-compile*:java-warning|mvnw:*verify*:test-failure|tsc:*:typescript|npm:*lint*:eslint-warning|npm:*test*:frontend-test|npm:*build*:build) exit 1 ;;
esac
exit 0
'''
        for file in ['backend/mvnw', 'bin/docker', 'bin/java', 'bin/node', 'bin/npm',
                     'frontend/node_modules/.bin/next', 'frontend/node_modules/.bin/tsc', 'frontend/node_modules/.bin/eslint']:
            path = self.root / file
            path.write_text(stub)
            path.chmod(0o755)
        self.env = {**os.environ, 'PATH': str(self.root / 'bin') + os.pathsep + os.environ['PATH'], 'FIXTURE_ROOT': str(self.root)}
        self.git('init', '-q')
        self.git('config', 'user.name', 'Quality Fixture')
        self.git('config', 'user.email', 'quality@example.invalid')
        (self.root / 'change.txt').write_text('fixture change\n')
        self.git('add', 'change.txt')

    def git(self, *args):
        return subprocess.run(['git', *args], cwd=self.root, env=self.env, capture_output=True, text=True, check=True)

    def run_gate(self, failure='', mode='--full'):
        (self.root / 'fail-stage').write_text(failure)
        return subprocess.run([str(self.root / 'scripts/check-quality.sh'), mode], cwd=self.root, env=self.env, capture_output=True, text=True)

    def test_success_runs_all_required_stages(self):
        result = self.run_gate()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = (self.root / 'calls').read_text()
        for command in ['clean test-compile', 'verify', 'next typegen', 'tsc --noEmit', '--max-warnings=0', 'npm test', 'npm run build', 'docker rm -f fixture-container']:
            self.assertIn(command, calls)

    def test_failures_propagate_and_never_report_success(self):
        stages = {'java-error': 'backend-compile', 'java-warning': 'backend-compile', 'java-null-warning': 'java-analysis', 'test-failure': 'backend-tests', 'typescript': 'typescript', 'eslint-warning': 'eslint', 'frontend-test': 'frontend-tests', 'build': 'frontend-build'}
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
        self.git('config', 'core.hooksPath', '.githooks')
        for failure in ('java-null-warning', 'typescript'):
            with self.subTest(failure=failure):
                (self.root / 'fail-stage').write_text(failure)
                failed = subprocess.run(['git', 'commit', '-m', 'blocked fixture'], cwd=self.root, env=self.env, capture_output=True, text=True)
                self.assertNotEqual(failed.returncode, 0)
                self.assertNotEqual(subprocess.run(['git', 'rev-parse', '--verify', 'HEAD'], cwd=self.root, capture_output=True).returncode, 0)
        before = self.git('write-tree').stdout
        (self.root / 'fail-stage').write_text('')
        passed = self.git('commit', '-m', 'allowed fixture')
        self.assertEqual(passed.returncode, 0)
        self.assertEqual(self.git('rev-parse', 'HEAD^{tree}').stdout, before)


if __name__ == '__main__':
    unittest.main()
