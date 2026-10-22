#!/usr/bin/env bash
# Argo CD MCP (argoproj-labs/mcp-for-argocd, pinned) over stdio. OPTIONAL: needs Argo CD on kind (make argocd-up, make argocd-ui in another
# terminal). Read only (MCP_READ_ONLY=true). ARGOCD_BASE_URL and ARGOCD_API_TOKEN come from .mcp.json (${ARGOCD_API_TOKEN} of your shell:
# an Argo CD account token with the readonly role, see docs/mcp.md). The kind Argo CD has a self-signed certificate: set ARGOCD_INSECURE_LOCAL=1
# to accept it for this one local connection; never do that against another server.
set -euo pipefail
VERSION=0.9.0
if [[ -z "${ARGOCD_API_TOKEN:-}" ]]; then
  echo "argocd mcp: ARGOCD_API_TOKEN is not set (optional server, see docs/mcp.md)" >&2
  exit 1
fi
export MCP_READ_ONLY=true
[[ "${ARGOCD_INSECURE_LOCAL:-}" == 1 && "${ARGOCD_BASE_URL:-}" == https://localhost:* ]] && export NODE_TLS_REJECT_UNAUTHORIZED=0
exec npx -y "argocd-mcp@$VERSION" stdio
