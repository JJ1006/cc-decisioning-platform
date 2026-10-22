# Disparate impact below 0.8 (province or age band)

Severity: warning (compliance). Kibana rules `disparate-impact-below-0-8-for-a-province` and `...-for-an-age-band`. Method and caveats: [docs/kibana.md](../../kibana.md#alert-rules), [docs/compliance.md](../../compliance.md).

## What fired

Among groups with at least 30 automated decisions each in 24 hours, the lowest approval rate is below 0.8 times the highest (the four-fifths rule against the best-treated group).

## Impact

A fairness signal, not proof of discrimination. Province and age band are monitored only; they are prohibited grounds and never inputs to a rule (`make lint-rules` enforces it). Small groups and synthetic data can trigger it by chance.

## Look at

1. Kibana > 'Risk & Fairness': which group is lowest, its n, and the reason codes it receives.
2. `GET /v1/stats/fairness` or the `stats_fairness` tool: the ratio against the overall rate and `smallN` flags.
3. Is the gap explained by legitimate drivers (score, TDS, income) that differ between groups? Compare the score band distribution.
4. Is it new? Compare with the previous day and with the champion/challenger what-if report (`evals/what-if/out/report.md`).

## Fix

* Do not tune a rule to close a gap by using a prohibited ground. Escalate to compliance with the dashboard evidence; any rule change goes through the rule change flow (`odm-rule-change` skill, `make eval-rules`, `make what-if`).
* Chance on a small sample: wait for n to grow and document it.
