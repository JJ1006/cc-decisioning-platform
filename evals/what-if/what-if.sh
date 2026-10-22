#!/usr/bin/env bash
# Champion / challenger what-if end to end: builds the challenger from a patch of the rule sources (in a temp copy, rules/ is untouched), deploys it to RES as
# ccApproval <CHALLENGER> next to the champion, and replays the last N decisions through both (replay.py).
#   evals/what-if/what-if.sh [CHALLENGER=1.1] [N=1000] [PATCH=evals/what-if/challengers/tds-plus-2.patch]        (make what-if CHALLENGER=1.1 N=1000)
#   NO_DEPLOY=1 evals/what-if/what-if.sh ...      the challenger version is already in RES (for example deployed from Decision Center)
# Needs the stack (make up PROFILE=core,analytics) with the champion deployed and decisions in Elasticsearch (make demo).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
CHALLENGER=${CHALLENGER:-1.1}
N=${N:-1000}
PATCH=${PATCH:-evals/what-if/challengers/tds-plus-2.patch}
CHAMPION=${CHAMPION:-1.0}
[[ "$CHALLENGER" != "$CHAMPION" ]] || { echo "the challenger version must differ from the champion ($CHAMPION)" >&2; exit 2; }

if [[ -z "${NO_DEPLOY:-}" ]]; then
  [[ -f "$PATCH" ]] || { echo "patch not found: $PATCH" >&2; exit 2; }
  TMP=$(mktemp -d)
  trap 'rm -rf "$TMP"' EXIT
  cp -R rules/cc-approval-rules "$TMP/rules"
  patch -p1 -d "$TMP/rules" <"$PATCH" >/dev/null
  echo "deploying challenger ccApproval/$CHALLENGER from $PATCH (a copy; rules/ unchanged)"
  RULES_SRC="$TMP/rules" RULESET_VERSION="$CHALLENGER" DC_ZIP="$TMP/challenger.zip" scripts/deploy-dc.sh >"$TMP/deploy.log" 2>&1 || { tail -15 "$TMP/deploy.log" >&2; exit 1; }
fi
python3 evals/what-if/replay.py --champion "$CHAMPION" --challenger "$CHALLENGER" -n "$N"
