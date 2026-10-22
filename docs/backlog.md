# Backlog (known tech debt)

| Item | Why it is debt | Planned |
|---|---|---|
| Official RES MCP server has no masking or audit | It runs rulesets as given, and its remote mode is plain HTTP. | Local, synthetic-data use only; not exposed through the gateway. Reconsider when upstream adds TLS/hooks. |
| Kubernetes: Kibana saved objects, rules and roles | `make seed-kibana` is compose-only; the charts create Kibana but not the space, dashboards or alert rules | P11/P12: a Job like es-setup that runs `scripts/seed-kibana.sh` |
| Kubernetes: Elasticsearch, Logstash and blackbox exporters | the ELK dashboard and a few alerts of the obs profile have no data on Kubernetes (Kafka lag comes from the Strimzi `kafkaExporter` only when enabled) | P11 |
| Kubernetes: ECK pods under Pod Security `restricted` | ECK's own init containers need explicit security contexts; kind-full runs the namespace as `privileged` (Filebeat) and OpenShift relies on the SCC | verify on OpenShift, then add the contexts |
| Kafka 4.3 on Kubernetes vs 3.9.2 in compose | Strimzi 1.x ships Kafka 4.x only | move the compose broker to a 4.x image |
| GitOps: Sealed Secrets / External Secrets | secrets still come from `scripts/k8s-secrets.sh` (kind) or by hand (OpenShift); only documented | one more Application at wave -2 once a secret store is chosen |
| GitOps: OpenShift not run | the openshift environment is rendered, schema-checked and policy-linted, never synced on a cluster; `startingCSV` of the operators is not pinned | first OpenShift install |
| GitOps: memory on a 7 GB Docker VM | Argo CD (~0.7 GB) plus the lite platform leaves no room for a second replica | 12 GB VM, or trim ODM |
| Closed in P12: `whatif` indicative, audit `eventType` for MCP | the decision event carries the inputs (restricted index, `decision_inputs`, exact what-if); MCP audit records are `cc.audit.mcp.v1` | done |
| OWASP F-1 BOLA on application status | any PARTNER can read the status of any application id; no owner is stored with an application | store the submitting consumer in the submitted event and check it on status |
| OWASP F-2 no case assignment | any underwriter can decide any REFERRED application | assign referrals to an underwriter queue |
| Closed in P12 follow-ups: exact summary values in `cc-decisions`, Kafka ACLs in compose, dashboard panels for the MCP audit trail and fairness ratios | `applicantSummary` keeps bands only (`scoreBand`, `tdsBand`, `incomeBand`, `limitBand`, `ageBand`), exact values only in `cc-decision-inputs`; `StandardAuthorizer` with generated ACLs; two Kibana panels | done |
| OWASP F-4 no body size limit at the gateway | the Tomcat default (2 MB) applies | `client-max-body-size` on the gateway |
| Crypto-shredding for Kafka | a Kafka-side erasure waits for retention (7 to 30 days); the unmasked original also sits in the `*.dlq` topics for 7 days | per-applicant data keys held by a key service, destroyed on erasure (envelope encryption of the PII fields before they are produced; design in data-classification.md); until then keep dlq retention short |
| Erase or export by person, not by application | the applicant token finds the applications of one applicant but no endpoint loops over them | `POST /v1/ops/privacy/erase-applicant` |
| Privacy operations in their own service | the read-only query API holds a second, delete-capable Elasticsearch credential (`privacy-ops` user) for the erase and export endpoints | in production move them to a `privacy-api` service with its own identity, network policy and ES user, so the analytics read path never holds a write credential; locally the extra user is the documented trade-off |
| French notice review | the French texts are written by hand and not reviewed by a native speaker; the credit bureau and contact lines are placeholders | compliance review |
