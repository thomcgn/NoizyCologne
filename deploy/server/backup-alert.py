#!/usr/bin/env python3
"""Read-only alarm check: backup age, SHA256, offsite status and restore age.

Emits one JSON object to stdout and, when configured, passes the same object on
the stdin of an operator-provided hook. No Docker, database or network access;
no secret or archive content is read. Exit 1 means at least one alarm condition.
"""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def utc_now():
    return dt.datetime.now(dt.timezone.utc)


def parse_time(value):
    stamp = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    return stamp if stamp.tzinfo else stamp.replace(tzinfo=dt.timezone.utc)


def check(args):
    now = utc_now()
    alarms = []
    candidates = [path for path in args.output.glob('noizycologne-*.tar.json') if not path.is_symlink()]
    if not candidates:
        alarms.append({'code': 'NO_BACKUP', 'detail': 'no generation state found'})
        payload = {'event': 'NO_BACKUP', 'events': alarms,
                   'timestamp_utc': now.isoformat(), 'generation': '', 'sha256': '',
                   'backup_age_hours': None, 'restore_age_days': None, 'offsite': 'NOT_VERIFIED'}
    else:
        status = max(candidates, key=lambda path: path.stat().st_mtime)
        metadata = json.loads(status.read_text())
        archive = status.with_suffix('.gpg')
        sha256 = metadata.get('sha256', '')
        if not archive.is_file() or archive.is_symlink() or digest(archive) != sha256:
            alarms.append({'code': 'SHA256_MISMATCH', 'detail': 'archive digest differs from state'})
        offsite = metadata.get('offsite', 'NOT_VERIFIED')
        if offsite != 'VERIFIED_COPY':
            alarms.append({'code': 'OFFSITE_NOT_VERIFIED', 'detail': 'no verified offsite copy'})
        try:
            created = parse_time(metadata['created_at'])
        except (KeyError, TypeError, ValueError):
            created = dt.datetime.fromtimestamp(status.stat().st_mtime, dt.timezone.utc)
        age_hours = max(0.0, (now - created).total_seconds() / 3600)
        if age_hours > args.max_backup_age_hours:
            alarms.append({'code': 'BACKUP_STALE', 'detail': 'newest generation exceeds maximum age'})
        restore_days = None
        try:
            tested = parse_time(json.loads(args.restore_state.read_text())['tested_at'])
            restore_days = max(0.0, (now - tested).total_seconds() / 86400)
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            alarms.append({'code': 'RESTORE_STALE', 'detail': 'missing or unreadable restore test state'})
        else:
            if restore_days > args.max_restore_age_days:
                alarms.append({'code': 'RESTORE_STALE', 'detail': 'restore test exceeds maximum age'})
        payload = {'event': alarms[0]['code'] if alarms else 'OK', 'events': alarms,
                   'timestamp_utc': now.isoformat(), 'generation': status.name, 'sha256': sha256,
                   'backup_age_hours': round(age_hours, 2), 'restore_age_days': None if restore_days is None else round(restore_days, 2),
                   'offsite': offsite}
    line = json.dumps(payload, sort_keys=True)
    print(line)
    if args.hook:
        if not args.hook.is_file() or not args.hook.stat().st_mode & 0o111:
            print('backup-alert: alarm hook is not executable', file=sys.stderr)
            return 1
        result = subprocess.run([str(args.hook)], input=(line + '\n').encode(), stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, check=False, timeout=60)
        if result.returncode:
            print('backup-alert: alarm hook delivery failed', file=sys.stderr)
            return 1
    return 0 if not alarms else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--restore-state', type=Path)
    parser.add_argument('--max-backup-age-hours', type=float, default=36)
    parser.add_argument('--max-restore-age-days', type=float, default=35)
    parser.add_argument('--hook', type=Path)
    args = parser.parse_args()
    if args.max_backup_age_hours <= 0 or args.max_restore_age_days <= 0:
        print('backup-alert: maximum ages must be positive', file=sys.stderr)
        return 2
    try:
        return check(args)
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        print(f'backup-alert: check failed ({type(error).__name__})', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
