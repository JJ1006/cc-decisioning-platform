#!/usr/bin/env bash
# Path A: headless Decision Center flow. Generates the decision service from git sources, imports it into
# Decision Center (replacing any previous copy), builds the RuleApp (fails on build errors), deploys it to
# RES through DC, saves the archive to rules/ruleapps/ccApprovalApp.jar and checks the ruleset is listed.
#   scripts/deploy-dc.sh            (make deploy-rules)
#   RULES_SRC=<dir> RULESET_VERSION=1.1 scripts/deploy-dc.sh   a changed copy of the rule sources as ccApproval 1.1 next to 1.0 (evals/what-if); the jar is not saved
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
source scripts/res-env.sh
SERVER=${DC_SERVER:-Decision Service Execution}
JAR=$ROOT/rules/ruleapps/ccApprovalApp.jar
ZIP=${DC_ZIP:-tmp/cc-approval.zip}
export RULESET_VERSION
VARIANT=0; [[ -n "${RULES_SRC:-}" || "$RULESET_VERSION" != 1.0 ]] && VARIANT=1

# Decision Center creates its schema a little after the container reports healthy: wait for it
for _ in $(seq 1 60); do
  [[ $(scripts/dc.sh -o /dev/null -w '%{http_code}' /v1/decisionservices || true) == 200 ]] && break
  echo "waiting for Decision Center ..."
  sleep 5
done

[[ -d rules/xom/target/classes ]] || mvn -B -ntp -q -pl rules/xom -DskipTests package
python3 scripts/gen_dc_project.py "$ZIP"

old=$(scripts/dc.sh /v1/decisionservices | jq -r '.elements[] | select(.name=="cc-approval") | .id')
[[ -z "$old" ]] || scripts/dc.sh -X DELETE -o /dev/null "/v1/decisionservices/$old"
scripts/dc.sh -X POST -F "file=@$ZIP" -o tmp/import.json /v1/decisionservices/import
ds=$(jq -r '.decisionService.id // empty' tmp/import.json)
[[ -n "$ds" ]] || { echo "import failed: $(cat tmp/import.json)" >&2; exit 1; }
echo "imported decision service cc-approval ($ds), $(jq .published tmp/import.json) elements"

dep=$(scripts/dc.sh "/v1/decisionservices/$ds/deployments" | jq -r '.elements[0].id')
scripts/dc.sh -X POST -o tmp/build.json "/v1/deployments/$dep/build"
if [[ $(jq -r .status tmp/build.json) != COMPLETED ]]; then
  echo "FAIL  build errors:" >&2
  jq -r '.messages.elements[]? | "  \(.severity) \(.ruleArtifact.path // "-"): \(.message)"' tmp/build.json >&2
  exit 1
fi
echo "built RuleApp $(jq -r .ruleAppName tmp/build.json): 0 errors"

server=$(scripts/dc.sh /v1/servers | jq -r --arg n "$SERVER" '.elements[] | select(.name==$n) | .id')
[[ -n "$server" ]] || { echo "DC server '$SERVER' not found" >&2; exit 1; }
scripts/dc.sh -X POST -o tmp/deploy.json "/v1/deployments/$dep/deploy?serverId=$server&rulesetsNewVersions=$RULESET:$RULESET_VERSION"
[[ $(jq -r .status tmp/deploy.json) == COMPLETED ]] || { jq -r '.reason // (.messages.elements[]?.message)' tmp/deploy.json >&2; exit 1; }
jq -r '.messages.elements[]?.message' tmp/deploy.json

if [[ $VARIANT == 0 ]]; then
  mkdir -p "$(dirname "$JAR")"
  scripts/dc.sh -o "$JAR" "/v1/deployments/$dep/download"
  echo "saved $(basename "$JAR") ($(wc -c <"$JAR") bytes, gitignored)"
fi

list=$(res_curl "${RES_API:-$RES_URL/res/apiauth/v1}/ruleapps/$RULEAPP/$RULEAPP_VERSION/rulesets" -H 'Accept: application/json')
if jq -e --arg rs "$RULESET" '.. | objects | select(.id? == $rs or .name? == $rs)' >/dev/null 2>&1 <<<"$list"; then
  echo "PASS  ruleset $RULESET listed under /$RULEAPP/$RULEAPP_VERSION"
else
  echo "FAIL  ruleset $RULESET not listed: $list" >&2; exit 1
fi
