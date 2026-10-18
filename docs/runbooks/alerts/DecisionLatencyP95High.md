# DecisionLatencyP95High

Severity: warning. Rule: `observability/prometheus/rules/alerts.yml`. Alertmanager UI: <http://localhost:9097>. Overview: [docs/observability.md](../../observability.md).

## What fired

p95 of the ODM RES call (`res_call_seconds`, orchestrator) is above 300 ms for 5 minutes.

## Impact

Decisions take longer; with sustained load consumer lag follows (KafkaConsumerLagHigh) and the circuit breaker may open.

## Look at

1. Grafana > ODM decision latency: orchestrator timer against the client spans in the traces; slowest traces table (click a trace id for the waterfall: where the time goes).
2. A cold ODM after start-up is slow for the first minutes (the first calls compile the ruleset): expect this alert after `make up`.
3. ODM CPU and heap: `docker stats` (the `odm` container), Grafana > JVM for the orchestrator.
4. Host overload: Docker Desktop CPU/memory; Elasticsearch and Logstash share the machine.

## Fix

* Cold start: wait. Overload: reduce the generator rate (`/admin/generator/start?tps=`), stop optional containers (kafka-ui, Kibana).
* A slow rule: compare the fired rules in the slow traces and the Decision Center ruleset; ODM changes go through the rules pipeline, not this runbook.
