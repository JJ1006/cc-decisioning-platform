#!/usr/bin/env bash
# curl against Decision Center (mutual TLS, our CA and the admin client certificate, demo user from scripts/res-env.sh style env).
#   scripts/dc.sh [curl args...] PATH-under-DC_API   e.g. scripts/dc.sh /v1/decisionservices
# Prefix the path with "@" to call the DC web root instead of the API (e.g. @/decisioncenter).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/res-env.sh"
DC_USER=${DC_USER:-odmAdmin}
DC_PASS=${DC_PASS:-$DC_USER}
DC_API=${DC_API:-$RES_URL/decisioncenter-api}
args=("$@"); path=${args[${#args[@]}-1]}; unset 'args[${#args[@]}-1]'
case "$path" in @*) url=$RES_URL${path#@} ;; *) url=$DC_API$path ;; esac
[[ -f "$RES_CA" ]] || { echo "CA not found: $RES_CA (run make pki)" >&2; exit 2; }
exec curl -sS --cacert "$RES_CA" --cert "$RES_CERT" --key "$RES_CERT_KEY" -K <(printf 'user = "%s:%s"\n' "$DC_USER" "$DC_PASS") ${args[@]+"${args[@]}"} "$url"
