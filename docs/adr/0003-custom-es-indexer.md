# ADR-0003: Custom es-indexer instead of Kafka Connect

* Status: superseded by [ADR-0009](0009-elk-ingestion.md)
* Date: 2026-10-17

## Context and problem statement

Decisions must be PII-masked before indexing, with DLQ on mapping errors.

## Decision

Write a small Spring Boot es-indexer consumer that masks, bulk-indexes and dead-letters.

## Consequences

More code than a Connect sink, but masking and retry/DLQ behaviour are explicit and testable.
