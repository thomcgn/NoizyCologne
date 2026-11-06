#!/usr/bin/env bash
set -Eeuo pipefail

case "${1:---full}" in
  --backend|--frontend|--full) mode="${1:---full}" ;;
  --help) printf 'Usage: scripts/check-quality.sh [--backend|--frontend|--full]\n'; exit 0 ;;
  *) printf 'Unknown quality mode: %s\n' "$1" >&2; exit 64 ;;
esac
if (( $# > 1 )); then printf 'Choose exactly one quality mode.\n' >&2; exit 64; fi
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
require() { command -v "$1" >/dev/null 2>&1 || { printf 'Required tool missing: %s\n' "$1" >&2; exit 1; }; }
for tool in git python3 tee; do require "$tool"; done
mkdir -p .quality
log_dir=$(mktemp -d "$root/.quality/run.XXXXXX")
container=''
step='prerequisites'
cleanup() {
  local status=$?
  trap - EXIT
  if [[ -n "$container" ]]; then
    if ! docker rm -f "$container" >/dev/null 2>&1; then
      printf 'Could not remove the disposable quality database: %s\n' "$container" >&2
      status=1
    fi
  fi
  if (( status != 0 )); then
    printf '[quality] FAILED at %s. Later checks were not completed. Logs: %s\n' "$step" "$log_dir" >&2
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
run() {
  step=$1; shift
  printf '\n[quality] %s\n' "$step"
  if "$@" 2>&1 | tee "$log_dir/$step.log"; then
    return 0
  else
    local status=$?
    return "$status"
  fi
}

run diff-worktree git diff --check
run diff-index git diff --cached --check
run repository python3 scripts/check-repository.py
run gate-tests python3 -m unittest discover -s scripts/tests -p 'test_quality_*.py' -v
run bash-syntax bash -n scripts/check-quality.sh
run hook-syntax sh -n .githooks/pre-commit
run hook-installer-syntax bash -n scripts/install-hooks.sh

if [[ "$mode" != --frontend ]]; then
  require java
  require docker
  [[ -x backend/mvnw ]] || { printf 'Maven wrapper missing/not executable: backend/mvnw\n' >&2; exit 1; }
  run docker docker info --format '{{.ServerVersion}}'
  run backend-compile ./backend/mvnw -f backend/pom.xml -B -ntp clean test-compile -DskipTests=false -Dmaven.test.skip=false
  run java-tools ./backend/mvnw -f backend/pom.xml -B -ntp dependency:build-classpath dependency:copy \
    -Dmdep.outputFile=target/quality-classpath.txt -DincludeScope=test \
    -Dartifact=org.eclipse.jdt:ecj:3.46.100 -DoutputDirectory=target/quality-tools
  run java-analysis python3 scripts/check-java.py
  run java-negative-tests python3 -m unittest discover -s scripts/tests -p test_java_analysis.py -v
  step=database
  password=$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')
  container=$(docker run --rm -d -e POSTGRES_DB=noizy_quality -e POSTGRES_USER=noizy_quality \
    -e "POSTGRES_PASSWORD=$password" -p 127.0.0.1::5432 postgres:17-alpine)
  ready=false
  for (( attempt=0; attempt<60; attempt++ )); do
    if docker exec "$container" pg_isready -h 127.0.0.1 -U noizy_quality -d noizy_quality >/dev/null 2>&1; then
      ready=true; break
    fi
    sleep 0.5
  done
  [[ "$ready" == true ]] || { printf 'Disposable PostgreSQL did not become ready.\n' >&2; exit 1; }
  # Wait for the final TCP server, not just initdb's temporary Unix-socket server.
  port=$(docker port "$container" 5432/tcp)
  [[ "$port" =~ ^127\.0\.0\.1:([0-9]+)$ ]] || { printf 'Unexpected test database port mapping.\n' >&2; exit 1; }
  export DB_URL="jdbc:postgresql://127.0.0.1:${BASH_REMATCH[1]}/noizy_quality"
  export DB_USER=noizy_quality DB_PASSWORD="$password" DISCOVERY_ENABLED=false MEDIA_ROOT="$log_dir/media"
  if [[ -z "${NOIZY_DISCOVERY_SNAPSHOT:-}" ]]; then
    printf '[quality] Optional historical discovery replay NOT RUN: NOIZY_DISCOVERY_SNAPSHOT is unset (private export required).\n'
  fi
  run backend-tests ./backend/mvnw -f backend/pom.xml -B -ntp verify -DskipTests=false -Dmaven.test.skip=false
fi

if [[ "$mode" != --backend ]]; then
  require node
  require npm
  for tool in next tsc eslint; do
    [[ -x "frontend/node_modules/.bin/$tool" ]] || { printf 'Missing frontend dependency: %s. Run npm ci in frontend.\n' "$tool" >&2; exit 1; }
  done
  cd "$root/frontend"
  run next-types ./node_modules/.bin/next typegen
  run typescript ./node_modules/.bin/tsc --noEmit
  run eslint npm run lint -- --max-warnings=0
  run frontend-tests npm test
  cd "$root"
  run frontend-negative-tests python3 -m unittest discover -s scripts/tests -p test_frontend_analysis.py -v
  cd "$root/frontend"
  run frontend-build npm run build
  cd "$root"
fi
run final-diff-worktree git diff --check
run final-diff-index git diff --cached --check
run final-repository python3 scripts/check-repository.py
printf '\n[quality] PASSED %s. Logs: %s\n' "$mode" "$log_dir"
