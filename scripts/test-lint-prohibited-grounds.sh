#!/usr/bin/env bash
# Proves the lint can fail: copies the scanned trees to a temporary directory, plants one violation at a time and expects the lint to reject it;
# the untouched copy must pass. Nothing in the repository is modified.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
LINT="$ROOT/scripts/lint-prohibited-grounds.sh"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fresh() { rm -rf "$T/r" && mkdir -p "$T/r" && (cd "$ROOT" && tar cf - rules/cc-approval-rules rules/cc-approval-ds rules/dc-export rules/tests rules/xom/src/main docs/rules-catalog.md contracts \
  deploy/logstash/pipelines deploy/logstash/ruby deploy/es observability/kibana mcp/odm-dc-mcp-server/src 2>/dev/null) | tar xf - -C "$T/r"; mkdir -p "$T/r/services"; cp -R "$ROOT"/services/*/src "$T/r/services/" 2>/dev/null; }
failed=0
expect() { # expect pass|fail NAME
  local want=$1 name=$2
  if LINT_ROOT="$T/r" "$LINT" >"$T/out" 2>&1; then got=pass; else got=fail; fi
  if [[ $got == "$want" ]]; then echo "PASS  $name -> lint $got"; else echo "FAIL  $name: lint should $want but $got"; cat "$T/out"; failed=1; fi
}
fresh; expect pass "clean copy"

fresh; f=$(ls "$T"/r/rules/cc-approval-rules/rules/eligibility/ELIG-02*.brl | head -1); printf "\n// gender of the applicant\n" >>"$f"; expect fail "'gender' in a rule"
fresh; printf '\n  public String getGender() { return null; }\n' >/dev/null; f=$(ls "$T"/r/rules/xom/src/main/java/ca/maplenorthern/cc/xom/Applicant.java); sed -i.bak 's/^public class Applicant {/public class Applicant {\n  private String gender;/' "$f"; expect fail "'gender' as a BOM/XOM field"
fresh; sed -i.bak 's/"province"/"province", "maritalStatus"/' "$T/r/contracts/defs.schema.json" 2>/dev/null; python3 - "$T/r/contracts/defs.schema.json" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read().replace('"applicantId": {', '"maritalStatus": { "type": "string" }, "applicantId": {', 1)
open(p, "w").write(s)
PY
expect fail "'marital status' in a contract"
fresh; printf '\nfilter { mutate { add_field => { "religion" => "x" } } }\n' >>"$T/r/deploy/logstash/pipelines/kafka-events/50-filter.conf"; expect fail "'religion' in a Logstash pipeline"
fresh; printf '{"x":"disability"}\n' >"$T/r/observability/kibana/planted.json"; expect fail "'disability' in the Kibana space"
fresh; f=$(ls "$T"/r/rules/cc-approval-rules/rules/risk/RISK-01*.brl | head -1); printf "\n// the age in years of the applicant\n" >>"$f"; expect fail "age used in a risk rule (only ELIG-01 may)"
fresh; f=$(ls "$T"/r/rules/cc-approval-rules/rules/limit/*.brl 2>/dev/null | head -1); [[ -z $f ]] && f=$(ls "$T"/r/rules/cc-approval-rules/rules/product/*.brl | head -1); printf "\n// postal code FSA\n" >>"$f"; expect fail "postal code / FSA used in a rule"
fresh; printf 'trace racetrack embrace grace sextant essex\n' >"$T/r/contracts/harmless.txt"; expect pass "words that only contain a ground (trace, embrace, grace, essex) are fine"
[[ $failed == 0 ]] && echo "prohibited-grounds lint: all planted violations rejected"
exit $failed
