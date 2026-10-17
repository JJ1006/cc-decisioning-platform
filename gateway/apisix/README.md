# API gateway (Apache APISIX, standalone)

APISIX 3.13 (pinned in `deploy/compose/docker-compose.yml`, profile `edge`, `mem_limit 256m`) is the only published entry to the APIs:
`https://localhost:8443`, mutual TLS, a client certificate signed by our CA is required. Why and how identity works:
[ADR-0011](../../docs/adr/0011-gateway-identity.md) and [docs/security-mtls.md](../../docs/security-mtls.md). Apigee concepts:
[docs/apigee-mapping.md](../../docs/apigee-mapping.md). Calls: [docs/api/README.md](../../docs/api/README.md).

| File | What |
|---|---|
| `config.yaml` | static configuration: standalone (`config_provider: yaml`, no etcd, Admin API off), TLS listener `9443` (container), loopback-only http `9080`, `radixtree_uri_with_parameter` router, Prometheus exporter on `9091` (internal), OpenTelemetry attributes |
| `apisix.yaml.tmpl` | the declarative file: SSL objects, upstreams, the `quota` plugin config, the global rule (identity, correlation id, CORS, security headers, Prometheus, OpenTelemetry), routes |
| `entrypoint.sh` | renders `apisix.yaml` from the template at container start, inserting the three PEM blocks from the PKI mounts (the certificates are not committed), then starts APISIX |

## Routes

| Route | Method and path | Roles | Upstream |
|---|---|---|---|
| `applications-submit` | `POST /v1/applications` (spike arrest 5/s, burst 5) | PARTNER | application-api |
| `applications-status` | `GET /v1/applications/:id/status` | PARTNER, UNDERWRITER | application-api |
| `applications-manual-decision` | `POST /v1/applications/:id/manual-decision` | UNDERWRITER | application-api |
| `decisions-search` | `GET /v1/decisions` | ANALYST | decision-query-api |
| `decisions-item` | `GET /v1/decisions/*` (decision, explanation) | ANALYST, UNDERWRITER | decision-query-api |
| `stats` | `GET /v1/stats/*` | ANALYST | decision-query-api |
| `ops` | `GET /v1/ops/*` | OPS | decision-query-api |
| `generator-admin` | `/admin/generator/*` rewritten to `/admin/*` | OPS | txn-generator |
| `mcp-placeholder` | `/mcp/*` | any consumer | none: 501 until phase 9 |

All routes share one quota: 100 requests per minute per consumer (`429`). A route without `labels.roles` refuses everyone (403).

## Consumers

The client certificate's CN is the consumer; the table is in the global rule (`roles` in the Lua function):

| CN (`scripts/pki.sh` client) | Role |
|---|---|
| `demo-partner` | PARTNER |
| `underwriter-portal` | UNDERWRITER |
| `analyst` | ANALYST |
| `ops-admin` | OPS |
| `load-test` | ANALYST (used by `make verify-mtls` for the quota check) |

A valid certificate with another CN is `403` ("not a known consumer").

## Change something

* **New consumer:** add the CN to `CLIENTS` in `scripts/pki.sh`, to `roles` in the global rule, run `make pki`, restart the gateway.
* **New route:** add a route with `labels.roles`, an `upstream_id` and `plugin_config_id: quota`; add a check to `scripts/verify-mtls.sh`.
* **New upstream service:** an `upstreams:` entry (https, `tls.client_cert_id: upstream-client`, `verify: true`) and the gateway's CN in the service's
  `platform.security.allowed-client-cns`.
* APISIX reloads `apisix.yaml` when it changes, but the file is rendered at start: edit the template and `docker compose restart apisix`.

## Test and debug

* `make verify-mtls` (gateway and bypass sections): partner 202, analyst 403, no certificate refused, other CA refused, spoofed headers ignored,
  HSTS and no server banner, correlation id kept, spike arrest and quota produce 429, service ports unreachable, service certificates refused by CN.
* `make api-call CLIENT=analyst P=/v1/stats/latency`.
* Logs go to stdout (shipped by Filebeat to Kibana). Raise `nginx_config.error_log_level` in `config.yaml` to `info` to see route and upstream
  decisions. A first request to a cold JVM can take a few seconds (the upstream connect timeout of 15 s includes the TLS handshake).
