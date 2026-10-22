#!/usr/bin/env bash
# Idempotent: deploys the rules (scripts/deploy-dc.sh) only when the ruleset is not listed in RES. The ODM dev image keeps Rule Execution
# Server and Decision Center in a non-persistent Derby database, so every new ODM container starts without the RuleApp.
#   scripts/ensure-rules.sh           (make up runs it after the stack is healthy; make demo has the same check)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
source scripts/res-env.sh

list=$(res_curl "$RES_URL/res/apiauth/v1/ruleapps/$RULEAPP/$RULEAPP_VERSION/rulesets" -H 'Accept: application/json' 2>/dev/null || true)
if jq -e --arg rs "$RULESET" '.. | objects | select(.id? == $rs or .name? == $rs)' >/dev/null 2>&1 <<<"$list"; then
  echo "rules: $RULEAPP/$RULEAPP_VERSION/$RULESET already deployed"
else
  echo "rules: $RULESET not deployed (ODM keeps rules in a non-persistent Derby database): running deploy-dc.sh"
  scripts/deploy-dc.sh
fi
