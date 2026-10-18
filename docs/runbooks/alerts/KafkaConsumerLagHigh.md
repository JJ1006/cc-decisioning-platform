# KafkaConsumerLagHigh

Severity: warning. Rule: `observability/prometheus/rules/alerts.yml`. Alertmanager UI: <http://localhost:9097>. Overview: [docs/observability.md](../../observability.md).

## What fired

A consumer group is more than 500 messages behind on a topic for 5 minutes (`kafka_consumergroup_lag`, kafka-exporter).

## Impact

Decisions, notices or indexing are late: applications wait for their decision, customers for their notice, dashboards lag behind. Nothing is lost: Kafka keeps the messages.

## Look at

1. Grafana > Kafka: which group and topic (`decision-orchestrator` on `cc.applications.submitted.v1`, `notification-svc` on the decision topics, `logstash-indexer` / `logstash-dlq` for Elasticsearch).
2. Is the consumer running? `docker compose ps`, Grafana > Platform overview (Targets down).
3. Is it slow, not stopped? Grafana > ODM decision latency (a slow RES slows the orchestrator), Grafana > JVM (GC pauses, heap), `docker stats`.
4. Logstash lag: Grafana > Elasticsearch and Logstash (events in vs out, queue size); Elasticsearch heap (alert ElasticsearchHeapHigh).
5. Find the failing requests of one application: Kibana dashboard 'Trace an application' or Grafana Explore > Tempo with the trace id.

## Fix

* Stopped consumer: restart it (`docker compose restart <service>`); it resumes from its committed offset, no message is lost or repeated beyond the at-least-once contract.
* Slow ODM: see DecisionLatencyP95High. Open circuit breaker: the orchestrator retries into the dead letter topic, see DeadLetterRate.
* Burst load that will clear by itself: wait; the alert resolves when lag falls under 500.
