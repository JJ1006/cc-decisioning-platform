---
name: grafana-dashboard
description: "Add or change a Grafana dashboard, panel, Prometheus rule or alert with its runbook. Use when asked to edit Grafana, a metrics panel, a PromQL query, an SLO, an alert rule or the observability dashboards."
---

# Grafana dashboard

Dashboards are generated: edit the generator, never the JSON by hand.

## Steps

1. Generator: `observability/grafana/bootstrap/gen_dashboards.py` (the JSON in `observability/grafana/dashboards/` must equal its output, `make lint-obs` checks it).
2. Metrics: Micrometer prometheus names of the services, Kafka/Elasticsearch/Logstash exporters. Check a query against the live Prometheus first: `make check-dashboards` lists queries without data.
3. A new alert rule (`observability/prometheus/rules/alerts.yml`, `slo.yml`) needs a runbook `docs/runbooks/alerts/<AlertName>.md`; `make lint-obs` fails without it.
4. Regenerate, then `make lint-obs` (generator = files, promtool, amtool, collector validate, runbooks).
5. `make up PROFILE=core,edge,obs`, `make demo`, `make grafana-shots` for `docs/images/grafana-*.png`.

## Verify

```bash
make lint-obs && make check-dashboards
```
