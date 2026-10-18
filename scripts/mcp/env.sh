# Sourced by scripts/mcp/odm-res.sh and odm-dc.sh: the local settings of the MCP servers. Secrets come from the gitignored env file that
# scripts/compose.sh derives from the root env file (so .mcp.json holds none); certificates come from scripts/pki.sh (make pki).
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
PKI=${PKI_OUT:-$ROOT/security/pki/out}
DERIVED=$ROOT/deploy/compose/.env.derived
if [[ ! -f "$DERIVED" || ! -f "$PKI/ca/ca.crt" ]]; then
  echo "odm mcp: run 'make pki' and 'make up PROFILE=core,analytics,edge' once (they create the certificates and $DERIVED)" >&2
  exit 1
fi
set -a
# shellcheck disable=SC1090
. "$DERIVED"
set +a
# ODM for Developers demo users: password = user name unless the env file says otherwise
RES_USERNAME=${RES_USERNAME:-resAdmin}
RES_PASSWORD=${RES_PASSWORD:-$RES_USERNAME}
DC_USERNAME=${DC_USERNAME:-odmAdmin}
DC_PASSWORD=${DC_PASSWORD:-$DC_USERNAME}
