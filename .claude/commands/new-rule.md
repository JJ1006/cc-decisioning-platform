---
description: Add a new ODM rule (spec, source, scenarios, catalog) and run the checks
argument-hint: <package> <RULE-ID> <one-line intent>
---

Use the `odm-rule-change` skill to add a rule: `$ARGUMENTS`.

1. Read `docs/rules-catalog.md` and the package folder under `rules/cc-approval-rules/rules/`; pick the next free id; reuse an existing reason code if one fits.
2. Write the catalog entry first (intent, condition, action, reason code), then the `.brl`, then scenarios (boundaries both sides).
3. Prohibited grounds: age only in ELIG-01, no postal code. Run `make lint-rules`.
4. Run the `rules-reviewer` agent on the diff and show its findings.
5. If ODM is up (`make up PROFILE=core`): `make deploy-rules && make test-rules`; show the output. Otherwise say so.

Stop before commit: a human reads the rule diff.
