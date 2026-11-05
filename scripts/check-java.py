#!/usr/bin/env python3
"""Run the pinned ECJ independently of javac, against main AND test sources."""
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ECJ = ROOT / 'backend/target/quality-tools/ecj-3.46.100.jar'


def verify_report(report):
    """Reject every reported problem, including ECJ's otherwise nonfatal INFOs."""
    try:
        root = ET.parse(report).getroot()
    except (OSError, ET.ParseError) as error:
        print(f'Cannot verify ECJ diagnostics: {report}: {error}', file=sys.stderr)
        return False
    if root.tag != 'compiler' or root.find('sources') is None:
        print(f'Incomplete ECJ diagnostic report: {report}', file=sys.stderr)
        return False
    problems = root.findall('.//problem')
    if problems:
        counts = {}
        for problem in problems:
            severity = problem.get('severity', 'UNKNOWN')
            counts[severity] = counts.get(severity, 0) + 1
        summary = ', '.join(f'{count} {severity}' for severity, count in sorted(counts.items()))
        print(f'ECJ diagnostics block the quality gate: {summary}. Report: {report}', file=sys.stderr)
        return False
    return True


def main():
    classpath = (ROOT / 'backend/target/quality-classpath.txt').read_text().strip()
    if not ECJ.is_file() or not classpath:
        raise RuntimeError('Missing ECJ/classpath; run scripts/check-quality.sh --backend')
    for source in ('main', 'test'):
        output = ROOT / f'backend/target/quality-{source}'
        output.mkdir(parents=True, exist_ok=True)
        report = ROOT / f'backend/target/quality-{source}.xml'
        report.unlink(missing_ok=True)
        paths = [str(ROOT / 'backend/target/quality-main'), classpath]
        command = ['java', '-jar', str(ECJ), '-21', '-proc:none', '-failOnWarning',
                   '-properties', str(ROOT / 'config/java-quality.prefs'),
                   '-classpath', ':'.join(paths), '-d', str(output),
                   '-log', str(report),
                   str(ROOT / f'backend/src/{source}/java')]
        result = subprocess.run(command, check=False)
        clean_report = verify_report(report)
        if result.returncode:
            return result.returncode
        if not clean_report:
            return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
