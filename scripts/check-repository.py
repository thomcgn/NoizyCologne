#!/usr/bin/env python3
"""Check both staged blobs and tracked worktree files without printing secret contents."""
from pathlib import Path
import os
import re
import subprocess
import sys

SECRET_PATTERNS = {
    'private key': re.compile(r'-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----'),
    'GitHub token': re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,})\b'),
    'AWS access key': re.compile(r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'),
    'Slack token': re.compile(r'\bxox[baprs]-[A-Za-z0-9-]{20,}\b'),
    'Google API key': re.compile(r'\bAIza[0-9A-Za-z_-]{35}\b'),
}
CONFLICT = re.compile(r'^(?:<{7}(?: |$)|={7}$|>{7}(?: |$)|\|{7}(?: |$))', re.MULTILINE)


def findings(name, data):
    text = data.decode('utf-8', errors='replace')
    problems = []
    if CONFLICT.search(text):
        problems.append('merge conflict marker')
    problems.extend(label for label, pattern in SECRET_PATTERNS.items() if pattern.search(text))
    base = Path(name).name
    if (base == '.env' or base.startswith('.env.')) and not base.endswith('.example'):
        problems.append('non-example environment file in index')
    return problems


def main():
    root = Path(subprocess.check_output(['git', 'rev-parse', '--show-toplevel'], text=True).strip())
    paths = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).split(b'\0')
    failed = False
    for raw in sorted(set(paths) - {b''}):
        name = os.fsdecode(raw)
        blob = subprocess.check_output(['git', 'show', f':{name}'], cwd=root)
        versions = [('index', blob)]
        path = root / name
        if path.is_symlink():
            versions.append(('worktree', os.fsencode(os.readlink(path))))
        elif path.is_file():
            versions.append(('worktree', path.read_bytes()))
        for location, data in versions:
            for problem in findings(name, data):
                failed = True
                print(f'{location}: {name}: {problem}', file=sys.stderr)
    if failed:
        return 1
    print('Tracked worktree and staged blobs: no conflict markers or configured secret patterns found.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
