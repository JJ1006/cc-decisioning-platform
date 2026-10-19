#!/usr/bin/env bash
# End-to-end demo on the running core stack: deploy the rules if missing, send synthetic applications (a few
# of them poison), wait for the pipeline to drain, then print record counts per Kafka topic and Elasticsearch
# index and check parity and PII masking. A masking canary (an audit event full of personal data) is sent too.
#   scripts/demo.sh [total=1000] [tps=50] [poison-rate=0.01]        (make demo)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
TOTAL=${1:-1000}
TPS=${2:-50}
POISON=${3:-0.01}
gen() { scripts/api-call.sh ops-admin -f "$@"; } # the generator admin API is reached through the gateway (ops-admin certificate)
source scripts/res-env.sh
export PROFILE=core

# 1. the ruleset must be deployed
scripts/ensure-rules.sh
# a freshly deployed (or just restarted) ODM takes a moment to execute the ruleset: wait until RES stops answering 404 for it, whether we deployed or not
for _ in $(seq 1 60); do
  code=$(res_curl -o /dev/null -w '%{http_code}' -X POST "$RES_URL/DecisionService/rest/$RULEAPP/$RULEAPP_VERSION/$RULESET/$RULESET_VERSION" \
    -H 'Content-Type: application/json' -H 'Accept: application/json' -d '{}' 2>/dev/null || echo 000)
  [[ "$code" != 404 && "$code" != 000 ]] && break
  sleep 2
done

kafka_sh() { # kafka_sh TOOL args... : a Kafka CLI inside the broker container over mTLS
  local tool=$1
  shift
  scripts/compose.sh exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka "/opt/kafka/bin/$tool" --bootstrap-server kafka:9093 "$@"
}
topic_records() { # the values of the data records, not offsets: transactional topics also hold commit markers
  kafka_sh kafka-console-consumer.sh --consumer.config /pki/kafka-client.properties --topic "$1" \
    --from-beginning --isolation-level read_committed --timeout-ms 20000 2>/dev/null
}
topic_count() { # notices are at-least-once (a redelivery is an identical event with the same id, Elasticsearch keeps one): count unique ids
  if [[ "$1" == cc.notifications.v1 ]]; then topic_records "$1" | jq -r .eventId | sort -u | wc -l | tr -d ' '; else topic_records "$1" | wc -l | tr -d ' '; fi
}
es() { scripts/es-curl.sh "$@"; }
es_count() { es "/$1/_count" | jq -r '.count // 0'; }
refresh() { es -X POST "/cc-*/_refresh" >/dev/null 2>&1 || true; }

# baselines, taken before this run sends anything (earlier runs stay in the indexes)
base_dec=$(es_count cc-decisions)
base_not=$(es_count cc-notifications)
base_aud=$(es_count cc-audit)
base_dlq=$(es_count cc-dlq)

# 2. masking canary: personal data in an audit event; it must reach Elasticsearch masked
canary_id=$(uuidgen | tr 'A-Z' 'a-z') # unique per run: the same eventId would be (correctly) deduplicated by Elasticsearch
canary='{"eventId":"'"$canary_id"'","correlationId":"canary","eventType":"cc.audit.v1","schemaVersion":1,"occurredAt":"2026-01-01T00:00:00Z","producer":"demo","payload":{"actor":"demo","action":"MASK_CANARY","resourceType":"application","resourceId":"CANARY","result":"SUCCESS","details":{"firstName":"Avery","lastName":"Tremblay","sin":"046454286","postalCode":"M5V 2T6","dateOfBirth":"1990-05-14","line1":"100 Queen St W"}}}'
printf 'CANARY|%s\n' "$canary" | scripts/compose.sh exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:9093 --producer.config /pki/kafka-client.properties --topic cc.audit.v1 \
  --property parse.key=true --property key.separator='|' >/dev/null 2>&1 || echo "WARN could not send the masking canary"

echo "starting generator: total=$TOTAL tps=$TPS poisonRate=$POISON"
gen -X POST "/admin/generator/start?total=$TOTAL&tps=$TPS&poisonRate=$POISON" | jq -c .
while [[ $(gen /admin/generator/status | jq -r .running) == true ]]; do sleep 2; done
status=$(gen /admin/generator/status)
poison=$(jq -r .poison <<<"$status")
sent=$(jq -r .sent <<<"$status")
good=$((sent - poison))
echo "generator done: sent=$sent poison=$poison good=$good"

# 3. wait for Logstash to catch up (decisions are the slowest to appear)
for _ in $(seq 1 90); do
  refresh
  [[ "$(( $(es_count cc-decisions) - base_dec ))" -ge "$good" && "$(( $(es_count cc-notifications) - base_not ))" -ge "$good" ]] && break
  sleep 2
done
sleep 5
refresh

# 4. counts
dec_topics=0
printf '\n%-42s %s\n' "Kafka topic (data records)" "count"
for t in cc.applications.submitted.v1 cc.decisions.approved.v1 cc.decisions.declined.v1 cc.decisions.referred.v1 \
  cc.notifications.v1 cc.audit.v1; do
  c=$(topic_count "$t")
  printf '%-42s %s\n' "$t" "$c"
  case $t in cc.decisions.*) dec_topics=$((dec_topics + c)) ;; esac
  case $t in cc.notifications.v1) notif_topic=$c ;; cc.audit.v1) audit_topic=$c ;; esac
done
dlq_topics=0
for t in $(kafka_sh kafka-topics.sh --command-config /pki/kafka-client.properties --list 2>/dev/null | grep '\.dlq$'); do
  c=$(topic_count "$t")
  [[ "$c" -gt 0 ]] && printf '%-42s %s\n' "$t" "$c"
  dlq_topics=$((dlq_topics + c))
done

printf '\n%-42s %s\n' "Elasticsearch index (via Logstash)" "count"
es_dec=$(es_count cc-decisions)
es_aud=$(es_count cc-audit)
es_not=$(es_count cc-notifications)
es_dlq=$(es_count cc-dlq)
for _ in $(seq 1 30); do # Filebeat scans for new log lines every 10 s
  es_log=$(es_count cc-logs)
  [[ "$es_log" -gt 0 ]] && break
  sleep 2
done
printf '%-42s %s\n' cc-decisions "$es_dec" cc-audit "$es_aud" cc-notifications "$es_not" cc-dlq "$es_dlq" cc-logs "$es_log"

# 5. masking, checked with queries
canary_doc=$(es "/cc-audit/_search?q=eventId:$canary_id&size=1" | jq -c '.hits.hits[0]._source.payload.details // {}')
leaks=$(es "/cc-*/_search?q=046454286&size=0" | jq -r '.hits.total.value // 0')
sin_fields=$(es "/cc-audit/_count?q=_exists_:payload.details.sin" | jq -r '.count // 0')
echo
echo "masking canary as indexed: $canary_doc"
echo "documents containing the raw SIN: $leaks   documents with a sin field: $sin_fields"

# 6. verdict
fail=0
check() { if [[ "$2" == "$3" ]]; then echo "PASS  $1 ($2)"; else echo "FAIL  $1: expected $3, got $2" >&2; fail=1; fi; }
echo
check "decisions: this run indexed = good applications" "$((es_dec - base_dec))" "$good"
check "decisions: Elasticsearch = decision topics (all runs)" "$es_dec" "$dec_topics"
check "audit: this run indexed = good applications + the canary" "$((es_aud - base_aud))" "$((good + 1))"
if [[ "$base_aud" -eq 0 ]]; then check "audit: Elasticsearch = topic" "$es_aud" "$audit_topic"; fi
check "notifications: this run indexed = good applications" "$((es_not - base_not))" "$good"
check "notifications: Elasticsearch = topic" "$es_not" "$notif_topic"
check "dlq: Elasticsearch = all dlq topics (poison included)" "$es_dlq" "$dlq_topics"
check "dlq: this run added the poison messages" "$((es_dlq - base_dlq))" "$poison"
check "no raw SIN anywhere in cc-*" "$leaks" "0"
# the analyst-readable cc-decisions keeps bands only; the exact score, TDS and income live in the restricted cc-decision-inputs
exact=$(ES_USER=analyst es -X POST -H 'Content-Type: application/json' \
  -d '{"query":{"bool":{"should":[{"exists":{"field":"payload.applicantSummary.bureauScore"}},{"exists":{"field":"payload.applicantSummary.tdsRatio"}},{"exists":{"field":"payload.applicantSummary.annualIncome"}}]}}}' \
  /cc-decisions/_count | jq -r '.count // "error"')
check "analyst: no exact score, TDS or income in cc-decisions" "$exact" "0"
check "no sin field in audit details" "$sin_fields" "0"
check "canary name reduced to initials" "$(jq -r '[.firstInitial, .lastInitial, .fsa, .ageBand] | join(" ")' <<<"$canary_doc")" "A. T. M5V 35-44"
[[ "$es_log" -gt 0 ]] && echo "PASS  container logs reach cc-logs ($es_log)" || { echo "FAIL  no logs in cc-logs" >&2; fail=1; }
exit $fail
