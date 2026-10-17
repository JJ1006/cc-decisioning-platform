# ADR-0005: Grafana LGTM + OpenTelemetry instead of Dynatrace

* Status: accepted
* Date: 2026-10-17

## Context and problem statement

A full-stack observability view is needed at zero cost.

## Decision

OpenTelemetry in every service; Prometheus, Tempo, Loki, Grafana, Alertmanager. Compose uses grafana/otel-lgtm.

## Consequences

No automatic root-cause AI like Dynatrace; dashboards and alerts are authored by us.

## Update (ADR-0009)

Logs are no longer part of the Grafana stack: Loki is dropped. Logs go through Filebeat and Logstash into Elasticsearch and are read in Kibana. Grafana keeps metrics (Prometheus) and traces (Tempo).
