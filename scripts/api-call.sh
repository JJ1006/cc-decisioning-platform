#!/usr/bin/env bash
# curl through the API gateway with the client certificate of one consumer (mutual TLS, project CA verified).
#   scripts/api-call.sh CLIENT [curl args...] /path
#   scripts/api-call.sh analyst /v1/stats/latency
#   scripts/api-call.sh demo-partner -X POST -H 'Content-Type: application/json' --data @app.json /v1/applications
# CLIENT is a certificate name from scripts/pki.sh and the gateway consumer: demo-partner (PARTNER), underwriter-portal
# (UNDERWRITER), analyst and load-test (ANALYST), ops-admin (OPS). The path is the last argument. GATEWAY overrides the base URL.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PKI=${PKI_OUT:-$ROOT/security/pki/out}
GATEWAY=${GATEWAY:-https://localhost:8443}
[[ $# -ge 2 ]] || { sed -n '2,8p' "$0" >&2; exit 2; }
client=$1
shift
for f in "$PKI/$client/$client.crt" "$PKI/$client/$client.key" "$PKI/ca/ca.crt"; do
  [[ -f "$f" ]] || { echo "not found: $f (run make pki)" >&2; exit 2; }
done
args=("$@")
path=${args[${#args[@]} - 1]}
unset 'args[${#args[@]}-1]'
exec curl -sS --cacert "$PKI/ca/ca.crt" --cert "$PKI/$client/$client.crt" --key "$PKI/$client/$client.key" \
  ${args[@]+"${args[@]}"} "$GATEWAY$path"
