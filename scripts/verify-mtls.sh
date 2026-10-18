#!/usr/bin/env bash
# Verifies the local stack from the outside: TLS/mTLS reachability and rejection of bad clients, the gateway (identity, roles,
# rate limits) and the paths that try to bypass it. Run after `make up PROFILE=core,analytics,edge`. Checks only what is
# running; prints PASS/FAIL per check.
#   scripts/verify-mtls.sh            all running services
#   scripts/verify-mtls.sh gateway    only the gateway checks (kafka | es | odm | kibana | gateway | bypass)
# The burst checks use the demo-partner and load-test consumers: they spend up to a minute of their quota (100 per minute).
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PKI=${PKI_OUT:-$ROOT/security/pki/out}
CA=$PKI/ca/ca.crt
DC="$ROOT/scripts/compose.sh"
export PROFILE=${PROFILE:-core,analytics,edge}
only=${1:-all}
failures=0

pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; failures=$((failures + 1)); }
skip() { printf 'SKIP  %s\n' "$1"; }
want() { [[ "$only" == all || "$only" == "$1" ]]; }
running() { [[ -n "$("$DC" ps --status running --services 2>/dev/null | grep -x "$1")" ]]; }
envval() { grep -E "^$1=" "$ROOT/.env" | tail -1 | cut -d= -f2-; }
source "$ROOT/scripts/res-env.sh" # RES_USER, RES_PASS: the ODM for Developers demo user
gw() { "$ROOT/scripts/api-call.sh" "$@"; } # gw CLIENT [curl args] /path : through the gateway with that consumer's certificate

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
# a certificate from ANOTHER CA that claims a real consumer's name: must be refused wherever our CA is required
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=demo-partner" -keyout "$WORK/other.k" -out "$WORK/other.c" 2>/dev/null

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
  # 4. audit: every Kafka client of the stack has its own certificate; the only plaintext listener is the KRaft controller,
  #    which is not published on the host
  if "$DC" port kafka 9095 >/dev/null 2>&1; then
    fail "kafka: the controller listener 9095 is published on the host"
  else
    pass "kafka: the controller listener (the only plaintext one) is not published"
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
  # the PKI realm (certificate login) is not part of the basic licence: Logstash and the query API use TLS plus their own
  # least-privilege users (ADR-0011)
  pki_available=$(curl -s --cacert "$CA" -u "elastic:$pw" 'https://localhost:9200/_xpack/usage' | jq -r '.security.realms.pki.available')
  [[ "$pki_available" == false ]] && pass "elasticsearch: PKI realm not available on this licence (documented fallback: TLS + least-privilege users)" \
    || skip "elasticsearch: PKI realm is available (licence upgraded?): ADR-0011 could switch Logstash and the query API to certificates"
fi

if want odm && running odm; then
  odm_get() { curl -s --cacert "$CA" --cert "$PKI/admin/admin.crt" --key "$PKI/admin/admin.key" -o /dev/null -w '%{http_code}' "$1"; }
  # with the admin client certificate, our CA verified (no -k)
  code=$(odm_get https://localhost:9443/res/)
  [[ "$code" == 200 || "$code" == 302 ]] && pass "ODM RES console https://localhost:9443/res -> $code (mutual TLS, CA verified)" \
    || fail "ODM RES console HTTP $code"
  code=$(odm_get https://localhost:9443/decisioncenter/)
  [[ "$code" == 200 || "$code" == 302 ]] && pass "ODM Decision Center /decisioncenter -> $code (mutual TLS)" \
    || fail "ODM Decision Center HTTP $code"
  code=$(odm_get https://localhost:9443/DecisionRunner/)
  [[ "$code" == 200 || "$code" == 302 ]] && pass "ODM Decision Runner /DecisionRunner -> $code (mutual TLS)" \
    || fail "ODM Decision Runner HTTP $code"
  # without a client certificate, and with one from another CA: refused in the handshake
  if curl -s --max-time 10 --cacert "$CA" -o /dev/null https://localhost:9443/res/; then
    fail "ODM: a client WITHOUT certificate was accepted"
  else
    pass "ODM: client without certificate rejected (handshake)"
  fi
  if curl -s --max-time 10 --cacert "$CA" --cert "$WORK/other.c" --key "$WORK/other.k" -o /dev/null https://localhost:9443/res/; then
    fail "ODM: a certificate from another CA was accepted"
  else
    pass "ODM: certificate from another CA rejected"
  fi
  # the REST API of RES: the orchestrator's certificate plus basic auth (what the orchestrator does)
  code=$(curl -s --cacert "$CA" --cert "$PKI/decision-orchestrator/decision-orchestrator.crt" --key "$PKI/decision-orchestrator/decision-orchestrator.key" \
    -K <(printf 'user = "%s:%s"\n' "$RES_USER" "$RES_PASS") -o /dev/null -w '%{http_code}' \
    https://localhost:9443/res/apiauth/v1/ruleapps)
  [[ "$code" == 200 ]] && pass "ODM RES REST with the orchestrator certificate and basic auth -> 200" \
    || fail "ODM RES REST with the orchestrator certificate -> HTTP $code"
  # the plain dev port is not published
  if curl -s --max-time 3 -o /dev/null http://localhost:9060/res/; then
    if [[ -n "${COMPOSE_DEBUG:-}" ]]; then skip "ODM plain port 9060 is published (COMPOSE_DEBUG)"; else fail "ODM plain http port 9060 is reachable from the host"; fi
  else
    pass "ODM: plain http port 9060 is not published"
  fi
fi

if want kibana && running kibana; then
  code=$(curl -s -o /dev/null -w '%{http_code}' --cacert "$CA" https://localhost:5601/api/status)
  [[ "$code" == 200 || "$code" == 401 ]] && pass "kibana HTTPS :5601 -> $code (CA verified; browser UI with login, no client certificate)" \
    || fail "kibana HTTP $code"
fi

if want gateway && running apisix; then
  jq '.payload.application | del(.applicationId)' "$ROOT/contracts/examples/application-submitted.example.json" >"$WORK/app.json"
  post_app() { gw demo-partner -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' "$@" --data @"$WORK/app.json" /v1/applications; }

  code=$(post_app)
  [[ "$code" == 202 ]] && pass "gateway: demo-partner certificate, POST /v1/applications -> 202" || fail "gateway: demo-partner POST -> HTTP $code"
  code=$(gw analyst -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data @"$WORK/app.json" /v1/applications)
  [[ "$code" == 403 ]] && pass "gateway: analyst certificate, POST /v1/applications -> 403 (role ANALYST may not submit)" || fail "gateway: analyst POST -> HTTP $code"
  if curl -s --max-time 10 --cacert "$CA" -o /dev/null https://localhost:8443/v1/stats/latency; then
    fail "gateway: a request WITHOUT client certificate was accepted"
  else
    pass "gateway: no client certificate -> TLS handshake refused"
  fi
  if curl -s --max-time 10 --cacert "$CA" --cert "$WORK/other.c" --key "$WORK/other.k" -o /dev/null https://localhost:8443/v1/stats/latency; then
    fail "gateway: a certificate from another CA (CN=demo-partner) was accepted"
  else
    pass "gateway: self-signed certificate from another CA (CN=demo-partner) -> refused"
  fi
  # identity is the certificate, never a header: a partner who sends the underwriter's headers is still a partner
  code=$(gw demo-partner -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H 'X-Role: UNDERWRITER' \
    -H 'X-Consumer: underwriter-portal' --data '{"outcome":"DECLINE","creditLimit":0,"reason":"x","underwriterId":"UW-1"}' \
    /v1/applications/NOPE/manual-decision)
  [[ "$code" == 403 ]] && pass "gateway: partner certificate with spoofed X-Role: UNDERWRITER -> 403 (still PARTNER)" || fail "gateway: spoofed role -> HTTP $code"
  code=$(gw demo-partner -s -o /dev/null -w '%{http_code}' -H 'X-Role: ANALYST' /v1/stats/latency)
  [[ "$code" == 403 ]] && pass "gateway: partner certificate with spoofed X-Role: ANALYST on /v1/stats -> 403" || fail "gateway: spoofed analyst role -> HTTP $code"
  if running decision-query-api; then
    title=$(gw demo-partner -s /v1/applications/NOPE/status | jq -r .title)
    [[ "$title" == "Application not found" ]] && pass "gateway: partner may call GET /v1/applications/{id}/status (the 404 is the service's problem+json, not a missing route)" || fail "gateway: partner status -> $title"
  else
    skip "gateway: partner status needs decision-query-api (analytics profile)"
  fi
  headers=$(gw analyst -s -I /v1/stats/latency)
  if grep -qi '^strict-transport-security:' <<<"$headers" && ! grep -qiE '^server: .*[0-9]' <<<"$headers"; then
    pass "gateway: HSTS present, no server version banner"
  else
    fail "gateway: response hardening missing: $(grep -iE '^(server|strict)' <<<"$headers" | tr -d '\r' | tr '\n' ' ')"
  fi
  corr=$(gw analyst -s -I -H 'X-Correlation-Id: verify-mtls-1' /v1/stats/latency | tr -d '\r' | grep -i '^x-correlation-id:' | cut -d' ' -f2)
  [[ "$corr" == verify-mtls-1 ]] && pass "gateway: X-Correlation-Id from the client is kept and echoed" || fail "gateway: correlation id not echoed ($corr)"

  # the AI agent consumers (P9): mcp-agent reads decisions and statistics, nothing else; mcp-client reaches only /mcp/dc
  code=$(gw mcp-agent -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
    --data '{"outcome":"APPROVE","creditLimit":3000,"product":"NO_FEE","reason":"agent","underwriterId":"UW-1"}' /v1/applications/NOPE/manual-decision)
  [[ "$code" == 403 ]] && pass "gateway: mcp-agent certificate, POST manual-decision -> 403 (an agent never decides)" || fail "gateway: mcp-agent manual decision -> HTTP $code"
  code=$(gw mcp-agent -s -o /dev/null -w '%{http_code}' /v1/ops/dlq)
  [[ "$code" == 403 ]] && pass "gateway: mcp-agent certificate, GET /v1/ops/dlq -> 403" || fail "gateway: mcp-agent ops -> HTTP $code"
  if running decision-query-api; then
    code=$(gw mcp-agent -s -o /dev/null -w '%{http_code}' /v1/stats/latency)
    [[ "$code" == 200 ]] && pass "gateway: mcp-agent certificate, GET /v1/stats/latency -> 200" || fail "gateway: mcp-agent stats -> HTTP $code"
  else
    skip "gateway: mcp-agent stats needs decision-query-api (analytics profile)"
  fi
  if curl -s --max-time 10 --cacert "$CA" -o /dev/null -X POST -H 'Content-Type: application/json' --data '{}' https://localhost:8443/mcp/dc; then
    fail "gateway: /mcp/dc answered a request WITHOUT client certificate"
  else
    pass "gateway: /mcp/dc without client certificate -> TLS handshake refused"
  fi
  code=$(gw analyst -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{}' /mcp/dc)
  [[ "$code" == 403 ]] && pass "gateway: analyst certificate on /mcp/dc -> 403 (role MCP_CLIENT only)" || fail "gateway: analyst on /mcp/dc -> HTTP $code"
  if running odm-dc-mcp; then
    code=$(gw mcp-client -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
      --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"verify-mtls","version":"0"}}}' /mcp/dc)
    [[ "$code" == 200 ]] && pass "gateway: mcp-client certificate, MCP initialize on /mcp/dc -> 200" || fail "gateway: MCP initialize -> HTTP $code"
  else
    skip "gateway: MCP initialize needs the mcp profile (odm-dc-mcp)"
  fi

  # spike arrest on POST /v1/applications: 5 per second per consumer, burst 5
  n429=0
  for _ in $(seq 1 40); do
    c=$(gw demo-partner -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{}' /v1/applications)
    [[ "$c" == 429 ]] && n429=$((n429 + 1))
  done
  [[ "$n429" -ge 1 ]] && pass "gateway: spike arrest, burst of 40 POST /v1/applications -> $n429 x 429" || fail "gateway: no 429 in a burst of 40 POSTs"
  # quota: 100 per minute per consumer (load-test is a dedicated consumer)
  n200=0
  n429=0
  for _ in $(seq 1 130); do
    c=$(gw load-test -s -o /dev/null -w '%{http_code}' /v1/stats/latency)
    [[ "$c" != 429 ]] && n200=$((n200 + 1)) # answered by the service (200, or 503 when decision-query-api is not running)
    [[ "$c" == 429 ]] && n429=$((n429 + 1))
  done
  [[ "$n429" -ge 1 && "$n200" -ge 1 ]] && pass "gateway: quota, 130 requests by one consumer -> $n200 answered then $n429 x 429" || fail "gateway: quota not enforced ($n200 answered, $n429 x 429)"
fi

if want bypass && running apisix; then
  # the services publish no host port (unless COMPOSE_DEBUG is set)
  if [[ -n "${COMPOSE_DEBUG:-}" ]]; then
    skip "bypass: host ports are published (COMPOSE_DEBUG)"
  else
    open=""
    for port in 8081 8082 8083 8084 8085; do
      curl -s --max-time 2 -o /dev/null -k "https://localhost:$port/" && open="$open $port"
    done
    [[ -z "$open" ]] && pass "bypass: service ports 8081-8085 are not reachable from the host" || fail "bypass: reachable from the host:$open"
  fi
  # inside the network, a service port answers only the certificates its filter allows
  inside() { "$DC" exec -T "$1" sh -c "curl -sk -o /dev/null -w '%{http_code}' $2 https://$3:8080$4" 2>/dev/null; }
  if running txn-generator && running decision-query-api; then
    code=$(inside txn-generator '' decision-query-api /v1/stats/latency)
    [[ "$code" == 000 ]] && pass "bypass: decision-query-api, no client certificate -> handshake refused" || fail "bypass: decision-query-api without certificate -> HTTP $code"
    code=$(inside txn-generator '--cert-type P12 --cert "/pki/keystore.p12:$KEYSTORE_PASSWORD"' decision-query-api /v1/stats/latency)
    [[ "$code" == 403 ]] && pass "bypass: decision-query-api with txn-generator's own valid certificate -> 403 (CN not allowed)" || fail "bypass: txn-generator certificate -> HTTP $code"
    code=$("$DC" exec -T application-api sh -c "curl -sk -o /dev/null -w '%{http_code}' --cert-type P12 --cert \"/pki/keystore.p12:\$KEYSTORE_PASSWORD\" https://decision-query-api:8080/v1/stats/latency" 2>/dev/null)
    [[ "$code" == 200 ]] && pass "service to service: application-api certificate -> decision-query-api 200 (mutual TLS)" || fail "service to service: application-api -> decision-query-api HTTP $code"
    code=$(inside decision-query-api '--cert-type P12 --cert "/pki/keystore.p12:$KEYSTORE_PASSWORD"' application-api /v1/applications/NOPE/status)
    [[ "$code" == 403 ]] && pass "bypass: application-api with decision-query-api's certificate -> 403 (only the gateway may call it)" || fail "bypass: decision-query-api certificate on application-api -> HTTP $code"
  else
    skip "bypass: needs txn-generator and decision-query-api"
  fi
fi

echo
[[ $failures -eq 0 ]] && echo "verify-mtls: all checks passed" || echo "verify-mtls: $failures check(s) failed"
exit $((failures > 0))
