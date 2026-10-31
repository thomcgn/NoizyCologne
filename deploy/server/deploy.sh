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

main() {
  cd "$(dirname "${BASH_SOURCE[0]}")"

  local image_tag="${1:?image tag is required}"
  local previous_tag_file=".previous-image-tag"
  local previous_tag="$(cat "$previous_tag_file" 2>/dev/null || true)"
  local compose=(docker compose -p noizycologne)
  local legacy_root="$(cd ../.. && pwd)"
  local legacy_compose="$legacy_root/compose.yaml"

  restore_previous_or_legacy_stack() {
    if [[ -n "$previous_tag" ]]; then
      echo "Attempting to restore image tag $previous_tag" >&2
      IMAGE_TAG="$previous_tag" "${compose[@]}" up -d db api frontend --wait || true
      IMAGE_TAG="$previous_tag" "${compose[@]}" up -d --no-deps caddy --wait || true
      return
    fi

    if [[ -f "$legacy_compose" ]]; then
      echo "Attempting to restore the previous noizycologne stack" >&2
      docker compose -p noizycologne -f "$legacy_compose" up -d caddy landing smoke || true
    else
      echo "Legacy compose file not found at $legacy_compose; manual rollback is required" >&2
    fi
  }

  "${compose[@]}" config >/dev/null
  IMAGE_TAG="$image_tag" "${compose[@]}" pull api frontend landing
  "${compose[@]}" run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
  check_host_port_conflicts

  if ! IMAGE_TAG="$image_tag" "${compose[@]}" up -d db api frontend --wait; then
    restore_previous_or_legacy_stack
    exit 1
  fi

  if ! IMAGE_TAG="$image_tag" "${compose[@]}" up -d --no-deps caddy --wait; then
    restore_previous_or_legacy_stack
    exit 1
  fi

  printf '%s\n' "$image_tag" > "$previous_tag_file"
  "${compose[@]}" ps
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
