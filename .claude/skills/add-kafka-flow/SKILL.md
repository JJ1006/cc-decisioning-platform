---
name: add-kafka-flow
description: "Add a Kafka topic or an event flow end to end. Use when asked to add a topic, a producer, a consumer, an event type, a retry topic or dead letter flow, or to wire a new service into Kafka and Elasticsearch."
---

# Add a Kafka flow

Rules of the repo: idempotent producer, manual commit, retry topic + DLQ, Testcontainers test, mTLS client certificate from `scripts/pki.sh`, least-privilege ACL.

## Steps

1. **Topic**: add a row to the table in `docs/kafka-topics.md` (name `cc.<noun>.<verb>.v1`, key, partitions, retention, producer, consumers, data class). `scripts/create-topics.sh` creates it plus `<topic>.retry` and `<topic>.dlq`.
2. **Contract**: `contracts/<name>.schema.json` (envelope + payload), an example in `contracts/examples/`, bump `schemaVersion` for any change of an existing schema. `scripts/validate-contracts.py` and the `ContractRoundTripTest` check it.
3. **ACL**: add the client to the table "Kafka clients and ACLs" in `docs/security-mtls.md` (reads with group, writes, transactional id). Then `scripts/gen-k8s-kafka.py` (Strimzi `KafkaUser`s and `deploy/compose/kafka-acls.args`), and `scripts/helm-sync.sh`. A new service needs a certificate: `COMPONENTS`/`CLIENTS` in `scripts/pki.sh`.
4. **Code** (module under `services/`): producer with `enable.idempotence=true`; consumer with manual commit, non-blocking retry topic, then DLQ; JSON logs with `correlationId`.
5. **Logstash**: topic in `deploy/logstash/pipelines/kafka-events` (and the dlq pipeline); target alias and ES template in `deploy/es/`; masking in `deploy/logstash/ruby/` for any field with personal data; fixture in `deploy/logstash/test/cases/`. Run `scripts/helm-sync.sh`.
6. **Privacy**: classify every new field in `docs/privacy/data-classification.md` (privacy-reviewer agent).
7. **Test**: a Testcontainers Kafka test (happy path, poison message to DLQ, retry, no duplicate on redelivery).

## Verify

```bash
scripts/validate-contracts.py && scripts/gen-k8s-kafka.py --check && scripts/helm-sync.sh --check
make test-logstash && make verify
make up PROFILE=core,analytics,edge && scripts/verify-mtls.sh kafka   # ACLs hold
```
