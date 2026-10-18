#!/bin/sh
# Maps the platform's variable names to the ones of the official server and starts it in remote mode (streamable HTTP on :3000/mcp).
# ODM is reached over mutual TLS (client certificate mcp-res) plus the ODM demo user. The server listens on plain HTTP inside the compose network
# and is not published; it has no route at the gateway (docs/mcp.md).
set -eu
export ODM_USERNAME=${RES_USERNAME:?}
export ODM_PASSWORD=${RES_PASSWORD:?}
export TRACE_ENABLE=${TRACE_ENABLE:-False}
export TRACES_DIR=${TRACES_DIR:-/tmp/mcp-traces}
exec start --transport streamable-http --host 0.0.0.0 --port 3000
