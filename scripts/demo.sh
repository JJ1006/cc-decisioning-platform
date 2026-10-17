#!/usr/bin/env bash
# End-to-end demo on the running core stack: deploy the rules if missing, send synthetic applications
# (a few of them poison), wait for the pipeline to drain, then print record counts per Kafka topic and
# Elasticsearch index. The poison messages must be in cc.applications.submitted.v1.dlq.
#   scripts/demo.sh [total=1000] [tps=50] [poison-rate=0.01]        (make demo)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
TOTAL=${1:-1000}
TPS=${2:-50}
POISON=${3:-0.01}
GEN=${GEN_URL:-http://localhost:8081}
source scripts/res-env.sh
export PROFILE=core

# 1. the ruleset must be deployed
list=$(res_curl "$RES_URL/res/apiauth/v1/ruleapps/$RULEAPP/$RULEAPP_VERSION/rulesets" -H 'Accept: application/json' 2>/dev/null || true)
if ! jq -e --arg rs "$RULESET" '.. | objects | select(.id? == $rs or .name? == $rs)' >/dev/null 2>&1 <<<"$list"; then
  echo "RuleApp missing: running deploy-rules"
  scripts/deploy-dc.sh
fi

topic_count() { # data records, not offsets: transactional topics also hold commit markers
  scripts/compose.sh exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server kafka:9093 --consumer.config /pki/kafka-client.properties --topic "$1" \
    --from-beginning --isolation-level read_committed --timeout-ms 8000 2>/dev/null | wc -l | tr -d ' '
}
es_admin() { # admin credentials from the root env file, passed as a curl config
  local pw
  pw=$(grep -E '^ELASTIC_PASSWORD=' "$ROOT/.env" | tail -1 | cut -d= -f2-)
  curl -sS --cacert "$RES_CA" -K <(printf 'user = "elastic:%s"\n' "$pw") "$@"
}
es_count() { es_admin "https://localhost:9200/$1/_count" | jq -r '.count // 0'; }

echo "starting generator: total=$TOTAL tps=$TPS poisonRate=$POISON"
curl -sf -X POST "$GEN/admin/start?total=$TOTAL&tps=$TPS&poisonRate=$POISON" | jq -c .
while [[ $(curl -sf "$GEN/admin/status" | jq -r .running) == true ]]; do sleep 2; done
status=$(curl -sf "$GEN/admin/status")
poison=$(jq -r .poison <<<"$status")
sent=$(jq -r .sent <<<"$status")
good=$((sent - poison))
echo "generator done: sent=$sent poison=$poison good=$good"

# 3. wait for the indexer to catch up
for _ in $(seq 1 90); do
  es_admin -X POST "https://localhost:9200/cc-decisions/_refresh" >/dev/null 2>&1 || true
  [[ "$(es_count cc-decisions)" -ge "$good" ]] && break
  sleep 2
done

echo
printf '%-42s %s\n' "Kafka topic (data records)" "count"
for t in cc.applications.submitted.v1 cc.decisions.approved.v1 cc.decisions.declined.v1 cc.decisions.referred.v1 \
  cc.notifications.v1 cc.audit.v1 cc.applications.submitted.v1.dlq; do
  printf '%-42s %s\n' "$t" "$(topic_count "$t")"
done
echo
printf '%-42s %s\n' "Elasticsearch index" "count"
for i in cc-decisions cc-audit cc-notifications; do printf '%-42s %s\n' "$i" "$(es_count "$i")"; done
echo
dlq=$(topic_count cc.applications.submitted.v1.dlq)
if [[ "$(es_count cc-decisions)" -eq "$good" && "$dlq" -ge "$poison" ]]; then
  echo "PASS  decisions indexed = $good, DLQ holds the $poison poison message(s)"
else
  echo "FAIL  expected decisions=$good (got $(es_count cc-decisions)) and dlq>=$poison (got $dlq)" >&2
  exit 1
fi
