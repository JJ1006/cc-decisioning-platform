# Runbooks

How to run, fix and operate the platform. `scripts/check-runbooks.sh` (in `make verify`) fails when an alert has no runbook or a runbook is missing from this index.

## Alerts

| Where | Runbooks |
|---|---|
| Prometheus (infrastructure, SLO) | [alerts/README.md](alerts/README.md): [KafkaConsumerLagHigh](alerts/KafkaConsumerLagHigh.md), [DeadLetterRate](alerts/DeadLetterRate.md), [DecisionLatencyP95High](alerts/DecisionLatencyP95High.md), [ApiErrorRateHigh](alerts/ApiErrorRateHigh.md), [TargetDown](alerts/TargetDown.md), [JvmHeapHigh](alerts/JvmHeapHigh.md), [ElasticsearchHeapHigh](alerts/ElasticsearchHeapHigh.md), [CertificateExpiringSoon](alerts/CertificateExpiringSoon.md) |
| Kibana (business, compliance) | [kibana-alerts/README.md](kibana-alerts/README.md): [ErrorLogLines](kibana-alerts/ErrorLogLines.md), [ApprovalRateDrop](kibana-alerts/ApprovalRateDrop.md), [DisparateImpact](kibana-alerts/DisparateImpact.md) (dead letters and latency reuse the Prometheus runbooks) |

## Operations

| Runbook | For |
|---|---|
| [local-setup.md](local-setup.md) | Docker Compose: prerequisites, profiles, start, stop, reset |
| [event-services.md](event-services.md) | the Kafka event services and the ELK ingestion |
| [dc-api.md](dc-api.md) | Decision Center REST API as used by `scripts/deploy-dc.sh` |
| [gitops.md](gitops.md) | Argo CD on kind and OpenShift: sync, promote, rollback |
| [dependabot.md](dependabot.md) | dependency updates: grouping, what is ignored, how to review |
