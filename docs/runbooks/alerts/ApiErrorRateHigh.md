# ApiErrorRateHigh

Severity: critical. Rule: `observability/prometheus/rules/alerts.yml`. Alertmanager UI: <http://localhost:9097>. Overview: [docs/observability.md](../../observability.md).

## What fired

More than 1% of the gateway's requests answered with 5xx for 5 minutes (at least 0.05 requests per second).

## Impact

Clients see failures; the 99.5% availability objective burns its error budget (Grafana > API SLO).

## Look at

1. Grafana > API SLO and APISIX: which route and which status (502/504 mean a service did not answer, 503 a service refused).
2. Grafana > Platform overview: error ratio by service from the traces; Targets down.
3. Open a failing trace (Explore > Tempo, `{ status = error }`): the span with the error and its logs (Tempo links to `cc-logs` by trace id).
4. Gateway to service mutual TLS problems: `make verify-mtls`; a certificate that expired: CertificateExpiringSoon.

## Fix

* Restart the failing service; if it keeps failing, read its log in Kibana (`cc-logs`, level ERROR, service).
* Dependency down (Kafka, decision-query-api, Elasticsearch): bring it back; application status calls need decision-query-api, submissions only need Kafka.
