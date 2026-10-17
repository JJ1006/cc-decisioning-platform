# ADR-0005: Grafana LGTM + OpenTelemetry instead of Dynatrace

* Status: accepted
* Date: 2026-10-17

## Context and problem statement

A full-stack observability view is needed at zero cost.

## Decision

OpenTelemetry in every service; Prometheus, Tempo, Loki, Grafana, Alertmanager. Compose uses grafana/otel-lgtm.

## Consequences

No automatic root-cause AI like Dynatrace; dashboards and alerts are authored by us.
