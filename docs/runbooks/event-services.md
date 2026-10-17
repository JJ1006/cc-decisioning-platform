# Event services and ELK ingestion (P4, P4b)

```text
txn-generator --> cc.applications.submitted.v1 --> decision-orchestrator --RES--> cc.decisions.{approved,declined,referred}.v1
                                                                         \--> cc.audit.v1
cc.decisions.* --> notification-svc --> cc.notifications.v1
cc.decisions.* + cc.audit.v1 + cc.notifications.v1 --> Logstash (mask PII) --> Elasticsearch (cc-decisions, cc-audit, cc-notifications)
every <topic>.dlq --> Logstash --> cc-dlq          container logs --> Filebeat --TLS--> Logstash --> cc-logs
```

| Service | Port (host) | Does |
|---|---|---|
| `txn-generator` | 8081 | Datafaker en-CA applications; `POST /admin/start?tps=&total=&poisonRate=&seed=`, `POST /admin/stop`, `GET /admin/status` |
| `decision-orchestrator` | 8082 | consume submitted, call RES, route by outcome, audit; transactional consume-produce |
| `notification-svc` | 8083 | approval, adverse-action and referral notices (never sent anywhere) |
| `application-api` | 8084 (profile `edge`) | HTTP: submit applications, status, underwriter decisions: [docs/api](../api/README.md) |
| `decision-query-api` | 8085 (profile `analytics`) | HTTP: decisions, explanations, statistics, dead letters, logs from Elasticsearch: [docs/api](../api/README.md) |
| `logstash` | 9600 (monitoring API) | four pipelines, below |
| `filebeat` | none | ships the logs of containers labelled `co.elastic.logs/enabled=true` |

Our three Java services: Spring Boot, `/actuator/health` (liveness and readiness probes), `/actuator/prometheus`, JSON logs with
`correlationId`, mTLS to Kafka with their own keystore from `make pki`, multi-stage Dockerfile (non-root UID 10001, group 0, any UID),
`mem_limit` 256 to 320 MB with `-XX:MaxRAMPercentage=70`. Logstash and Filebeat are the official Elastic images (Filebeat runs as root: it
reads the Docker socket and the container log files).

## Run

```bash
make pki && make up PROFILE=core     # kafka, elasticsearch, es-setup, odm, three services, logstash, filebeat (Docker Desktop 6 GB or more)
make demo                            # deploys the rules if missing, sends 1000 apps (about 1% poison), prints counts and PASS/FAIL
make test-logstash                   # Logstash pipeline tests, no stack needed (Docker only)
```

`make demo` checks, with queries: Elasticsearch counts equal Kafka data records per type, this run's decisions and notifications equal the
good applications sent, the poison messages are in `cc-dlq`, no document contains the raw SIN, and a masking canary (an audit event full of personal
data, sent through Kafka) arrives as initials, FSA and age band. It counts data records with a console consumer because offsets also count the
commit markers of transactional topics.

Look around: `scripts/es-curl.sh '/cc-decisions/_search?size=1'` (admin, CA verified); Kibana and kafka-ui come with `PROFILE=core,analytics`;
Logstash stats: `curl localhost:9600/_node/stats/pipelines`.

## Logstash pipelines (`deploy/logstash/`)

| Pipeline | Input | Output | Notes |
|---|---|---|---|
| `kafka-events` | decisions, manual, audit and notification topics, mTLS, group `logstash-indexer` | `cc-decisions-write`, `cc-audit-write`, `cc-notifications-write` | persistent queue; parse envelope, mask PII (`ruby/cc_mask.rb`), `@timestamp` from `occurredAt`, document id = `eventId` (redelivery is a 409, ignored); decisions get `decisionSource` ODM, or MANUAL for `cc.decisions.manual.v1` |
| `kafka-dlq` | every `cc.*.dlq` topic, group `logstash-dlq` | `cc-dlq-write` | flattens the dead letter envelope; `originalValue` is masked or redacted and cut to 1 KB |
| `logs` | Filebeat on :5044, TLS with client certificate | `cc-logs-write` | JSON log lines parsed, `service` from the compose service name; ILM 7 days |
| `events-dlq` | Logstash's own dead letter queue | `cc-dlq-write` | documents Elasticsearch rejected (strict mapping violations) |

Unparseable events and JSON that is not an event envelope never block the pipeline: they become `cc-dlq` documents (`source=logstash-parse`).
PII masking applies to every payload tree: SIN dropped, names become initials, postal code becomes its FSA, date of birth becomes an age band,
street line and employer dropped.

## Elasticsearch bootstrap

`scripts/es-setup.sh` (compose `es-setup`) creates role and user `logstash_writer` (`create_doc` on the five write aliases, cluster `monitor`
for the output's start-up check, nothing else; password generated into the root env file on first run), role and user `query_reader` /
`decision-query-api` (`read` on the five read aliases only, for the query API; password generated the same way), ILM policies `cc-logs-7d` and
`cc-dlq-30d`, the index templates in `deploy/es/`, and the first index behind each `-write` alias. Decisions, audit and notifications are
date-suffixed indices rolled by re-running es-setup (`docker compose ... run --rm es-setup`); logs and dlq roll through ILM. After changing a
template, delete the old indices (or `make down V=1`): an existing index keeps its old strict mapping.

## Behaviour worth knowing

- **Orchestrator.** Strips `dateOfBirth` and `sin` (the BOM does not expose them), posts `{"application": {...}}` to
  `/DecisionService/rest/ccApprovalApp/1.0/ccApproval/1.0`, reads `{"decision": {...}}`. 5xx and timeouts are retried (3 tries), then a circuit
  breaker opens; a 4xx or an unreadable event is poison and goes to the DLQ. `decision.latency` (Micrometer) and `processingMillis` record the RES
  latency. Decision, audit event and consumed offset commit in one Kafka transaction.
- **Error handling.** `ErrorHandling` from common-lib: bounded in-place retries with exponential backoff, then `<topic>.dlq`; the partition never blocks.
- **Notifications.** At least once; the notice `eventId` is derived from the decision `eventId`, so a redelivery is an identical event.
- **Logs.** Docker Desktop defaults to the `local` log driver, which Filebeat cannot read, so the labelled services use `json-file`.

## Tests

Unit tests and Testcontainers (Kafka, WireMock for RES) in the three services, `services/flow-test` (generator, orchestrator, notification-svc
against real Kafka: every good application decided once, audited and notified, poison dead-lettered), and `scripts/test-logstash.sh`: fixture
events through the real Logstash filters (masking, dead letters, log parsing). `make verify` runs all of them and needs Docker.
