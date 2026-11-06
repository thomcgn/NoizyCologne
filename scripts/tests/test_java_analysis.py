"""Real negative compiler fixtures, isolated from application code and databases."""
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ECJ = ROOT / 'backend/target/quality-tools/ecj-3.46.100.jar'
JDK_GENERIC_READS = (
    ('java.util.List', 'List', 'value.get(0)'),
    ('java.util.List', 'List', 'value.getFirst()'),
    ('java.util.List', 'List', 'value.getLast()'),
    ('java.util.Optional', 'Optional', 'value.get()'),
    ('java.util.Optional', 'Optional', 'value.orElseThrow()'),
    ('java.util.Optional', 'Optional', 'value.orElseThrow(IllegalStateException::new)'),
    ('java.util.concurrent.Future', 'Future', 'value.get()'),
    ('java.util.concurrent.Future', 'Future', 'value.get(1, java.util.concurrent.TimeUnit.SECONDS)'),
)


class JavaAnalysisTests(unittest.TestCase):
    def compile(self, source, *, legacy_source=None, binary_sources=None):
        self.assertTrue(ECJ.is_file(), 'Run backend quality preparation first; ECJ is required')
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'Probe.java'
            file.write_text(source)
            cp = (ROOT / 'backend/target/quality-classpath.txt').read_text().strip()
            libraries = dict(binary_sources or {})
            if legacy_source:
                libraries['Legacy.java'] = legacy_source
            if libraries:
                files = []
                for name, content in libraries.items():
                    library = Path(directory) / 'library-src' / name
                    library.parent.mkdir(parents=True, exist_ok=True)
                    library.write_text(content)
                    files.append(str(library))
                classes = Path(directory) / 'library-classes'
                compiled = subprocess.run(['javac', '--release', '21', '-proc:none', '-classpath', cp, '-d', str(classes), *files], capture_output=True, text=True)
                self.assertEqual(compiled.returncode, 0, compiled.stderr)
                cp += ':' + str(classes)
            return subprocess.run(['java', '-jar', str(ECJ), '-21', '-proc:none', '-failOnWarning',
                                   '-properties', str(ROOT / 'config/java-quality.prefs'),
                                   '-classpath', cp, '-d', directory, str(file)], capture_output=True, text=True)

    def test_valid_null_guard_passes(self):
        result = self.compile('import org.jspecify.annotations.Nullable; class Probe { int length(@Nullable String value) { return value == null ? 0 : value.length(); } }')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_preserved_annotations_and_checked_library_return_pass(self):
        result = self.compile('import java.util.List; import java.util.Objects; import org.jspecify.annotations.NonNull; class Probe { List<@NonNull String> retain(List<@NonNull String> input) { return input; } @NonNull String first(List<@NonNull String> input) { return Objects.requireNonNull(input.get(0)); } }')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn('INFO', result.stdout + result.stderr)

    def test_null_conversion_and_legacy_generic_return_fail(self):
        fixtures = [
            ('import java.util.List; import org.jspecify.annotations.NonNull; class Probe { List<String> erase(List<@NonNull String> input) { return input; } }', 'Unsafe null type conversion', None),
            ('import org.jspecify.annotations.NonNull; class Probe { @NonNull String first(Legacy<@NonNull String> input) { return input.value(); } }', 'Unsafe interpretation', 'class Legacy<T> { T value() { throw new IllegalStateException(); } }'),
            ('import org.jspecify.annotations.NonNull; class Probe { @NonNull String value(String input) { return input; } }', 'Null type safety', None),
        ]
        for source, diagnostic, legacy_source in fixtures:
            with self.subTest(diagnostic=diagnostic):
                result = self.compile(source, legacy_source=legacy_source)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(diagnostic.lower(), (result.stdout + result.stderr).lower())

    def test_nullable_jdk_results_cannot_satisfy_nonnull_contracts(self):
        for imported, container, expression in JDK_GENERIC_READS:
            with self.subTest(expression=expression, container=container):
                result = self.compile(f'import {imported}; import org.jspecify.annotations.Nullable; import org.jspecify.annotations.NonNull; class Probe {{ @NonNull String first({container}<@Nullable String> value) throws Exception {{ return {expression}; }} }}')
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('null type mismatch', (result.stdout + result.stderr).lower())

    def test_unannotated_jdk_generic_returns_require_explicit_guards(self):
        for imported, container, expression in JDK_GENERIC_READS:
            with self.subTest(expression=expression, container=container):
                result = self.compile(f'import {imported}; import java.util.Objects; import org.jspecify.annotations.NonNull; class Probe {{ @NonNull String first({container}<@NonNull String> value) throws Exception {{ return Objects.requireNonNull({expression}); }} }}')
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertNotIn('INFO', result.stdout + result.stderr)

    def test_binary_nullmarked_defaults_and_flexible_type_variables(self):
        library = {
            'marked/package-info.java': '@org.jspecify.annotations.NullMarked package marked;',
            'marked/Api.java': '''package marked; public class Api {
                public static String required() { return "value"; }
                public static void consume(String value) { }
                public static @org.jspecify.annotations.Nullable String optional() { return null; }
                public static <T extends @org.jspecify.annotations.Nullable Object> T flexible(T value) { return value; }
            }''',
        }
        fixtures = [
            ('import org.jspecify.annotations.NonNull; class Probe { @NonNull String value() { return marked.Api.required(); } }', None),
            ('class Probe { void call() { marked.Api.consume(null); } }', 'null type mismatch'),
            ('import org.jspecify.annotations.NonNull; class Probe { @NonNull String value() { return marked.Api.optional(); } }', 'null type mismatch'),
            ('import org.jspecify.annotations.Nullable; class Probe { @Nullable String value(@Nullable String input) { return marked.Api.flexible(input); } }', None),
            ('import org.jspecify.annotations.Nullable; import org.jspecify.annotations.NonNull; class Probe { @NonNull String value(@Nullable String input) { return marked.Api.flexible(input); } }', 'null type safety'),
        ]
        for source, diagnostic in fixtures:
            with self.subTest(source=source):
                result = self.compile(source, binary_sources=library)
                if diagnostic:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(diagnostic, (result.stdout + result.stderr).lower())
                else:
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertNotIn('INFO', result.stdout + result.stderr)

    def test_null_callback_cannot_fill_nonnull_list_elements(self):
        result = self.compile('import java.util.List; import org.jspecify.annotations.NonNull; class Probe { void update(List<@NonNull String> value) { value.replaceAll(old -> null); } }')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('null type mismatch', (result.stdout + result.stderr).lower())

    def test_nullmarked_default_requires_a_nullable_result_guard(self):
        prefix = 'import org.jspecify.annotations.NullMarked; import org.jspecify.annotations.Nullable; @NullMarked class Probe { String value(@Nullable String input) { return '
        unsafe = self.compile(prefix + 'input; } }')
        self.assertNotEqual(unsafe.returncode, 0)
        self.assertIn('null type mismatch', (unsafe.stdout + unsafe.stderr).lower())
        guarded = self.compile(prefix + 'input == null ? "" : input; } }')
        self.assertEqual(guarded.returncode, 0, guarded.stderr)

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
