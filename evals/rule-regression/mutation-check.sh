#!/usr/bin/env bash
# Proves the regression gate works: flips one threshold in a TEMP COPY of the rule sources, regenerates the Decision Center project from that copy,
# deploys it to RES (same version 1.0), runs the gate (it must FAIL, and name the cases around the flipped boundary), then restores the real rules
# from git and runs the gate again (it must PASS). Nothing in rules/ is touched.
#   evals/rule-regression/mutation-check.sh [income|tds ...]       (make eval-rules-mutation; default: both)
# Needs the stack (make up PROFILE=core) with the rules deployed.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
OUT=$ROOT/evals/rule-regression/out
mkdir -p "$OUT"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mutations=("$@")
[[ ${#mutations[@]} -gt 0 ]] || mutations=(income tds)
status=0

mutate() { # mutate NAME DIR : change one threshold in the copy in DIR; prints what changed
  local name=$1 dir=$2
  case $name in
    income) # RISK-01 minimum income 15000 -> 14000: the case at 14,999 now passes the rule
      python3 - "$dir/rules/risk/RISK-01-MinimumIncome.brl" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
assert "is less than 15000" in s
open(p, "w").write(s.replace("is less than 15000", "is less than 14000"))
PY
      echo "RISK-01 minimum income 15000 -> 14000" ;;
    tds) # score 680+ : borderline TDS upper bound 0.50 -> 0.52
      python3 - "$dir/decision-tables/ScoreTdsOutcome.csv" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
assert "680,900,<=0.50,,," in s
s = s.replace("680,900,<=0.50,,,", "680,900,<=0.52,,,").replace("680,900,>0.50 and <=0.60", "680,900,>0.52 and <=0.60")
open(p, "w").write(s)
PY
      echo "ScoreTdsOutcome score 680+: approve up to TDS 0.50 -> 0.52" ;;
    *) echo "unknown mutation $name" >&2; return 1 ;;
  esac
}

for m in "${mutations[@]}"; do
  echo "=== mutation: $m"
  cp -R rules/cc-approval-rules "$TMP/rules-$m"
  what=$(mutate "$m" "$TMP/rules-$m") || exit 2
  echo "changed: $what"
  RULES_SRC="$TMP/rules-$m" DC_ZIP="$TMP/mutant-$m.zip" scripts/deploy-dc.sh >"$OUT/mutation-$m-deploy.log" 2>&1 \
    || { echo "FAIL  could not deploy the mutated rules, see $OUT/mutation-$m-deploy.log"; tail -5 "$OUT/mutation-$m-deploy.log"; status=1; continue; }
  if python3 evals/rule-regression/run.py --out "$OUT/mutation-$m" >"$OUT/mutation-$m.log" 2>&1; then
    echo "FAIL  the gate PASSED on mutated rules (it must fail): $what"
    status=1
  else
    echo "PASS  the gate FAILED on the mutated rules, as it must:"
    sed -n 1,6p "$OUT/mutation-$m.log" | cut -c1-170
  fi
done

echo "=== restore: deploying the real rules from git"
scripts/deploy-dc.sh >"$OUT/mutation-restore-deploy.log" 2>&1 || { echo "FAIL  restore deploy failed, see $OUT/mutation-restore-deploy.log"; exit 1; }
if python3 evals/rule-regression/run.py >"$OUT/mutation-restore.log" 2>&1; then
  echo "PASS  gate green again after the restore: $(head -1 "$OUT/mutation-restore.log")"
else
  echo "FAIL  gate still red after the restore"; head -5 "$OUT/mutation-restore.log"; status=1
fi
exit $status
