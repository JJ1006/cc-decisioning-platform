#!/usr/bin/env bash
# Deploys a RuleApp archive to ODM RES over HTTPS and checks the ruleset is listed.
#   scripts/deploy-ruleapp.sh [path/to/ccApprovalApp.jar]      (make deploy-rules)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=res-env.sh
source "$ROOT/scripts/res-env.sh"
JAR=${1:-$ROOT/rules/ruleapps/ccApprovalApp.jar}
API=${RES_API:-$RES_URL/res/apiauth/v1}

[[ -f "$JAR" ]] || { echo "NEED USER: RuleApp archive missing: $JAR (export it from Rule Designer, see rules/README.md)" >&2; exit 2; }

echo "deploying $(basename "$JAR") to $RES_URL ..."
resp=$(mktemp); trap 'rm -f "$resp"' EXIT
code=$(res_curl -o "$resp" -w '%{http_code}' -X POST -F "file=@$JAR;type=application/java-archive" "$API/ruleapps")
if [[ "$code" != 2* ]]; then
  echo "deploy failed: HTTP $code" >&2; cat "$resp" >&2; echo >&2; exit 1
fi

# Verify: the ruleset must now be listed under the RuleApp.
list=$(res_curl "$API/ruleapps/$RULEAPP/$RULEAPP_VERSION/rulesets" -H 'Accept: application/json')
if jq -e --arg rs "$RULESET" '.. | objects | select(.id? == $rs or .name? == $rs)' >/dev/null 2>&1 <<<"$list"; then
  echo "PASS  ruleset $RULESET listed under /$RULEAPP/$RULEAPP_VERSION"
else
  echo "FAIL  ruleset $RULESET not listed under /$RULEAPP/$RULEAPP_VERSION: $list" >&2
  exit 1
fi
