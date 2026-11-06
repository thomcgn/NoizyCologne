"""Protect the gate against successful compiler exits with remaining diagnostics."""
import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('java_check', ROOT / 'scripts/check-java.py')
java_check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(java_check)


class JavaReportTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.report = self.root / 'diagnostics.xml'

    def verify(self, content):
        self.report.write_text(content)
        with contextlib.redirect_stderr(io.StringIO()):
            return java_check.verify_report(self.report)

    def test_report_without_diagnostics_passes(self):
        self.assertTrue(self.verify('<compiler><sources><source/></sources></compiler>'))

    def test_every_diagnostic_blocks_including_information(self):
        for severity in ('ERROR', 'WARNING', 'INFO', 'UNRECOGNIZED'):
            with self.subTest(severity=severity):
                self.assertFalse(self.verify(f'<compiler><sources><source><problems><problem severity="{severity}"/></problems></source></sources></compiler>'))

    def test_missing_corrupt_and_incomplete_reports_fail(self):
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertFalse(java_check.verify_report(self.report))
        for content in ('<compiler>', '<unrelated/>', '<compiler/>'):
            with self.subTest(content=content):
                self.assertFalse(self.verify(content))

    def run_compiler(self, severity=None, returncode=0, write_report=True):
        target = self.root / 'backend/target'
        target.mkdir(parents=True, exist_ok=True)
        (target / 'quality-classpath.txt').write_text('fixture-classpath')
        ecj = target / 'ecj.jar'
        ecj.touch()

        def compile_fixture(command, check):
            self.assertFalse(check)
            report = Path(command[command.index('-log') + 1])
            # A stale clean report must never mask a compiler that produces no report.
            self.assertFalse(report.exists())
            if write_report:
                problem = f'<problem severity="{severity}"/>' if severity else ''
                report.write_text(f'<compiler><sources><source><problems>{problem}</problems></source></sources></compiler>')
            return subprocess.CompletedProcess(command, returncode)

        (target / 'quality-main.xml').write_text('<compiler><sources/></compiler>')
        with patch.object(java_check, 'ROOT', self.root), patch.object(java_check, 'ECJ', ecj), \
                patch.object(java_check.subprocess, 'run', side_effect=compile_fixture) as compiler, \
                contextlib.redirect_stderr(io.StringIO()):
            result = java_check.main()
            return result, compiler.call_count

    def test_successful_exit_with_info_still_fails_and_stops_test_compilation(self):
        result, calls = self.run_compiler(severity='INFO')
        self.assertNotEqual(result, 0)
        self.assertEqual(calls, 1)

    def test_clean_main_and_test_reports_are_required(self):
        result, calls = self.run_compiler()
        self.assertEqual(result, 0)
        self.assertEqual(calls, 2)

    def test_compiler_failure_and_missing_report_cannot_pass(self):
        for returncode in (0, 1):
            with self.subTest(returncode=returncode):
                result, calls = self.run_compiler(returncode=returncode, write_report=False)
                self.assertNotEqual(result, 0)
                self.assertEqual(calls, 1)


if __name__ == '__main__':
    unittest.main()
