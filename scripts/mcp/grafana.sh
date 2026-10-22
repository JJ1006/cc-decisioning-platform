#!/usr/bin/env bash
# Grafana MCP (grafana/mcp-grafana, pinned) over stdio. Read only: --disable-write, and the service-account token should have the Viewer role
# (scripts/mcp/grafana-token.sh). GRAFANA_URL and GRAFANA_SERVICE_ACCOUNT_TOKEN come from .mcp.json (${GRAFANA_SA_TOKEN} of your shell).
# Needs the observability profile: make up PROFILE=core,edge,obs
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
VERSION=2.0.2
if [[ -z "${GRAFANA_SERVICE_ACCOUNT_TOKEN:-}" ]]; then
  echo "grafana mcp: GRAFANA_SA_TOKEN is not set: eval \"\$(scripts/mcp/grafana-token.sh)\" and restart Claude Code" >&2
  exit 1
fi
exec uvx "mcp-grafana==$VERSION" --disable-write --tls-ca-file "${PKI_OUT:-$ROOT/security/pki/out}/ca/ca.crt"
