#!/usr/bin/env bash
# Dev loop: regenerate, re-import (replacing any previous cc-approval), build, print build messages.
#   scripts/dc-cycle.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
python3 scripts/gen_dc_project.py >/dev/null
old=$(scripts/dc.sh /v1/decisionservices | jq -r '.elements[] | select(.name=="cc-approval") | .id')
[[ -n "$old" ]] && scripts/dc.sh -X DELETE -o /dev/null "/v1/decisionservices/$old"
scripts/dc.sh -X POST -F "file=@tmp/cc-approval.zip" -o tmp/import.json /v1/decisionservices/import
jq -c '{published, name: .decisionService.name, error, reason}' tmp/import.json
ds=$(jq -r '.decisionService.id // empty' tmp/import.json)
[[ -n "$ds" ]] || exit 1
dep=$(scripts/dc.sh "/v1/decisionservices/$ds/deployments" | jq -r '.elements[0].id')
scripts/dc.sh -X POST -o tmp/build.json "/v1/deployments/$dep/build"
jq -r '.status, (.messages.elements[]? | "\(.severity): \(.message)")' tmp/build.json
