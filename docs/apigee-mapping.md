# Apigee concepts and their Apache APISIX equivalents

Why APISIX and not Apigee: [ADR-0004](adr/0004-apisix-instead-of-apigee.md). The gateway here runs in standalone mode: one declarative file
(`gateway/apisix/apisix.yaml.tmpl`) and no etcd, no Admin API. Identity design: [ADR-0011](adr/0011-gateway-identity.md).

| Apigee | APISIX in this repo | Notes |
|---|---|---|
| API proxy (ProxyEndpoint) | **Route** (`routes:`: uri, methods, `labels`, plugins) | one route per operation here, with `labels.roles` as the authorization list |
| Target endpoint / target server | **Upstream** (`upstreams:`: nodes, scheme, `tls`, timeouts, keepalive pool) | the upstream presents the gateway's client certificate (`ssls` of `type: client`) and verifies the service against our CA |
| Virtual host / environment TLS | **SSL object** (`ssls:`: `snis`, certificate, `client.ca`, `client.depth`) | `client` makes the client certificate mandatory (mutual TLS) |
| Shared flow | **Plugin config** (`plugin_configs:`) and **global rule** (`global_rules:`) | `quota` is a plugin config shared by the routes; identity and security headers are a global rule |
| API product | the set of routes a role may call (`labels.roles`) | no product object: the product is the role |
| Developer, developer app | **Consumer** = client certificate CN (`demo-partner`, `underwriter-portal`, `analyst`, `ops-admin`) | OSS APISIX has no certificate-to-consumer plugin; a serverless function maps CN to role (consumer table in the global rule) |
| API key (VerifyAPIKey) | `key-auth` plugin with consumers | not used: the certificate is the credential. Add `key-auth` per route to require both |
| OAuth 2.0 / JWT (OAuthV2, VerifyJWT) | `openid-connect`, `jwt-auth` plugins | not used yet; a JWT from the partner's IdP would be a second factor on the same routes |
| Quota policy | `limit-count` (`count`, `time_window`, `key`, `group`, `rejected_code: 429`) | 100 per minute per consumer, one counter across all routes (`group: per-consumer`); `policy: local` counts per gateway instance, `redis` shares them |
| Spike arrest | `limit-req` (`rate`, `burst`, `nodelay`) | 5 per second, burst 5, per consumer, on `POST /v1/applications` |
| Concurrent rate limit | `limit-conn` | not used |
| AssignMessage (set or remove headers) | `proxy-rewrite`, `response-rewrite`, serverless functions | the global rule deletes client-sent `X-Role` and `X-Consumer` and sets them; `response-rewrite` adds HSTS and friends and removes `Server` |
| RaiseFault / FaultRules | `fault-injection` abort | the `/mcp/*` placeholder answers 501 problem+json |
| CORS policy | `cors` plugin | allow origin `https://localhost:8443` only |
| Correlation id (`messageid`, custom) | `request-id` plugin (`header_name: X-Correlation-Id`) | keeps the client's value, creates one otherwise, echoes it |
| Analytics, Monitoring | `prometheus` plugin (port 9091, internal), `opentelemetry` plugin (collector arrives in P8) | dashboards in Grafana (P8); request logs reach Kibana through Filebeat |
| Trace tool, debug sessions | error log level (`nginx_config.error_log_level`), `COMPOSE_DEBUG=1` | no UI |
| Environment groups, revisions, deploy | Git: the file is reviewed and versioned; APISIX reloads it when it changes | rollback is `git revert` |
| Management API / UI | none in standalone mode (`enable_admin: false`) | smaller attack surface; the Admin API exists in the etcd mode |
| Apigee X / hybrid runtime | the APISIX Helm chart (data plane) in P10 | same declarative file as a ConfigMap |

## What does not port

* **JavaScript and Java callouts, ServiceCallout, ExtensionCallout.** APISIX plugins are Lua (or an external plugin runner for Go, Java, Python,
  JavaScript). The CN-to-role function is Lua in the declarative file; nothing written for an Apigee callout runs unchanged.
* **Policy flow editor and the shared-flow hierarchy.** Plugin order is by plugin priority, not by drawing; behaviour that depends on the order of
  policies must be re-checked (for example, the role check runs in the rewrite phase, before the quota in the access phase).
* **Developer portal, API product subscription and monetization.** None of it exists here; the "subscription" is the certificate you were issued.
* **Distributed quota out of the box.** Apigee's quota is global; here `limit-count` is local to a gateway instance unless you configure Redis.
* **Key and secret management (KVM, encrypted maps).** Use Kubernetes secrets or Vault; the gateway certificate is mounted from the PKI.
* **Advanced analytics (custom reports, anomaly detection).** Prometheus metrics and Elasticsearch logs cover the basics, not Apigee's reports.
* **Built-in threat protection** (JSON and XML threat, regex protection). The services validate bodies against JSON Schema; add `request-validation`
  or a WAF in front for more.
