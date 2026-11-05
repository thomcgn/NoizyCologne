#!/usr/bin/env python3
"""Run the pinned ECJ independently of javac, against main AND test sources."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
ECJ = ROOT / 'backend/target/quality-tools/ecj-3.46.100.jar'


def main():
    classpath = (ROOT / 'backend/target/quality-classpath.txt').read_text().strip()
    if not ECJ.is_file() or not classpath:
        raise RuntimeError('Missing ECJ/classpath; run scripts/check-quality.sh --backend')
    for source in ('main', 'test'):
        output = ROOT / f'backend/target/quality-{source}'
        output.mkdir(parents=True, exist_ok=True)
        paths = [str(ROOT / 'backend/target/quality-main'), classpath]
        command = ['java', '-jar', str(ECJ), '-21', '-proc:none', '-failOnWarning',
                   '-properties', str(ROOT / 'config/java-quality.prefs'),
                   '-classpath', ':'.join(paths), '-d', str(output),
                   str(ROOT / f'backend/src/{source}/java')]
        result = subprocess.run(command, check=False)
        if result.returncode:
            return result.returncode
    return 0


if __name__ == '__main__':
    sys.exit(main())
