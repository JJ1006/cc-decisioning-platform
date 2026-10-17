#!/usr/bin/env bash
# Verifies the local stack from the outside: TLS/mTLS reachability and rejection of bad clients.
# Run after `make up PROFILE=...`. Checks only what is running; prints PASS/FAIL per check.
#   scripts/verify-mtls.sh            all running services
#   scripts/verify-mtls.sh kafka      only the kafka checks (kafka | es | odm | kibana)
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PKI=${PKI_OUT:-$ROOT/security/pki/out}
CA=$PKI/ca/ca.crt
DC="$ROOT/scripts/compose.sh"
export PROFILE=${PROFILE:-core,analytics}
only=${1:-all}
failures=0

pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; failures=$((failures + 1)); }
want() { [[ "$only" == all || "$only" == "$1" ]]; }
running() { [[ -n "$("$DC" ps --status running --services 2>/dev/null | grep -x "$1")" ]]; }
envval() { grep -E "^$1=" "$ROOT/.env" | tail -1 | cut -d= -f2-; }

if want kafka && running kafka; then
  # 1. admin client with a CA-signed client cert can list topics
  if out=$("$DC" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9093 \
    --command-config /pki/kafka-client.properties --list 2>&1); then
    n=$(grep -c '^cc\.' <<<"$out" || true)
    [[ "$n" -ge 21 ]] && pass "kafka mTLS: admin lists $n cc.* topics (>= 21 expected)" \
      || fail "kafka mTLS: only $n cc.* topics listed (topic-init done?)"
  else
    fail "kafka mTLS: admin client could not list topics: $(tail -1 <<<"$out")"
  fi
  # 2. a client that trusts the CA but presents NO client certificate is rejected
  printf 'security.protocol=SSL\nssl.truststore.type=PKCS12\nssl.truststore.location=/pki/truststore.p12\nssl.truststore.password=%s\n' \
    "$(envval PKI_PASSWORD)" | "$DC" exec -T kafka bash -c 'cat > /tmp/nocert.properties'
  if "$DC" exec -T kafka bash -c 'KAFKA_HEAP_OPTS=-Xmx128m /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9093 --command-config /tmp/nocert.properties --list --timeout-ms 10000' >/dev/null 2>&1; then
    fail "kafka mTLS: client WITHOUT certificate was accepted"
  else
    pass "kafka mTLS: client without certificate rejected"
  fi
  # 3. host listener presents a cert signed by our CA and demands a client cert
  if openssl s_client -connect localhost:9093 -CAfile "$CA" -verify_return_error </dev/null 2>&1 \
    | grep -q "Verification: OK"; then
    pass "kafka host listener localhost:9093 certificate verifies against demo CA"
  else
    fail "kafka host listener certificate did not verify"
  fi
fi

if want es && running elasticsearch; then
  pw=$(envval ELASTIC_PASSWORD)
  code=$(curl -s -o /dev/null -w '%{http_code}' --cacert "$CA" -u "elastic:$pw" \
    'https://localhost:9200/_cluster/health')
  [[ "$code" == 200 ]] && pass "elasticsearch TLS: cluster health 200 (CA verified)" \
    || fail "elasticsearch TLS: HTTP $code"
  health=$(curl -s --cacert "$CA" -u "elastic:$pw" 'https://localhost:9200/_cluster/health' | jq -r .status)
  [[ "$health" == green || "$health" == yellow ]] && pass "elasticsearch status: $health" \
    || fail "elasticsearch status: $health"
  if curl -s -o /dev/null --max-time 5 -u "elastic:$pw" 'https://localhost:9200/' 2>/dev/null; then
    fail "elasticsearch: plain curl without the CA unexpectedly succeeded"
  else
    pass "elasticsearch: untrusted-CA client rejected (curl without --cacert fails)"
  fi
  # client-cert path (mTLS optional on http): CA-signed cert + password still authenticates
  code=$(curl -s -o /dev/null -w '%{http_code}' --cacert "$CA" --cert "$PKI/admin/admin.crt" \
    --key "$PKI/admin/admin.key" -u "elastic:$pw" 'https://localhost:9200/')
  [[ "$code" == 200 ]] && pass "elasticsearch: request with CA-signed client cert accepted" \
    || fail "elasticsearch: client-cert request HTTP $code"
fi

if want odm && running odm; then
  # --cacert (no -k): proves Liberty presents our CA-signed odm certificate
  code=$(curl -s --cacert "$CA" -o /dev/null -w '%{http_code}' https://localhost:9443/res/)
  [[ "$code" == 200 || "$code" == 302 ]] && pass "ODM RES console https://localhost:9443/res -> $code (CA verified)" \
    || fail "ODM RES console HTTP $code"
  code=$(curl -s --cacert "$CA" -o /dev/null -w '%{http_code}' https://localhost:9443/decisioncenter/)
  [[ "$code" == 200 || "$code" == 302 ]] && pass "ODM Decision Center /decisioncenter -> $code (CA verified)" \
    || fail "ODM Decision Center HTTP $code"
  code=$(curl -s --cacert "$CA" -o /dev/null -w '%{http_code}' https://localhost:9443/DecisionRunner/)
  [[ "$code" == 200 || "$code" == 302 ]] && pass "ODM Decision Runner /DecisionRunner -> $code (CA verified)" \
    || fail "ODM Decision Runner HTTP $code"
fi

if want kibana && running kibana; then
  code=$(curl -s -o /dev/null -w '%{http_code}' --cacert "$CA" https://localhost:5601/api/status)
  [[ "$code" == 200 || "$code" == 401 ]] && pass "kibana HTTPS :5601 -> $code (CA verified)" \
    || fail "kibana HTTP $code"
fi

echo
[[ $failures -eq 0 ]] && echo "verify-mtls: all checks passed" || echo "verify-mtls: $failures check(s) failed"
exit $((failures > 0))
