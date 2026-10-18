#!/usr/bin/env bash
# Private demo PKI for mTLS: one root CA, one cert per component (server + client auth),
# PKCS12/JKS keystores, truststores and a Kafka client config. Idempotent.
#
#   scripts/pki.sh            create what is missing
#   scripts/pki.sh --force    wipe and regenerate everything
#
# Output: security/pki/out/ (gitignored; never committed). DEV ONLY: leaf private keys and
# keystores are world-readable so non-root container users can read bind mounts. The CA key is
# 0600 and is never mounted into a container. In Kubernetes, cert-manager replaces this script.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${PKI_OUT:-$ROOT/security/pki/out}
DAYS_CA=3650
DAYS_LEAF=825

# Keystore password: env, else .env, else the dev default.
PASS=${PKI_PASSWORD:-}
if [[ -z "$PASS" && -f "$ROOT/.env" ]]; then
  PASS=$(grep -E '^PKI_PASSWORD=' "$ROOT/.env" | tail -1 | cut -d= -f2- || true)
fi
PASS=${PASS:-changeit}

# Components that serve TLS and/or present a client certificate (name == compose service name).
COMPONENTS=(
  kafka elasticsearch kibana odm apisix
  application-api decision-query-api txn-generator decision-orchestrator notification-svc
  logstash filebeat
  kafka-ui grafana kafka-exporter
)
# Pure clients: client authentication only. The CN is the consumer name at the gateway (gateway/apisix/README.md); load-test is
# used by verify-mtls for the rate-limit checks so the real consumers keep their quota.
CLIENTS=(admin demo-partner underwriter-portal analyst ops-admin load-test mcp)

KEYTOOL=${KEYTOOL:-$(command -v keytool || true)}
[[ -z "$KEYTOOL" && -x /opt/homebrew/opt/openjdk@21/bin/keytool ]] && KEYTOOL=/opt/homebrew/opt/openjdk@21/bin/keytool

if [[ "${1:-}" == "--force" ]]; then
  rm -rf "$OUT"
fi
mkdir -p "$OUT/ca" "$OUT/ca-private"
chmod 755 "$OUT" "$OUT/ca"
chmod 700 "$OUT/ca-private"

ca() {
  if [[ -f "$OUT/ca/ca.crt" && -f "$OUT/ca-private/ca.key" ]]; then
    return
  fi
  echo "==> root CA"
  openssl genrsa -out "$OUT/ca-private/ca.key" 4096 2>/dev/null
  chmod 600 "$OUT/ca-private/ca.key"
  openssl req -x509 -new -sha256 -days $DAYS_CA \
    -key "$OUT/ca-private/ca.key" \
    -subj "/O=Maple Northern Bank (demo)/CN=Maple Northern Demo Root CA" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -out "$OUT/ca/ca.crt"
  chmod 644 "$OUT/ca/ca.crt"
}

truststores() {
  if [[ ! -f "$OUT/truststore.p12" ]]; then
    echo "==> truststore.p12"
    "${KEYTOOL:-keytool}" -importcert -noprompt -alias maple-demo-ca -file "$OUT/ca/ca.crt" \
      -keystore "$OUT/truststore.p12" -storetype PKCS12 -storepass "$PASS" >/dev/null
    chmod 644 "$OUT/truststore.p12"
  fi
  if [[ -n "$KEYTOOL" && ! -f "$OUT/truststore.jks" ]]; then
    echo "==> truststore.jks"
    "$KEYTOOL" -importcert -noprompt -alias maple-demo-ca -file "$OUT/ca/ca.crt" \
      -keystore "$OUT/truststore.jks" -storetype JKS -storepass "$PASS" >/dev/null 2>&1
    chmod 644 "$OUT/truststore.jks"
  fi
}

# issue <name> <server|client>
issue() {
  local name=$1 kind=$2 dir="$OUT/$1"
  if [[ -f "$dir/$name.p12" ]]; then
    return
  fi
  echo "==> $kind cert: $name"
  mkdir -p "$dir"
  chmod 755 "$dir"
  local eku="clientAuth" san=""
  if [[ "$kind" == "server" ]]; then
    eku="serverAuth,clientAuth"
    san="subjectAltName=DNS:$name,DNS:localhost,IP:127.0.0.1,IP:::1"
  fi
  openssl genrsa -out "$dir/$name.key" 2048 2>/dev/null
  # PKCS#8 copy for software that rejects traditional RSA keys (Logstash beats input)
  openssl pkcs8 -topk8 -nocrypt -in "$dir/$name.key" -out "$dir/$name.pk8.key"
  openssl req -new -key "$dir/$name.key" -subj "/O=Maple Northern Bank (demo)/CN=$name" \
    -out "$dir/$name.csr"
  {
    echo "basicConstraints=critical,CA:FALSE"
    echo "keyUsage=critical,digitalSignature,keyEncipherment"
    echo "extendedKeyUsage=$eku"
    [[ -n "$san" ]] && echo "$san"
  } >"$dir/ext.cnf"
  openssl x509 -req -sha256 -days $DAYS_LEAF -in "$dir/$name.csr" \
    -CA "$OUT/ca/ca.crt" -CAkey "$OUT/ca-private/ca.key" -CAcreateserial \
    -CAserial "$OUT/ca-private/ca.srl" -extfile "$dir/ext.cnf" -out "$dir/$name.crt" 2>/dev/null
  cat "$dir/$name.crt" "$OUT/ca/ca.crt" >"$dir/$name-chain.crt"
  openssl pkcs12 -export -inkey "$dir/$name.key" -in "$dir/$name.crt" -certfile "$OUT/ca/ca.crt" \
    -name "$name" -out "$dir/$name.p12" -passout "pass:$PASS"
  if [[ -n "$KEYTOOL" ]]; then
    "$KEYTOOL" -importkeystore -noprompt -srckeystore "$dir/$name.p12" -srcstoretype PKCS12 \
      -srcstorepass "$PASS" -destkeystore "$dir/$name.jks" -deststoretype JKS \
      -deststorepass "$PASS" >/dev/null 2>&1
  fi
  rm -f "$dir/$name.csr" "$dir/ext.cnf"
  chmod 644 "$dir"/*
}

kafka_client_properties() {
  local f="$OUT/kafka-client.properties"
  [[ -f "$f" ]] && return
  echo "==> kafka-client.properties (paths as seen inside containers)"
  cat >"$f" <<EOF
security.protocol=SSL
ssl.keystore.type=PKCS12
ssl.keystore.location=/pki/admin/admin.p12
ssl.keystore.password=$PASS
ssl.truststore.type=PKCS12
ssl.truststore.location=/pki/truststore.p12
ssl.truststore.password=$PASS
ssl.endpoint.identification.algorithm=
EOF
  chmod 644 "$f"
}

command -v openssl >/dev/null || { echo "openssl not found" >&2; exit 1; }
[[ -n "$KEYTOOL" ]] || echo "WARN: keytool not found - JKS files and truststore.p12 need a JDK" >&2

ca
truststores
for c in "${COMPONENTS[@]}"; do issue "$c" server; done
for c in "${CLIENTS[@]}"; do issue "$c" client; done
kafka_client_properties

echo "PKI ready in ${OUT#"$ROOT"/}"
