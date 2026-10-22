---
name: odm-rule-change
description: "Change an ODM decision rule or decision table of the ccApproval ruleset. Use when asked to edit, add, retire or tune a rule, a .brl file, a decision-table CSV, a threshold, a reason code or the ruleflow, or to \"change the approval rules\"."
---

# ODM rule change

ODM is the system of record. A rule change is a reviewed git change; an LLM never decides a credit outcome and never edits rules without a human reading the diff.

## Steps

1. Read the rule in `docs/rules-catalog.md` (id, intent, condition, action, reason code) and its source in `rules/cc-approval-rules/`:
   - rule text: `rules/<package>/*.brl` (packages `eligibility`, `risk`, `limit`, `limitcap`, `product`, `reasons`)
   - tables: `decision-tables/IncomeScoreLimit.csv`, `decision-tables/ScoreTdsOutcome.csv`
   - order: `ruleflow/ccApproval.md`
2. Prohibited grounds: no gender, race, religion, marital status and so on. **Age only in ELIG-01** (age of majority). No postal code in any rule. Run `make lint-rules` early.
3. Edit the `.brl` / `.csv`. A new reason code also goes in `rules/xom` (`ReasonCode`) and its plain-language text in the query API catalog.
4. Update `docs/rules-catalog.md` (the human spec) and the scenarios `rules/tests/scenarios.json` (add one case per new branch and per boundary); then `scripts/gen-rules-catalog.py`.
5. Generate and deploy: `python3 scripts/gen_dc_project.py` (writes `tmp/cc-approval.zip`), then `make deploy-rules` (import to Decision Center, build with 0 errors, deploy to RES). Needs `make up PROFILE=core`.
6. `make test-rules` (spec check + the scenarios against RES) and `make lint-rules`.
7. `make verify`. Conventional commit `feat(rules): ...`; PR; a human reviews the rule diff (rules-reviewer agent first).

## Verify

```bash
make lint-rules && make deploy-rules && make test-rules && scripts/gen-rules-catalog.py --check
```

Done = show the output: 0 build errors, every scenario PASS, catalog up to date.
