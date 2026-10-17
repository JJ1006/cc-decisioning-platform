#!/usr/bin/env bash
# curl against the local Elasticsearch as the admin user (HTTPS, project CA). Reads the password from the
# root env file inside the script, so it never appears on a command line.
#   scripts/es-curl.sh [curl args] /cc-decisions/_count
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/res-env.sh"
pw=$(grep -E '^ELASTIC_PASSWORD=' "$ROOT/.env" | tail -1 | cut -d= -f2-)
args=("$@")
path=${args[${#args[@]} - 1]}
unset 'args[${#args[@]}-1]'
exec curl -sS --cacert "$RES_CA" -K <(printf 'user = "elastic:%s"\n' "$pw") ${args[@]+"${args[@]}"} "${ES_URL:-https://localhost:9200}$path"
