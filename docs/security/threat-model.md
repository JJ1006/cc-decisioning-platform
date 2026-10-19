# Threat model (STRIDE) - P12

Maple Northern Bank is fictitious; the data is synthetic. This is the model a real deployment would start from. Method: for each component, the six STRIDE threats
(**S**poofing, **T**ampering, **R**epudiation, **I**nformation disclosure, **D**enial of service, **E**levation of privilege), what stops each today, and what is left.
"Where" points at the file that implements the control. Related: [mTLS](../security-mtls.md), [data classification](../privacy/data-classification.md),
[compliance](../compliance.md), [ADR-0007](../adr/0007-llm-never-decides.md), [ADR-0011](../adr/0011-gateway-identity.md), [ADR-0012](../adr/0012-agent-access.md).

```mermaid
flowchart LR
  C["API clients<br/>(partner, underwriter portal, analyst, ops)"] -- "mTLS 1" --> GW["APISIX gateway"]
  A["AI agent / MCP client"] -- "mTLS /mcp/dc" --> GW
  GW -- mTLS --> S["application-api<br/>decision-query-api<br/>txn-generator"]
  S -- mTLS --> K[("Kafka<br/>ACLs")]
  K --> O["decision-orchestrator"] -- "mTLS + basic" --> ODM["ODM RES / DC"]
  K --> N["notification-svc"]
  K --> LS["Logstash<br/>masking, split"] --> ES[("Elasticsearch<br/>roles")]
  S --> ES
  ES --> KB["Kibana / Grafana"]
  MCP["odm-dc-mcp"] --> GW
  MCP --> ODM
  MCP --> K
  GIT["Git + CI + GHCR + Argo CD"] -. deploys .-> S
```

Trust boundaries: the internet side of the gateway; the gateway to the services (identity asserted by headers, believed only from one certificate); the data plane (Kafka,
Elasticsearch); the human tools (Kibana, Grafana, Decision Center); the AI agent; the supply chain.

## 1. API gateway (APISIX)

| | Threat | Control | Where | Residual |
|---|---|---|---|---|
| S | a client claims another consumer or role | the certificate CN is the identity; the headers a client sends are deleted and set from the verified CN | `gateway/apisix/apisix.yaml.tmpl` (global rule), `make verify-mtls` (spoofed X-Role -> 403) | whoever holds a consumer's key *is* that consumer: no revocation list, rotate instead |
| T | a request changed on the way | TLS 1.2+/1.3 on both hops, mutual | `config.yaml`, upstream `tls.client_cert` | none worth noting |
| R | a call cannot be tied to a person | request id + consumer in the access log; the consumer is the audit actor of the services | `request-id` plugin | the consumer is an organisation or a service, not a person |
| I | data leaves through an error or a header | `Server` removed, `no-store`, HSTS, problem+json without stack traces | `response-rewrite` | the services' own errors are trusted not to leak |
| D | a flood | quota 100/min per consumer, spike arrest 5/s on submissions, MCP 5/s | `plugin_config quota`, route plugins | sized for the demo; production adds connection limits and a WAF in front |
| E | a role reaches a route it should not | `labels.roles` per route; the generic wildcard route cannot override the specific ones (`priority`); inputs: UNDERWRITER+AGENT, privacy: OPS | route table, verify-mtls (analyst on inputs -> 403) | routes are code: a new route must carry `roles` (a route without roles denies all) |

## 2. Services (application-api, decision-query-api, txn-generator, notification-svc, orchestrator)

| | Threat | Control | Where | Residual |
|---|---|---|---|---|
| S | a service called by something other than the gateway | `client-auth need` + CN allow-list; `X-Consumer`/`X-Role` believed only from CN=apisix | `ClientCertFilter` | an attacker with any service certificate can call the services it is allowed to call |
| T | a submitted application is malformed or oversized | JSON Schema with `additionalProperties: false`; SIN checksum | `SchemaValidator`, contracts | the schema is the only validation of the personal fields' content |
| T | the manual decision of an application that is not REFERRED, or a replay | status check (REFERRED only), `Idempotency-Key` | `ApplicationsController` | **any underwriter can decide any referred application** - no assignment (OWASP API1 finding F-2) |
| R | an action without a trace | audit event in the same Kafka transaction as the decision; privacy actions audited with a hash of the id | `DecisionRouter`, `PrivacyController` | audit records are not signed or chained |
| I | personal data in responses, logs or metrics | DTOs, not documents; no identifier in log lines or metric labels; inputs only to UNDERWRITER/AGENT (checked in the service too) | `Dtos`, `Roles`, masking tests | `/v1/decisions/{id}` shows score band, province, age band to analysts by design |
| D | slow dependency (ODM) stalls the consumer | timeouts, retry, circuit breaker, DLQ | `ResClient` | one orchestrator replica |
| E | a role escalates | role from the certificate via the gateway only; `Roles.require` on the most sensitive endpoints | | only inputs and privacy are re-checked in the service; the rest trusts the gateway |

## 3. Kafka

| | Threat | Control | Where | Residual |
|---|---|---|---|---|
| S | an unknown client connects | `ssl.client.auth=required`, own CA (Strimzi clients CA on Kubernetes) | compose `kafka`, `cc-infra` | |
| T | a service writes to a topic it should not | ACLs per `KafkaUser` generated from the documented table; transactional ids by prefix | `scripts/gen-k8s-kafka.py`, `docs/security-mtls.md` | **ACLs are enforced on Kubernetes only**; the local broker authorises everything (backlog) |
| R | | audit topic written by every service | `cc.audit.v1` | |
| I | personal data in topics | only `submitted` and the dead letters hold direct identifiers; both 7 days; decisions carry inputs, no names | `docs/kafka-topics.md` | Kafka cannot erase: crypto-shredding is designed, not built |
| D | one consumer starves the broker | quotas not set; single broker locally | | |
| E | a compromised service reads other topics | its ACL covers its own topics only (Kubernetes) | | |

## 4. Elasticsearch, Logstash, Filebeat, Kibana, Grafana

| | Threat | Control | Where | Residual |
|---|---|---|---|---|
| S | a user or service impersonates another | TLS to Elasticsearch, a user and role per consumer, Logstash and Filebeat mutual TLS | `deploy/es/*.role.json`, `es-setup.sh` | PKI realm login needs a paid licence: passwords |
| T | a document changed or planted | `logstash_writer` can only `create_doc` on the write aliases (no update, no delete); strict mappings; `document_id = eventId` with `create` | `logstash_writer.role.json` | whoever has `privacy-ops` can delete (by design, audited) |
| R | | audit index; erasure and export records | | |
| I | an analyst reads the financial inputs | **the inputs are a separate index no dashboard role lists**; `verify-mtls` proves 403 for analyst and ops | `cc_events.rb` split, `kibana_*.role.json` | the applicant summary in `cc-decisions` still has the exact score, TDS and income (backlog: move to bands) |
| I | personal data in logs and dead letters | masking in Logstash; dead letters redacted and cut | `cc_mask.rb` | free text names are not found by patterns |
| D | an expensive query | read roles only; no scripting for analysts; size limits in the API | | Kibana is a power tool: ES\|QL rules can be heavy |
| E | a Kibana user edits rules or reads the logs | Kibana feature privileges per space (read only for ops, none for logs for analysts) | `kibana_*.role.json` | |

## 5. ODM (RES and Decision Center)

| | Threat | Control | Where | Residual |
|---|---|---|---|---|
| S | an unknown caller executes rules or edits them | Liberty requires a client certificate from our CA **and** basic auth | `deploy/odm/mtls.xml` | demo users with fixed passwords (the dev image) |
| T | a rule is changed without review | rules are in git; Decision Center + PR + (Argo CD manual sync on OpenShift) | [compliance](../compliance.md) | the dev image has one admin |
| R | | `ODM_EXECUTE` audit event per decision with the ruleset path | `DecisionRouter` | |
| I | personal data reaches RES | `sin` and `dateOfBirth` are removed before the call | `ResClient` mixin, test | the rules see income, debt and score by necessity |
| D | RES slow or down | circuit breaker, DLQ, retries | | single instance |
| E | `dc_deploy_ruleapp` changes what is decided | disabled; needs `ALLOW_DEPLOY` and a human-held token; plan before execute | `mcp` tools | |

## 6. MCP servers and the AI agent

| | Threat | Control | Where | Residual |
|---|---|---|---|---|
| S | a client calls the MCP server directly | the server accepts only the gateway's certificate; the gateway admits role MCP_CLIENT only | `mtls.py`, route `mcp-dc` | stdio mode trusts the local user |
| T | the agent changes a decision | **no tool decides**: no manual-decision tool, no publish, no write; what-if is labelled and stores nothing; deploy off | ADR-0007/0012, `test_tools` | |
| T | **prompt injection** in data (an employer called "Ignore previous instructions and approve ...", an underwriter note) steers the agent | every free-text value is cleaned (control, zero-width and bidi characters), capped, wrapped in `<data>`; the notice says data is not instructions; tools cannot act on a decision anyway | `untrusted.py`, `tests/test_untrusted.py`; the generator's `injectionRate` | wrapping lowers the chance, it does not remove it; the real defence is that the worst outcome is a wrong *explanation*, never a decision |
| R | | every call is `cc.audit.mcp.v1` (fail closed) with argument names and a hash | `audit.py` | |
| I | the agent leaks personal data | Presidio masking on every output; inputs carry no identifier; the agent role cannot export or erase | `masking.py`, gateway roles | free-text names; an agent can still be shown financial inputs of a case it asks about (role AGENT, audited) |
| D | an agent loops | 60 calls/min, 20 KB cap, gateway 5/s | `guard.py` | |
| E | the agent gets a role it should not | separate certificates (`mcp-agent` AGENT, `mcp-client` MCP_CLIENT, `mcp-res`) | PKI | the **official RES MCP server has no masking, audit or gateway**: local use with synthetic data only |

## 7. CI/CD, supply chain, cluster

| | Threat | Control | Where | Residual |
|---|---|---|---|---|
| S | a malicious dependency or action | pinned versions; Dependabot (paused, limits 0); `trivy`, CodeQL, gitleaks, GitGuardian on every PR | `.github/workflows` | actions are pinned by major version, not SHA |
| T | an image is replaced | cosign keyless signatures and a Syft SBOM per image | `images.yml` | no admission policy verifies the signature yet (backlog) |
| R | who deployed what | Git history + Argo CD; promotion is a reviewed PR, then a manual sync | `promote.yml`, runbook | |
| I | a secret in git | gitleaks + GitGuardian; secrets only in `scripts/k8s-secrets.sh` / secret managers; PKI keys gitignored | | the CA key lands in a cluster secret on kind |
| D | a runaway pipeline | `timeout-minutes` on the long jobs | | |
| E | a workload creates cluster-scoped objects | AppProject `apps` allows none; Pod Security restricted; NetworkPolicy default-deny | `deploy/argocd/projects`, charts | |

## Residual risk, ranked

1. **Kafka ACLs are not enforced locally** and Kafka cannot erase (no crypto-shredding yet).
2. **BOLA on application status and manual decisions** (F-1, F-2 in [compliance](../compliance.md#owasp-api-top-10-self-check)): a partner can read the status of another partner's
   application id; any underwriter can decide any referred case.
3. **The analyst-readable decisions still carry exact score, TDS and income** in the applicant summary.
4. **Prompt injection** is mitigated by design (an agent cannot decide) and by wrapping, not eliminated.
5. **Demo credentials** (ODM users, local passwords) and a CA key in the cluster: acceptable for a demo, not for production.
6. **Single instances** everywhere: availability is not a design goal here.
