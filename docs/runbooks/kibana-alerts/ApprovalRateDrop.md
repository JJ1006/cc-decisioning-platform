# Approval rate fell more than 20% against the last hour

Severity: warning. Kibana rule `approval-rate-fell-more-than-20-against-the-last-hour`. Rules table: [docs/kibana.md](../../kibana.md#alert-rules).

## What fired

In the last 5 minutes (at least 20 decisions) the approval rate is below 80% of the rate of the hour before.

## Impact

Either the applicant mix changed (a campaign, a test burst, the demo generator's profile) or a rule, a threshold or the data feeding it changed. A rule problem moves real credit decisions, so look at it before assuming noise.

## Look at

1. Kibana > Executive dashboard > approval rate over time, and 'Risk & Fairness' for the reason code mix: which `R_*` code rose?
2. `GET /v1/stats/approval-rate` (role ANALYST) or the `stats_approval_rate` tool of the odm-dc MCP server for the same window.
3. Did the ruleset version change? Every decision stores `rulesetVersion` and the deployment time; compare before and after.
4. After an ODM restart the rules are gone and applications are dead-lettered, not declined; check [DeadLetterRate](../alerts/DeadLetterRate.md).
5. A rule change in a pull request is gated by `make eval-rules` and `make what-if` ([docs/evals.md](../../evals.md)); run the what-if to see the expected effect.

## Fix

* If the mix changed and the rules are as specified: acknowledge, nothing to fix.
* If a rule or table is wrong: revert it through Decision Center or git, redeploy (`make deploy-rules`), run `make eval-rules` (300/300) and watch the rate recover. The LLM does not change rules or decisions.
