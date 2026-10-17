# ADR-0009: Logstash and Filebeat for ingestion and logs

* Status: accepted
* Date: 2026-10-17
* Supersedes: [ADR-0003](0003-custom-es-indexer.md)

## Context and problem statement

P4 shipped a custom Spring Boot `es-indexer`. It works, but we want the Elastic stack (Elasticsearch, Logstash, Kibana, Beats) as the
platform for events and logs, and the indexer left three gaps: DLQ topics were not indexed, container logs had no home (the plan said Loki),
and every ingestion rule was Java code to build, patch and image-scan.

## Decision

Use **Logstash 8.x** (pinned to the Elasticsearch version) for Kafka to Elasticsearch ingestion, and **Filebeat 8.x** for container logs.

* Pipeline `kafka-events`: Kafka input over mTLS, persistent queue, PII masking filter (SIN dropped, names to initials, postal code to FSA,
  date of birth to age band: the same rules as `PiiMasker`), output to the existing write aliases as user `logstash_writer`.
* Pipeline `kafka-dlq`: every `*.dlq` topic into `cc-dlq`, masked.
* Pipeline `logs`: Filebeat (TLS, client certificate) into `cc-logs-*` with ILM 7 days.
* Pipeline `events-dlq`: reads Logstash's own dead letter queue (documents Elasticsearch rejected, e.g. mapping errors) into `cc-dlq`.
* Loki is dropped. Grafana keeps Prometheus (metrics) and Tempo (traces).

## Consequences

* Less code and one fewer service to build and scan; masking becomes configuration plus a ruby filter, tested by `scripts/test-logstash.sh`.
* Logstash costs about 768 MB and Filebeat 128 MB, so Docker Desktop needs 6 GB or more.
* Logstash and Filebeat images are Elastic-licensed (free basic tier), not Apache-2.0: fine for this demo, noted in `NOTICE`.
* Logstash is JVM and Ruby, so masking is not unit-testable with JUnit; fixtures run through a real Logstash in Docker.
* The Kafka input commits offsets after events reach the persistent queue (at least once); document ids equal event ids, so redelivery is idempotent.
