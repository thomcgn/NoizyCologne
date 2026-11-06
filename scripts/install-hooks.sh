#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
current=$(git config --get core.hooksPath || true)
if [[ "$current" == .githooks ]]; then
  printf 'Versioned hooks already configured.\n'
  exit 0
fi
if [[ -n "$current" ]]; then
  printf 'Existing core.hooksPath=%s; review/integrate existing hooks before changing it. Nothing changed.\n' "$current" >&2
  exit 1
fi
hooks=$(git rev-parse --path-format=absolute --git-path hooks)
for hook in "$hooks"/*; do
  [[ -e "$hook" || -L "$hook" ]] || continue
  [[ "$hook" == *.sample ]] && continue
  printf 'Existing default hook: %s; integrate it explicitly first. Nothing changed.\n' "$hook" >&2
  exit 1
done
[[ -x .githooks/pre-commit && -x scripts/check-quality.sh ]] || { printf 'Versioned hook/quality script missing or not executable.\n' >&2; exit 1; }
git config --local core.hooksPath .githooks
printf 'Installed core.hooksPath=.githooks; commits now require the full quality gate.\n'
