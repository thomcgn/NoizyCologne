#!/usr/bin/env bash
set -Eeuo pipefail

check_host_port_conflicts() {
  local conflicts

  conflicts="$(docker ps --filter status=running \
    --format '{{.Names}}\t{{.Label "com.docker.compose.project"}}\t{{.Label "com.docker.compose.service"}}\t{{.Ports}}' \
    | awk -F '\t' '
      function owns_host_port(ports, port) {
        return ports ~ (":" port "(-[0-9]+)?->[0-9]+/(tcp|udp)")
      }

      (owns_host_port($4, 80) || owns_host_port($4, 443)) &&
        !($2 == "noizycologne" && $3 == "caddy") { print }
    ')"

  if [[ -n "$conflicts" ]]; then
    echo "Deployment aborted: another container owns host ports 80/443." >&2
    echo "$conflicts" >&2
    return 1
  fi
}

ensure_media_volume() {
  local media_volume="${MEDIA_DATA_VOLUME:-}"

  if [[ -z "$media_volume" && -f .env ]]; then
    media_volume="$(awk -F= '$1 == "MEDIA_DATA_VOLUME" { print substr($0, index($0, "=") + 1); exit }' .env)"
  fi
  media_volume="${media_volume:-noizycologne_media_data}"

  if [[ ! "$media_volume" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]]; then
    echo "Deployment aborted: invalid MEDIA_DATA_VOLUME name." >&2
    return 1
  fi

  if ! docker volume inspect "$media_volume" >/dev/null 2>&1; then
    echo "Creating missing persistent media volume $media_volume"
    docker volume create "$media_volume" >/dev/null
  fi
}

# Install one stable internal key on the VPS if none exists yet.
# It is shared only by the backend and frontend containers and never printed.
ensure_factory_reset_secret() {
  if [[ ! -f .env ]]; then
    echo "Deployment aborted: missing persistent .env" >&2
    return 1
  fi
  if [[ -n "${FACTORY_RESET_SECRET:-}" ]]; then
    if (( ${#FACTORY_RESET_SECRET} < 32 )); then
      echo "Deployment aborted: FACTORY_RESET_SECRET is too short" >&2
      return 1
    fi
    return
  fi
  if grep -q '^FACTORY_RESET_SECRET=' .env; then
    local existing
    existing="$(awk -F= '$1 == "FACTORY_RESET_SECRET" { print substr($0, index($0, "=") + 1); exit }' .env)"
    if (( ${#existing} < 32 )); then
      echo "Deployment aborted: existing FACTORY_RESET_SECRET is too short" >&2
      return 1
    fi
    return
  fi
  local secret
  secret="$(od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]')"
  if (( ${#secret} != 64 )); then
    echo "Deployment aborted: could not generate factory reset key" >&2
    return 1
  fi
  printf '\nFACTORY_RESET_SECRET=%s\n' "$secret" >> .env
  chmod 600 .env
  echo "Installed a dedicated internal factory-reset key (value withheld)"
}

prepare_media_volume_permissions() {
  local media_volume="${MEDIA_DATA_VOLUME:-}"
  local api_image="$1"

  if [[ -z "$media_volume" && -f .env ]]; then
    media_volume="$(awk -F= '$1 == "MEDIA_DATA_VOLUME" { print substr($0, index($0, "=") + 1); exit }' .env)"
  fi
  media_volume="${media_volume:-noizycologne_media_data}"

  echo "Preparing persistent media volume permissions"
  docker run --rm --user 0 \
    --entrypoint /usr/bin/chown \
    -v "$media_volume:/app/uploads" \
    "$api_image" -R 10001:10001 /app/uploads
}

# Read only this setting; never source the persistent .env as shell code.
setting() {
  local key="$1" value="${!1-}"
  if [[ -z "$value" && -f .env ]]; then
    value="$(awk -F= -v key="$key" '$1 == key { print substr($0,index($0,"=")+1); exit }' .env)"
    value="${value%\"}"; value="${value#\"}"
    value="${value%\'}"; value="${value#\'}"
  fi
  printf '%s' "$value"
}

ensure_api_trust_secret() {
  local value
  value="$(setting API_TRUST_SECRET)"
  if [[ -z "$value" ]]; then
    if grep -q '^API_TRUST_SECRET=' .env; then echo 'API_TRUST_CONFIG_FAILED: existing key is empty' >&2; return 1; fi
    value="$(od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]')"
    (( ${#value} == 64 )) || { echo 'API_TRUST_CONFIG_FAILED: key generation failed' >&2; return 1; }
    printf '\nAPI_TRUST_SECRET=%s\n' "$value" >> .env
    chmod 600 .env
  fi
  (( ${#value} >= 32 )) || { echo 'API_TRUST_CONFIG_FAILED: key too short (value withheld)' >&2; return 1; }
  export API_TRUST_SECRET="$value"
}

ensure_auth_service_secret() {
  local value
  value="$(setting AUTH_SERVICE_SECRET)"
  if [[ -z "$value" ]]; then
    if grep -q '^AUTH_SERVICE_SECRET=' .env; then echo 'AUTH_CONFIG_FAILED: existing key is empty' >&2; return 1; fi
    value="$(od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]')"
    (( ${#value} == 64 )) || { echo 'AUTH_CONFIG_FAILED: key generation failed' >&2; return 1; }
    printf '\nAUTH_SERVICE_SECRET=%s\n' "$value" >> .env
    chmod 600 .env
  fi
  [[ "$value" =~ ^[A-Za-z0-9_-]{32,128}$ ]] || { echo 'AUTH_CONFIG_FAILED: invalid key format (value withheld)' >&2; return 1; }
  export AUTH_SERVICE_SECRET="$value"
}

browser_preflight() {
  local release="$1" token profile available
  token="$(setting BROWSER_WORKER_TOKEN)"
  if [[ -z "$token" ]]; then
    token="$(od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]')"
    (( ${#token} == 64 )) || { echo 'BROWSER_CONFIG_INVALID: token generation failed' >&2; return 1; }
    printf '\nBROWSER_WORKER_TOKEN=%s\n' "$token" >> .env
    chmod 600 .env
  fi
  (( ${#token} >= 32 )) || { echo 'BROWSER_CONFIG_INVALID: token must have at least 32 characters (value withheld)' >&2; return 1; }
  export BROWSER_WORKER_TOKEN="$token"
  profile="$(setting BROWSER_SECCOMP_PROFILE)"
  profile="${profile:-$release/seccomp_profile.json}"
  [[ "$profile" == /* && -s "$profile" ]] || { echo 'BROWSER_SECCOMP_MISSING: an absolute, existing seccomp profile is required' >&2; return 1; }
  cmp -s "$profile" "$release/seccomp_profile.json" || { echo 'BROWSER_SECCOMP_MISMATCH: profile must match the qualified release' >&2; return 1; }
  export BROWSER_SECCOMP_PROFILE="$profile"
  available="$(awk '$1 == "MemAvailable:" {print $2}' /proc/meminfo)"
  [[ "$available" =~ ^[0-9]+$ ]] && (( available >= 1048576 )) || { echo 'BROWSER_RAM_INSUFFICIENT: require at least 1 GiB available RAM before rollout' >&2; return 1; }
  echo "Browser preflight: available RAM $((available/1024)) MiB; worker 768 MiB/256 PIDs, gateway 128 MiB/64 PIDs"
}

compose_for() {
  local release="$1" browser="$2"
  compose=(docker compose --env-file "$server_dir/.env" -f "$release/compose.yaml")
  if [[ "$browser" == true ]]; then compose+=(-f "$release/compose.browser.yaml"); fi
  compose+=(-p noizycologne)
}

restore_configuration() {
  local tag="$1" release="$2" browser="$3"
  local -a compose services=(db api frontend landing)
  [[ "$tag" =~ ^[0-9a-f]{40}$ && -f "$release/compose.yaml" ]] || { echo 'ROLLBACK_UNAVAILABLE: no recorded qualified release; manual recovery required' >&2; return 1; }
  compose_for "$release" "$browser"
  if [[ "$browser" == true ]]; then services+=(browser-worker browser-gateway); fi
  echo "Restoring release $tag (browser=$browser)"
  IMAGE_TAG="$tag" DISCOVERY_BROWSER_ENABLED="$browser" BROWSER_SECCOMP_PROFILE="$release/seccomp_profile.json" "${compose[@]}" up -d --no-build "${services[@]}" --wait --wait-timeout 180 || { echo 'ROLLBACK_FAILED: services did not become healthy' >&2; return 1; }
  IMAGE_TAG="$tag" DISCOVERY_BROWSER_ENABLED="$browser" BROWSER_SECCOMP_PROFILE="$release/seccomp_profile.json" "${compose[@]}" up -d --no-build --no-deps caddy --wait --wait-timeout 180 || { echo 'ROLLBACK_FAILED: caddy did not become healthy' >&2; return 1; }
}

rollback() {
  local tag release browser current_release
  tag="$(cat .rollback-image-tag)"; release="$(cat .rollback-release)"; browser="$(cat .rollback-browser-enabled)"
  current_release="$(cat .active-release)"
  restore_configuration "$tag" "$release" "$browser"
  if [[ "$browser" == false && -f "$current_release/compose.browser.yaml" ]]; then
    local -a compose
    compose_for "$current_release" true
    BROWSER_WORKER_TOKEN="${BROWSER_WORKER_TOKEN:-rollback-placeholder-token-not-used-123456}" BROWSER_SECCOMP_PROFILE="$current_release/seccomp_profile.json" "${compose[@]}" rm -sf browser-gateway browser-worker
  fi
  printf '%s\n' "$tag" > .previous-image-tag
  printf '%s\n' "$release" > .active-release
  printf '%s\n' "$browser" > .active-browser-enabled
}

browser_smoke() {
  local release browser
  release="$(cat .active-release)"; browser="$(cat .active-browser-enabled)"
  if [[ "$browser" != true ]]; then echo 'BROWSER_SMOKE_NOT_RUN: persistent browser setting is disabled'; return; fi
  local -a compose
  compose_for "$release" true
  export BROWSER_SECCOMP_PROFILE="$release/seccomp_profile.json"
  "${compose[@]}" exec -T api sh -ec 'wget -q -O /dev/null http://browser-gateway:3128/ready'
  "${compose[@]}" exec -T api sh -ec 'printf "%s\n" "$API_TRUST_SECRET"' | "${compose[@]}" exec -T browser-gateway node smoke.mjs --key-stdin
}

main() {
  local release_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  local server_dir="${NOIZY_SERVER_DIR:-$release_dir}"
  cd "$server_dir"
  if [[ "${1:-}" == --rollback ]]; then rollback; return; fi
  if [[ "${1:-}" == --browser-smoke ]]; then browser_smoke; return; fi
  local image_tag="${1:?qualified commit SHA is required}"
  [[ "$image_tag" =~ ^[0-9a-f]{40}$ ]] || { echo 'Deployment requires an immutable 40-character commit SHA' >&2; return 1; }
  local previous_tag="$(cat .previous-image-tag 2>/dev/null || true)"
  local previous_release="$(cat .active-release 2>/dev/null || printf '%s' "$server_dir")"
  local previous_browser="$(cat .active-browser-enabled 2>/dev/null || printf false)"
  local browser="$(setting DISCOVERY_BROWSER_ENABLED)"
  browser="${browser:-false}"
  [[ "$browser" == true || "$browser" == false ]] || { echo 'BROWSER_CONFIG_INVALID: DISCOVERY_BROWSER_ENABLED must be true or false' >&2; return 1; }
  export DISCOVERY_BROWSER_ENABLED="$browser"
  local -a compose images=(api frontend landing) services=(db api frontend)
  ensure_factory_reset_secret
  ensure_api_trust_secret
  ensure_auth_service_secret
  if [[ "$browser" == true ]]; then
    browser_preflight "$release_dir"
    images+=(browser-worker browser-gateway); services+=(browser-worker browser-gateway)
  fi
  compose_for "$release_dir" "$browser"
  IMAGE_TAG="$image_tag" "${compose[@]}" config --quiet
  IMAGE_TAG="$image_tag" "${compose[@]}" pull "${images[@]}" || { echo 'IMAGE_PULL_FAILED: qualified release is incomplete; active stack unchanged' >&2; return 1; }
  if [[ "$browser" == true ]]; then
    IMAGE_TAG="$image_tag" "${compose[@]}" run --rm --no-deps browser-worker node --input-type=module -e "await (await import('./sandbox-check.mjs')).verifySandbox()" || { echo 'BROWSER_SANDBOX_FAILED: preflight failed; active stack unchanged' >&2; return 1; }
  fi
  ensure_media_volume
  prepare_media_volume_permissions "ghcr.io/thomcgn/noizycologne-backend:$image_tag"
  IMAGE_TAG="$image_tag" "${compose[@]}" run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
  check_host_port_conflicts

  if ! IMAGE_TAG="$image_tag" "${compose[@]}" up -d --no-build "${services[@]}" --wait --wait-timeout 180 || ! IMAGE_TAG="$image_tag" "${compose[@]}" up -d --no-build --no-deps caddy --wait --wait-timeout 180; then
    IMAGE_TAG="$image_tag" "${compose[@]}" ps >&2
    IMAGE_TAG="$image_tag" "${compose[@]}" logs --tail=40 browser-gateway browser-worker >&2 || true
    restore_configuration "$previous_tag" "$previous_release" "$previous_browser" || return 1
    if [[ "$browser" == true && "$previous_browser" == false ]]; then IMAGE_TAG="$image_tag" "${compose[@]}" rm -sf browser-gateway browser-worker; fi
    return 1
  fi
  if [[ "$browser" == false && "$previous_browser" == true ]]; then
    local -a selected_compose=("${compose[@]}")
    compose_for "$previous_release" true
    if ! BROWSER_SECCOMP_PROFILE="$previous_release/seccomp_profile.json" "${compose[@]}" rm -sf browser-gateway browser-worker; then
      restore_configuration "$previous_tag" "$previous_release" "$previous_browser"
      return 1
    fi
    compose=("${selected_compose[@]}")
  fi
  # Keep the exact previous manifest and browser selection for a failed external smoke test.
  printf '%s\n' "$previous_tag" > .rollback-image-tag
  printf '%s\n' "$previous_release" > .rollback-release
  printf '%s\n' "$previous_browser" > .rollback-browser-enabled
  printf '%s\n' "$image_tag" > .previous-image-tag
  printf '%s\n' "$release_dir" > .active-release
  printf '%s\n' "$browser" > .active-browser-enabled
  IMAGE_TAG="$image_tag" "${compose[@]}" ps
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
