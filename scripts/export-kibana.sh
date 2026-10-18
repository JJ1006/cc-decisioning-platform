#!/usr/bin/env bash
# Round trip: writes what is in the live space maple-northern back to observability/kibana/ so edits made in the Kibana UI end up
# in Git (saved-objects.ndjson, alerting/connectors.json, alerting/rules/*.json). Output is sorted and stripped of timestamps
# and versions so a diff shows only real changes.
#   scripts/export-kibana.sh            (make export-kibana)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
KB=$ROOT/observability/kibana
SPACE=maple-northern
ks() { KBN_SPACE=$SPACE "$ROOT/scripts/kibana-curl.sh" "$@"; }

# saved objects (the data views are exported with the dashboards that use them)
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
ks -f -X POST -H 'Content-Type: application/json' \
  -d '{"type":["index-pattern","search","lens","dashboard"],"excludeExportDetails":true,"includeReferencesDeep":false}' \
  /api/saved_objects/_export >"$tmp"
jq -cS 'del(.created_at, .updated_at, .created_by, .updated_by, .version, .managed, .namespaces)' "$tmp" \
  | jq -cs 'sort_by([.type, .id]) | .[]' >"$KB/saved-objects.ndjson"
echo "saved-objects.ndjson: $(wc -l <"$KB/saved-objects.ndjson" | tr -d ' ') objects"

# connectors and rules have UUID ids (Kibana demands it for saved objects with encrypted attributes); rule files are named after the rule
# connectors (secrets are never exported; ours have none)
ks -f /api/actions/connectors \
  | jq '[.[] | select(.id | startswith("6d6f2f5e-0a6c-4f0a-9d10-4d4e5c")) | {id, name, connector_type_id, config, secrets: {}}] | sort_by(.id)' >"$KB/alerting/connectors.json"
echo "connectors.json: $(jq length "$KB/alerting/connectors.json") connectors"

# rules tagged maple-northern, one file each
rm -f "$KB"/alerting/rules/*.json
ks -f '/api/alerting/rules/_find?per_page=100&search_fields=tags&search=maple-northern' \
  | jq -c '.data[]' | while read -r rule; do
  slug=$(jq -r '.name | ascii_downcase | gsub("[^a-z0-9]+"; "-") | sub("^-"; "") | sub("-$"; "")' <<<"$rule")
  jq '{id, name, tags, rule_type_id, consumer, schedule, params,
       actions: [.actions[] | {group, id, params, frequency: (.frequency | {summary, notify_when, throttle})}]}' <<<"$rule" >"$KB/alerting/rules/$slug.json"
done
echo "rules: $(ls "$KB"/alerting/rules | wc -l | tr -d ' ') files"
