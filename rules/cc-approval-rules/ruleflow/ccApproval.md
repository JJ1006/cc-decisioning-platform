# Ruleflow `ccApproval`

Build in Rule Designer: New > Ruleflow, name `ccApproval`, package root. Set it as the ruleset's main ruleflow
(Decision operation > Ruleflow = `ccApproval`). In the headless flow `scripts/gen_dc_project.py` generates it from this spec.

```text
start
  |  (the OUT variable 'the decision' is created by its initial value: new ca.maplenorthern.cc.xom.Decision())
  v
[eligibility]     rule task, package eligibility, algorithm Default
  |
<outcome of 'the decision' is null ?>  -- no --> end   (DECLINE already decided)
  | yes
[risk]            rule task, package risk (rules RISK-01..04 + decision table ScoreTdsOutcome)
  |
<outcome of 'the decision' is null ?>  -- no --> end   (DECLINE or REFER)
  | yes
[limit]           rule task, package limit: decision table IncomeScoreLimit
  |
[limitcap]        rule task, package limitcap: LIM-02 (runs after the table, so it needs its own package)
  |
[product]         rule task, package product (PROD-01..06, APR-01..04)
  |
[reasons]         rule task, package reasons (RSN-01 sets APPROVE + R_APPROVED_STANDARD)
  |
end
```

Order matters only at the gateways. Inside `eligibility` and `risk`, rules call `escalate`, which keeps the most severe
outcome (DECLINE over REFER over APPROVE), so rule order does not change the result.

Task execution mode: RetePlus for all tasks.
