"""Real frontend failure fixtures; no tracked source file is edited."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
FRONTEND = ROOT / 'frontend'


class FrontendAnalysisTests(unittest.TestCase):
    def test_typescript_error_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix='.quality-fixture-', dir=FRONTEND) as directory:
            file = Path(directory) / 'probe.ts'
            file.write_text('export const value: string = 42;\n')
            result = subprocess.run([str(FRONTEND / 'node_modules/.bin/tsc'), '--noEmit', '--strict', '--skipLibCheck', str(file)], cwd=FRONTEND, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('TS2322', result.stdout + result.stderr)

    def test_eslint_warning_blocks_zero_warning_policy(self):
        with tempfile.TemporaryDirectory(prefix='.quality-fixture-', dir=FRONTEND) as directory:
            root = Path(directory)
            file = root / 'probe.js'
            file.write_text('const unused = 42;\n')
            config = root / 'eslint.config.mjs'
            # Warning severity is deliberate in this isolated fixture, never the application config.
            config.write_text('export default [{files: ["**/*.js"], rules: {"no-unused-vars": "warn"}}];\n')
            result = subprocess.run([str(FRONTEND / 'node_modules/.bin/eslint'), '--config', str(config), '--max-warnings=0', str(file)], cwd=FRONTEND, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('no-unused-vars', result.stdout + result.stderr)
            self.assertIn('warning', (result.stdout + result.stderr).lower())
