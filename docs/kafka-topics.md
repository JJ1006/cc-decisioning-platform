# Kafka topics

All keys are `applicationId` (strings), values are JSON envelopes ([data-model](data-model.md)).
**Retention follows the data class** ([data classification](privacy/data-classification.md)): a topic that holds direct identifiers (the submitted application and the dead letters, which keep the unmasked original) is kept 7 days; nothing personal is kept longer than it is needed to process. Every business topic also has `<topic>.retry` (non-blocking retries) and `<topic>.dlq` (dead letters, `cc.dlq.v1` envelope).

| Topic | Key | Partitions | Retention | Producer | Consumers | Data class |
|---|---|---|---|---|---|---|
| `cc.applications.submitted.v1` | applicationId | 3 | 7d | txn-generator, application-api | decision-orchestrator | Direct identifier (the full application) |
| `cc.decisions.approved.v1` | applicationId | 3 | 7d | decision-orchestrator | notification-svc, logstash | Sensitive financial + pseudonym (inputs, applicantToken), derived bands |
| `cc.decisions.declined.v1` | applicationId | 3 | 7d | decision-orchestrator | notification-svc, logstash | Sensitive financial + pseudonym (inputs, applicantToken), derived bands |
| `cc.decisions.referred.v1` | applicationId | 3 | 7d | decision-orchestrator | notification-svc, logstash | Sensitive financial + pseudonym (inputs, applicantToken), derived bands |
| `cc.decisions.manual.v1` | applicationId | 1 | 30d | application-api | logstash, notification-svc | Operational (underwriter note is free text: treat as sensitive) |
| `cc.notifications.v1` | applicationId | 3 | 7d | notification-svc | logstash | Operational (reasons in plain language, opaque recipient reference) |
| `cc.audit.v1` | resourceId | 3 | 30d | every service, MCP servers | logstash | Accountability (no argument values, ids hashed in erasure records) |
| `<topic>.retry` | same as source | 3 | 1d | the consuming service | the consuming service | Same as the topic |
| `<topic>.dlq` | same as source | 1 | 7d | the consuming service | operators (kafka-ui), logstash (cc-dlq index, DLQ count panel) | Direct identifier (the unmasked original): short retention |

Topic names are constants in `ca.maplenorthern.cc.common.Topics`.

## Client rules

- Producers are idempotent with `acks=all` (`KafkaConfigs.producerProps`).
- Consumers commit manually after processing (`KafkaConfigs.consumerProps`, `manualAck`) and read committed data only.
- On failure: in-place retries with exponential backoff, then `DlqRecoverer` publishes the record to `<topic>.dlq` (`ErrorHandling.errorHandler`). Poison messages (bad JSON, illegal arguments) skip retries.
- Listeners use mTLS (`security.protocol=SSL`); certificates come from `scripts/pki.sh`.
