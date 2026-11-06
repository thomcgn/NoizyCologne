"""QUICK scope and failure propagation in disposable Git repositories."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from test_quality_workflow import ROOT, WorkflowFixture, foreign_repository_env

spec = importlib.util.spec_from_file_location('quality_scope', ROOT / 'scripts/quality-scope.py')
scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scope)


class ScopeTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.env = foreign_repository_env()
        self.env.pop('QUALITY_BASE_REF', None)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Scope Fixture')
        self.git('config', 'user.email', 'scope@example.invalid')
        self.write('.gitignore', '.quality/\nbackend/target/\nfrontend/node_modules/\n')
        self.write('README.md', 'Documentation\n')
        self.write('frontend/src/page.tsx', 'export const value = "valid";\n')
        self.write('backend/src/main/java/fixture/Probe.java', 'package fixture; class Probe {}\n')
        self.write('backend/src/test/java/fixture/ProbeTests.java', 'package fixture; class ProbeTests { Probe value; }\n')
        self.write('backend/src/test/java/fixture/OtherTests.java', 'package fixture; class OtherTests {}\n')
        self.write('backend/src/test/java/fixture/ProbeIntegrationTests.java', 'package fixture; @SpringBootTest class ProbeIntegrationTests {}\n')
        self.git('add', '.')
        self.git('commit', '-m', 'scope base')

    def git(self, *arguments, env=None):
        return subprocess.run(['git', *arguments], cwd=self.root, env=env or self.env,
                              capture_output=True, text=True, check=True)

    def write(self, name, contents):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents)

    def plan(self, paths=None, **environment):
        with patch.dict(os.environ, {**self.env, **environment}, clear=True):
            return scope.plan(self.root, paths)

    def test_worktree_index_and_untracked_inputs_are_all_included(self):
        self.write('README.md', 'Unstaged documentation\n')
        self.write('backend/src/main/java/fixture/Probe.java', 'package fixture; class Probe { int value; }\n')
        self.git('add', 'backend/src/main/java/fixture/Probe.java')
        self.write('frontend/src/space in name.ts', 'export const value = 1;\n')
        self.write('.quality/ignored.ts', 'ignored output\n')
        result = self.plan()
        self.assertEqual(result['paths'], ['README.md', 'backend/src/main/java/fixture/Probe.java', 'frontend/src/space in name.ts'])
        self.assertTrue(result['backend'])
        self.assertTrue(result['frontend'])
        self.assertEqual(result['frontend_lint'], ['src/space in name.ts'])

    def test_docs_only_does_not_select_application_checks(self):
        self.write('README.md', 'Changed documentation\n')
        self.write('docs/new guide.md', 'More documentation\n')
        result = self.plan()
        self.assertFalse(result['backend'])
        self.assertFalse(result['frontend'])
        self.assertFalse(result['tooling'])
        self.assertEqual(result['backend_tests'], [])

    def test_browser_only_selects_worker_without_database_or_frontend(self):
        result = self.plan(['browser-worker/render.mjs'])
        self.assertTrue(result['browser'])
        self.assertFalse(result['backend'])
        self.assertFalse(result['frontend'])
        self.assertFalse(result['backend_integration'])

    def test_renames_keep_old_and_new_component_scopes(self):
        self.git('mv', 'frontend/src/page.tsx', 'backend/src/main/java/fixture/Renamed.java')
        result = self.plan()
        self.assertIn('frontend/src/page.tsx', result['paths'])
        self.assertIn('backend/src/main/java/fixture/Renamed.java', result['paths'])
        self.assertTrue(result['backend'])
        self.assertTrue(result['frontend'])
        self.assertTrue(result['frontend_lint_all'])

    def test_deleted_java_resets_old_outputs_and_checks_remaining_tests(self):
        self.git('rm', 'backend/src/main/java/fixture/Probe.java')
        result = self.plan()
        self.assertTrue(result['backend_reset_outputs'])
        self.assertEqual(result['backend_tests'], ['fixture.OtherTests', 'fixture.ProbeIntegrationTests', 'fixture.ProbeTests'])

    def test_main_change_selects_referencing_unit_without_database(self):
        result = self.plan(['backend/src/main/java/fixture/Probe.java'])
        self.assertEqual(result['backend_tests'], ['fixture.ProbeTests'])
        self.assertFalse(result['backend_integration'])
        self.assertFalse(result['backend_reset_outputs'])

    def test_service_change_uses_package_fallback_and_isolated_database(self):
        self.write('backend/src/main/java/fixture/ProbeService.java', 'package fixture; class ProbeService {}\n')
        result = self.plan(['backend/src/main/java/fixture/ProbeService.java'])
        self.assertEqual(result['backend_tests'], ['fixture.OtherTests', 'fixture.ProbeIntegrationTests', 'fixture.ProbeTests'])
        self.assertTrue(result['backend_integration'])

    def test_direct_integration_test_change_selects_only_that_test(self):
        result = self.plan(['backend/src/test/java/fixture/ProbeIntegrationTests.java'])
        self.assertEqual(result['backend_tests'], ['fixture.ProbeIntegrationTests'])
        self.assertTrue(result['backend_integration'])

    def test_surefire_prefix_and_testcase_names_are_selected(self):
        names = ['backend/src/test/java/fixture/TestProbe.java', 'backend/src/test/java/fixture/ProbeTestCase.java']
        for name in names:
            self.write(name, 'package fixture; class ' + Path(name).stem + ' {}\n')
        result = self.plan(names)
        self.assertEqual(result['backend_tests'], ['fixture.ProbeTestCase', 'fixture.TestProbe'])
        self.assertFalse(result['backend_integration'])

    def test_runtime_text_resource_is_not_mistaken_for_documentation(self):
        self.write('backend/src/main/resources/genres.txt', 'Runtime input\n')
        result = self.plan(['backend/src/main/resources/genres.txt'])
        self.assertTrue(result['backend'])
        self.assertEqual(result['backend_tests'], ['fixture.OtherTests', 'fixture.ProbeIntegrationTests', 'fixture.ProbeTests'])
        self.assertTrue(result['backend_integration'])

    def test_staged_and_unstaged_code_overlap_is_reported_but_docs_are_allowed(self):
        self.write('frontend/src/page.tsx', 'export const value = "index";\n')
        self.write('README.md', 'Staged documentation\n')
        self.git('add', 'frontend/src/page.tsx', 'README.md')
        self.write('frontend/src/page.tsx', 'export const value = "worktree";\n')
        self.write('README.md', 'Unstaged documentation\n')
        self.assertEqual(self.plan()['index_worktree_overlap'], ['frontend/src/page.tsx'])

    def test_staged_deletion_recreated_as_untracked_code_is_a_different_commit_input(self):
        self.git('rm', 'frontend/src/page.tsx')
        self.write('frontend/src/page.tsx', 'export const value = "only in worktree";\n')
        self.assertEqual(self.plan()['index_worktree_overlap'], ['frontend/src/page.tsx'])

    def test_active_alternative_index_is_honored_and_staged_blobs_are_preserved(self):
        ordinary_index = self.root / '.git/index'
        before = ordinary_index.read_bytes()
        alternate = self.root / '.git/quality-fixture-index'
        env = {**self.env, 'GIT_INDEX_FILE': str(alternate)}
        self.git('read-tree', 'HEAD', env=env)
        self.write('backend/src/main/java/fixture/Probe.java', 'package fixture; class Probe { int staged; }\n')
        self.git('add', 'backend/src/main/java/fixture/Probe.java', env=env)
        alternate_before = self.git('ls-files', '--stage', env=env).stdout
        result = self.plan(GIT_INDEX_FILE=str(alternate))
        self.assertEqual(result['paths'], ['backend/src/main/java/fixture/Probe.java'])
        self.assertEqual(result['index_worktree_overlap'], [])
        self.assertEqual(ordinary_index.read_bytes(), before)
        # git diff may refresh stat-cache entries; staged content is immutable.
        self.assertEqual(self.git('ls-files', '--stage', env=env).stdout, alternate_before)

    def test_missing_or_invalid_ci_base_fails_toward_all_components(self):
        for base in ('', '0' * 40, 'unavailable-ref'):
            with self.subTest(base=base):
                result = self.plan(QUALITY_BASE_REF=base)
                self.assertTrue(result['base_fallback'])
                self.assertTrue(result['backend'])
                self.assertTrue(result['frontend'])
                self.assertTrue(result['tooling'])
                self.assertTrue(result['backend_integration'])

    def test_ci_base_includes_committed_changes_in_a_clean_checkout(self):
        base = self.git('rev-parse', 'HEAD').stdout.strip()
        self.write('frontend/src/page.tsx', 'export const value = "commit";\n')
        self.git('add', 'frontend/src/page.tsx')
        self.git('commit', '-m', 'frontend change')
        result = self.plan(QUALITY_BASE_REF=base)
        self.assertFalse(result['base_fallback'])
        self.assertEqual(result['paths'], ['frontend/src/page.tsx'])
        self.assertTrue(result['frontend'])
        self.assertFalse(result['backend'])


class QuickWorkflowTests(WorkflowFixture, unittest.TestCase):
    def calls(self):
        path = self.root / 'calls'
        return path.read_text() if path.exists() else ''

    def test_docs_only_quick_has_no_application_build_or_database(self):
        result = self.run_gate(mode='--quick')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('[quality] PASSED --quick', result.stdout)
        for command in ('mvnw ', 'npm ', 'tsc ', 'next ', 'docker '):
            self.assertNotIn(command, self.calls())

    def test_frontend_quick_runs_types_lint_and_tests_without_backend_or_build(self):
        self.change_code('frontend')
        result = self.run_gate(mode='--quick')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = self.calls()
        for command in ('next typegen', 'tsc --noEmit', 'npm run lint', '--max-warnings=0', 'src/app/Probe.tsx', 'npm test'):
            self.assertIn(command, calls)
        for command in ('mvnw ', 'docker ', 'npm run build'):
            self.assertNotIn(command, calls)

    def test_backend_quick_is_incremental_selects_units_and_avoids_database(self):
        self.change_code('backend')
        result = self.run_gate(mode='--quick')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = self.calls()
        for command in ('test-compile', 'surefire:test', '-Dtest=fixture.ProbeTests'):
            self.assertIn(command, calls)
        self.assertIn('Simulated Java analysis fixture', result.stdout)
        for command in (' clean ', ' verify ', 'docker ', 'npm '):
            self.assertNotIn(command, calls)

    def test_unit_scope_never_inherits_development_database_settings(self):
        self.change_code('backend')
        self.env.update(DB_URL='jdbc:postgresql://must-not-connect.invalid/development',
                        SPRING_DATASOURCE_URL='jdbc:postgresql://must-not-connect.invalid/development',
                        SPRING_FLYWAY_URL='jdbc:postgresql://must-not-connect.invalid/development')
        result = self.run_gate(mode='--quick')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.root / 'test-connection').read_text().splitlines(),
                         ['jdbc:postgresql://127.0.0.1:1/noizy_quality_unavailable'] * 3)
        self.assertNotIn('docker ', self.calls())

    def test_integration_scope_uses_disposable_database_and_cleans_up_after_failure(self):
        path = self.root / 'backend/src/test/java/fixture/ProbeIntegrationTests.java'
        path.write_text('package fixture; @SpringBootTest class ProbeIntegrationTests {}\n')
        self.git('add', str(path.relative_to(self.root)))
        result = self.run_gate('test-failure', '--backend')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('FAILED at backend-tests.', result.stderr)
        self.assertIn('docker run --rm -d', self.calls())
        self.assertIn('docker rm -f fixture-container', self.calls())
        self.assertEqual((self.root / 'test-connection').read_text().splitlines(),
                         ['jdbc:postgresql://127.0.0.1:55435/noizy_quality'] * 3)

    def test_quick_defers_database_tests_explicitly_but_compiles_them(self):
        path = self.root / 'backend/src/test/java/fixture/ProbeIntegrationTests.java'
        path.write_text('package fixture; @SpringBootTest class ProbeIntegrationTests {}\n')
        self.git('add', str(path.relative_to(self.root)))
        result = self.run_gate(mode='--quick')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('Deferred database integration tests', result.stdout)
        self.assertIn('fixture.ProbeIntegrationTests', result.stdout)
        self.assertIn('test-compile', self.calls())
        self.assertNotIn('docker ', self.calls())
        self.assertNotIn('surefire:test', self.calls())

    def test_backend_standard_uses_incremental_compile_and_full_database_tests(self):
        result = self.run_gate(mode='--backend')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('test-compile', self.calls())
        self.assertNotIn(' clean ', self.calls())
        self.assertIn(' verify ', self.calls())
        self.assertIn('docker run', self.calls())

    def test_database_cleanup_failure_cannot_report_a_successful_gate(self):
        path = self.root / 'backend/src/test/java/fixture/ProbeIntegrationTests.java'
        path.write_text('package fixture; @SpringBootTest class ProbeIntegrationTests {}\n')
        self.git('add', str(path.relative_to(self.root)))
        result = self.run_gate('cleanup', '--backend')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('FAILED at database-cleanup.', result.stderr)
        self.assertNotIn('[quality] PASSED', result.stdout)

    def test_quick_propagates_compiler_warning_null_safety_and_test_failures(self):
        self.change_code('backend')
        self.change_code('frontend')
        for failure in ('java-error', 'java-warning', 'java-null-warning', 'test-failure', 'typescript', 'eslint-warning', 'frontend-test'):
            with self.subTest(failure=failure):
                result = self.run_gate(failure, '--quick')
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertNotIn('[quality] PASSED', result.stdout)
                self.assertIn('[quality] FAILED', result.stderr)

    def test_quick_blocks_staged_source_hidden_by_cleaner_worktree(self):
        self.change_code('frontend')
        file = self.root / 'frontend/src/app/Probe.tsx'
        file.write_text('export const value: string = 42;\n')
        self.git('add', 'frontend/src/app/Probe.tsx')
        file.write_text('export const value: string = "clean worktree";\n')
        before = self.git('write-tree').stdout
        result = self.run_gate(mode='--quick')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('[quality] PASSED', result.stdout)
        self.assertEqual(self.git('write-tree').stdout, before)

    def test_quick_timings_record_success_and_failed_stage_without_credentials(self):
        self.change_code('frontend')
        for failure in ('', 'typescript'):
            with self.subTest(failure=failure):
                result = self.run_gate(failure, '--quick')
                report_path = max((self.root / '.quality').glob('run.*/timings.json'), key=lambda path: path.stat().st_mtime_ns)
                report = json.loads(report_path.read_text())
                self.assertEqual(report['mode'], '--quick')
                self.assertEqual(report['exit_code'], result.returncode)
                self.assertGreater(report['duration_ms'], 0)
                self.assertTrue(report['steps'])
                self.assertEqual(report['failed_step'] is None, not failure)
                for step in report['steps']:
                    self.assertGreaterEqual(step['duration_ms'], 0)
                    self.assertIn('exit_code', step)
                self.assertNotIn('POSTGRES_PASSWORD', report_path.read_text())
                self.assertNotIn('DB_PASSWORD', report_path.read_text())


if __name__ == '__main__':
    unittest.main()
