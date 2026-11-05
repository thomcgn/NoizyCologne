"""Real negative compiler fixtures, isolated from application code and databases."""
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ECJ = ROOT / 'backend/target/quality-tools/ecj-3.46.100.jar'


class JavaAnalysisTests(unittest.TestCase):
    def compile(self, source):
        self.assertTrue(ECJ.is_file(), 'Run backend quality preparation first; ECJ is required')
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'Probe.java'
            file.write_text(source)
            cp = (ROOT / 'backend/target/quality-classpath.txt').read_text().strip()
            return subprocess.run(['java', '-jar', str(ECJ), '-21', '-proc:none', '-failOnWarning',
                                   '-properties', str(ROOT / 'config/java-quality.prefs'),
                                   '-classpath', cp, '-d', directory, str(file)], capture_output=True, text=True)

    def test_valid_null_guard_passes(self):
        result = self.compile('import org.jspecify.annotations.Nullable; class Probe { int length(@Nullable String value) { return value == null ? 0 : value.length(); } }')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_syntax_unused_import_deprecation_and_null_access_fail(self):
        fixtures = [
            ('class Probe { int value = "wrong"; }', 'Type mismatch'),
            ('import java.util.Map; class Probe {}', 'never used'),
            ('class Old { @Deprecated static void old() {} } class Probe { void call() { Old.old(); } }', 'deprecated'),
            ('import org.jspecify.annotations.Nullable; class Probe { int length(@Nullable String value) { return value.length(); } }', 'null pointer'),
        ]
        for source, diagnostic in fixtures:
            with self.subTest(diagnostic=diagnostic):
                result = self.compile(source)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(diagnostic.lower(), (result.stdout + result.stderr).lower())

    def test_javac_warning_is_an_error(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'Probe.java'
            file.write_text('class Probe { java.util.List values; }')
            result = subprocess.run(['javac', '--release', '21', '-Xlint:all', '-Werror', str(file)], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('rawtypes', result.stderr)

    def test_actual_failing_junit_test_fails_maven(self):
        parent = ET.parse(ROOT / 'backend/pom.xml').getroot()
        version = parent.find('{*}parent/{*}version').text
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'src/test/java/ProbeTest.java'
            source.parent.mkdir(parents=True)
            source.write_text('import org.junit.jupiter.api.Test; class ProbeTest { @Test void fails() { org.junit.jupiter.api.Assertions.fail("intentional quality fixture failure"); } }')
            (root / 'pom.xml').write_text(f'''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>{version}</version><relativePath/></parent>
<groupId>quality.fixture</groupId><artifactId>failure</artifactId><version>1</version><properties><java.version>21</java.version></properties>
<dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency></dependencies></project>''')
            result = subprocess.run([str(ROOT / 'backend/mvnw'), '-B', '-ntp', '-q', '-f', str(root / 'pom.xml'), 'test'], cwd=ROOT, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('intentional quality fixture failure', result.stdout + result.stderr)
