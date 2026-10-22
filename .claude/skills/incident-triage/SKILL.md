---
name: incident-triage
description: "Triage an alert or incident of the decisioning platform. Use when an alert fires, decisions stop, latency or dead letters rise, a consumer lags, or someone says \"something is wrong\", \"triage\", \"incident\", \"why is X down\"."
---

# Incident triage

Read-only first. Do not restart, delete or deploy during triage without a human saying so.

## Steps

1. **Alert to runbook**: `docs/runbooks/alerts/<AlertName>.md` (ApiErrorRateHigh, DeadLetterRate, DecisionLatencyP95High, KafkaConsumerLagHigh, TargetDown, JvmHeapHigh, ElasticsearchHeapHigh, CertificateExpiringSoon). Follow its checks in order.
2. **Grafana MCP** (Viewer token, `scripts/mcp/grafana-token.sh`): dashboards "Platform overview", "Kafka", "ODM latency"; query the alert's expression; find the first bad minute and a trace in Tempo.
3. **Logs by correlation id**: pick a failing id (Kibana "Trace" dashboard, or the query API `GET /v1/ops/logs?correlationId=<id>` as `ops-admin`: `scripts/api-call.sh ops-admin "/v1/ops/logs?correlationId=<id>"`). Follow it across services.
4. **Dead letters**: `GET /v1/ops/dlq?topic=<original topic>&from=<time>` (masked values); error class and topic say which consumer and why. Do not replay without a human decision.
5. **Decision-side**: `odm-dc` tools `decision_get`, `decision_explain`, `stats_latency`. ODM down: rules are in a non-persistent database, `scripts/ensure-rules.sh` redeploys them.
6. Write the finding: what, since when, blast radius, evidence (ids, queries), proposed next step. A human decides the fix.

## Verify

State the evidence for each claim (query + result). Stop at a recommendation.
