#!/usr/bin/env bash
# docker compose wrapper: picks the compose file, .env and profiles (PROFILE=core,analytics).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
if [[ ! -f "$ROOT/.env" ]]; then
  cp "$ROOT/.env.example" "$ROOT/.env"
  echo "created .env from .env.example - edit the placeholder passwords" >&2
fi
profiles=()
IFS=, read -ra wanted <<<"${PROFILE:-core}"
for p in "${wanted[@]}"; do profiles+=(--profile "$p"); done
exec docker compose -f "$ROOT/deploy/compose/docker-compose.yml" --env-file "$ROOT/.env" \
  "${profiles[@]}" "$@"
