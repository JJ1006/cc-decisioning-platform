# Kibana alert runbooks

One runbook per Kibana alert rule of the Maple Northern space. Infrastructure and SLO alerts are Prometheus rules: [../alerts](../alerts/README.md). Every rule's server-log message ends with its runbook path; `scripts/check-runbooks.sh` (part of `make verify`) checks that every Prometheus and Kibana alert has one.

| Rule | Runbook |
|---|---|
| Dead letters in the last 5 minutes | [DeadLetterRate](../alerts/DeadLetterRate.md) |
| More than 10 error log lines in 5 minutes | [ErrorLogLines](ErrorLogLines.md) |
| Decision latency p95 above 300 ms | [DecisionLatencyP95High](../alerts/DecisionLatencyP95High.md) |
| Approval rate fell more than 20% against the last hour | [ApprovalRateDrop](ApprovalRateDrop.md) |
| Disparate impact below 0.8 for a province / an age band | [DisparateImpact](DisparateImpact.md) |
