#!/usr/bin/env bash
set -Eeuo pipefail

test_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mock_bin="$test_root/.mock-bin"
fixture="$test_root/.docker-ps-fixture"
trap 'rm -rf -- "$mock_bin" "$fixture"' EXIT

mkdir -p "$mock_bin"
cat > "$mock_bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "${1:-}" == "ps" ]]; then
  cat "$MOCK_DOCKER_PS_FIXTURE"
fi
MOCK
chmod 0755 "$mock_bin/docker"

# shellcheck source=../deploy.sh
source "$test_root/../deploy.sh"

run_case() {
  local name="$1"
  local expected_status="$2"
  local fixture_content="$3"
  local actual_status

  printf '%s\n' "$fixture_content" > "$fixture"
  if PATH="$mock_bin:$PATH" MOCK_DOCKER_PS_FIXTURE="$fixture" check_host_port_conflicts; then
    actual_status=0
  else
    actual_status=$?
  fi

  if [[ "$actual_status" != "$expected_status" ]]; then
    echo "FAIL: $name (expected $expected_status, got $actual_status)" >&2
    exit 1
  fi
  echo "PASS: $name"
}

run_case \
  "same project caddy owns 80 and 443" \
  0 \
  $'noizycologne-caddy-1\tnoizycologne\tcaddy\t0.0.0.0:80->80/tcp, :::443->443/tcp'

run_case \
  "foreign caddy owns 80 and 443" \
  1 \
  $'foreign-caddy\tother-project\tcaddy\t0.0.0.0:80->80/tcp, :::443->443/tcp'

run_case \
  "other container owns port 80" \
  1 \
  $'foreign-web\tnoizycologne\tfrontend\t0.0.0.0:80->80/tcp'

run_case \
  "no caddy and no published ports" \
  0 \
  $'noizycologne-api-1\tnoizycologne\tapi\t8080/tcp'
