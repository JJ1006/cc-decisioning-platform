#!/usr/bin/env bash
# Tests the project hooks in .claude/hooks with fake hook JSON on stdin (no Claude Code needed). Prints PASS/FAIL per case; make lint-claude-hooks.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
export CLAUDE_PROJECT_DIR=$ROOT
H=$ROOT/.claude/hooks
OCP=deploy/argocd/apps/openshift
fail=0

# run NAME HOOK EXPECTED_EXIT JSON [ENV=VAL]
run() {
  local name=$1 hook=$2 want=$3 json=$4 envv=${5:-X=1}
  env "$envv" "$H/$hook" <<<"$json" >/dev/null 2>&1
  local got=$?
  if [[ $got == "$want" ]]; then echo "PASS  $name (exit $got)"; else echo "FAIL  $name: expected exit $want, got $got"; fail=1; fi
}
bash_json() { jq -nc --arg c "$1" '{tool_name:"Bash",tool_input:{command:$c}}'; }
file_json() { jq -nc --arg t "$1" --arg f "$2" '{tool_name:$t,tool_input:{file_path:$f}}'; }

run "ocp: Edit in the openshift folder is blocked" pre_ocp_guard.sh 2 "$(file_json Edit "$ROOT/$OCP/values/cc-platform.yaml")"
run "ocp: Write in kind-lite passes" pre_ocp_guard.sh 0 "$(file_json Write "$ROOT/deploy/argocd/apps/kind-lite/x.yaml")"
run "ocp: sed -i in the folder is blocked" pre_ocp_guard.sh 2 "$(bash_json "sed -i s/a/b/ $OCP/values/x.yaml")"
run "ocp: redirect into the folder is blocked" pre_ocp_guard.sh 2 "$(bash_json "echo x > $OCP/x.yaml")"
run "ocp: ls of the folder passes" pre_ocp_guard.sh 0 "$(bash_json "ls $OCP/")"
run "ocp: a heredoc that only mentions the folder passes" pre_ocp_guard.sh 0 "$(bash_json "$(printf 'cat > /tmp/a.md <<EOF\nnever edit %s\nEOF' "$OCP")")"
run "ocp: ALLOW_OCP_EDIT=1 lets the human through" pre_ocp_guard.sh 0 "$(file_json Edit "$ROOT/$OCP/x.yaml")" ALLOW_OCP_EDIT=1

# contract guard: warns (exit 0) with a JSON context on a breaking change; silent otherwise
contract() { # contract MODE : the Write JSON for audit.schema.json after the change
  python3 - "$1" "$ROOT" <<'PY'
import json, sys
mode, root = sys.argv[1], sys.argv[2]
f = root + "/contracts/audit.schema.json"
d = json.load(open(f))
pl = d["properties"]["payload"]
if mode == "breaking":
    del pl["properties"]["result"]
elif mode == "additive":
    pl["properties"]["newOptional"] = {"type": "string"}
elif mode == "bumped":
    del pl["properties"]["result"]
    d["properties"]["eventType"] = {"enum": ["cc.audit.v2", "cc.audit.mcp.v2"]}
print(json.dumps({"tool_name": "Write", "tool_input": {"file_path": f, "content": json.dumps(d)}}))
PY
}
warns() { # warns NAME MODE EXPECT(yes|no)
  local out
  out=$(contract "$2" | "$H/pre_contract_guard.sh" 2>/dev/null)
  if [[ ($3 == yes && "$out" == *"breaking change without a version bump"*) || ($3 == no && -z "$out") ]]; then
    echo "PASS  contract: $1"
  else
    echo "FAIL  contract: $1 (output: ${out:0:80})"
    fail=1
  fi
}
warns "a removed property warns" breaking yes
warns "an additive change is silent" additive no
warns "a breaking change with the event type bumped to .v2 is silent" bumped no

# rules lint after an edit under rules/: clean passes, a planted prohibited ground fails with exit 2
planted=$ROOT/rules/cc-approval-rules/rules/risk/ZZ-planted.brl
trap 'rm -f "$planted"' EXIT
run "rules lint: clean tree passes" post_rules_lint.sh 0 "$(file_json Edit "$ROOT/rules/cc-approval-rules/rules/risk/anything.brl")"
run "rules lint: an edit outside rules/ does not run it" post_rules_lint.sh 0 "$(file_json Edit "$ROOT/docs/mcp.md")"
echo 'if the applicant gender is X then reject' >"$planted"
run "rules lint: a planted prohibited ground exits 2" post_rules_lint.sh 2 "$(file_json Write "$planted")"
rm -f "$planted"

exit $fail
