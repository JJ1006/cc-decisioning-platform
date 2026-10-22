# More than 10 error log lines in 5 minutes

Severity: warning. Kibana rule `more-than-10-error-log-lines-in-5-minutes` (`observability/kibana/alerting/rules/`). Rules table: [docs/kibana.md](../../kibana.md#alert-rules).

## What fired

More than 10 documents with `level: ERROR` reached the `cc-logs` index (Filebeat > Logstash) in 5 minutes.

## Impact

Some service is failing repeatedly. It may be a single noisy bug or the first sign of a dependency outage (ODM, Kafka, Elasticsearch).

## Look at

1. Kibana > Operations dashboard > 'Error log lines by service', then Discover on `cc-logs` filtered on `level: ERROR` and the service.
2. Group by `message`: one message repeated is one bug; many different messages from many services is an outage of a shared dependency.
3. Take a `correlationId` from an error and open 'Trace an application' to see how far it got.
4. Check the Prometheus side: `TargetDown`, `res_circuitbreaker_state`, [KafkaConsumerLagHigh](../alerts/KafkaConsumerLagHigh.md).

## Fix

* Fix or restart the failing service (`make logs SVC=<name>`), or restore the dependency, then watch the count fall below 10 in the next window.
* A cold start of ODM produces a burst of errors for a few seconds; that is expected, not an incident.
