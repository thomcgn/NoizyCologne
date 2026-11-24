#!/usr/bin/env bash
# Fail-closed maintenance wrapper for backup.py.
#
# Configuration comes from explicit non-secret environment variables (systemd
# EnvironmentFile or the operator's shell). The persistent .env is NEVER sourced
# as shell code; only Docker Compose parses it via --env-file. No secret is
# passed on a command line or written to stdout/stderr.
#
# Required: NOIZY_SERVER_DIR, NOIZY_BACKUP_OUTPUT, NOIZY_OFFSITE_DIR,
#           NOIZY_BACKUP_RECIPIENT (explicit GPG fingerprint)
# Optional: NOIZY_COMPOSE_PROJECT (default noizycologne),
#           NOIZY_BACKUP_ALARM_HOOK (executable receiving one JSON event on stdin)
set -Eeuo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

# Initialise every global before the EXIT trap can run.
server_dir='' output='' offsite='' recipient='' hook=''
project='noizycologne'
sha256='' generation='' offsite_status='NOT_VERIFIED'
event='BACKUP_FAILED' reason_code='NONE'
restart_needed=0
running_services=()

# Alarm contract: one JSON object per event on the hook's stdin; no secrets.
# reason_code is from a fixed vocabulary: NONE, MISSING_CONFIG, STOP_FAILED,
# BACKUP_FAILED, RESTART_FAILED, PRUNE_FAILED, INSPECTION_FAILED.
emit() {
    [[ -n $hook ]] || return 0
    if [[ ! -x $hook ]]; then
        printf 'noizy-backup: alarm hook is not executable\n' >&2
        return 0
    fi
    local stamp
    stamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    printf '{"event":"%s","timestamp_utc":"%s","reason_code":"%s","sha256":"%s","generation":"%s","offsite":"%s"}\n' \
        "$1" "$stamp" "$2" "$sha256" "$generation" "$3" | "$hook" >/dev/null || \
        printf 'noizy-backup: alarm hook delivery failed\n' >&2
}

finish() {
    local status=$?
    trap - EXIT
    if (( restart_needed )) && (( ${#running_services[@]} )); then
        if ! "${compose[@]}" start "${running_services[@]}"; then
            printf 'noizy-backup: restart failed; manual intervention required\n' >&2
            event='BACKUP_FAILED'
            reason_code='RESTART_FAILED'
            status=1
        fi
    fi
    emit "$event" "$reason_code" "$offsite_status"
    exit "$status"
}
trap finish EXIT

fail() {
    printf 'noizy-backup: %s\n' "$1" >&2
    event='BACKUP_FAILED'
    reason_code=${2:-MISSING_CONFIG}
    exit "${3:-1}"
}

server_dir=${NOIZY_SERVER_DIR:-}
output=${NOIZY_BACKUP_OUTPUT:-}
offsite=${NOIZY_OFFSITE_DIR:-}
recipient=${NOIZY_BACKUP_RECIPIENT:-}
project=${NOIZY_COMPOSE_PROJECT:-$project}
hook=${NOIZY_BACKUP_ALARM_HOOK:-}

[[ -n $server_dir && -n $output && -n $offsite && -n $recipient ]] || \
    fail 'NOIZY_SERVER_DIR, NOIZY_BACKUP_OUTPUT, NOIZY_OFFSITE_DIR and NOIZY_BACKUP_RECIPIENT are required'
[[ $recipient =~ ^[A-Fa-f0-9]{40,64}$ ]] || fail 'NOIZY_BACKUP_RECIPIENT must be an explicit fingerprint'
[[ $project =~ ^[A-Za-z0-9][A-Za-z0-9_-]*$ ]] || fail 'NOIZY_COMPOSE_PROJECT is invalid'

envfile="$server_dir/.env"
[[ -f $envfile && ! -L $envfile ]] || fail 'a private persistent .env is required'
[[ $(stat -c %a -- "$envfile") == 600 ]] || fail 'persistent .env must be mode 0600'
[[ -d $offsite && ! -L $offsite ]] || fail 'the offsite directory must already exist'
[[ $(stat -c %a -- "$offsite") == 700 ]] || fail 'offsite directory must be mode 0700'

# In the SHA-release layout the manifest lives in the active release directory.
release_dir=$server_dir
if [[ -f $server_dir/.active-release && ! -L $server_dir/.active-release ]]; then
    release=''
    IFS= read -r release < "$server_dir/.active-release" || true
    case "$release" in
        "$server_dir"/releases/*) release_dir=$release ;;
        *) fail 'active release must stay inside the persistent server directory' ;;
    esac
fi
compose_file="$release_dir/compose.yaml"
[[ -f $compose_file && ! -L $compose_file ]] || fail 'compose.yaml is missing or unsafe'
compose=(docker compose --env-file "$envfile" -f "$compose_file" -p "$project")

# Remember exactly what was running; never start a service the operator had
# intentionally stopped before the maintenance window.
for service in api frontend; do
    ids=$("${compose[@]}" ps -aq "$service") || fail 'cannot inspect compose services' INSPECTION_FAILED
    [[ -n $ids ]] || continue
    state=$(docker inspect --format '{{.State.Running}}' "${ids%%$'\n'*}") || fail 'cannot inspect container state' INSPECTION_FAILED
    if [[ $state == true ]]; then
        running_services+=("$service")
    fi
done

if (( ${#running_services[@]} )); then
    restart_needed=1
    if ! "${compose[@]}" stop "${running_services[@]}"; then
        fail 'stopping API/frontend failed' STOP_FAILED
    fi
fi

result=''
backup_status=0
result=$(python3 "$script_dir/backup.py" backup --server-dir "$server_dir" --project "$project" \
    --output "$output" --recipient "$recipient" --offsite-dir "$offsite") || backup_status=$?
if (( backup_status != 0 )); then
    fail "backup failed with exit $backup_status" BACKUP_FAILED "$backup_status"
fi
printf '%s\n' "$result"
sha256=$(printf '%s\n' "$result" | sed -n 's/.*"sha256"[[:space:]]*:[[:space:]]*"\([0-9a-f]\{64\}\)".*/\1/p')
generation=$(printf '%s\n' "$result" | sed -n 's/.*"file"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
offsite_status='VERIFIED_COPY'
event='BACKUP_OK'
reason_code='NONE'

# Retention is bounded after every verified generation, including local-only
# leftovers from earlier manual backups.
if ! python3 "$script_dir/backup.py" prune --output "$output"; then
    fail 'prune failed after backup' PRUNE_FAILED
fi
