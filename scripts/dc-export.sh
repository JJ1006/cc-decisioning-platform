#!/usr/bin/env bash
# Round trip: export the decision service from Decision Center and unpack it into rules/dc-export/
# (committed; the source of truth for what DC holds). Re-run after editing rules in the Business Console.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
ds=$(scripts/dc.sh /v1/decisionservices | jq -r '.elements[] | select(.name=="cc-approval") | .id')
[[ -n "$ds" ]] || { echo "cc-approval not in Decision Center (run make deploy-rules)" >&2; exit 1; }
mkdir -p tmp && scripts/dc.sh -o tmp/dc-export.zip "/v1/decisionservices/$ds/export"
rm -rf rules/dc-export && mkdir -p rules/dc-export && unzip -q tmp/dc-export.zip -d rules/dc-export
echo "exported to rules/dc-export ($(find rules/dc-export -type f | wc -l | tr -d ' ') files)"
