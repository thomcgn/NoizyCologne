#!/usr/bin/env python3
"""Conservative QUICK scope from the active Git index, worktree and CI base."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
DOCUMENTS = {'.md', '.rst', '.txt'}
FRONTEND_LINT = {'.js', '.jsx', '.mjs', '.cjs', '.ts', '.tsx', '.mts', '.cts'}


def is_document(name):
    if name.startswith(('backend/src/', 'frontend/src/', 'frontend/public/', 'scripts/tests/')):
        return False
    return Path(name).suffix.lower() in DOCUMENTS


def git_paths(root, *arguments):
    result = subprocess.run(['git', *arguments], cwd=root, check=True, capture_output=True)
    return [os.fsdecode(value) for value in result.stdout.split(b'\0') if value]


def changed_paths(root):
    """Do not replace the hook's GIT_INDEX_FILE (including commit --only)."""
    paths = set(git_paths(root, 'diff', '--name-only', '-z', '--no-renames', '--'))
    paths.update(git_paths(root, 'diff', '--cached', '--name-only', '-z', '--no-renames', '--'))
    paths.update(git_paths(root, 'ls-files', '--others', '--exclude-standard', '-z'))
    return sorted(paths)


def classify_paths(paths):
    result = {'backend': False, 'frontend': False, 'tooling': False, 'browser': False,
              'frontend_lint': [], 'frontend_lint_all': False, 'java_analysis_changed': False, 'frontend_analysis_changed': False}
    lint = set()
    for name in paths:
        path = Path(name)
        if is_document(name):
            continue
        if name.startswith('browser-worker/') or name in ('scripts/check-browser.sh', 'deploy/server/compose.browser.yaml'):
            result['browser'] = True
        elif name.startswith('backend/'):
            result['backend'] = True
        elif name.startswith('frontend/'):
            result['frontend'] = True
            relative = name.removeprefix('frontend/')
            if relative.startswith(('src/', 'tests/')) and path.suffix in FRONTEND_LINT:
                lint.add(relative)
            elif path.suffix in FRONTEND_LINT or relative in ('package.json', 'package-lock.json', 'tsconfig.json'):
                result['frontend_lint_all'] = True
        elif name.startswith(('scripts/', '.githooks/', '.github/workflows/')) or name == '.github/branch-protection-main.json':
            result['tooling'] = True
            if name in ('scripts/check-java.py', 'scripts/tests/test_java_analysis.py'):
                result['backend'] = True
        elif name in ('config/java-quality.prefs', '.vscode/settings.json'):
            result['tooling'] = True
            result['backend'] = True
        elif name in ('.gitignore', '.gitattributes', '.editorconfig', '.vscode/extensions.json'):
            result['tooling'] = True
        else:
            # Unknown build/configuration inputs must not silently receive a docs-only pass.
            result.update(backend=True, frontend=True, browser=True, tooling=True, frontend_lint_all=True, java_analysis_changed=True, frontend_analysis_changed=True)
    result['java_analysis_changed'] = result['java_analysis_changed'] or any(name in ('scripts/check-java.py', 'scripts/tests/test_java_analysis.py', 'config/java-quality.prefs', 'backend/pom.xml') for name in paths)
    result['frontend_analysis_changed'] = result['frontend_lint_all'] or 'scripts/tests/test_frontend_analysis.py' in paths
    result['frontend_lint'] = sorted(lint)
    return result


def ci_paths(root):
    """An unavailable push base requires both modules, never an empty CI check."""
    base = os.environ.get('QUALITY_BASE_REF')
    if base is None:
        return [], False
    if not base or not base.strip('0'):
        return [], True
    resolved = subprocess.run(['git', 'rev-parse', '--verify', '--end-of-options', f'{base}^{{commit}}'],
                              cwd=root, capture_output=True, text=True)
    if resolved.returncode:
        return [], True
    try:
        return git_paths(root, 'diff', '--name-only', '-z', '--no-renames', resolved.stdout.strip(), 'HEAD', '--'), False
    except subprocess.CalledProcessError:
        return [], True


def code_input(name):
    return not is_document(name)


def database_test(path):
    return ('Integration' in path.name or path.stem.endswith('IT') or
            re.search(r'@(?:SpringBootTest|DataJpaTest|JdbcTest|DataJdbcTest|SpringJUnitConfig)\b', path.read_text()) is not None)


def test_classes(root, paths, all_backend=False):
    tests_root = root / 'backend/src/test/java'
    tests = sorted(tests_root.rglob('*.java')) if tests_root.exists() else []
    runnable = [path for path in tests if path.stem.startswith('Test') or
                path.stem.endswith(('Test', 'Tests', 'TestCase', 'IT'))]
    chosen = set(runnable) if all_backend else set()
    reset = all_backend
    for name in paths:
        if not name.startswith('backend/') or not code_input(name):
            continue
        file = root / name
        if not file.exists():
            # Deleted/renamed Java or resources must not leave executable stale bytecode.
            reset = True
            chosen.update(runnable)
            continue
        if name.startswith('backend/src/test/java/') and file.suffix == '.java':
            if file in runnable:
                chosen.add(file)
            else:
                chosen.update(path for path in runnable if path.is_relative_to(file.parent))
        elif name.startswith('backend/src/main/java/') and file.suffix == '.java':
            package = file.parent.relative_to(root / 'backend/src/main/java')
            same_package = [path for path in runnable if path.is_relative_to(tests_root / package)]
            stem = file.stem
            references = [path for path in runnable
                          if re.search(r'\b' + re.escape(stem) + r'\b', path.read_text())]
            chosen.update(references)
            if not references or stem.endswith(('Controller', 'Service', 'Repository')) or stem == 'package-info':
                chosen.update(same_package)
        else:
            # POM, wrapper, shared test resources and runtime configuration affect the module.
            chosen.update(runnable)
            reset = True
    names = sorted('.'.join(path.relative_to(tests_root).with_suffix('').parts) for path in chosen)
    # Existing backend integration tests use SpringBootTest. Other standard Spring
    # database annotations and naming conventions fail conservatively toward isolation.
    integration = any(database_test(path) for path in chosen)
    return names, integration, reset


def plan(root, paths=None):
    paths = changed_paths(root) if paths is None else sorted(set(paths))
    base_paths, fallback = ci_paths(root)
    paths = sorted(set(paths) | set(base_paths))
    result = classify_paths(paths)
    result.update(paths=paths, base_fallback=fallback)
    if fallback:
        result.update(backend=True, frontend=True, browser=True, tooling=True, frontend_lint_all=True, java_analysis_changed=True, frontend_analysis_changed=True)
    deleted_lint = [name for name in result['frontend_lint'] if not (root / 'frontend' / name).is_file()]
    if deleted_lint:
        result['frontend_lint_all'] = True
    result['frontend_lint'] = [name for name in result['frontend_lint'] if name not in deleted_lint]
    staged = set(git_paths(root, 'diff', '--cached', '--name-only', '-z', '--no-renames', '--'))
    unstaged = set(git_paths(root, 'diff', '--name-only', '-z', '--no-renames', '--'))
    # A staged deletion recreated as an untracked file is also a different
    # worktree version; compiling it must not certify its removal from the index.
    unstaged.update(git_paths(root, 'ls-files', '--others', '--exclude-standard', '-z'))
    result['index_worktree_overlap'] = sorted(name for name in staged & unstaged if code_input(name))
    shared_backend = fallback or any(name in ('config/java-quality.prefs', '.vscode/settings.json') or
                                      (not name.startswith(('backend/', 'frontend/', 'browser-worker/', 'scripts/', '.githooks/', '.github/workflows/')) and
                                       code_input(name) and name not in ('.gitignore', '.gitattributes', '.editorconfig', '.vscode/extensions.json', '.github/branch-protection-main.json'))
                                      for name in paths)
    names, integration, reset = test_classes(root, paths, shared_backend)
    result.update(backend_tests=names, backend_integration=integration, backend_reset_outputs=reset)
    tests_root = root / 'backend/src/test/java'
    deferred = [name for name in names if database_test(tests_root / (name.replace('.', '/') + '.java'))]
    result.update(backend_unit_tests=[name for name in names if name not in deferred], backend_deferred_tests=deferred)
    return result


if __name__ == '__main__':
    try:
        print(json.dumps(plan(ROOT), indent=2))
    except (OSError, subprocess.CalledProcessError) as error:
        print(f'Cannot determine quality scope: {error}', file=sys.stderr)
        sys.exit(1)
