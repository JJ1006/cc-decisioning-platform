#!/usr/bin/env bash
# Runs rules/tests/scenarios.json against the deployed ruleset (RES REST execution API over HTTPS)
# and diffs outcome, creditLimit, product, aprTier and sorted reasonCodes against the expectations.
#   scripts/test-ruleset.sh [scenarios.json]      (make test-rules)
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=res-env.sh
source "$ROOT/scripts/res-env.sh"
FILE=${1:-$ROOT/rules/tests/scenarios.json}
URL=${EXEC_URL:-$RES_URL/DecisionService/rest/$RULEAPP/$RULEAPP_VERSION/$RULESET/$RULESET_VERSION}
# Project the fields under test into one canonical shape.
PROJECT='{outcome, creditLimit: (.creditLimit // 0), product, aprTier, reasonCodes: ((.reasonCodes // []) | sort)}'

total=$(jq length "$FILE"); pass=0; fail=0
for i in $(seq 0 $((total - 1))); do
  id=$(jq -r ".[$i].id" "$FILE"); name=$(jq -r ".[$i].name" "$FILE")
  body=$(jq -c ".[$i].request" "$FILE")
  want=$(jq -S ".[$i].expected | $PROJECT" "$FILE")
  raw=$(res_curl -X POST "$URL" -H 'Content-Type: application/json' -H 'Accept: application/json' -d "$body") || raw=""
  got=$(jq -S ".decision | $PROJECT" <<<"$raw" 2>/dev/null) || got=""
  if [[ -n "$got" && "$got" == "$want" ]]; then
    printf 'PASS  %s  %s\n' "$id" "$name"; pass=$((pass + 1))
  else
    printf 'FAIL  %s  %s\n' "$id" "$name"; fail=$((fail + 1))
    diff <(echo "$want") <(echo "${got:-<no decision in response: ${raw:0:300}>}") | sed 's/^/        /' || true
  fi
done
printf '\n%d/%d passed\n' "$pass" "$total"
[[ $fail -eq 0 ]]
