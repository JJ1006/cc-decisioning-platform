# Demo script

Two versions: **15 minutes** (the full story) and **5 minutes** (the short cut, at the end). Synthetic data, fictitious bank. Say it once at the start: *"Maple Northern Bank does not exist; every applicant is generated."*

The one sentence to land: **ODM decides, Kafka carries, Elasticsearch remembers, and the AI only explains. It cannot decide.**

## Before you start (5 minutes, not part of the demo)

RAM decides what you can show at the same time (Docker Desktop VM 7 GB). Plan two stack changes; each takes about two minutes.

| Phase | Stack | Command |
|---|---|---|
| A. minutes 0-9 | core + analytics + edge | `make pki && make up PROFILE=core,analytics,edge && make demo` |
| B. minutes 9-12 | swap Kibana for Grafana | `make down && make up PROFILE=core,edge,obs` (then `make demo` again, 200 is enough: `scripts/demo.sh 200 20 0.01`) |
| C. minutes 12-15 | MCP + rule change | `make down && make up PROFILE=core,analytics,edge,mcp` |

Ahead of time (the day before): run it once, warm the Docker image cache, open the browser tabs, check certificate days (`make cert-expiry`). Keep one terminal at the repository root and the `claude` CLI open in the same directory with `/mcp` already approved. Do not print passwords: they live in a gitignored env file; the browser asks for them (`analyst` / `ops`).

---

## 15-minute walkthrough

### 1. Generate (0:00 - 1:30)

```bash
make demo
```

**Output to expect:** a block of `PASS` lines (topic counts equal index counts, dead letters about 1% on purpose, parity checks) and `1000` applications.
**Say:** "A generator sends a thousand synthetic applications. About one percent are poisoned on purpose, to show what a dead letter looks like. Nothing here is a real person."

### 2. Kafka (1:30 - 3:00)

Open kafka-ui at <http://127.0.0.1:8080> (loopback only; it is a dev tool).

**Show:** topic `cc.applications.submitted.v1` (3 partitions, key = applicationId), `cc.decisions.approved / declined / referred.v1`, a `.dlq` topic with a few messages.
**Say:** "Every step is a topic. Producers are idempotent, consumers commit by hand after the work, a failure goes to a retry topic and then to a dead letter topic. The services talk to Kafka over mutual TLS and the broker has ACLs: default deny."

### 3. Kibana: executive and fairness (3:00 - 6:00)

Open <https://localhost:5601/s/maple-northern/app/dashboards> as `analyst`. Open **Credit Decisioning - Executive**, then **Risk & Fairness**.

**Show:** approval rate and trend, decisions by outcome, the reason-code mix, the **disparate impact ratio per province and age band with group size**, "ODM vs MANUAL decisions".
**Say:** "This is the business view. Province and age are monitored here but they are never inputs to a rule; a lint fails the build if a rule touches a prohibited ground. The ratio is a signal, not a verdict: small groups are flagged. The analyst role cannot see the logs or the exact applicant inputs; try to open `cc-logs` and you get a permission error, and that is the access control working."

*If asked about personal data:* "Logstash masks it before Elasticsearch. The exact inputs live in a separate restricted index that even the Kibana users cannot read."

### 4. API through the gateway and mTLS (6:00 - 8:30)

```bash
make api-call CLIENT=analyst P=/v1/stats/latency
curl -sS --cacert security/pki/out/ca/ca.crt https://localhost:8443/v1/stats/latency
```

**Expect:** the first prints JSON statistics. The second, with no client certificate, **fails in the TLS handshake** (curl error 56 or 35, "certificate required"): no HTTP status, because the connection never gets that far.
**Say:** "One door: the gateway on 8443. The certificate is the identity. The common name becomes a consumer, the consumer maps to a role, the role decides which routes work. A request without a certificate is refused before any HTTP. Behind the gateway every hop is mutual TLS again, and only the gateway's certificate may assert who the caller is." Optional proof for the sceptic: `make verify-mtls` (about a minute).

### 5. Swap to Grafana: trace and SLO (8:30 - 11:00)

```bash
make down && make up PROFILE=core,edge,obs && scripts/demo.sh 200 20 0.01
```

Open <https://localhost:3000>. Show **Platform overview**, then **API SLO** (error budget, p95 against the 300 ms target), then **Service graph**, then a **trace waterfall** (gateway to application-api to Kafka to orchestrator to ODM).
**Say:** "Metrics, traces and logs share one correlation id. The waterfall shows where the time goes: almost all of it is the ODM call. If it were slow, an alert with a runbook would fire; every alert has one."

*Fallback:* if the swap is too slow, show the PNGs in `docs/images/` (`grafana-*.png`) and say they were captured from this stack.

### 6. The AI copilot, and what it cannot do (11:00 - 13:00)

```bash
make down && make up PROFILE=core,analytics,edge,mcp && make demo
scripts/api-call.sh analyst "/v1/decisions?outcome=REFER&size=3"
```

In Claude Code (`/mcp` shows `odm-dc` connected): *"Explain why `<a REFER application id>` was referred, show its inputs, and tell me what happens if the monthly debt were 300."*

**Expect:** `decision_explain` (rule `RISK-DT-01`, reason `R_BORDERLINE_TDS`), `decision_inputs` (no identifiers), `whatif` labelled **SIMULATION - not a decision**.
Then ask: *"Approve it."* **Expect a refusal**: there is no tool that approves.
Then, as the human, with the underwriter's certificate:

```bash
scripts/api-call.sh underwriter-portal -X POST -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  --data '{"outcome":"APPROVE","creditLimit":3000,"product":"NO_FEE","reason":"verified debt by statement","underwriterId":"UW-77"}' \
  /v1/applications/<id>/manual-decision
```

**Say:** "The model reads, explains and simulates. The decision is ODM's, or, for a referral, a named human's. Every tool call is an audit event with the tool and the caller but never the argument values, and if the audit trail is down the tool returns nothing: it fails closed." Full flow: [underwriter-copilot](underwriter-copilot.md).

### 7. A rule change and the gate (13:00 - 14:30)

Safe, scripted version: `make eval-rules-mutation` flips a threshold in a temp copy, deploys it, shows the gate fail, restores the real rules and shows it pass again. Live version: lower the minimum income of `RISK-01` from 15 000 to 14 000 in `rules/cc-approval-rules/` (do not commit it).

```bash
make deploy-rules        # Decision Center: import, build with 0 errors, deploy to RES
make eval-rules          # 300 golden cases against the oracle
```

**Expect:** `eval-rules` **FAIL 297/300** and names the boundary cases (G089-G091, income 14 999). Revert (`git checkout rules/`), `make deploy-rules`, `make eval-rules` **PASS 300/300**.
**Say:** "The expected answers come from an independent oracle written from the specification, not from ODM. Any difference fails, in CI too: `rules-gate` is a required check on `main`. The first time we ran it, it found a real null-pointer in five rules." Then `make what-if CHALLENGER=1.1 N=500`: "before shipping, replay the last decisions through a challenger: approval +2.4 points, 12 referrals flip, fairness ratios move by at most 0.06." ([evals](evals.md))

### 8. GitOps (14:30 - 15:00)

- **kind:** `make down && make argocd-up ENV=kind-lite` then `make argocd-status`; show every Application Synced / Healthy and say: "Git is the only way anything reaches a cluster; main syncs automatically."
- **OpenShift:** show `deploy/argocd/apps/openshift` and say: "The same charts; a release tag is promoted by a pull request; the production sync is a **manual gate**. It is rendered and policy-checked but not yet synced on a real cluster. That is in [limitations](limitations.md)."

---

## 5-minute version

| Time | Show | Say (one line) |
|---|---|---|
| 0:00 | `make demo` output (already run) | "Thousand synthetic applications through Kafka and ODM." |
| 0:45 | Kibana **Risk & Fairness** | "Decisions, reasons and fairness ratios, with group sizes." |
| 1:45 | the two `curl` commands of step 4 | "Certificate is identity; no certificate, no connection." |
| 2:30 | Claude: explain a REFER, what-if, then "approve it" is refused | "The AI explains. It cannot decide." |
| 3:45 | `make eval-rules` PASS 300/300 (and the FAIL screenshot of the mutation proof from [evals](evals.md)) | "Every rule change must match an independent oracle." |
| 4:30 | `docs/architecture/trust-boundaries.md` on GitHub | "One CA, mutual TLS on every hop, ODM is the only decider." |

## When RAM is tight

| Symptom | Do this |
|---|---|
| Docker VM below 6.5 GB or other stacks running | `make down` first; stop other projects' containers; never run kind and compose together |
| Elasticsearch or ODM restarting | skip phase B (Grafana) and show `docs/images/grafana-*.png`; use `PROFILE=core,analytics,edge` for everything else |
| `make demo` slow | `scripts/demo.sh 200 20 0.01` (200 applications) is enough for every dashboard |
| Kibana panels empty | `make seed-kibana`, then wait one minute for the first documents |
| ODM lost its rules after a restart | `scripts/ensure-rules.sh` (or `make up`, which runs it) |
| Claude Code cannot reach `odm-dc` | `make down && make up PROFILE=core,analytics,edge,mcp`; check `/mcp`; as a last resort run the MCP inspector (`npx @modelcontextprotocol/inspector@0.17.2 --cli`) against `scripts/mcp/odm-dc.sh` and call the tools by hand |
| kind too heavy | show `make argocd-status` output saved from a CI `kind-smoke` run, or the diagrams in [deployment](architecture/deployment.md) |
| Disk full in the Docker VM | `docker builder prune -a -f` (cache only; do not prune other projects' images or volumes) |
