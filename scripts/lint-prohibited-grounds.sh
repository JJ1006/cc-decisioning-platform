#!/usr/bin/env bash
# Prohibited grounds of discrimination (Canadian Human Rights Act s.3 and the provincial human rights codes) must never feed a credit decision
# or travel in our data. Three checks; any hit fails (make verify, CI):
#   1. the grounds as words anywhere in rules/, the XOM (main code), contracts/, the Logstash pipelines and the Kibana space, the services' main code:
#      gender, sex, race, ethnic origin, colour (the word, not CSS colors), religion, marital status, family status, disability, sexual orientation, national or ethnic origin, pardon
#   2. age: only the eligibility rule ELIG-01 (age of majority) may use it; no other rule, decision table or ruleflow
#   3. the postal code (a proxy for who lives there): no rule uses the postal code or its FSA - they exist only to be masked
# The scan root can be moved (LINT_ROOT=dir) so scripts/test-lint-prohibited-grounds.sh can prove the lint fails on a planted word.
set -uo pipefail
ROOT=${LINT_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}
cd "$ROOT"

# word patterns, case-insensitive. "sex" must be a word (essex, sextant); "race" must not be the middle of another word (trace, embrace, grace)
GROUNDS='gender|(^|[^[:alpha:]])sex(ual)?([^[:alpha:]]|$)|(^|[^[:alpha:]])race([^[:alpha:]]|$)|racial|ethnic|(^|[^[:alpha:]])colour([^[:alpha:]]|$)|religio|marital|family[ _-]?status|disabilit|sexual[ _-]?orientation|national[ _-]?origin|pardon'

targets=()
for t in rules/cc-approval-rules rules/cc-approval-ds rules/dc-export rules/tests rules/xom/src/main docs/rules-catalog.md contracts \
  deploy/logstash/pipelines deploy/logstash/ruby deploy/es observability/kibana services/*/src/main mcp/odm-dc-mcp-server/src; do
  [[ -e "$t" ]] && targets+=("$t")
done
[[ ${#targets[@]} -gt 0 ]] || { echo "lint-prohibited-grounds: nothing to scan"; exit 0; }
fail=0

# 1. the grounds as words. Javadoc/comment lines that only say "no gender" would be hits too: write the policy in docs/, not next to the code
if hits=$(grep -rniE --exclude-dir=target --exclude-dir=node_modules --exclude='*.png' --exclude='*.pdf' "$GROUNDS" "${targets[@]}" 2>/dev/null); then
  echo "FAIL  1. a prohibited ground is mentioned:" >&2
  echo "$hits" | head -20 >&2
  fail=1
fi

# 2. age only in ELIG-01 (a file age in months is the age of the credit file, not of the person)
rule_dirs=()
for d in rules/cc-approval-rules/rules rules/cc-approval-rules/decision-tables rules/cc-approval-rules/ruleflow rules/cc-approval-rules/bom; do
  [[ -d "$d" ]] && rule_dirs+=("$d")
done
if [[ ${#rule_dirs[@]} -gt 0 ]]; then
  if hits=$(grep -rniE --exclude-dir=target --exclude=README.md "age in years|ageyears|date of birth|dateofbirth|(^|[^[:alpha:]])age([^[:alpha:]]|$)" "${rule_dirs[@]}" \
    | grep -viE "file age|fileage|ELIG-01" ); then
    echo "FAIL  2. age is used outside ELIG-01 (age of majority):" >&2
    echo "$hits" | head -20 >&2
    fail=1
  fi
fi

# 3. the postal code and its FSA never in a rule
if [[ -d rules/cc-approval-rules ]]; then
  if hits=$(grep -rniE --exclude-dir=target "postal ?code|(^|[^[:alpha:]])fsa([^[:alpha:]]|$)|forward sortation" rules/cc-approval-rules rules/cc-approval-ds 2>/dev/null); then
    echo "FAIL  3. a rule uses the postal code or FSA:" >&2
    echo "$hits" | head -20 >&2
    fail=1
  fi
fi

[[ $fail == 0 ]] && echo "PASS  no prohibited ground in ${#targets[@]} locations; age only in ELIG-01; no postal code or FSA in rules"
exit $fail
