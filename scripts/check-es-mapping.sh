#!/usr/bin/env bash
# After make up: warn when an existing cc-decisions index predates the current mapping (an index keeps the strict
# mapping it was created with). Hint only, never fails. Silent when Elasticsearch is not reachable.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
# CHECK_ES_MAPPING_JSON: a saved _mapping answer, for testing the check without Elasticsearch
if [[ -n "${CHECK_ES_MAPPING_JSON:-}" ]]; then maps=$(cat "$CHECK_ES_MAPPING_JSON"); else maps=$("$ROOT/scripts/es-curl.sh" '/cc-decisions-*/_mapping' 2>/dev/null) || exit 0; fi
[[ -n "$maps" ]] || exit 0
stale=$(jq -r 'if type == "object" then [keys[] as $i | select(.[$i].mappings.properties | has("decisionSource") | not) | $i] | join(" ") else "" end' <<<"$maps" 2>/dev/null)
if [[ -n "$stale" ]]; then
  echo "HINT  stale Elasticsearch indices (older mapping, no decisionSource): $stale" >&2
  echo "HINT  run: make down V=1   (deletes the volumes), then make up again" >&2
fi
exit 0
