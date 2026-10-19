#!/usr/bin/env bash
# curl against the local Elasticsearch as the admin user (HTTPS, project CA). Reads the password from the
# root env file inside the script, so it never appears on a command line.
#   scripts/es-curl.sh [curl args] /cc-decisions/_count
#   ES_USER=analyst scripts/es-curl.sh /cc-decision-inputs/_count    as another user (password: NAME_PASSWORD of the root env file, for
#   example ANALYST_PASSWORD, OPS_PASSWORD; privacy-ops: PRIVACY_OPS_PASSWORD; decision-query-api: QUERY_API_PASSWORD)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/res-env.sh"
user=${ES_USER:-elastic}
case $user in
  elastic) var=ELASTIC_PASSWORD ;;
  decision-query-api) var=QUERY_API_PASSWORD ;;
  *) var=$(printf '%s' "$user" | tr 'a-z-' 'A-Z_')_PASSWORD ;;
esac
pw=$(grep -E "^$var=" "$ROOT/.env" | tail -1 | cut -d= -f2-)
args=("$@")
path=${args[${#args[@]} - 1]}
unset 'args[${#args[@]}-1]'
exec curl -sS --cacert "$RES_CA" -K <(printf 'user = "%s:%s"\n' "$user" "$pw") ${args[@]+"${args[@]}"} "${ES_URL:-https://localhost:9200}$path"
