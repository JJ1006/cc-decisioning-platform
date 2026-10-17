# Ruleflow `ccApproval`

Build in Rule Designer: New > Ruleflow, name `ccApproval`, package root. Set it as the ruleset's main ruleflow
(Decision operation > Ruleflow = `ccApproval`).

```text
start
  |  initial action:  set 'the decision' to a new decision ;
  v
[eligibility]     rule task, package eligibility, algorithm Default
  |
<outcome of 'the decision' is null ?>  -- no --> end   (DECLINE already decided)
  | yes
[risk]            rule task, package risk (rules RISK-01..04 + decision table ScoreTdsOutcome)
  |
<outcome of 'the decision' is null ?>  -- no --> end   (DECLINE or REFER)
  | yes
[limit]           rule task, package limit, Sequential: decision table IncomeScoreLimit, then LIM-02
  |
[product]         rule task, package product (PROD-01..06, APR-01..04)
  |
[reasons]         rule task, package reasons (RSN-01 sets APPROVE + R_APPROVED_STANDARD)
  |
end
```

Order matters only at the gateways. Inside `eligibility` and `risk`, rules call `escalate`, which keeps the most severe
outcome (DECLINE over REFER over APPROVE), so rule order does not change the result.

Task execution mode: Default (RetePlus) for all tasks, except `limit` which must run the table before LIM-02 (give the
table priority High, or use two tasks).
