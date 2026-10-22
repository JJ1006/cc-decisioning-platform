# Backlog

What is known and not done at 1.0, grouped by what it blocks. The honest summary of the product's limits is [limitations.md](limitations.md). Items marked **post-1.0** are planned; **prod-gap** items must be closed before this is anything other than a demo.

## Closed in P15 (the cheap wins)

| Item | What was done |
|---|---|
| OWASP F-4: no body size limit at the gateway | APISIX answers 413 above 64 KiB (`client_max_body_size`, `gateway/apisix/config.yaml`); `make verify-mtls` checks it with a 100 KB body |
| kafka-ui `ClusterAuthorizationException` every few seconds | the generated ACLs give `kafka-ui` Describe on the cluster (`scripts/gen-k8s-kafka.py`, Strimzi user template, `docs/security-mtls.md`) |
| MCP audit: the first call after a Kafka restart could fail closed | the audit producer warms up at server start and retries a failed send once (3 new tests) |
| `rules-gate` was not a required check | required on `main` since the first green run (PR 56); the workflow passes at once when no gated path changed |
| Dependabot paused | re-enabled for maven, uv, github-actions, docker and docker-compose ([runbook](runbooks/dependabot.md)); no npm manifest exists |
| Floating pins | actions by commit SHA, third-party images by `tag@sha256`, tools by version; `scripts/check-pins.sh` in `make verify` |
| Alerts without runbooks | every Prometheus and Kibana alert has one; `scripts/check-runbooks.sh` in `make verify` |
| Vulnerable dependencies found by trivy | Tomcat 10.1.61, OpenTelemetry Java agent 2.26.1, no pip/setuptools in the Python runtime images |

## post-1.0

| Item | Why it is debt | Planned |
|---|---|---|
| Spring Boot 4 / Spring Framework 7 | `spring-webmvc` 6.2.19 carries two CRITICAL CVEs (CVE-2026-47884, CVE-2026-47890) fixed only in 7.0.9; 6.2.x has no further release. Accepted in `.trivyignore` until **2026-12-31**: no XSLT view, no view resolver, no server-sent events in any service | upgrade the reactor to Boot 4, then delete the `.trivyignore` entries |
| `decision_explain` lists every reason code a fired decision table can raise | for `RISK-DT-01` the explanation carries all four codes of the table next to the one that applied; an agent can mention codes that did not apply (the agent eval counts claims, not mentions) | return only the codes of the decision in `firedRuleDetails`, and the table's possible codes under another name |
| Kibana saved objects, rules and roles on Kubernetes | `make seed-kibana` is compose-only; the charts create Kibana but not the space, dashboards or alert rules. A Job like `es-setup` that runs `scripts/seed-kibana.sh` needs `jq`, a ConfigMap for the 1 MB ndjson and a kind run to prove it, which does not fit a one-hour change | a Job in `cc-infra` that runs a pinned image with `jq` and the exported objects |
| Kubernetes: Elasticsearch, Logstash and blackbox exporters | the ELK dashboard and a few alerts of the obs profile have no data on Kubernetes (Kafka lag comes from the Strimzi `kafkaExporter` only when enabled) | add the exporters to `cc-observability` |
| Kafka 4.x on Kubernetes vs 3.9.2 in compose | Strimzi 1.x ships Kafka 4.x only | move the compose broker to a 4.x image |
| Evals: agent evals are manual | they need a model and credentials: `workflow_dispatch` only, skipped without the `ANTHROPIC_API_KEY` secret | a nightly schedule once the secret and a budget are agreed |
| Evals: k6 skips TLS verification of the gateway | k6 has no option for a private CA; `evals/k6/run.sh` verifies the certificate with openssl first, then runs k6 with `insecureSkipTLSVerify` against localhost | a k6 build or image with the CA, or run k6 inside the Docker network |
| Evals: the load test raises the gateway limits by restarting the gateway | quota and spike arrest would answer 429; a crash between leaves the raised limits until the next `make up` | a dedicated `load-test` consumer with its own quota |
| Erase or export by person, not by application | the applicant token finds the applications of one applicant but no endpoint loops over them | `POST /v1/ops/privacy/erase-applicant` |
| npm tools are pinned by hand | promptfoo, Playwright, Spectral and the `claude` CLI have no `package.json`, so Dependabot cannot move them | one `package.json` per tool directory with a lock file |
| Third-party images carry known CVEs | see [limitations](limitations.md#known-vulnerabilities-in-third-party-images): the observability and ELK images are demo-grade versions; Dependabot proposes their updates, ODM and Elastic move by hand | bump the observability stack as one reviewed change with the dashboards re-checked |
| French notice review | the French texts are written by hand and not reviewed by a native speaker; the credit bureau and contact lines are placeholders | compliance review |

## prod-gap (do not run this for customers before these)

| Item | Why it is debt | Planned |
|---|---|---|
| ODM is the developer edition | Derby database that loses the rules on restart, licence for development only, never redistributed ([ADR-0001](adr/0001-odm-dev-licence.md)) | the IBM ODM operator or Helm chart with a real database and a purchased licence |
| OWASP F-1 BOLA on application status | any PARTNER can read the status of any application id; no owner is stored with an application | store the submitting consumer in the submitted event and check it on status |
| OWASP F-2 no case assignment | any underwriter can decide any REFERRED application | assign referrals to an underwriter queue |
| Crypto-shredding for Kafka | a Kafka-side erasure waits for retention (7 to 30 days); the unmasked original also sits in the `*.dlq` topics for 7 days | per-applicant data keys held by a key service, destroyed on erasure (design in [data-classification](privacy/data-classification.md)); until then keep dlq retention short |
| Privacy operations in their own service | the read-only query API holds a second, delete-capable Elasticsearch credential (`privacy-ops` user) for the erase and export endpoints | a `privacy-api` service with its own identity, network policy and ES user; locally the extra user is the documented trade-off |
| Official RES MCP server has no masking or audit | it runs rulesets as given, and its remote mode is plain HTTP | local, synthetic-data use only; not exposed through the gateway; reconsider when upstream adds TLS and hooks |
| GitOps: OpenShift never synced | rendered, schema-checked and policy-linted, never synced on a cluster; the operators' `startingCSV` is not pinned | first OpenShift install |
| GitOps: Sealed Secrets / External Secrets | secrets still come from `scripts/k8s-secrets.sh` (kind) or by hand (OpenShift); only documented | one more Application at wave -2 once a secret store is chosen |
| ECK pods under Pod Security `restricted` | ECK's own init containers need explicit security contexts; kind-full runs the namespace as `privileged` (Filebeat) and OpenShift relies on the SCC | verify on OpenShift, then add the contexts |
| Sizing | the 100 rps spike and the 2 s end-to-end threshold fail on a one-node 7 GB Docker VM (reported in [evals](evals.md)); Argo CD plus the lite platform leaves no room for a second replica | a sized cluster; 12 GB VM for local kind-full |
| Credit bureau, mail, identity | simulated; no real integration, no consent capture, no adverse-action letter delivery | out of scope for a portfolio project |
