#!/usr/bin/env bash
set -Eeuo pipefail

case "${1:---full}" in
  --quick|--backend|--frontend|--full) mode="${1:---full}" ;;
  --help) printf 'Usage: scripts/check-quality.sh [--quick|--backend|--frontend|--full]\n'; exit 0 ;;
  *) printf 'Unknown quality mode: %s\n' "$1" >&2; exit 64 ;;
esac
if (( $# > 1 )); then printf 'Choose exactly one quality mode.\n' >&2; exit 64; fi
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
require() { command -v "$1" >/dev/null 2>&1 || { printf 'Required tool missing: %s\n' "$1" >&2; exit 1; }; }
for tool in git python3 tee; do require "$tool"; done
mkdir -p .quality
log_dir=$(mktemp -d "$root/.quality/run.XXXXXX")
report="$log_dir/timings.json"
python3 "$root/scripts/quality-report.py" init "$report" "$mode"
container=''
step='prerequisites'
completed=false
run() {
  step=$1; shift
  local started status=0
  started=$(python3 -c 'import time; print(time.monotonic_ns())')
  printf '\n[quality] %s\n' "$step"
  if "$@" 2>&1 | tee "$log_dir/$step.log"; then
    status=0
  else
    status=$?
  fi
  python3 "$root/scripts/quality-report.py" step "$report" "$step" "$started" "$status" || return 1
  return "$status"
}
cleanup() {
  local status=$? failed_step=$step
  trap - EXIT
  if [[ -n "$container" ]]; then
    if ! run database-cleanup docker rm -f "$container"; then
      printf 'Could not remove the disposable quality database: %s\n' "$container" >&2
      status=1
      failed_step=database-cleanup
    fi
  fi
  if ! python3 "$root/scripts/quality-report.py" finish "$report" "$status" "$failed_step"; then
    status=1
    failed_step=timing-report
  fi
  if (( status != 0 )); then
    printf '[quality] FAILED at %s. Later checks were not completed. Logs: %s\n' "$failed_step" "$log_dir" >&2
  elif [[ "$completed" == true ]]; then
    printf '\n[quality] PASSED %s. Logs: %s\n[quality] Timings: %s\n' "$mode" "$log_dir" "$report"
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

run diff-worktree git diff --check
run diff-index git diff --cached --check
run repository python3 scripts/check-repository.py

scope="$log_dir/scope.json"
run scope python3 "$root/scripts/quality-scope.py"
cp "$log_dir/scope.log" "$scope"
scope_value() {
  python3 -c 'import json, sys; value=json.load(open(sys.argv[1]))[sys.argv[2]]; print(str(value).lower() if isinstance(value, bool) else value)' "$scope" "$1"
}
scope_list() {
  python3 -c 'import json, os, sys; values=json.load(open(sys.argv[1]))[sys.argv[2]]; sys.stdout.buffer.write(b"".join(os.fsencode(value)+b"\0" for value in values))' "$scope" "$1"
}
backend=false
frontend=false
browser=false
case "$mode" in
  --full) backend=true; frontend=true; browser=true ;;
  --backend) backend=true ;;
  --frontend) frontend=true ;;
  --quick) backend=$(scope_value backend); frontend=$(scope_value frontend); browser=$(scope_value browser) ;;
esac

# Compilers read the worktree. Do not certify a different staged code version.
# Respect the active hook index, so partial commits retain their normal semantics.
step=index-worktree-consistency
mapfile -d '' -t overlap < <(scope_list index_worktree_overlap)
if (( ${#overlap[@]} )); then
  printf 'Code/tool files have both staged and unstaged changes. The staged version cannot be certified from worktree checks.\n' >&2
  printf 'Stage the intended complete version, or separate the changes before checking/committing:\n' >&2
  printf '  %s\n' "${overlap[@]}" >&2
  exit 1
fi

if [[ "$mode" != --quick || $(scope_value tooling) == true ]]; then
  run gate-tests python3 -m unittest discover -s scripts/tests -p 'test_quality_*.py' -v
fi
run bash-syntax bash -n scripts/check-quality.sh
run hook-syntax sh -n .githooks/pre-commit
run hook-installer-syntax bash -n scripts/install-hooks.sh

start_database() {
  container=$(docker run --rm -d -e POSTGRES_DB=noizy_quality -e POSTGRES_USER=noizy_quality \
    -e "POSTGRES_PASSWORD=$DB_PASSWORD" -p 127.0.0.1::5432 postgres:17-alpine) || return
  local ready=false port attempt
  for (( attempt=0; attempt<60; attempt++ )); do
    if docker exec "$container" pg_isready -h 127.0.0.1 -U noizy_quality -d noizy_quality >/dev/null 2>&1; then
      ready=true; break
    fi
    sleep 0.5
  done
  [[ "$ready" == true ]] || { printf 'Disposable PostgreSQL did not become ready.\n' >&2; return 1; }
  # Wait for the final TCP server, not initdb's temporary Unix-socket server.
  port=$(docker port "$container" 5432/tcp) || return
  [[ "$port" =~ ^127\.0\.0\.1:([0-9]+)$ ]] || { printf 'Unexpected test database port mapping.\n' >&2; return 1; }
  export DB_URL="jdbc:postgresql://127.0.0.1:${BASH_REMATCH[1]}/noizy_quality"
  export SPRING_DATASOURCE_URL="$DB_URL" SPRING_FLYWAY_URL="$DB_URL"
}

if [[ "$backend" == true ]]; then
  step=backend-prerequisites
  require java
  [[ -x backend/mvnw ]] || { printf 'Maven wrapper missing/not executable: backend/mvnw\n' >&2; exit 1; }
  compile_goals=(test-compile)
  if [[ "$mode" != --quick || $(scope_value backend_reset_outputs) == true ]]; then
    # FULL/standard retain a fresh javac warning pass. QUICK only cleans after
    # deletions, renames or shared configuration changes that can retain stale classes.
    compile_goals=(clean test-compile)
  fi
  run backend-compile ./backend/mvnw -f backend/pom.xml -B -ntp "${compile_goals[@]}" -DskipTests=false -Dmaven.test.skip=false
  run java-tools ./backend/mvnw -f backend/pom.xml -B -ntp dependency:build-classpath dependency:copy \
    -Dmdep.outputFile=target/quality-classpath.txt -DincludeScope=test \
    -Dartifact=org.eclipse.jdt:ecj:3.46.100 -DoutputDirectory=target/quality-tools
  run java-analysis python3 scripts/check-java.py
  if [[ "$mode" != --quick || $(scope_value tooling) == true ]]; then
    run java-negative-tests python3 -m unittest discover -s scripts/tests -p test_java_analysis.py -v
  fi

  # An unexpectedly database-dependent selected unit test must fail closed,
  # rather than use inherited development/production connection settings.
  export DB_URL=jdbc:postgresql://127.0.0.1:1/noizy_quality_unavailable DB_USER=noizy_quality
  DB_PASSWORD=$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')
  export DB_PASSWORD DISCOVERY_ENABLED=false DISCOVERY_MONITOR_ENABLED=false MEDIA_ROOT="$log_dir/media"
  export SPRING_DATASOURCE_URL="$DB_URL" SPRING_DATASOURCE_USERNAME="$DB_USER" SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD"
  export SPRING_FLYWAY_URL="$DB_URL" SPRING_FLYWAY_USER="$DB_USER" SPRING_FLYWAY_PASSWORD="$DB_PASSWORD"
  if [[ "$mode" != --quick || $(scope_value backend_integration) == true ]]; then
    step=database-prerequisites
    require docker
    run docker docker info --format '{{.ServerVersion}}'
    step=database
    started=$(python3 -c 'import time; print(time.monotonic_ns())')
    printf '\n[quality] database\n'
    database_status=0
    # Run in this shell: connection settings and container cleanup must survive.
    if start_database > "$log_dir/database.log" 2>&1; then database_status=0; else database_status=$?; fi
    cat "$log_dir/database.log"
    python3 "$root/scripts/quality-report.py" step "$report" database "$started" "$database_status"
    (( database_status == 0 )) || exit "$database_status"
  fi
  if [[ -z "${NOIZY_DISCOVERY_SNAPSHOT:-}" ]]; then
    printf '[quality] Optional historical discovery replay NOT RUN: NOIZY_DISCOVERY_SNAPSHOT is unset (private export required).\n'
  fi
  if [[ "$mode" == --quick ]]; then
    mapfile -d '' -t tests < <(scope_list backend_tests)
    if (( ${#tests[@]} )); then
      selection=$(IFS=,; printf '%s' "${tests[*]}")
      run backend-tests ./backend/mvnw -f backend/pom.xml -B -ntp surefire:test "-Dtest=$selection" \
        -DfailIfNoTests=true -Dsurefire.failIfNoSpecifiedTests=true -DskipTests=false -Dmaven.test.skip=false
    else
      printf '[quality] No existing backend test class matches this scope; strict main/test compilation completed. FULL covers the complete suite.\n'
    fi
  else
    run backend-tests ./backend/mvnw -f backend/pom.xml -B -ntp verify -DskipTests=false -Dmaven.test.skip=false
  fi
fi

if [[ "$frontend" == true ]]; then
  step=frontend-prerequisites
  require node
  require npm
  for tool in next tsc eslint; do
    [[ -x "frontend/node_modules/.bin/$tool" ]] || { printf 'Missing frontend dependency: %s. Run npm ci in frontend.\n' "$tool" >&2; exit 1; }
  done
  cd "$root/frontend"
  run next-types ./node_modules/.bin/next typegen
  run typescript ./node_modules/.bin/tsc --noEmit
  if [[ "$mode" == --quick && $(scope_value frontend_lint_all) != true ]]; then
    mapfile -d '' -t lint_files < <(scope_list frontend_lint)
    if (( ${#lint_files[@]} )); then
      run eslint npm run lint -- --max-warnings=0 -- "${lint_files[@]}"
    else
      printf '[quality] No changed JavaScript/TypeScript files require ESLint.\n'
    fi
  else
    run eslint npm run lint -- --max-warnings=0
  fi
  # The current frontend suite is one shared regression file; all its tests apply.
  run frontend-tests npm test
  cd "$root"
  if [[ "$mode" != --quick || $(scope_value tooling) == true ]]; then
    run frontend-negative-tests python3 -m unittest discover -s scripts/tests -p test_frontend_analysis.py -v
  fi
  if [[ "$mode" != --quick ]]; then
    cd "$root/frontend"
    run frontend-build npm run build
    cd "$root"
  fi
fi
if [[ "$browser" == true ]]; then
  if [[ "$mode" == --quick ]]; then run browser-tests scripts/check-browser.sh --unit
  else run browser-tests scripts/check-browser.sh --full
  fi
fi
run final-diff-worktree git diff --check
run final-diff-index git diff --cached --check
run final-repository python3 scripts/check-repository.py
completed=true
