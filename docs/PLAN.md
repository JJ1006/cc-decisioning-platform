# Plan — Credit Card Application Decisioning Platform (IBM ODM + Kafka + Elastic, OCP-ready, GitOps)

> **Next action once approved:** copy this file to `ODM_kafka_OCP/docs/PLAN.md` and copy the "SONNET RUNBOOK" section to `ODM_kafka_OCP/SONNET_RUNBOOK.md`. Build nothing else; Sonnet runs the phases.

## Context
Build a demo of a credit-card approval system for a large Canadian bank. It is portfolio-grade and free of cost:
- IBM ODM (Rule Designer, Decision Center, RES) makes the rule decisions.
- Kafka is the backbone between every step, decisions are stored in Elasticsearch, and Kibana shows business analytics.
- An API gateway with mTLS sits in front, with a query API over Elasticsearch.
- Prometheus and Grafana handle monitoring, Helm and ArgoCD handle deployment, and two MCP servers let AI agents work with ODM.

Everything is free; everything except the ODM image is open source.
The repo is empty (`ODM_kafka_OCP/`). The machine is an M2 with **8 GB RAM**. docker, kubectl, helm and kind are installed.

## Tech decisions
| Need | Choice | Why / licence |
|---|---|---|
| Rules engine | **IBM ODM for Developers** 9.5.x (`icr.io/cpopen/odm-k8s/odm`): RES, Decision Center, Decision Runner | Free for dev/eval, **not OSS, not for production**. Pulled at runtime and never redistributed. NOTICE file. |
| Rule authoring | **ODM Rule Designer** (Eclipse plug-in, free dev download) | Rule project source lives in git and syncs to Decision Center |
| Event backbone | Apache Kafka 3.9+ (KRaft), **Strimzi** on k8s/OCP, kafka-ui (kafbat) | Apache-2.0 |
| Search / storage | Elasticsearch and Kibana 8.x, **ECK** on k8s | AGPL/basic, free |
| API gateway (Apigee substitute) | **Apache APISIX** + APISIX Dashboard | Apache-2.0. Has mTLS, rate limits, API keys/JWT, OpenAPI import, and Prometheus metrics, all free. Kong's mTLS is Enterprise-only, and Apigee is paid (60-day eval). `docs/apigee-mapping.md` maps Apigee concepts (proxies, products, quotas, spike arrest) to APISIX. |
| mTLS / PKI | Private CA. Compose: `scripts/pki.sh` (openssl/step). k8s: **cert-manager** | Apache-2.0 |
| Monitoring (Dynatrace substitute) | **OpenTelemetry** in every service, plus the **Grafana LGTM** stack: Prometheus (metrics), Tempo (traces, service map), Loki (logs), Grafana (dashboards), Alertmanager | Free and open source; the closest match to Dynatrace's full-stack view. Compose uses the single `grafana/otel-lgtm` container. k8s uses `kube-prometheus-stack`, Tempo and Loki Helm charts. Exporters cover Kafka (kafka-exporter), JVM/Actuator (Micrometer), APISIX, ES and ODM JMX. |
| Services | Java 21, Spring Boot 3, Maven multi-module | — |
| Packaging / delivery | **Helm** umbrella chart plus **ArgoCD** app-of-apps (OpenShift GitOps on OCP) | Apache-2.0 |
| MCP | (1) the official `DecisionsDev/ibm-odm-decision-mcp-server` (Apache-2.0, RES; supports mTLS). (2) Our own **`odm-dc-mcp-server`** (Python FastMCP), which wraps the Decision Center REST API plus our query API | No official Decision Center MCP exists |
| Platform | Docker Compose and kind locally; Helm values for OpenShift (Routes, restricted-v2 SCC, NetworkPolicies) | Same charts deploy to OKD/OCP or the Developer Sandbox |
| Brand | Fictitious "**Maple Northern Bank**" | No real-bank impersonation |

## AI-engineering layer (bank-grade)
**Principle:** ODM is the deterministic system of record. **An LLM never makes or overrides a credit decision.** Agents only explain, assist and draft, and a human approves any change.

### MCP servers
| Server | Use | Mode |
|---|---|---|
| `ibm-odm-decision-mcp-server` (official) | Run and explain decisions in RES | runtime, mTLS |
| `odm-dc-mcp-server` (ours) | Decision Center: projects, rules, tests, what-if simulation | **read-only by default**; deploy needs approval |
| `cc-decision-query-mcp` (part of ours) | Query decisions and stats through decision-query-api (not raw ES) | read-only, PII-masked |
| GitHub MCP (`github/github-mcp-server`) | Issues, PRs, reviews | dev |
| Kubernetes/OpenShift MCP (`containers/kubernetes-mcp-server`) | Inspect kind/OCP | dev, read-only flag |
| Grafana MCP (`grafana/mcp-grafana`) | Query metrics, traces and dashboards during incidents | dev/SRE |
| ArgoCD MCP (`argoproj-labs/mcp-for-argocd`) | App sync and health | dev, read-only |
| Context7 | Current library docs for Sonnet while coding | dev |

All MCP servers are checked into `.mcp.json`. Each third-party server gets its repo, licence and maintenance status re-verified in Phase 13 before we adopt it.

### Claude Code project setup (`.claude/` in repo)
- **`CLAUDE.md`:** architecture summary, conventions, how to run and test, rules of the road (no real PII, no secrets, Conventional Commits).
- **`settings.json` permissions:**
  - Deny reading `**/*.key`, `**/*.p12`, `.env*`, and `security/pki/out/**`.
  - Deny `git push --force`, `kubectl delete ns`, and deleting things in the ArgoCD `openshift` app.
  - Allow make, mvn, docker compose, and helm template.
- **Hooks:**
  - `PreToolUse`: block secret and key file access, destructive shell commands, and edits to `main` (must be on a branch). Run a gitleaks scan on staged content.
  - `PostToolUse` (on Edit/Write): auto-format (spotless, ruff), run yamllint/helm lint on chart edits, validate the schema on `contracts/` edits.
  - `Stop`: run fast unit tests for the touched modules and report failures.
- **Skills (`.claude/skills/`):**
  - `odm-rule-author`: how to write BAL rules and decision tables against our BOM, with reason-code conventions.
  - `add-kafka-flow`: topic + schema + producer + consumer with the retry/DLQ pattern + tests.
  - `deploy-ruleapp`: build, test, deploy, verify.
  - `kibana-dashboard` and `grafana-dashboard`.
  - `release`: release-please flow.
  - `incident-triage`: Grafana, Kafka lag, logs, runbook.
- **Subagents (`.claude/agents/`):** `rules-reviewer` (rule quality, overlaps, fairness), `security-reviewer` (mTLS, secrets, PII, OWASP API Top 10), `sre-reviewer` (resources, probes, alerts), `compliance-reviewer` (PIPEDA, OSFI E-23, FCAC wording).
- **Slash commands:** `/new-rule`, `/new-topic`, `/demo`, `/triage`.

### Runtime guardrails
- **Decision governance:** Decision Center release and change workflow with approvals. A RuleApp deploy needs a passing eval gate plus human approval (ArgoCD manual sync to the `openshift` env).
- **MCP security:**
  - Access: tools run with least privilege (separate RES monitor/execute roles), mTLS plus OIDC client credentials, and APISIX rate limits in front of the remote MCPs.
  - Data: tool outputs are PII-masked (Microsoft **Presidio**, MIT, masks SIN, name, address), and tool output is treated as data, not instructions (prompt-injection defence).
  - Audit: every MCP call is logged to `cc.audit.v1`, then ES.
- **Fairness and compliance:**
  - A CI lint fails if the BOM or rules reference prohibited grounds: gender, ethnicity, marital status, or age beyond the age-of-majority check.
  - A fairness report shows approval-rate parity by province and age band (Kibana and Grafana).
- **Explainability:** the ODM execution trace (fired rules) and reason codes are stored with each decision, and `GET /v1/decisions/{id}/explanation` returns them.
- **Data protection:** synthetic data only, masking at the indexer, Kafka ACLs per service (Strimzi KafkaUser), ES snapshots, ILM retention, and a STRIDE threat model doc.

### Evals (`evals/`)
- **Rule regression (CI gate):** a golden dataset of about 300 labelled applications covering edge cases (age 18 vs 19 by province, TDS boundaries, bankruptcy) runs against RES. Any diff fails the PR.
- **Champion/challenger what-if:** replay recent decisions from ES through a challenger ruleset, then report the change in approval rate, limit exposure and decline reasons before a release.
- **Agent/MCP evals (promptfoo, MIT):**
  - Correctness: "why was app X declined?" must call tools, cite real reason codes, and not hallucinate.
  - Safety: the agent must refuse to override or approve, must not leak PII, and must resist prompt-injection planted in applicant fields.
  - Runs nightly or on demand. Needs your own LLM API key (optional; it is the only cost).
- **Non-functional:** k6 load test with SLO thresholds (p95 decision under 300 ms excluding emulation), contract tests, and a chaos drill (kill the broker, check zero lost decisions).

### Use-case demo: underwriter copilot
For REFER cases, Claude (Desktop or Code, through the MCPs) gathers the case, explains which rules fired, does a what-if ("would income +10k approve?"), and drafts an underwriter note. **The human makes the decision** and records it via application-api, which writes to `cc.decisions.manual.v1`.

## Architecture
```
Clients/Partners --mTLS--> [APISIX gateway] --mTLS--> [application-api]  POST /v1/applications
                                   |                         | produce
                                   +--mTLS--> [decision-query-api] --> Elasticsearch (GET /v1/decisions/{id}, search, stats)
[txn-generator] ------------------------------------------> cc.applications.submitted.v1
                                                                   |
                                     [decision-orchestrator] --HTTPS/mTLS--> ODM RES (ccApproval ruleset)
                                                                   |              ^ RuleApp from Decision Center <- Rule Designer (git)
             cc.decisions.approved.v1 / declined.v1 / referred.v1  |
                    +--> [notification-svc] --> cc.notifications.v1
                    +--> [es-indexer] --> Elasticsearch --> Kibana (business dashboards)
 cc.audit.v1 from every service · retry and DLQ topics on every consumer · Kafka listeners mTLS (Strimzi KafkaUser TLS)
 OTel agent in every service --> OTel Collector --> Prometheus / Tempo / Loki --> Grafana (+ Alertmanager)
 [MCP #1 RES runtime]  [MCP #2 Decision Center + query API]  --> Claude / agents
```

## Credit rules (ODM)
- **Eligibility:** applicant meets the provincial age of majority (18 or 19), is a Canadian resident or holds a valid permit, and has no active bankruptcy.
- **Risk:** synthetic bureau scores (300–900), TDS ratio, income, and existing debt.
- **Outcome:** APPROVE, DECLINE or REFER, plus the credit limit, the product (No-fee, Cashback, Travel Infinite), the APR tier, and reason codes (for adverse-action notices).
- **Artifacts:**
  - Java XOM: `rules/xom` (a Maven module shared with the services).
  - Rule Designer project `rules/cc-approval-rules`: BOM with verbalization, action rules, decision tables, and a ruleflow.
  - Decision service `rules/cc-approval-ds`.
  - Test scenarios for Decision Runner.
- **Flow:** Rule Designer → git → synchronize to Decision Center (business users edit there) → deploy RuleApp `/ccApprovalApp/1.0/ccApproval/1.0` to RES. Deployment uses `scripts/deploy-ruleapp.sh` (RES REST API) or Decision Center deployment configs.
- **Compliance notes in the docs:** PIPEDA (privacy), OSFI E-23 (model risk), FCAC disclosure. Only synthetic data is used.

## Repo structure
```
cc-decisioning-platform/
├─ README.md LICENSE(Apache-2.0) NOTICE(ODM licence) CONTRIBUTING.md SECURITY.md CODE_OF_CONDUCT.md CHANGELOG.md
├─ .github/ workflows/(ci, helm, security, release) ISSUE_TEMPLATE/ PULL_REQUEST_TEMPLATE.md CODEOWNERS dependabot.yml
├─ docs/ architecture/(C4, mermaid) adr/ runbooks/ api/(OpenAPI) rules-catalog.md data-model.md apigee-mapping.md security-mtls.md
├─ contracts/                 JSON Schemas for every topic
├─ rules/ xom/ cc-approval-rules/ cc-approval-ds/ tests/ README(Rule Designer setup)
├─ services/ common-lib/ application-api/ txn-generator/ decision-orchestrator/ es-indexer/ decision-query-api/ notification-svc/
├─ gateway/apisix/            routes, upstreams, consumers, plugins (declarative yaml, standalone mode)
├─ mcp/ odm-res-mcp/ odm-dc-mcp-server/
├─ observability/ otel/ grafana/dashboards/ prometheus/rules/ kibana/(saved objects, index templates, ILM)
├─ security/pki/              CA and cert-generation scripts (no keys committed)
├─ deploy/ compose/(docker-compose.yml + profiles) helm/cc-platform/(subcharts, values-kind|openshift.yaml)
│         argocd/(root app, AppProjects, apps/) kind/cluster.yaml
├─ scripts/ bootstrap.sh pki.sh deploy-ruleapp.sh seed-kibana.sh load-test(k6)
├─ evals/ golden-dataset/ rule-regression/ what-if/ promptfoo/ k6/ chaos/
├─ .claude/ CLAUDE.md settings.json hooks/ skills/ agents/ commands/   .mcp.json
├─ Makefile  pom.xml
```

## Git and GitHub practices
- **Branching:** trunk-based with short-lived `feat/*`, `fix/*` and `docs/*` branches, PR into `main`. Branch protection requires a review and green CI, and only squash merges are allowed.
- **Commits:** Conventional Commits (commitlint). Versions and the CHANGELOG come from release-please, with semver tags.
- **Pre-commit hooks:** gitleaks, spotless, ruff, yamllint, markdownlint.
- **CI:**
  - Build and test: Maven build, Testcontainers tests (Kafka, ES), contract validation, OpenAPI lint (spectral).
  - Deployment checks: `helm lint`, and kubeconform for both the kind and OpenShift output.
  - Security: Trivy, CodeQL, and an SBOM (syft).
- **Images:** pushed to GHCR, multi-arch. ODM stays amd64 only.
- **Secrets and keys:** none committed. `.env.example` holds placeholders, and k8s uses Sealed Secrets. Certificates are generated locally or by cert-manager.

## Memory budget (8 GB Mac; Docker Desktop about 6.5 GB with Rosetta) — run by profile
| Profile | Contents | ~RAM |
|---|---|---|
| `core` | Kafka, ODM, orchestrator, generator, indexer, ES | 4.5 GB |
| `analytics` | + Kibana, query-api | +1.0 GB |
| `edge` | + APISIX (standalone, no etcd), application-api | +0.5 GB |
| `obs` | + otel-lgtm | +1.0 GB |
| `mcp` | 2 MCP servers | +0.2 GB |

- Never run everything at once. Run `core` plus one extra profile.
- kind with ArgoCD runs a reduced set, with ODM reached outside the cluster in Compose.

## Phases (one PR each; Sonnet gets one caveman prompt per phase)
0. **Scaffold:** git init, skeleton, licence files, Maven parent, Makefile, pre-commit, GitHub templates, CI skeleton, ADRs, plus **`.claude/` (CLAUDE.md, settings, guardrail hooks)** so every later Sonnet phase runs under the hooks. **You create the empty GitHub repo `cc-decisioning-platform` (no README) before this phase.**
1. **Contracts and domain:** JSON Schemas, `common-lib`, Java XOM.
2. **PKI and local infrastructure:** `pki.sh` (CA plus server and client certs), Compose with Kafka (mTLS listener), kafka-ui, ODM (HTTPS), ES and Kibana, plus health checks and topic init.
3. **ODM rules:** Rule Designer setup guide, BOM, rules, decision tables, ruleflow, scenarios, Decision Center sync, `deploy-ruleapp.sh`. ⚠ Rule Designer is a GUI. Sonnet writes the XOM, the rule text and the scenario files, and you open the project in Rule Designer to build and export the RuleApp.
4. **Event services:** txn-generator (Datafaker, Canadian synthetic data, configurable TPS), decision-orchestrator, notification-svc, es-indexer. Unit and Testcontainers tests.
5. **APIs:** application-api and decision-query-api (search by id, applicant, province, date; stats endpoints; pagination), OpenAPI specs.
6. **Gateway and mTLS:** APISIX routes, client-cert consumers, rate limits, API keys, Apigee mapping doc, mTLS on every hop (client→gateway→service, service→Kafka/ES/ODM).
7. **Kibana:** index templates, ILM, dashboard ndjson (approval rate, decline reasons, decisions by province, score distribution, limits, throughput, DLQ).
8. **Observability:** OTel agent, Collector, Prometheus scrape configs, Grafana dashboards (Kafka lag, JVM, API latency SLOs, APISIX, ODM), Tempo service map, Loki logs, alert rules.
9. **MCP:** container for the official RES MCP (mTLS to RES) plus `odm-dc-mcp-server`. Its tools: list projects and branches, get rules, run tests, deploy a RuleApp, query decisions and stats. Claude config sample.
10. **Helm and OpenShift:** umbrella chart, Strimzi, ECK and cert-manager CRs, kind values (Ingress) and OpenShift values (Routes, SCC, NetworkPolicies).
11. **ArgoCD GitOps:** ArgoCD in kind, then the app-of-apps pointing at `main` (sync waves: operators → infra → platform → apps, with auto-sync and self-heal). OpenShift GitOps documented.
12. **Guardrails and explainability:** Presidio masking, the prohibited-grounds lint, the explanation endpoint, the MCP audit trail, the fairness dashboards, and the threat model.
13. **AI tooling:** `.mcp.json` with the vetted third-party MCPs, the skills, subagents and slash commands, and the underwriter-copilot demo.
14. **Evals:** golden dataset with the rule-regression CI gate, what-if replay, promptfoo agent and red-team suite, and the k6 and chaos drills.
15. **Hardening and release:** C4 diagrams, runbooks, demo script, scan fixes, v1.0.0.

## Verification
- **Bring-up:** `make pki && make up PROFILE=core,analytics` brings all containers to healthy. RES answers on `:9443/res`, Decision Center on `/decisioncenter`, Kibana on `:5601`, kafka-ui on `:8080`.
- **End-to-end:** `make demo` sends 1,000 applications, which produce events on the three outcome topics, matching ES counts, and a populated Kibana dashboard. A poison message lands in the DLQ.
- **mTLS:** `curl --cert client.crt --key client.key https://gateway/v1/decisions/stats` returns 200. Without a cert the gateway rejects the TLS handshake. An unauthorized Kafka client is rejected.
- **Observability:** Grafana shows one trace spanning gateway → api → Kafka → orchestrator → ODM → indexer, Kafka lag panels, and a firing test alert.
- **Tests and CI:** `mvn verify` passes, PR CI is green, and the `helm template` output passes kubeconform with both values files.
- **MCP:** the MCP Inspector lists the tools for both servers, and Claude calls `ccApproval` and gets a decision back.
- **kind / ArgoCD:** `make kind-up` leaves every ArgoCD Application Synced and Healthy, and a git change triggers an auto-sync.
- **Guardrails:**
  - Hooks: a test edit touching `*.key` is blocked by the hook, and adding a `gender` attribute to the BOM fails the CI lint.
  - Masking: MCP responses show a masked SIN.
- **Evals:** `make eval-rules` shows 0 diffs, and a deliberately broken rule fails the gate. `make eval-agent` passes the promptfoo correctness and red-team suites. `make what-if` produces the impact report.

---
# SONNET RUNBOOK (prompts in caveman)

## How to use it
1. Install: `brew install gh uv openjdk@21 maven pre-commit` then `gh auth login`. In Docker Desktop: 6.5 GB RAM, Rosetta on.
2. On GitHub, create an **empty** repo `cc-decisioning-platform` (no README, no licence). Put the URL in place of `<REPO_URL>` and your username in place of `<GH_USER>` in P0.
3. For each phase:
   - Open a terminal: `cd ~/Documents/GitHub/ODM_kafka_OCP && claude --model sonnet`.
   - Paste the prompt. It starts with `/caveman`.
   - When it finishes, review the PR on GitHub, squash-merge it, then type `/clear` and paste the next prompt.
4. When Sonnet stops with `NEED USER:`, do the manual step it asks for, then type `continue`.

Shared rules live in `.claude/CLAUDE.md` (created in P0), which keeps every later prompt short.

---
### P0 — scaffold + guardrails
```
/caveman
Phase 0 scaffold. Repo = cwd. Plan = ~/.claude/plans/i-want-to-create-expressive-sutton.md. Read whole plan first.
Do:
1. git init -b main. Copy plan -> docs/PLAN.md.
2. Dirs per PLAN "Repo structure". .gitkeep in empty.
3. Root files: README (overview, mermaid arch, quickstart, profiles table, ODM licence warning), LICENSE Apache-2.0, NOTICE (ODM for Developers = not OSS, dev/eval only, pulled at runtime, never redistributed), CONTRIBUTING (branch/commit/PR flow), SECURITY, CODE_OF_CONDUCT, CHANGELOG, .gitignore (java, python, node, certs, .env), .editorconfig, .env.example (placeholders only).
4. pom.xml parent: Java 21, Spring Boot 3 latest stable, spotless (google-java-format), jacoco, enforcer. Modules list empty.
5. Makefile w/ help + stub targets: up down logs verify test pki demo kind-up ocp-render eval-rules eval-agent what-if. `make up PROFILE=core,analytics` style.
6. .pre-commit-config.yaml: gitleaks, ruff, yamllint, markdownlint, commitlint (conventional). Add commitlint config.
7. .github/: PULL_REQUEST_TEMPLATE (what/why/test/risk/checklist), ISSUE_TEMPLATE bug/feature/rule-change, CODEOWNERS `* @<GH_USER>`, dependabot (maven, pip, docker, github-actions weekly), workflows: ci.yml (mvn verify, skip if no modules), security.yml (gitleaks, trivy fs, codeql java+python), release.yml (release-please). Pin action versions by SHA or major.
8. docs/adr/ MADR format: 0001-odm-dev-licence 0002-kafka-kraft-strimzi 0003-custom-es-indexer 0004-apisix-instead-of-apigee 0005-lgtm-instead-of-dynatrace 0006-mcp-strategy 0007-llm-never-decides 0008-mtls-everywhere.
9. .claude/: CLAUDE.md, settings.json, hooks/:
   - pre_tool_guard.sh (PreToolUse Bash|Read|Edit|Write): block *.key *.p12 *.pem-private .env* security/pki/out/**, git push --force, rm -rf /, kubectl delete ns, git commit on main after P0. exit 2 + reason.
   - post_edit_fmt.sh (PostToolUse Edit|Write): java->spotless apply file module, py->ruff format, yaml->yamllint, contracts/*.json->schema validate.
   - stop_tests.sh (Stop): run unit tests of changed modules, quick only.
   Wire all in settings.json. Permissions deny per PLAN. Test each hook w/ fake JSON stdin, show output.
CLAUDE.md content (keep tight):
- talk caveman. read docs/PLAN.md section for current phase only.
- bank = "Maple Northern Bank" fictitious. synthetic data only. no real PII. no secrets in git.
- LLM never decides/overrides credit. ODM = system of record.
- Java 21 + Spring Boot 3 + Maven module under services/. Python 3.13 + uv. 
- every service: multi-stage Dockerfile, non-root, arbitrary UID ok (OCP), /actuator/health liveness+readiness, Micrometer prometheus, OTel-ready, mem limit in compose, JSON logs w/ correlationId.
- every Kafka consumer: manual commit, idempotent producer, retry topic + DLQ, Testcontainers test.
- mTLS where PLAN says. certs from scripts/pki.sh only.
- PR flow: `git switch -c <type>/pN-<slug>` -> small conventional commits -> `make verify` green -> push -> `gh pr create` w/ template filled -> STOP. no merge.
- done = show verify cmd output. no claim w/o proof.
- blocked (GUI, licence, tool missing, RAM) -> print `NEED USER: <what>` and stop.
Git: commit "chore: initial scaffold" on main. `git remote add origin <REPO_URL>`, push. Then gh api branch protection main: require PR, require status check ci, 0 approvals (solo), linear history, block force push, no delete. Enable auto-delete branches, squash only.
Done when: tree printed, hook tests pass, `pre-commit run -a` pass, GitHub Actions green.
```

### P1 — contracts + domain
```
/caveman
Phase 1. Read .claude/CLAUDE.md + PLAN "Credit rules" + "Architecture".
Branch feat/p1-contracts-domain.
1. contracts/: JSON Schema 2020-12 for every topic in PLAN (submitted, approved, declined, referred, manual, notifications, audit, dlq envelope). Common envelope: eventId, correlationId, eventType, schemaVersion, occurredAt, producer, payload. Example JSON per schema. CI step validates examples (ajv-cli or python jsonschema).
2. rules/xom: Maven module, plain Java POJOs (Applicant, Address w/ province enum, Employment, CreditBureauReport, Application, Decision{outcome, creditLimit, product, aprTier, reasonCodes[], firedRules[]}, ReasonCode enum). No prohibited fields (gender, ethnicity, marital). Javadoc for BOM verbalization.
3. services/common-lib: DTOs mapped to schemas, Kafka config (idempotent producer, error handler -> retry/DLQ), correlationId MDC filter, JSON logging config, topic name constants.
4. Unit tests: schema<->DTO round-trip for each example.
5. docs/data-model.md (mermaid class diagram) + docs/kafka-topics.md (topic, key, partitions, retention, producer, consumer).
Verify: mvn -q verify. PR flow.
```

### P2 — PKI + local infra
```
/caveman
Phase 2. Read CLAUDE.md + PLAN "Tech decisions", "Memory budget".
Branch feat/p2-pki-infra.
1. scripts/pki.sh: root CA + server certs (kafka, es, kibana, odm, apisix, each service) + client certs (each service, demo-partner, mcp). SANs for localhost + compose names. Output security/pki/out/ (gitignored). Also PKCS12/JKS for Java. Idempotent. `make pki`.
2. deploy/compose/docker-compose.yml w/ profiles:
   - core: kafka (apache/kafka KRaft single node, listeners: PLAINTEXT internal-dev off by default, SSL mTLS 9093 required client auth), topic-init job (create all topics from docs/kafka-topics.md), odm (icr.io/cpopen/odm-k8s/odm — verify latest 9.5.x tag, LICENSE=accept, platform linux/amd64, ports 9060/9443), elasticsearch (8.x single node, security on, TLS, heap 512m)
   - analytics: kibana (TLS to ES), kafka-ui (kafbat, mTLS to kafka)
   - mem_limit every container, healthchecks, depends_on healthy.
3. Makefile up/down/logs/ps wired. scripts/bootstrap.sh checks docker mem >= 6GB, rosetta, tools.
4. docs/runbooks/local-setup.md (urls, default creds from .env, troubleshooting M2/emulation).
Verify: make pki && make up PROFILE=core,analytics -> all healthy (`docker compose ps`), curl ODM RES console 200, ES health green/yellow over TLS, kafka topic list over mTLS (kcat or kafka-topics w/ client cert), client w/o cert rejected. Show outputs. PR flow.
If RAM/emulation fails -> NEED USER.
```

### P3 — ODM rules (Rule Designer)
```
/caveman
Phase 3 ODM rules. Read CLAUDE.md + PLAN "Credit rules". Use rules/xom from P1.
Branch feat/p3-odm-rules.
1. rules/README.md: step-by-step Rule Designer install (free ODM for Developers Eclipse, version match RES 9.5.x), import projects, link Decision Center (sync), build RuleApp, export jar to rules/ruleapps/.
2. Create Rule Designer projects as files: rules/cc-approval-rules (BOM from XOM, verbalization, packages eligibility/risk/limit/product/reasons, ruleflow eligibility->risk->limit->product, decision tables for score-band x TDS -> outcome, limit by income band x score), rules/cc-approval-ds decision service + ruleset params (in: application, out: decision), agent.enabled=true, agent.description for MCP.
   Write BAL rules as text in .brl + human spec docs/rules-catalog.md (rule id, intent, condition, action, reason code). Province age of majority: 19 = BC, NB, NL, NS, NT, NU, YT; 18 others.
3. rules/tests/: Decision Runner/Excel-less scenario JSON (30 cases) + script scripts/test-ruleset.sh calling RES REST execution API w/ JSON, diff expected.
4. scripts/deploy-ruleapp.sh: deploy jar to RES via RES REST (/res/apiauth/v1/...), verify ruleset listed. `make deploy-rules`.
5. Lint script scripts/lint-prohibited-grounds.sh: fail if rules/BOM mention gender|ethnic|race|marital|religion. Add to CI.
Then print: NEED USER: open Rule Designer, import rules/*, fix any BOM/BAL errors, build + export RuleApp to rules/ruleapps/ccApprovalApp.jar, sync to Decision Center. Wait.
After user says continue: make deploy-rules + scripts/test-ruleset.sh all pass. Show. PR flow.
```

### P4 — event services
```
/caveman
Phase 4. Read CLAUDE.md + PLAN "Architecture". Use common-lib.
Branch feat/p4-event-services.
Services (each own module, Dockerfile, compose service in profile core, tests):
1. txn-generator: Datafaker en-CA. Realistic Canadian applicants (provinces weighted by pop, postal code format, income dist, score 300-900, TDS, bankruptcy 2%). Edge-case mix 10%. Config TPS + total + poison-rate. Produce cc.applications.submitted.v1 key=applicationId. REST /admin/start /admin/stop.
2. decision-orchestrator: consume submitted -> call ODM RES REST (mTLS, retry w/ backoff, circuit breaker resilience4j, timeout) -> map to Decision incl firedRules (enable ruleset trace) -> route to approved/declined/referred topic. Audit event. Failures -> retry topic -> DLQ. Exactly-once-ish: idempotent producer + transactional consume-produce.
3. notification-svc: consume decision topics -> build applicant notice (approval or FCAC-style adverse action w/ reason text) -> cc.notifications.v1 (no real email).
4. es-indexer: consume decisions + audit + notifications -> bulk index ES (mTLS/TLS), mask PII (SIN last 3, name initials, postal FSA only) before index, index per type w/ date suffix alias. DLQ on mapping errors.
Testcontainers (Kafka + ES + WireMock for RES) integration tests each. `make demo` = generator 1000 apps.
Verify: mvn verify green; make up PROFILE=core; make demo; show counts per topic (kafka-ui or cli) + ES _count matches; poison msg in DLQ. PR flow.
```

### P5 — APIs
```
/caveman
Phase 5. Branch feat/p5-apis. Read CLAUDE.md.
1. application-api: POST /v1/applications (validate vs schema, 202 + applicationId, produce submitted), GET /v1/applications/{id}/status, POST /v1/applications/{id}/manual-decision (underwriter only, role claim, produce cc.decisions.manual.v1 + audit). Idempotency-Key header.
2. decision-query-api: ES-backed. GET /v1/decisions/{id}, /v1/decisions/{id}/explanation (fired rules + reason text), /v1/decisions?province&outcome&from&to&minScore&page&size (search_after paging), /v1/stats/approval-rate?groupBy=province|product|day, /v1/stats/decline-reasons, /v1/stats/fairness?by=province|ageBand. Read-only ES user. Responses PII-masked.
3. springdoc OpenAPI -> docs/api/*.yaml committed. spectral lint in CI.
4. RFC 7807 problem+json errors. Validation. Tests (MockMvc + Testcontainers ES).
Compose profile analytics (query-api), edge (application-api).
Verify: mvn verify; curl examples in docs/api/README.md run + outputs shown. PR flow.
```

### P6 — gateway + mTLS everywhere
```
/caveman
Phase 6. Branch feat/p6-gateway-mtls. Read CLAUDE.md + PLAN guardrails.
1. gateway/apisix/: APISIX standalone (apisix.yaml declarative, no etcd). Routes /v1/applications*, /v1/decisions*, /v1/stats*, /mcp/*. SSL w/ client CA verify (mTLS required). Consumers per client cert CN (demo-partner, underwriter-portal, mcp-client) + key-auth or jwt. Plugins: limit-count, limit-req (spike arrest), cors, request-id -> X-Correlation-Id, prometheus, opentelemetry, response-rewrite strip server headers. Upstream mTLS to services.
2. Services: Spring SSL bundles, server.ssl.client-auth=need behind gateway; services->Kafka/ES/ODM already mTLS — audit all, fix gaps.
3. docs/apigee-mapping.md: Apigee proxy/target/product/developer app/quota/spike arrest/verify API key/OAuth -> APISIX equivalent table. docs/security-mtls.md: cert chain diagram, rotation, every hop.
4. Compose profile edge.
Verify: curl w/ demo-partner cert -> 202/200; no cert -> TLS fail; wrong CA -> fail; over rate -> 429. Show. PR flow.
```

### P7 — Kibana
```
/caveman
Phase 7. Branch feat/p7-kibana. Read CLAUDE.md.
1. observability/kibana/: index templates + mappings (keyword vs text, dates, geo by province centroid), ILM (hot 7d -> delete 90d demo), aliases.
2. Saved objects ndjson: data views + dashboard "Credit Card Decisioning — Maple Northern Bank": KPIs (apps today, approval %, referral %, avg limit), approval rate over time, decisions by province map/bar, decline reason top-N, score distribution histogram, limit by product, TDS vs outcome heatmap, latency p95 (orchestrator timing field), DLQ count, fairness approval parity by ageBand/province, recent decisions table. Controls: province, product, outcome, date.
3. scripts/seed-kibana.sh: import via Kibana saved objects API (TLS), idempotent. `make seed-kibana`.
Verify: make demo then seed; screenshot instructions + curl _find shows dashboard. PR flow.
```

### P8 — observability (Dynatrace substitute)
```
/caveman
Phase 8. Branch feat/p8-observability. Read CLAUDE.md + PLAN "Tech decisions" monitoring row.
1. OTel Java agent in every service image (env OTEL_EXPORTER_OTLP_ENDPOINT, service.name, resource attrs). Trace context propagated over Kafka headers.
2. Compose profile obs: grafana/otel-lgtm (Grafana+Prometheus+Tempo+Loki+collector), kafka-exporter, elasticsearch-exporter, ODM JMX exporter if feasible (else NEED USER note). Prometheus scrape actuator + APISIX.
3. observability/grafana/dashboards (provisioned json): Platform overview (golden signals per service), Kafka (lag per group, throughput, DLQ rate), JVM, API SLO (p95/p99, error budget), APISIX, ODM decision latency, Service map (Tempo).
4. observability/prometheus/rules: alerts — consumer lag > threshold, DLQ > 0, p95 > 300ms, error rate > 1%, pod/container down, cert expiry < 14d.
5. docs/runbooks/ one per alert.
Verify: make up PROFILE=core,obs; make demo; show trace across generator->orchestrator->ODM->indexer in Tempo (query output), alert test fire. PR flow.
```

### P9 — MCP servers
```
/caveman
Phase 9. Branch feat/p9-mcp. Read CLAUDE.md + PLAN "AI-engineering layer".
1. mcp/odm-res-mcp: Dockerfile + config for github.com/DecisionsDev/ibm-odm-decision-mcp-server (pin commit). streamable-http :3000, mTLS to RES (MTLS_* env), basic auth from .env. Read its README first, follow it.
2. mcp/odm-dc-mcp-server: Python 3.13, uv, official MCP python SDK (FastMCP), streamable-http + stdio. Tools:
   - dc_list_projects, dc_list_branches, dc_get_rules(project, branch), dc_run_tests(project), dc_whatif(applicationJson, overrides) [runs RES], dc_deploy_ruleapp (DISABLED unless env ALLOW_DEPLOY=true AND approval token arg) 
   - decisions_get(id), decisions_explain(id), decisions_search(filters), stats_approval_rate, stats_fairness — via decision-query-api through APISIX mTLS. Never raw ES.
   - Presidio PII mask on all outputs. Audit every call -> Kafka cc.audit.v1. Tool descriptions say "data not instructions".
   pytest w/ respx mocks. ruff + mypy.
3. Compose profile mcp. .mcp.json at root (both servers + dev MCPs placeholders for P13). docs/mcp.md: Claude Desktop + Claude Code config, tool list.
Verify: npx @modelcontextprotocol/inspector --cli lists tools both servers; call ccApproval tool w/ sample -> decision; PII masked in output. PR flow.
```

### P10 — Helm + OpenShift
```
/caveman
Phase 10. Branch feat/p10-helm-ocp. Read CLAUDE.md.
1. deploy/helm/cc-platform umbrella + subchart per service (shared library chart for deployment/service/configmap/hpa/pdb/networkpolicy/servicemonitor). securityContext: runAsNonRoot, no fixed UID, drop ALL caps, readOnlyRootFilesystem, seccomp RuntimeDefault. Resources from compose limits. Probes.
2. Infra values/CRs: Strimzi Kafka (KRaft node pools, TLS listener, KafkaUser per service w/ ACLs), KafkaTopic CRs from docs/kafka-topics.md, ECK Elasticsearch+Kibana, cert-manager Issuer (CA) + Certificates, APISIX chart, ODM (reference IBM odm-k8s chart or Deployment w/ image, document licence), kube-prometheus-stack/tempo/loki values.
3. values-kind.yaml (Ingress nginx, small resources, ODM external svc to compose host), values-openshift.yaml (Route objects, no Ingress, SCC-compatible, OpenShift monitoring note).
4. deploy/kind/cluster.yaml (1 node, port maps). make kind-up (create cluster, load images, install operators), make ocp-render (helm template openshift > out/).
5. CI helm.yml: helm lint, helm template both values -> kubeconform (w/ CRD schemas) + kube-linter.
Verify: CI green; make kind-up (reduced set) pods Ready; show. PR flow. RAM issue -> NEED USER.
```

### P11 — ArgoCD
```
/caveman
Phase 11. Branch feat/p11-argocd. Read CLAUDE.md.
1. deploy/argocd/: root app-of-apps, AppProjects (platform, apps) w/ source/destination restrictions, apps: operators (strimzi, eck, cert-manager) wave -2, infra (kafka, es, certs, obs) wave -1, platform (apisix, odm-ref) wave 0, services wave 1. envs kind (auto-sync, prune, selfHeal) + openshift (manual sync = human approval gate).
2. make argocd-up: install ArgoCD in kind (minimal, no dex), apply root app pointing <REPO_URL> main.
3. docs/runbooks/gitops.md + OpenShift GitOps operator steps.
Verify: argocd app list all Synced/Healthy (show), change replica in git -> auto-sync shown. PR flow.
```

### P12 — guardrails + explainability
```
/caveman
Phase 12. Branch feat/p12-guardrails. Read CLAUDE.md + PLAN "Runtime guardrails".
Audit + finish: Presidio masking everywhere outward (query-api, MCP, indexer), prohibited-grounds lint in CI, explanation endpoint complete, MCP audit trail -> ES index + Kibana panel, fairness dashboard (Kibana + Grafana), Kafka ACL least-privilege review, ES roles least-privilege, docs/security/threat-model.md (STRIDE per component + mitigations), docs/compliance.md (PIPEDA, OSFI E-23 model risk: inventory, validation, monitoring; FCAC adverse action). 
Tests: add gender to BOM -> lint fails (test script, revert). MCP response SIN masked.
Verify + PR flow.
```

### P13 — AI tooling (MCPs, skills, agents)
```
/caveman
Phase 13. Branch feat/p13-ai-tooling. Read CLAUDE.md + PLAN "AI-engineering layer".
1. Verify each 3rd-party MCP (github/github-mcp-server, containers/kubernetes-mcp-server, grafana/mcp-grafana, argoproj-labs/mcp-for-argocd, context7): repo exists, licence OSS, last commit < 6mo, read-only option. Table in docs/mcp.md. Drop any failing, say why. Add passing to .mcp.json w/ read-only flags, secrets via env.
2. .claude/skills/: odm-rule-author, add-kafka-flow, deploy-ruleapp, kibana-dashboard, grafana-dashboard, release, incident-triage. Each SKILL.md w/ frontmatter name+description (trigger words), steps, repo paths, checklists. Short.
3. .claude/agents/: rules-reviewer, security-reviewer, sre-reviewer, compliance-reviewer (frontmatter: name, description, tools limited read-only).
4. .claude/commands/: new-rule, new-topic, demo, triage.
5. docs/underwriter-copilot.md: demo script — REFER case, Claude uses MCPs to explain + what-if + draft note; human records decision via application-api. Make clear LLM never decides.
Verify: each skill/agent loads (list), run /demo dry. PR flow.
```

### P14 — evals
```
/caveman
Phase 14. Branch feat/p14-evals. Read CLAUDE.md + PLAN "Evals".
1. evals/golden-dataset: 300 labelled apps JSON (gen script seeded + hand edge cases: age 18/19 per province boundary, TDS boundaries, score band edges, bankruptcy, non-resident). Expected outcome + reasonCodes from docs/rules-catalog.md (derive independently, not by calling RES).
2. evals/rule-regression: runner vs RES, diff report (md + junit). `make eval-rules`. CI job (self-hosted note: needs ODM; in GH Actions run ODM container amd64 service) — gate on rules/** changes.
3. evals/what-if: replay last N decisions from ES through challenger ruleset version -> impact report (approval Δ, exposure Δ, reason mix Δ, fairness Δ). `make what-if CHALLENGER=1.1`.
4. evals/promptfoo: agent suite via MCP: correctness (explain decline cites real reasons), refusal (approve/override request -> refuse), PII leak tests, prompt-injection in applicant name/employer fields, tool-use required. `make eval-agent` needs ANTHROPIC_API_KEY (optional, skip if unset).
5. evals/k6: load profile, thresholds p95<300ms error<1%. evals/chaos: stop kafka broker mid-demo, assert zero lost decisions (count in vs out).
Verify: eval-rules 0 diff; break one rule -> fails; others run outputs shown. PR flow.
```

### P15 — hardening + release
```
/caveman
Phase 15. Branch chore/p15-release. Read CLAUDE.md.
1. docs/architecture: C4 context/container/component (mermaid), sequence diagram one application end-to-end, deployment views (compose, kind, OCP).
2. README final: badges (CI, licence, release), 5-min quickstart, demo GIF placeholder, profile table, links all docs.
3. docs/demo-script.md: 15-min walkthrough (generator -> kafka-ui -> Kibana -> Grafana trace -> query API mTLS -> MCP copilot -> rule change in DC -> eval gate -> ArgoCD).
4. Fix all trivy HIGH/CRITICAL, codeql alerts, dependabot PRs.
5. Full clean run: make down -v; make pki up demo seed-kibana eval-rules. Show outputs.
6. PR flow. After user merges: release-please PR -> v1.0.0.
```

## Risks
- ODM is amd64 only, so it is slow under emulation on M2. Fallback: run it on a free cloud VM or the Developer Sandbox.
- 8 GB of RAM is tight, so profiles are mandatory. A full stack plus kind won't fit.
- Rule Designer and Decision Center steps involve the GUI, so expect one manual step in phase 3.
- APISIX is a stand-in for Apigee. Concepts map over, but Apigee-specific policies (e.g. JavaScript callouts) don't port directly.
