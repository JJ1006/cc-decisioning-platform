#!/usr/bin/env bash
# curl against the Kibana API as the elastic superuser (HTTPS, project CA). The password is read from the root env file inside
# the script, so it never appears on a command line. The path is the last argument; KBN_SPACE puts /s/<space> in front of it.
#   scripts/kibana-curl.sh /api/status
#   KBN_SPACE=maple-northern scripts/kibana-curl.sh -X GET /api/saved_objects/_find?type=dashboard
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CA=${KBN_CA:-$ROOT/security/pki/out/ca/ca.crt}
pw=$(grep -E '^ELASTIC_PASSWORD=' "$ROOT/.env" | tail -1 | cut -d= -f2-)
args=("$@")
path=${args[${#args[@]} - 1]}
unset 'args[${#args[@]}-1]'
[[ -n "${KBN_SPACE:-}" ]] && path="/s/$KBN_SPACE$path"
exec curl -sS --cacert "$CA" -K <(printf 'user = "elastic:%s"\n' "$pw") -H 'kbn-xsrf: true' \
  ${args[@]+"${args[@]}"} "${KBN_URL:-https://localhost:5601}$path"
