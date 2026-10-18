# TargetDown

Severity: critical. Rule: `observability/prometheus/rules/alerts.yml`. Alertmanager UI: <http://localhost:9097>. Overview: [docs/observability.md](../../observability.md).

## What fired

Prometheus cannot scrape a target for 2 minutes (`up == 0`), or a TLS endpoint fails its blackbox probe for 3 minutes (EndpointProbeFailing).

## Impact

That component is down or unreachable, or its metrics endpoint is broken. Its panels and alerts are blind.

## Look at

1. Prometheus > Status > Targets (<http://localhost:9090/targets>) for the error text (connection refused, timeout, TLS error).
2. `docker compose ps` and `docker logs <container>`; a restart loop shows as `Restarting`.
3. decision-query-api is only scraped when it runs (analytics profile): it does not alert when absent.
4. Probe failures: the gateway, ODM and Elasticsearch are probed over TLS with our CA; `make verify-mtls` tells which hop breaks.

## Fix

* Restart the container; for a crash loop read its log. Out-of-memory kills (`docker inspect`, exit code 137): raise `mem_limit` in `deploy/compose/docker-compose.yml` or stop other containers.
