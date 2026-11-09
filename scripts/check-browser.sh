#!/usr/bin/env bash
set -Eeuo pipefail
case "${1:---full}" in --unit|--full) mode="${1:---full}" ;; *) echo 'Usage: scripts/check-browser.sh [--unit|--full]' >&2; exit 64 ;; esac
(( $# <= 1 )) || exit 64
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
command -v node >/dev/null
command -v npm >/dev/null
node -e 'if(Number(process.versions.node.split(".")[0])<24)throw Error("Node >=24 required")'
for file in browser-worker/*.mjs browser-worker/tests/*.mjs browser-worker/tests/browser/*.mjs; do node --check "$file"; done
npm --prefix browser-worker test
if [[ "$mode" == --unit ]]; then exit 0; fi
[[ -x frontend/node_modules/.bin/eslint ]] || { echo 'Install frontend dependencies with npm ci before full browser validation.' >&2; exit 1; }
frontend/node_modules/.bin/eslint --no-config-lookup --config browser-worker/eslint.config.mjs browser-worker --max-warnings=0
command -v docker >/dev/null
docker info --format '{{.ServerVersion}}'
# Validate the production overlay without starting services or inheriting a real .env.
env POSTGRES_DB=fixture POSTGRES_USER=fixture POSTGRES_PASSWORD=fixture \
  TEAM_USER=fixture TEAM_PASSWORD_HASH=fixture TEAM_SESSION_SECRET=fixture ACME_EMAIL=fixture@example.invalid \
  POSTGRES_DATA_VOLUME=fixture-db CADDY_DATA_VOLUME=fixture-caddy CADDY_CONFIG_VOLUME=fixture-config MEDIA_DATA_VOLUME=fixture-media \
  BROWSER_WORKER_TOKEN=isolated-compose-fixture-token-123456789 BROWSER_SECCOMP_PROFILE="$root/browser-worker/seccomp_profile.json" \
  IMAGE_TAG=0000000000000000000000000000000000000000 \
  docker compose --env-file /dev/null -f deploy/server/compose.yaml -f deploy/server/compose.browser.yaml config --quiet
docker build -t noizycologne-browser-worker:quality browser-worker
container="noizy-browser-quality-$PPID-$RANDOM-$RANDOM"
containers=("$container")
cleanup() {
  status=$?
  trap - EXIT
  if ! docker rm -f "${containers[@]}"; then status=1; fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# No fixture request can reach the Internet or a development database.
# Sandbox stays enabled, including when testing on a restricted host.
docker run --name "$container" --init --user pwuser --network none \
  --cap-drop ALL --security-opt no-new-privileges:true \
  --security-opt "seccomp=$root/browser-worker/seccomp_profile.json" \
  --memory 768m --cpus 1 --pids-limit 256 --shm-size 256m --read-only \
  --tmpfs /tmp:rw,size=134217728,mode=1777 \
  -v "$root/browser-worker/tests:/app/tests:ro" \
  noizycologne-browser-worker:quality npm run test:browser

# A second disposable PID namespace proves watchdog retirement even when the
# renderer has detached descendants that are outside the child's process group.
lifecycle="$container-lifecycle"
containers+=("$lifecycle")
docker run --name "$lifecycle" --init --user pwuser --network none \
  --cap-drop ALL --security-opt no-new-privileges:true \
  --security-opt "seccomp=$root/browser-worker/seccomp_profile.json" \
  --memory 768m --cpus 1 --pids-limit 256 --shm-size 256m --read-only \
  --tmpfs /tmp:rw,size=134217728,mode=1777 \
  -v "$root/browser-worker/tests:/app/tests:ro" \
  noizycologne-browser-worker:quality node tests/browser/worker-lifecycle.mjs
