#!/bin/sh
# Renders apisix.yaml from the template: the certificates are PEM text inside the declarative file, and they must
# not be committed, so they are inserted at container start from the PKI mounts (scripts/pki.sh). Then starts APISIX.
set -eu
TEMPLATE=${APISIX_TEMPLATE:-/gateway/apisix.yaml.tmpl}
OUT=${APISIX_CONFIG_OUT:-/usr/local/apisix/conf/apisix.yaml}

pem() { # pem FILE: the file indented to sit under a "cert: |" block (6 spaces)
  sed 's/^/      /' "$1"
}

: >"$OUT"
while IFS= read -r line; do
  case "$line" in
    '@SERVER_CERT@') pem /pki/apisix.crt >>"$OUT" ;;
    '@SERVER_KEY@') pem /pki/apisix.key >>"$OUT" ;;
    '@CLIENT_CA@') pem /pki/ca.crt >>"$OUT" ;;
    *) printf '%s\n' "$line" >>"$OUT" ;;
  esac
done <"$TEMPLATE"
echo "apisix.yaml rendered from $TEMPLATE"

exec /docker-entrypoint.sh docker-start
