#!/usr/bin/env python3
"""Write step timings without commands, environment values or credentials."""
import json
from pathlib import Path
import sys
import time


def main(arguments):
    operation, file, *values = arguments
    path = Path(file)
    now = time.monotonic_ns()
    if operation == 'init':
        report = {'mode': values[0], 'started_monotonic_ns': now, 'steps': []}
    else:
        report = json.loads(path.read_text())
        if operation == 'step':
            name, start, status = values
            report['steps'].append({'name': name, 'duration_ms': round((now - int(start)) / 1_000_000, 3),
                                    'exit_code': int(status)})
        elif operation == 'finish':
            status, step = values
            report.update(exit_code=int(status), failed_step=step if int(status) else None,
                          duration_ms=round((now - report['started_monotonic_ns']) / 1_000_000, 3))
        else:
            raise ValueError(f'Unknown report operation: {operation}')
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(report, indent=2) + '\n')
    temporary.replace(path)


if __name__ == '__main__':
    main(sys.argv[1:])
