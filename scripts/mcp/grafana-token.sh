#!/usr/bin/env bash
# Creates (or reuses) the Grafana service account "claude-mcp" with the Viewer role and mints a short-lived token for it, through the Grafana API
# as admin. Prints an export line; the token is NOT saved anywhere:
#   eval "$(scripts/mcp/grafana-token.sh)"      then start Claude Code from this shell
# Needs the observability profile (make up PROFILE=core,edge,obs). TTL: GRAFANA_TOKEN_TTL_HOURS (default 24).
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
gc() { "$ROOT/scripts/grafana-curl.sh" "$@"; }
TTL=$(( ${GRAFANA_TOKEN_TTL_HOURS:-24} * 3600 ))

id=$(gc "/api/serviceaccounts/search?query=claude-mcp" | jq -r '.serviceAccounts[]? | select(.login | endswith("claude-mcp")) | .id' | head -1)
if [[ -z "$id" ]]; then
  id=$(gc -X POST -H 'Content-Type: application/json' -d '{"name":"claude-mcp","role":"Viewer","isDisabled":false}' /api/serviceaccounts | jq -r '.id // empty')
fi
[[ -n "$id" ]] || { echo "grafana-token: could not create the service account (is Grafana up? make up PROFILE=core,edge,obs)" >&2; exit 1; }

role=$(gc "/api/serviceaccounts/$id" | jq -r .role)
[[ "$role" == Viewer ]] || { echo "grafana-token: service account claude-mcp has role $role, expected Viewer" >&2; exit 1; }

token=$(gc -X POST -H 'Content-Type: application/json' \
  -d "{\"name\":\"claude-$(date +%Y%m%d-%H%M%S)\",\"secondsToLive\":$TTL}" "/api/serviceaccounts/$id/tokens" | jq -r '.key // empty')
[[ -n "$token" ]] || { echo "grafana-token: could not create a token" >&2; exit 1; }
echo "export GRAFANA_SA_TOKEN=$token"
echo "service account claude-mcp (Viewer), token valid ${GRAFANA_TOKEN_TTL_HOURS:-24} h; run: eval \"\$(scripts/mcp/grafana-token.sh)\"" >&2
