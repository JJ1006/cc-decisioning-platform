#!/usr/bin/env bash
# MCP server #1, the official IBM ODM Decision MCP server (github.com/DecisionsDev/ibm-odm-decision-mcp-server), over stdio for Claude Code or
# Claude Desktop. It exposes every ruleset with `agent.enabled=true` (our ccApproval) as a tool and talks to RES with mutual TLS (the mcp-res
# certificate) plus the ODM demo user. The commit is pinned: bump it deliberately after reading the changes (mcp/odm-res-mcp/README.md).
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"
SHA=${ODM_RES_MCP_SHA:-c08180cea3013d9e57cafa3085b288ed596e668c}
export ODM_URL=${ODM_URL:-https://localhost:9443/res}
export ODM_RUNTIME_URL=${ODM_RUNTIME_URL:-https://localhost:9443/DecisionService}
export ODM_USERNAME=$RES_USERNAME
export ODM_PASSWORD=$RES_PASSWORD
export SSL_CERT_PATH=$PKI/ca/ca.crt
export VERIFY_SSL_HOSTNAME=True
export MTLS_CERT_PATH=$PKI/mcp-res/mcp-res.crt
export MTLS_KEY_PATH=$PKI/mcp-res/mcp-res.key
export LOG_LEVEL=${LOG_LEVEL:-WARNING}
exec uvx --quiet --from "git+https://github.com/DecisionsDev/ibm-odm-decision-mcp-server@${SHA}" start
