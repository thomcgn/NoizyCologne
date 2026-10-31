#!/usr/bin/env bash
set -Eeuo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

image_tag="${1:?image tag is required}"
previous_tag_file=".previous-image-tag"
previous_tag="$(cat "$previous_tag_file" 2>/dev/null || true)"
compose=(docker compose -p noizycologne)
legacy_root="$(cd ../.. && pwd)"
legacy_compose="$legacy_root/compose.yaml"

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

current_caddy="$("${compose[@]}" ps -q caddy 2>/dev/null || true)"
port_conflict="$(docker ps --format '{{.ID}}\t{{.Names}}\t{{.Ports}}' \
  | awk '$0 ~ /0\.0\.0\.0:80->|0\.0\.0\.0:443->|:::80->|:::443->/ {print}' \
  | { if [[ -n "$current_caddy" ]]; then grep -v "^$current_caddy" || true; else cat; fi; })"
if [[ -n "$port_conflict" ]]; then
  echo "Deployment aborted: another container owns host ports 80/443." >&2
  echo "$port_conflict" >&2
  exit 1
fi

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
