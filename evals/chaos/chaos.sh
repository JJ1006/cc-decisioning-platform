#!/usr/bin/env bash
# Chaos drills on the compose stack: break something in the middle of a load, then prove that nothing was lost.
#   evals/chaos/chaos.sh kafka|odm|logstash          (make chaos SCENARIO=kafka)
#   kafka     stop the broker for 20 s mid-run. Accounting: every record Kafka acknowledged is decided or dead-lettered (sent - failed <= decided + dead letters <= sent), no event id twice.
#   odm       stop ODM for 60 s mid-run: the orchestrator's circuit breaker opens, calls retry, every application is decided or dead-lettered, an alert fired.
#   logstash  kill -9 Logstash mid-run (its persistent queue and Kafka offsets must carry the rest): Elasticsearch ends with every decision.
# Needs: make up PROFILE=core,analytics,edge (analytics for Elasticsearch, Kibana alerts and the query API) and the rules deployed. Report: evals/chaos/out/<scenario>.md.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
SCENARIO=${1:-}
[[ "$SCENARIO" == kafka || "$SCENARIO" == odm || "$SCENARIO" == logstash ]] || { echo "usage: chaos.sh kafka|odm|logstash" >&2; exit 2; }
export PROFILE=${PROFILE:-core,analytics,edge}
OUT=$ROOT/evals/chaos/out
mkdir -p "$OUT"
REPORT=$OUT/$SCENARIO.md

gen() { scripts/api-call.sh ops-admin -f "$@"; }
es() { scripts/es-curl.sh "$@"; }
dc() { scripts/compose.sh "$@"; }
refresh() { es -X POST "/cc-*/_refresh" >/dev/null 2>&1 || true; }
note() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*"; }
need() { echo "NEED USER: $*" >&2; exit 3; }

running() { [[ -n "$(dc ps --status running --services 2>/dev/null | grep -x "$1")" ]]; }
for s in kafka elasticsearch decision-orchestrator txn-generator logstash apisix; do running "$s" || need "$s is not running: make up PROFILE=$PROFILE"; done
[[ "$SCENARIO" == odm ]] && { running odm || need "odm is not running"; running kibana || need "kibana is not running (its alert rules are part of the odm drill): docker compose start kibana, or make up PROFILE=$PROFILE"
  for _ in $(seq 1 60); do [[ "$(docker inspect -f '{{.State.Health.Status}}' "$(dc ps -q kibana)" 2>/dev/null)" == healthy ]] && break; sleep 5; done # a Kibana that is still starting runs no rules
  [[ "$(docker inspect -f '{{.State.Health.Status}}' "$(dc ps -q kibana)" 2>/dev/null)" == healthy ]] || need "kibana is not healthy"; }
gen /admin/generator/status >/dev/null 2>&1 || need "the generator is not reachable through the gateway (make up PROFILE=$PROFILE; ops-admin certificate)"

# a 7 GB Docker VM cannot hold the whole stack while a component is being broken and recovered (ODM was OOM-killed with Kibana and kafka-ui running): they are
# not part of the decision path, so they are stopped for the drill (KEEP_UI=1 to keep them) and started again at the end
FREED=()
if [[ -z "${KEEP_UI:-}" ]]; then
  svcs=(kibana kafka-ui)
  [[ $SCENARIO == odm ]] && svcs=(kafka-ui) # the alert check needs Kibana's rules; ODM itself is down for a minute, which frees 1.5 GB
  for svc in "${svcs[@]}"; do
    if running "$svc"; then dc stop "$svc" >/dev/null 2>&1 && FREED+=("$svc"); fi
  done
fi
trap '[[ ${#FREED[@]} -eq 0 ]] || dc start "${FREED[@]}" >/dev/null 2>&1' EXIT

# ---- the plan per scenario
case $SCENARIO in
  kafka) TOTAL=300 TPS=15 POISON=0.02 BREAK_AFTER=5 BREAK_FOR=20 ;;
  odm) TOTAL=600 TPS=10 POISON=0.01 BREAK_AFTER=5 BREAK_FOR=60 ;;
  logstash) TOTAL=300 TPS=15 POISON=0.02 BREAK_AFTER=5 BREAK_FOR=15 ;;
esac

# ---- baselines (earlier runs stay in the indexes): everything is counted since START
START=$(date -u +%Y-%m-%dT%H:%M:%SZ)
note "scenario $SCENARIO: $TOTAL applications at $TPS/s, poison rate $POISON; fault after ${BREAK_AFTER}s for ${BREAK_FOR}s (from $START)"
SINCE="{\"range\":{\"@timestamp\":{\"gte\":\"$START\"}}}"
count_decided() { # unique application ids decided by the rules (ODM) since START
  refresh
  es -X POST -H 'Content-Type: application/json' -d "{\"size\":0,\"query\":{\"bool\":{\"filter\":[$SINCE,{\"term\":{\"decisionSource\":\"ODM\"}}]}},\"aggs\":{\"u\":{\"cardinality\":{\"field\":\"payload.applicationId\",\"precision_threshold\":40000}},\"e\":{\"cardinality\":{\"field\":\"eventId\",\"precision_threshold\":40000}}}}" "/cc-decisions/_search" \
    | jq -c '{docs:.hits.total.value, apps:.aggregations.u.value, events:.aggregations.e.value}'
}
count_dlq() { # dead letters of the submitted topic since START (poison messages and anything the orchestrator gave up on)
  refresh
  es -X POST -H 'Content-Type: application/json' -d "{\"size\":0,\"query\":{\"bool\":{\"filter\":[{\"range\":{\"failedAt\":{\"gte\":\"$START\"}}},{\"term\":{\"originalTopic\":\"cc.applications.submitted.v1\"}}]}},\"aggs\":{\"u\":{\"cardinality\":{\"field\":\"eventId\"}}}}" "/cc-dlq/_search" \
    | jq -c '{docs:.hits.total.value, unique:.aggregations.u.value}'
}

# ---- load + fault
gen -X POST "/admin/generator/start?total=$TOTAL&tps=$TPS&poisonRate=$POISON" | jq -c . || need "could not start the generator"
sleep "$BREAK_AFTER"
FAULT_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
case $SCENARIO in
  kafka) note "STOP kafka"; dc stop kafka >/dev/null 2>&1 ;;
  odm) note "STOP odm"; dc stop odm >/dev/null 2>&1 ;;
  logstash) note "KILL -9 logstash"; dc kill -s KILL logstash >/dev/null 2>&1 ;;
esac
sleep "$BREAK_FOR"
case $SCENARIO in
  kafka) note "START kafka"; dc start kafka >/dev/null 2>&1 ;;
  odm) note "START odm"; dc start odm >/dev/null 2>&1 ;;
  logstash) note "START logstash"; dc start logstash >/dev/null 2>&1 ;;
esac
HEAL_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)

if [[ $SCENARIO == odm ]]; then # a restarted ODM has lost its rules (non-persistent database): the deployment is part of the recovery
  note "waiting for ODM to be healthy, then redeploying the rules"
  for _ in $(seq 1 60); do [[ "$(docker inspect -f '{{.State.Health.Status}}' "$(dc ps -q odm)" 2>/dev/null)" == healthy ]] && break; sleep 10; done
  scripts/ensure-rules.sh >"$OUT/odm-redeploy.log" 2>&1 || note "WARN rules redeploy failed, see $OUT/odm-redeploy.log"
fi

# ---- wait for the generator to finish and the system to drain (no change for 90 s)
while [[ $(gen /admin/generator/status 2>/dev/null | jq -r .running) == true ]]; do sleep 3; done
# the generator reports "running: false" when it has HANDED OVER its last record: the producer then reports delivery failures asynchronously (here: after its delivery
# timeout, up to a minute after the outage). Wait until the failure count has been stable for 20 s: only then do we know how many records Kafka acknowledged.
prev=-1
stable_since=$(date +%s)
while true; do
  status=$(gen /admin/generator/status)
  failed_now=$(jq -r '.failed // 0' <<<"$status")
  if [[ "$failed_now" != "$prev" ]]; then prev=$failed_now; stable_since=$(date +%s); fi
  [[ $(( $(date +%s) - stable_since )) -ge 20 ]] && break
  sleep 5
done
SENT=$(jq -r .sent <<<"$status")
FAILED=$(jq -r '.failed // 0' <<<"$status")
POISON_SENT=$(jq -r .poison <<<"$status")
ACKED=$((SENT - FAILED))
note "generator finished: sent=$SENT failed=$FAILED (refused to the producer, so not acknowledged) acknowledged=$ACKED poison=$POISON_SENT"
last=""
stable=0
for _ in $(seq 1 120); do
  d=$(count_decided)
  l=$(count_dlq)
  total=$(( $(jq -r .apps <<<"$d") + $(jq -r .unique <<<"$l") ))
  [[ "$total" -ge "$ACKED" ]] && break
  [[ "$total" == "$last" ]] && stable=$((stable + 1)) || stable=0
  last=$total
  [[ $stable -ge 9 ]] && break
  sleep 10
done
sleep 5
D=$(count_decided)
L=$(count_dlq)
DECIDED=$(jq -r .apps <<<"$D")
DOCS=$(jq -r .docs <<<"$D")
EVENTS=$(jq -r .events <<<"$D")
DLQ=$(jq -r .unique <<<"$L")

# ---- alert (odm): the Kibana rules write a document to cc-alerts when they fire
ALERTS=""
if [[ $SCENARIO == odm ]]; then
  for _ in $(seq 1 24); do
    refresh
    ALERTS=$(es -X POST -H 'Content-Type: application/json' -d "{\"size\":5,\"query\":{\"range\":{\"@timestamp\":{\"gte\":\"$FAULT_AT\"}}}}" "/cc-alerts/_search" 2>/dev/null | jq -r '[.hits.hits[]._source.rule] | unique | join("; ")' 2>/dev/null)
    [[ -n "$ALERTS" ]] && break
    sleep 10
  done
  BREAKER=$(es -X POST -H 'Content-Type: application/json' -d "{\"size\":0,\"query\":{\"bool\":{\"filter\":[{\"range\":{\"@timestamp\":{\"gte\":\"$FAULT_AT\"}}}],\"must\":[{\"match_phrase\":{\"message\":\"circuit breaker\"}}]}}}" "/cc-logs/_search" 2>/dev/null | jq -r '.hits.total.value // 0')
fi

# ---- verdict
fail=0
verdict() { # verdict NAME ACTUAL EXPECTED
  if [[ "$2" == "$3" ]]; then echo "PASS  $1 ($2)"; else echo "FAIL  $1: expected $3, got $2"; fail=1; fi
}
{
  echo "# Chaos: $SCENARIO"
  echo
  echo "Load $TOTAL applications at $TPS per second (poison rate $POISON). Fault at $FAULT_AT, healed at $HEAL_AT. Counted since $START."
  echo
  echo "| Count | Value |"
  echo "|---|---|"
  echo "| Handed to the producer by the generator (sent) | $SENT |"
  echo "| Delivery failures reported to the producer (not acknowledged by Kafka; a real client gets an error) | $FAILED |"
  echo "| **Acknowledged by Kafka** (sent - failed) | $ACKED |"
  echo "| Decided by the rules (unique application ids in cc-decisions) | $DECIDED |"
  echo "| Dead letters of the submitted topic (unique) | $DLQ |"
  echo "| Decision documents / unique event ids | $DOCS / $EVENTS |"
  [[ $SCENARIO == odm ]] && echo "| Circuit breaker log lines | ${BREAKER:-0} |" && echo "| Alerts fired since the fault | ${ALERTS:-none} |"
  echo
} >"$REPORT"
{
  # everything Kafka acknowledged must be decided or dead-lettered (a failed send that actually reached the broker may add one or two: at-least-once)
  if [[ $((DECIDED + DLQ)) -ge $ACKED && $((DECIDED + DLQ)) -le $SENT ]]; then echo "PASS  no acknowledged application lost: acknowledged $ACKED <= decided + dead-lettered $((DECIDED + DLQ)) <= sent $SENT"
  else echo "FAIL  acknowledged $ACKED, decided + dead-lettered $((DECIDED + DLQ)), sent $SENT"; fail=1; fi
  verdict "no event id twice in Elasticsearch" "$DOCS" "$EVENTS"
  [[ $SCENARIO == odm ]] && { [[ "${BREAKER:-0}" -gt 0 ]] && echo "PASS  the circuit breaker logged its state changes (${BREAKER})" || { echo "FAIL  no circuit breaker log line after the fault"; fail=1; }; }
  [[ $SCENARIO == odm ]] && { [[ -n "$ALERTS" ]] && echo "PASS  an alert fired: $ALERTS" || { echo "FAIL  no alert fired within 4 minutes of the fault"; fail=1; }; }
} | tee -a "$REPORT.verdict"
{ echo "## Verdict"; echo; sed 's/^/- /' "$REPORT.verdict"; } >>"$REPORT"
rm -f "$REPORT.verdict"
echo
cat "$REPORT"
exit $fail
