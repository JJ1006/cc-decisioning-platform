# DeadLetterRate

Severity: warning. Rule: `observability/prometheus/rules/alerts.yml`. Alertmanager UI: <http://localhost:9097>. Overview: [docs/observability.md](../../observability.md).

## What fired

Messages were written to a `*.dlq` topic in the last 5 minutes (`kafka_topic_partition_current_offset`).

## Impact

Some events could not be processed after the retries: a poison message (unparseable JSON, a contract violation) or a persistent failure of the ODM call. Those applications have no decision yet.

## Look at

1. Kibana > Operations dashboard > 'Dead letters by topic and error', or `GET /v1/ops/dlq` through the gateway (role OPS): the original topic and the exception class (values are masked or redacted).
2. `JsonParseException` or a contract error: a bad producer; find the correlation id in the dead letter and trace it (Kibana 'Trace an application').
3. `CallNotPermittedException` or HTTP 5xx: the ODM circuit breaker was open or ODM was down; check `res_circuitbreaker_state`, ODM health, the `odm` container.
4. `HttpClientErrorException$NotFound` right after a deploy: the ruleset was not yet executable (`make demo` waits for it).

## Fix

* Fix the cause first. Then replay: the dead letter holds the original value (masked); resubmit the application through `POST /v1/applications` (idempotent by key).
* A one-off poison message (a demo run sends 1% on purpose): acknowledge, nothing to fix.
