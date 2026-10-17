# Event services (P4)

```text
txn-generator --> cc.applications.submitted.v1 --> decision-orchestrator --RES--> cc.decisions.{approved,declined,referred}.v1
                                                                         \--> cc.audit.v1
cc.decisions.* --> notification-svc --> cc.notifications.v1
cc.decisions.* + cc.audit.v1 + cc.notifications.v1 --> es-indexer --> Elasticsearch (cc-decisions, cc-audit, cc-notifications)
every consumer: failures -> <topic>.dlq
```

| Service | Port (host) | Does |
|---|---|---|
| `txn-generator` | 8081 | Datafaker en-CA applications; `POST /admin/start?tps=&total=&poisonRate=&seed=`, `POST /admin/stop`, `GET /admin/status` |
| `decision-orchestrator` | 8082 | consume submitted, call RES, route by outcome, audit; transactional consume-produce |
| `notification-svc` | 8083 | approval, adverse-action and referral notices (never sent anywhere) |
| `es-indexer` | 8084 | masks PII, bulk-indexes over TLS as the least-privilege `es-indexer` user |

Every service: Spring Boot, `/actuator/health` (liveness and readiness probes), `/actuator/prometheus`, JSON logs with
`correlationId`, mTLS to Kafka with its own keystore from `make pki`, multi-stage Dockerfile (non-root UID 10001, group 0,
runs under any UID), `mem_limit` 256 to 320 MB with `-XX:MaxRAMPercentage=70`.

## Run

```bash
make pki && make up PROFILE=core     # kafka, elasticsearch, es-setup, odm, the four services
make demo                            # deploys the rules if missing, sends 1000 apps (about 1% poison), prints counts
```

`make demo` ends with PASS only if the indexed decisions equal the good applications sent and the DLQ holds the poison
messages. It counts data records with a console consumer, because offsets also count the commit markers that transactional
topics contain.

Look around: `scripts/es-curl.sh '/cc-decisions/_search?size=1'` (admin, CA verified), kafka-ui and Kibana come with
`PROFILE=core,analytics`.

## Behaviour worth knowing

- **Orchestrator.** Reads `cc.applications.submitted.v1`, strips `dateOfBirth` and `sin` (the BOM does not expose them),
  posts `{"application": {...}}` to `/DecisionService/rest/ccApprovalApp/1.0/ccApproval/1.0` and reads `{"decision": {...}}`.
  The rules return `firedRules`, so no RES execution trace is needed. 5xx and timeouts are retried (3 tries) and then open a
  circuit breaker; a 4xx or unreadable event is poison and goes straight to the DLQ. `decision.latency` (Micrometer timer) and
  `processingMillis` on the event record the RES latency.
- **Exactly-once-ish.** Decision, audit event and consumed offset commit in one Kafka transaction
  (`transactional-id-prefix`); consumers elsewhere read `read_committed`.
- **Error handling.** `ErrorHandling` from common-lib: bounded in-place retries with exponential backoff, then
  `<topic>.dlq` with the original payload. The partition never blocks.
- **Notifications.** At least once. The notice `eventId` is derived from the decision `eventId`, so a redelivery produces the
  same event and Elasticsearch ignores the duplicate (document id = `eventId`, a 409 counts as success).
- **Indexer.** Index per type, date suffixed (`cc-decisions-2026.10.17`), written through the `<type>-write` alias, read
  through `<type>`. Strict mappings: a document with an unknown field gets HTTP 400 for itself only and is dead-lettered.
  PII masking (`PiiMasker`) applies to every payload tree: SIN dropped, names become initials, postal code becomes its FSA,
  date of birth becomes an age band, street line and employer dropped.
- **Elasticsearch bootstrap.** `scripts/es-setup.sh` (compose `es-setup`) creates role `cc_indexer` (`create_doc` on the three
  write aliases, nothing else), user `es-indexer` (password generated into the root env file on first run), the index
  templates in `deploy/es/` and today's indices. Re-run it daily (`docker compose ... run --rm es-setup`) to roll the write
  alias; there is no scheduler.

## Tests

Unit tests plus Testcontainers (Kafka, Elasticsearch with TLS and auth, WireMock for RES) in every service, and
`services/flow-test`: generator, orchestrator, notification-svc and indexer in one JVM against real Kafka and Elasticsearch,
checking that every good application is decided once, audited, notified and indexed, poison is dead-lettered and nothing
personal reaches Elasticsearch. They need Docker; `make verify` runs them.
