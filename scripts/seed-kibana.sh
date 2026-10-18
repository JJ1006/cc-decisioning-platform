#!/usr/bin/env bash
# Kibana as code, idempotent: the space maple-northern with its settings, the data views, saved searches, Lens visualizations
# and dashboards (observability/kibana/saved-objects.ndjson, imported with overwrite), the alert connectors and rules
# (observability/kibana/alerting/). Roles kibana_analyst and kibana_ops and the users analyst and ops come from scripts/es-setup.sh.
#   scripts/seed-kibana.sh            (make seed-kibana; make up with the analytics profile runs it)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
KB=$ROOT/observability/kibana
SPACE=maple-northern
K="$ROOT/scripts/kibana-curl.sh"
kb() { "$K" "$@"; }                  # default space
ks() { KBN_SPACE=$SPACE "$K" "$@"; } # the Maple Northern space

# 1. wait for Kibana
for _ in $(seq 1 90); do
  [[ "$(kb /api/status 2>/dev/null | jq -r '.status.overall.level // empty')" == available ]] && break
  sleep 2
done
[[ "$(kb /api/status | jq -r '.status.overall.level')" == available ]] || { echo "seed-kibana: Kibana is not available" >&2; exit 1; }

# 2. space and its advanced settings
code=$(kb -o /dev/null -w '%{http_code}' /api/spaces/space/$SPACE)
if [[ "$code" == 200 ]]; then
  kb -X PUT -H 'Content-Type: application/json' -d @"$KB/space.json" /api/spaces/space/$SPACE >/dev/null
  echo "space $SPACE updated"
else
  kb -X POST -H 'Content-Type: application/json' -d @"$KB/space.json" /api/spaces/space >/dev/null
  echo "space $SPACE created"
fi
ks -X POST -H 'Content-Type: application/json' -d @"$KB/settings.json" /api/kibana/settings >/dev/null
echo "space settings applied"

# 3. saved objects: data views, searches, Lens, dashboards
res=$(ks -X POST -F file=@"$KB/saved-objects.ndjson" '/api/saved_objects/_import?overwrite=true')
if [[ "$(jq -r .success <<<"$res")" != true ]]; then
  echo "seed-kibana: import failed: $(jq -c .errors <<<"$res")" >&2
  exit 1
fi
echo "imported $(jq -r .successCount <<<"$res") saved objects (data views, searches, visualizations, dashboards)"

# 4. alert connectors (server log, index cc-alerts) and rules; create, or update when they exist
for c in $(jq -r '.[].id' "$KB/alerting/connectors.json"); do
  body=$(jq -c --arg id "$c" '.[] | select(.id == $id) | del(.id)' "$KB/alerting/connectors.json")
  code=$(ks -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d "$body" "/api/actions/connector/$c")
  if [[ "$code" == 409 || "$code" == 400 ]]; then
    ks -f -o /dev/null -X PUT -H 'Content-Type: application/json' -d "$(jq -c 'del(.connector_type_id)' <<<"$body")" "/api/actions/connector/$c"
  elif [[ "$code" != 200 ]]; then
    echo "seed-kibana: connector $c: HTTP $code" >&2
    exit 1
  fi
  echo "connector $c ready"
done
for f in "$KB"/alerting/rules/*.json; do
  id=$(jq -r .id "$f")
  code=$(ks -o /dev/null -w '%{http_code}' "/api/alerting/rule/$id")
  if [[ "$code" == 200 ]]; then
    ks -f -o /dev/null -X PUT -H 'Content-Type: application/json' \
      -d "$(jq -c '{name, tags, schedule, params, actions}' "$f")" "/api/alerting/rule/$id"
  else
    ks -f -o /dev/null -X POST -H 'Content-Type: application/json' -d "$(jq -c 'del(.id)' "$f")" "/api/alerting/rule/$id"
  fi
  echo "rule $id ready"
done
echo "seed-kibana: done: $(printf 'https://localhost:5601/s/%s/app/dashboards' "$SPACE")"
