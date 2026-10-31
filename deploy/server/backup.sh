#!/usr/bin/env bash
set -Eeuo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
backup_dir="${BACKUP_DIR:-./backups}"
mkdir -p -- "$backup_dir"
backup_file="$backup_dir/noizycologne-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"

docker compose exec -T db pg_dump --clean --if-exists \
  -U "${POSTGRES_USER:?POSTGRES_USER is required}" \
  -d "${POSTGRES_DB:?POSTGRES_DB is required}" \
  | gzip -9 > "$backup_file"

find "$backup_dir" -type f -name 'noizycologne-*.sql.gz' -mtime +14 -delete
echo "Created $backup_file"
