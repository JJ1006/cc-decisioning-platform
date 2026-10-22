#!/bin/sh
# Renders apisix.yaml from the template: the certificates are PEM text inside the declarative file, and they must
# not be committed, so they are inserted at container start from the PKI mounts (scripts/pki.sh). Then starts APISIX.
set -eu
TEMPLATE=${APISIX_TEMPLATE:-/gateway/apisix.yaml.tmpl}
OUT=${APISIX_CONFIG_OUT:-/usr/local/apisix/conf/apisix.yaml}
# APISIX_OTEL_SAMPLER: always_on when the obs profile runs (traces go to the collector), else always_off

pem() { # pem FILE INDENT: the file indented to sit under a "cert: |" block (6 spaces) or a "ca: |" block one level deeper (8)
  sed "s/^/$2/" "$1"
}

: >"$OUT"
while IFS= read -r line; do
  case "$line" in
    '@SERVER_CERT@') pem /pki/apisix.crt '      ' >>"$OUT" ;;
    '@SERVER_KEY@') pem /pki/apisix.key '      ' >>"$OUT" ;;
    '@CLIENT_CA@') pem /pki/ca.crt '        ' >>"$OUT" ;;
    *) printf '%s\n' "$line" | sed -e "s/@OTEL_SAMPLER@/${APISIX_OTEL_SAMPLER:-always_off}/" -e "s/@QUOTA_PER_MINUTE@/${GATEWAY_QUOTA_PER_MINUTE:-100}/" -e "s/@SUBMIT_RATE@/${GATEWAY_SUBMIT_RATE:-5}/" -e "s/@SUBMIT_BURST@/${GATEWAY_SUBMIT_BURST:-5}/" >>"$OUT" ;; # the last three only change for a load test (evals/k6), never in a deployment
  esac
done <"$TEMPLATE"
echo "apisix.yaml rendered from $TEMPLATE"

exec /docker-entrypoint.sh docker-start
