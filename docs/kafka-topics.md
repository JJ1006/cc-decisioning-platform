# Kafka topics

All keys are `applicationId` (strings), values are JSON envelopes ([data-model](data-model.md)).
Every business topic also has `<topic>.retry` (non-blocking retries) and `<topic>.dlq` (dead letters, `cc.dlq.v1` envelope).

| Topic | Key | Partitions | Retention | Producer | Consumers |
|---|---|---|---|---|---|
| `cc.applications.submitted.v1` | applicationId | 3 | 7d | txn-generator, application-api | decision-orchestrator |
| `cc.decisions.approved.v1` | applicationId | 3 | 7d | decision-orchestrator | notification-svc, es-indexer |
| `cc.decisions.declined.v1` | applicationId | 3 | 7d | decision-orchestrator | notification-svc, es-indexer |
| `cc.decisions.referred.v1` | applicationId | 3 | 7d | decision-orchestrator | notification-svc, es-indexer |
| `cc.decisions.manual.v1` | applicationId | 1 | 30d | application-api | es-indexer, notification-svc |
| `cc.notifications.v1` | applicationId | 3 | 7d | notification-svc | es-indexer |
| `cc.audit.v1` | resourceId | 3 | 30d | every service, MCP servers | es-indexer |
| `<topic>.retry` | same as source | 3 | 1d | the consuming service | the consuming service |
| `<topic>.dlq` | same as source | 1 | 30d | the consuming service | operators (kafka-ui), es-indexer (DLQ count panel) |

Topic names are constants in `ca.maplenorthern.cc.common.Topics`.

## Client rules

- Producers are idempotent with `acks=all` (`KafkaConfigs.producerProps`).
- Consumers commit manually after processing (`KafkaConfigs.consumerProps`, `manualAck`) and read committed data only.
- On failure: in-place retries with exponential backoff, then `DlqRecoverer` publishes the record to `<topic>.dlq` (`ErrorHandling.errorHandler`). Poison messages (bad JSON, illegal arguments) skip retries.
- Listeners use mTLS (`security.protocol=SSL`); certificates come from `scripts/pki.sh`.
