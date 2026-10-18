#!/usr/bin/env bash
# MCP server #2, ours (mcp/odm-dc-mcp-server), over stdio for Claude Code or Claude Desktop: Decision Center (read-only), decisions and statistics through
# the API gateway as the mcp-agent consumer, what-if on RES, every call audited on cc.audit.v1. Needs the stack: make up PROFILE=core,analytics,edge.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export DC_URL=${DC_URL:-https://localhost:9443/decisioncenter-api}
export DC_USERNAME DC_PASSWORD
export RES_URL=${RES_URL:-https://localhost:9443}
export RES_USERNAME RES_PASSWORD
export GATEWAY_URL=${GATEWAY_URL:-https://localhost:8443}
export CA_FILE=$PKI/ca/ca.crt
export MCP_RES_CERT=$PKI/mcp-res/mcp-res.crt
export MCP_RES_CERT_KEY=$PKI/mcp-res/mcp-res.key
export MCP_AGENT_CERT=$PKI/mcp-agent/mcp-agent.crt
export MCP_AGENT_CERT_KEY=$PKI/mcp-agent/mcp-agent.key
export KAFKA_BOOTSTRAP=${KAFKA_BOOTSTRAP:-localhost:9093}
export MCP_CALLER=${MCP_CALLER:-claude-local:${USER:-user}}
exec uv run --quiet --project "$ROOT/mcp/odm-dc-mcp-server" odm-dc-mcp --transport stdio
