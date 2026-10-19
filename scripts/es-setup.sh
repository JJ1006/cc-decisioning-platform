#!/usr/bin/env bash
# One-shot Elasticsearch bootstrap (compose service es-setup):
#   1. set the kibana_system password
#   2. create the least-privilege role and user logstash_writer: create_doc on the five write aliases plus cluster
#      monitor (the Logstash output reads GET / at start-up), nothing else
#      and role / user query_reader / decision-query-api: read-only on the five read aliases (cc-decisions ...)
#      and the Kibana roles kibana_analyst / kibana_ops with the users analyst / ops (dashboards of the space maple-northern),
#      and the observability users: metrics_exporter (monitor only, for elasticsearch-exporter), grafana_logs_reader (read cc-logs)
#      plus the cc-alerts index the alert rules write to
#   3. install the ILM policies (logs 7d, dlq 30d) and the index templates
#   4. make sure every type has a current index behind its <type>-write alias:
#        cc-decisions / cc-audit / cc-notifications  date-suffixed (cc-decisions-2026.10.17), rolled by re-running this script
#        cc-logs / cc-dlq / cc-decision-inputs       <type>-000001, rolled and deleted by ILM (logs 7d, dead letters 30d, inputs 90d)
# Env: ELASTIC_PASSWORD, PRIVACY_OPS_PASSWORD, KIBANA_PASSWORD, LOGSTASH_WRITER_PASSWORD, QUERY_API_PASSWORD, ANALYST_PASSWORD, OPS_PASSWORD, OBS_EXPORTER_PASSWORD, GRAFANA_ES_PASSWORD (required); ES_URL, ES_CACERT, ES_DIR (optional).
set -euo pipefail
ES_URL=${ES_URL:-https://elasticsearch:9200}
ES_CACERT=${ES_CACERT:-/pki/ca.crt}
ES_DIR=${ES_DIR:-/es}
: "${ELASTIC_PASSWORD:?}" "${KIBANA_PASSWORD:?}" "${LOGSTASH_WRITER_PASSWORD:?}" "${QUERY_API_PASSWORD:?}" "${ANALYST_PASSWORD:?}" "${OPS_PASSWORD:?}" "${OBS_EXPORTER_PASSWORD:?}" "${GRAFANA_ES_PASSWORD:?}" "${PRIVACY_OPS_PASSWORD:?}"

es() { # es METHOD PATH [curl args...]; credentials via a curl config, never on the command line
  local method=$1 path=$2
  shift 2
  curl -sS --cacert "$ES_CACERT" -K <(printf 'user = "elastic:%s"\n' "$ELASTIC_PASSWORD") \
    -X "$method" -H 'Content-Type: application/json' "$@" "$ES_URL$path"
}
must() { # must METHOD PATH [BODY | @FILE]  -> fails the script unless HTTP 2xx
  local code
  code=$(es "$1" "$2" -o /tmp/es-out.json -w '%{http_code}' ${3:+--data-binary "$3"})
  [[ "$code" == 2* ]] || { echo "es-setup: $1 $2 -> HTTP $code: $(cat /tmp/es-out.json)" >&2; exit 1; }
}

must POST /_security/user/kibana_system/_password "{\"password\":\"$KIBANA_PASSWORD\"}"
echo "kibana_system password set"

must PUT /_security/role/logstash_writer "@$ES_DIR/logstash_writer.role.json"
must PUT /_security/user/logstash_writer \
  "{\"password\":\"$LOGSTASH_WRITER_PASSWORD\",\"roles\":[\"logstash_writer\"],\"full_name\":\"Logstash writer (service)\"}"
echo "role and user logstash_writer ready"

must PUT /_security/role/query_reader "@$ES_DIR/query_reader.role.json"
must PUT /_security/user/decision-query-api \
  "{\"password\":\"$QUERY_API_PASSWORD\",\"roles\":[\"query_reader\"],\"full_name\":\"decision-query-api (read only)\"}"
echo "role query_reader and user decision-query-api ready"

must PUT /_security/role/kibana_analyst "@$ES_DIR/kibana_analyst.role.json"
must PUT /_security/role/kibana_ops "@$ES_DIR/kibana_ops.role.json"
must PUT /_security/user/analyst \
  "{\"password\":\"$ANALYST_PASSWORD\",\"roles\":[\"kibana_analyst\"],\"full_name\":\"Analyst (Kibana dashboards)\"}"
must PUT /_security/user/ops \
  "{\"password\":\"$OPS_PASSWORD\",\"roles\":[\"kibana_ops\"],\"full_name\":\"Operations (Kibana dashboards, logs, alerts)\"}"
echo "roles kibana_analyst, kibana_ops and users analyst, ops ready"

must PUT /_security/role/metrics_exporter "@$ES_DIR/metrics_exporter.role.json"
must PUT /_security/user/metrics_exporter \
  "{\"password\":\"$OBS_EXPORTER_PASSWORD\",\"roles\":[\"metrics_exporter\"],\"full_name\":\"elasticsearch-exporter (monitor only)\"}"
must PUT /_security/role/grafana_logs_reader "@$ES_DIR/grafana_logs_reader.role.json"
must PUT /_security/user/grafana_logs_reader \
  "{\"password\":\"$GRAFANA_ES_PASSWORD\",\"roles\":[\"grafana_logs_reader\"],\"full_name\":\"Grafana (reads cc-logs)\"}"
echo "users metrics_exporter and grafana_logs_reader ready"
must PUT /_security/role/privacy_ops "@$ES_DIR/privacy_ops.role.json"
must PUT /_security/user/privacy-ops \
  "{\"password\":\"$PRIVACY_OPS_PASSWORD\",\"roles\":[\"privacy_ops\"],\"full_name\":\"Privacy operations: erasure and access requests (query API)\"}"
echo "role and user privacy-ops ready"
must PUT /_index_template/cc-alerts "@$ES_DIR/cc-alerts.template.json"
code=$(es GET "/cc-alerts" -o /dev/null -w '%{http_code}')
[[ "$code" == 404 ]] && must PUT /cc-alerts "{}" && echo "cc-alerts index created"

must PUT /_ilm/policy/cc-logs-7d "@$ES_DIR/cc-logs-7d.ilm.json"
must PUT /_ilm/policy/cc-dlq-30d "@$ES_DIR/cc-dlq-30d.ilm.json"
must PUT /_ilm/policy/cc-inputs-90d "@$ES_DIR/cc-inputs-90d.ilm.json"

today=$(date -u +%Y.%m.%d)
for type in cc-decisions cc-decision-inputs cc-audit cc-notifications cc-dlq cc-logs; do
  must PUT "/_index_template/$type" "@$ES_DIR/$type.template.json"
  code=$(es GET "/_alias/$type-write" -o /tmp/es-alias.json -w '%{http_code}')
  case $type in cc-logs | cc-dlq | cc-decision-inputs) first="$type-000001" ;; *) first="$type-$today" ;; esac
  if [[ "$code" == 404 ]]; then
    must PUT "/$first" "{\"aliases\":{\"$type-write\":{\"is_write_index\":true}}}"
    echo "$type: created $first behind $type-write"
  elif [[ "$first" == *"$today" ]]; then # date-suffixed types: roll the write alias to today's index
    current=$(grep -o '^{"[^"]*"' /tmp/es-alias.json | cut -d'"' -f2)
    if [[ "$current" == "$first" ]]; then
      echo "$type: $type-write already points at $first"
    else
      must PUT "/$first" "{}"
      must POST /_aliases "{\"actions\":[{\"add\":{\"index\":\"$current\",\"alias\":\"$type-write\",\"is_write_index\":false}},{\"add\":{\"index\":\"$first\",\"alias\":\"$type-write\",\"is_write_index\":true}}]}"
      echo "$type: write alias rolled from $current to $first"
    fi
  else
    echo "$type: $type-write exists (ILM rolls it)"
  fi
done
