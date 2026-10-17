#!/usr/bin/env bash
# Fails when rules, vocabulary, scenarios or the rules catalog mention a prohibited ground
# (human-rights protected characteristics must never feed a credit decision).
# rules/xom is excluded: its tests and javadoc legitimately name the words to forbid them.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
PATTERN='gender|ethnic|marital|religio|(^|[^[:alpha:]])race'
targets=()
for t in rules/cc-approval-rules rules/cc-approval-ds rules/dc-export rules/tests docs/rules-catalog.md; do
  [[ -e "$t" ]] && targets+=("$t")
done
[[ ${#targets[@]} -gt 0 ]] || { echo "lint-prohibited-grounds: nothing to scan"; exit 0; }
if hits=$(grep -rniE --exclude-dir=target "$PATTERN" "${targets[@]}"); then
  echo "FAIL  prohibited ground mentioned in rules/BOM:" >&2
  echo "$hits" >&2
  exit 1
fi
echo "PASS  no prohibited grounds in ${targets[*]}"
