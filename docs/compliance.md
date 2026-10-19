# Compliance mapping (demo)

> **This is a demonstration, not legal advice.** Maple Northern Bank is fictitious, every record is synthetic, and nothing here has been reviewed by a lawyer, a
> privacy officer, a model-risk officer or a regulator. The mapping shows where a real programme would find evidence in this repository, and where there is none yet.

| Framework | Where it is mapped below |
|---|---|
| PIPEDA (privacy, federal) | [PIPEDA](#pipeda-the-ten-fair-information-principles) |
| Canadian Human Rights Act and provincial codes (prohibited grounds) | [Prohibited grounds and fairness](#prohibited-grounds-and-fairness) |
| OSFI E-23 (model risk management) | [OSFI E-23](#osfi-e-23-model-risk) |
| OSFI B-13 (technology and cyber risk) | [OSFI B-13](#osfi-b-13-technology-and-cyber-risk-high-level) |
| FCAC / Consumer Protection Framework (adverse action, plain language) | [Adverse action](#fcac-adverse-action-and-plain-language) |
| OWASP API Security Top 10 (2023) | [OWASP API Top 10 self-check](#owasp-api-top-10-self-check) |

## PIPEDA: the ten fair information principles

| # | Principle | In this platform | Evidence | Gap |
|---|---|---|---|---|
| 1 | Accountability | named roles per API; every decision, manual decision, MCP call and privacy action audited | `cc.audit.v1`, [threat model](security/threat-model.md) | no named privacy officer (it is a demo) |
| 2 | Identifying purposes | the data is collected to decide a credit card application; the purpose of each field is its class | [data classification](privacy/data-classification.md) | no consent text |
| 3 | Consent | not modelled (synthetic applicants) | | consent capture and withdrawal |
| 4 | Limiting collection | the contracts list the fields; `additionalProperties: false`; inputs carry only what the rules read | `contracts/`, `DecisionInputs` | |
| 5 | Limiting use, disclosure and **retention** | masking before indexing; separate restricted index for financial inputs; retention per class (Kafka topic, ILM) | `cc_mask.rb`, `deploy/es`, `docs/kafka-topics.md` | production retention periods are for the bank to set |
| 6 | Accuracy | the decision is explainable from the stored inputs and the ruleset; the notice tells the applicant how to correct the credit report | `/v1/decisions/{id}/inputs`, [explanation](#explainability) | |
| 7 | Safeguards | mutual TLS on every hop, least-privilege Kafka and Elasticsearch roles, secrets outside git, signed images | [mTLS](security-mtls.md), [threat model](security/threat-model.md) | Kafka ACLs not enforced on the local broker |
| 8 | Openness | `docs/privacy/data-classification.md`, `docs/compliance.md` | | a customer-facing privacy notice |
| 9 | **Individual access** | `GET /v1/ops/privacy/export/{applicationId}`, audited | `PrivacyController`, test `exportReturnsEveryDataSet...` | export is by application, not by person; Kafka copies not included |
| 10 | Challenging compliance | the notice names the complaints route and the FCAC; erasure `POST /v1/ops/privacy/erase` | `NoticeTemplates`, `PrivacyController` | Kafka erasure waits for retention; crypto-shredding is designed only |

## Prohibited grounds and fairness

* **Prohibited grounds** (race, national or ethnic origin, colour, religion, age, sex, sexual orientation, gender identity or expression, marital status, family status, genetic characteristics, disability, a pardoned
  conviction): no rule, BOM member, contract, pipeline or dashboard mentions one. `scripts/lint-prohibited-grounds.sh` fails the build on the words in `rules/`, the XOM,
  `contracts/`, Logstash, Elasticsearch templates, Kibana and the services; on **age** anywhere in a rule except `ELIG-01` (age of majority); and on the **postal code or its FSA** in a rule
  (a proxy for where a person lives). `scripts/test-lint-prohibited-grounds.sh` plants each violation in a copy and proves the lint fails. Both run in `make verify` and CI.
* **Monitoring.** `GET /v1/stats/fairness?by=province|ageBand&minGroupSize=30` gives the approval rate per group, the ratio to the overall rate and a flag below 0.8 (the four-fifths
  rule of thumb). A group below `minGroupSize` is `insufficientSample` and never flagged. Kibana rules "Disparate impact below 0.8 for a province / an age band (24h, n >= 30)" write to
  `cc-alerts`. A flag is a prompt to investigate, not a finding of discrimination; the ratio is computed over *automated* decisions only.
* **Not covered:** province and age band are the only groups available (no protected characteristic is collected, by design, so disparate impact on the others cannot be measured; a bank
  that must measure it needs a lawful, separate, voluntary data source).

## OSFI E-23 (model risk)

The ccApproval ruleset is the model. A real programme maps the E-23 lifecycle to its own process; this is where the evidence lives here.

| E-23 element | Here | Evidence |
|---|---|---|
| **Model inventory** | one model: the ccApproval ruleset (21 rules, 2 decision tables), version `1.0`, owner: Credit Risk (fictitious), business use: automated credit card decisions, risk rating: high | `docs/rules-catalog.md` (generated into the explanation endpoint, `rules-catalog.json`), `rules/ruleapps` |
| **Ownership and roles** | rule author -> Decision Center branch -> pull request (`CODEOWNERS`) -> deploy; underwriters decide referrals | `.github/CODEOWNERS`, [gitops runbook](runbooks/gitops.md) |
| **Design and documentation** | the catalog is the specification; the `.brl` files implement it; a Python restatement checks the scenarios agree | `docs/rules-catalog.md`, `rules/tests/check_scenarios.py` |
| **Validation** | scenario regression of the ruleset (`make test-rules`); the golden dataset of about 300 labelled applications, the rule-regression gate and the champion/challenger replay are **P14** (planned, not built) | `rules/tests`, [PLAN P14](PLAN.md) |
| **Ongoing monitoring** | decision latency p95, approval rate, decline reasons, fairness ratios; alerts when the approval rate falls 20 % or a group's ratio falls below 0.8 | Grafana and Kibana dashboards, Kibana rules |
| **Explainability** | each decision stores the fired rules, the reason codes, the ruleset path, version and deployment time, and the exact inputs; `GET /v1/decisions/{id}/explanation` and `/inputs` | `DecisionPayload`, `DecisionsController` |
| **Change management** | rule change = Decision Center branch + git pull request + CI + `make deploy-rules`; on OpenShift the deployment is a reviewed promotion pull request and a **manual** Argo CD sync | [gitops runbook](runbooks/gitops.md), `promote.yml` |
| **Human oversight** | the rules REFER what they cannot decide; a human records the decision; **an LLM never decides or overrides** (no tool can) | ADR-0007, ADR-0012 |
| **Third-party / AI tooling risk** | MCP servers are read-only/simulating, masked, audited; the official ODM MCP server is local-only | [mcp](mcp.md) |

## OSFI B-13 (technology and cyber risk, high level)

| B-13 domain | Here |
|---|---|
| Governance and risk | threat model per component with ranked residual risks; ADRs for each security decision |
| Technology architecture and operations | infrastructure as code (compose, Helm, Argo CD); health probes, metrics, traces, alert runbooks; defined resource limits |
| Cyber security: identify, defend | classification of data; mutual TLS everywhere; least-privilege roles for Kafka and Elasticsearch; Pod Security restricted and default-deny NetworkPolicies on Kubernetes; signed images with an SBOM; secret scanning (gitleaks, GitGuardian); CodeQL and Trivy |
| Cyber security: detect, respond, recover | audit trail, alerts; **no** SIEM, incident process or tested recovery here (backup of Elasticsearch snapshots and Kafka mirroring are not built) |
| Third parties | pinned third-party images and operators; SBOM per image; the dev ODM image is not for production |

## FCAC adverse action and plain language

A declined application produces a notice (`NoticeTemplates`, `adverse-action.en.txt` / `.fr.txt`, bilingual in one body) with: the **principal reasons** in plain language (from the reason codes, never rule
names or scores), that the decision was reached by **automated rules**, the **credit reporting agency** used (a placeholder to configure: `[credit bureau name]`), the right to a **free
copy of the credit report** and to have inaccuracies corrected, the right to **ask for a review by a person**, a **contact** line and the **complaints route** ending at the Financial Consumer Agency of
Canada. French texts are written by hand, not machine translated, and need a native-speaker review before any real use. Delivery is not implemented (no notice leaves the system).
Whether this satisfies the actual disclosure requirements for a given product is for the bank's compliance team to decide.

## Explainability

`GET /v1/decisions/{applicationId}/explanation` returns: the outcome and decision source, the ruleset (path, version, deployment time), the fired rules **with their catalog entries** (package, intent,
action, reason codes) and the reason codes **ranked** (declines first), with applicant-facing text, and the underwriter's decision when there is one. `GET .../inputs` returns what the rules saw
(underwriter and AI-agent roles only). Together they let a person reproduce a decision with the what-if tool.

## OWASP API Top 10 self-check

A walk through the 2023 list against our APIs (application-api, decision-query-api, txn-generator admin, the gateway, the MCP endpoint). **F-n** are findings: gaps that exist today.

| # | Risk | Our APIs | Status |
|---|---|---|---|
| API1 | Broken object level authorization | **manual decision:** only role UNDERWRITER, and only on an application whose status is REFERRED (409 otherwise). **F-1:** `GET /v1/applications/{id}/status` returns the status of *any* application id to *any* PARTNER (there is no owner stored with an application; ids are sequential). **F-2:** any underwriter can decide any referred case (no case assignment) | gaps F-1, F-2 |
| API2 | Broken authentication | no passwords on the API: mutual TLS with a CA-signed certificate; the identity is the CN; a certificate from another CA is refused (`verify-mtls`); no revocation list | OK with a known limit |
| API3 | Broken object **property** level authorization (excessive data exposure, mass assignment) | DTOs, not documents; inputs only to UNDERWRITER/AGENT, checked in the gateway and in the service; the submission schema has `additionalProperties: false`; the manual-decision body binds a fixed record. **F-3:** `/v1/decisions/{id}` shows score, province, age band to ANALYST | gap F-3 (by design, documented) |
| API4 | Unrestricted resource consumption | quota 100/min per consumer, spike arrest 5/s on submissions, 1/s on erasure, MCP 5/s and 60 calls/min, `size` <= 100, result cap 20 KB, `minGroupSize` bounds. **F-4:** no explicit request body size limit at the gateway (the Tomcat default of 2 MB applies) | gap F-4 |
| API5 | Broken function level authorization | roles per route at the gateway (`labels.roles`); the specific routes outrank the wildcards (`priority`); the privacy and inputs endpoints re-check the role in the service. The remaining endpoints trust the gateway's header (only CN=apisix is believed) | OK, defence in depth on the sensitive ones |
| API6 | Unrestricted access to sensitive business flows | submission is spike-arrested and idempotent (`Idempotency-Key`); decisions cannot be re-run by a client; erasure is OPS-only, rate limited and audited | OK |
| API7 | Server-side request forgery | the services call fixed internal hosts from configuration; no endpoint takes a URL | OK |
| API8 | Security misconfiguration | TLS 1.2+ only, `Server` removed, `no-store`, HSTS; Pod Security restricted; default-deny policies. **F-5:** the Spring management port (9090) is plain http inside the network (probes and metrics); kafka-ui has no login (dev tool, loopback only locally, not deployed on Kubernetes) | gap F-5 (accepted) |
| API9 | Improper inventory management | OpenAPI of both APIs generated from code and drift-checked in CI (`docs/api`); the gateway route table is the inventory of what is exposed; the MCP tools are listed in `docs/mcp.md` | OK |
| API10 | Unsafe consumption of APIs | RES responses are parsed strictly and a missing decision is an error; MCP tool output (free text from applicants and underwriters) is untrusted: cleaned, capped and wrapped; the notice tells a model it is data | OK, injection is mitigated not eliminated |

**Findings to fix next** ([backlog](backlog.md)): F-1 (store the submitting consumer with the application and check it on status), F-2 (case assignment), F-3 (move the exact summary values to
bands), F-4 (gateway body limit), and Kafka ACL enforcement locally.
