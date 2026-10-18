#!/usr/bin/env bash
# curl against Grafana as admin (HTTPS, project CA). The password is read from the root env file inside the script, never on a command line.
# The path is the last argument. Datasources are reachable through Grafana's proxy, e.g. the Tempo and Prometheus APIs:
#   scripts/grafana-curl.sh /api/health
#   scripts/grafana-curl.sh '/api/datasources/proxy/uid/tempo/api/search?limit=5'
#   scripts/grafana-curl.sh '/api/datasources/proxy/uid/prometheus/api/v1/query?query=up'
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CA=${GRAFANA_CA:-$ROOT/security/pki/out/ca/ca.crt}
pw=$(grep -E '^GRAFANA_ADMIN_PASSWORD=' "$ROOT/.env" | tail -1 | cut -d= -f2-)
args=("$@")
path=${args[${#args[@]} - 1]}
unset 'args[${#args[@]}-1]'
exec curl -sS --cacert "$CA" -K <(printf 'user = "admin:%s"\n' "$pw") ${args[@]+"${args[@]}"} "${GRAFANA_URL:-https://localhost:3000}$path"
