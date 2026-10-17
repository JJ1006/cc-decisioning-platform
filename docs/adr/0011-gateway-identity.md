# ADR-0011: Gateway identity and mutual TLS to the backends

* Status: accepted
* Date: 2026-10-17
* Refines: [ADR-0008](0008-mtls-everywhere.md), replaces the `X-Role` placeholder of [ADR-0010](0010-api-tier.md)

## Context and problem statement

P5 trusted an `X-Role` header "until the gateway sets it". P6 adds the gateway, so identity has to come from something a caller cannot forge,
and every hop of the request path has to authenticate both ends.

## Decision

* **Consumer = client certificate CN.** APISIX 3.x in standalone mode terminates TLS on `:8443` and requires a client certificate signed by
  our CA (depth 2). A global rule maps the CN to a role (`demo-partner` PARTNER, `underwriter-portal` UNDERWRITER, `analyst` and `load-test`
  ANALYST, `ops-admin` OPS), checks it against the `labels.roles` of the matched route (fail closed: a route without roles is refused), and sets
  `X-Consumer` and `X-Role`, deleting whatever the client sent. APISIX OSS has no certificate-to-consumer plugin; a ten-line serverless
  function in the declarative file does it, and the table of consumers is in one place (`gateway/apisix/apisix.yaml.tmpl`).
* **The gateway is the only caller that may assert identity.** The services run Tomcat with `client-auth=need` and a servlet filter that
  admits only the listed certificate CNs: `apisix` for application-api and txn-generator, `apisix` and `application-api` for decision-query-api.
  Only a connection that presents the certificate with CN=apisix has its `X-Consumer` and `X-Role` believed; a service caller is identified by its own
  CN with role SERVICE and its headers are ignored. Services publish no host ports (`COMPOSE_DEBUG=1` adds a documented override).
* **ODM RES requires client certificates.** A Liberty override (`deploy/odm/mtls.xml`, `clientAuthentication=true`) makes the HTTPS port refuse
  clients without a certificate from our CA. The orchestrator presents its own certificate and keeps the basic-auth user; admin scripts and a
  browser use the `admin` client certificate. The plain dev port 9060 stays inside the container (Decision Center reaches RES on it) and is not published.
* **Elasticsearch keeps TLS plus least-privilege users.** The PKI realm (login by certificate) is not available on the basic licence
  (`GET _xpack/usage` reports `security.realms.pki.available: false`; `scripts/verify-mtls.sh` checks it), so Logstash (`logstash_writer`)
  and the query API (`decision-query-api`) authenticate with a generated password over TLS that verifies our CA. If the licence changes, switching
  is a realm setting and two keystores.
* **Kafka was already mutual TLS** (client auth required, no plaintext listener except the unpublished KRaft controller); every client has its own
  certificate (docs/security-mtls.md lists them and the ACLs a Strimzi `KafkaUser` will need in P10).

## Consequences

* A browser needs a client certificate for Decision Center (import `security/pki/out/admin/admin.p12`). Kibana and kafka-ui stay TLS or plain
  http UIs with their own login: they are operator tools, not part of the API path.
* A compromised gateway certificate can assert any role to every service: it is the crown jewel (rotation: docs/security-mtls.md). Services still
  check the route's role themselves where the action is sensitive (manual decisions).
* The lua function and the label convention are APISIX specific; docs/apigee-mapping.md shows where they would be policies in Apigee.
* Handshakes from the gateway to a cold JVM can take seconds on a busy laptop: the upstream connect timeout is 15 s and connections are pooled.
