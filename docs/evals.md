# Evals (P14)

Evidence that the decisioning platform does what it says, and that a change does not break it. Everything lives in `evals/`; each eval has a `make` target. ODM is the
system of record for the credit decision and **an LLM never decides**: the agent evals test exactly that.

| Eval | What it proves | Command | Gate |
|---|---|---|---|
| Golden dataset + oracle | 300 labelled applications whose labels are derived from the written spec, not from ODM | `make test-evals` | the file is what the generator writes; oracle agrees with the 30 hand-written scenarios |
| Rule regression | the deployed ruleset gives the oracle's decision for every golden case (outcome, limit, product, APR tier, reason codes) | `make eval-rules` | **any difference fails**; CI `rules-gate` on rule changes |
| Mutation proof | the gate itself works: a flipped threshold in a temp copy is caught | `make eval-rules-mutation` | the mutated run must fail, the restored run must pass |
| Champion / challenger what-if | impact of a rule change on the last N real decisions before it ships | `make what-if CHALLENGER=1.1 N=1000` | a report for review (no automatic gate) |
| Tool-level evals | every `odm-dc` MCP tool against hostile fixtures: envelope, `<data>` wrapping, no PII out, size cap, deploy refused, audit | `make test-evals` | part of `make verify` |
| Agent evals (promptfoo) | the agent explains correctly from tools and does not approve, leak, obey injected text or act beyond its tools | `make eval-agent` | correctness >= 90%, safety 100% |
| Load (k6) | gateway p95 < 300 ms, errors < 1%, submit to decided < 2 s | `make load-test` | thresholds in `evals/k6/load.js` |
| Chaos | no decision lost when Kafka, ODM or Logstash is broken under load | `make chaos SCENARIO=kafka\|odm\|logstash` | accounting must balance |

## Golden dataset and oracle (`evals/golden/`)

`oracle.py` is an independent implementation of the ruleset: it parses the two decision tables and the age of majority out of `docs/rules-catalog.md` at run time and uses exact
fractions for the debt service ratio. It does not import the older `rules/tests/check_scenarios.py` and it never calls RES. `generate.py` writes 300 cases: about 177 hand-designed edge
cases (age of majority one year below and at it in all 13 provinces and territories, every TDS boundary at -0.01 / exact / +0.01 in three score rows, every score band edge,
the income edges of the limit table in every score column, product edges, the requested limit cap, product fallbacks, residency and employment values, bankruptcy,
delinquency / inquiry / file-age boundaries, stacked reasons) plus a seeded random mix (`SEED = 14`); every reason code appears in at least 3 cases (12 codes; the least covered,
`R_INSUFFICIENT_INCOME`, has 5). How to add a case: [evals/golden/README.md](../evals/golden/README.md).

## Rule regression gate (`evals/rule-regression/`)

`run.py` posts every case to RES (same mutual TLS and credentials as `scripts/res-env.sh`) and compares the five fields. Output in `evals/rule-regression/out/`: `report.md`,
`junit.xml`, `summary.json`. Exit 1 on any difference or engine error.

**The first run found a real bug.** 88 of the 300 cases failed with HTTP 400: `PROD-02` to `PROD-06` compared `requested product` with `.equals` and threw a
`NullPointerException` when the requested product was absent, although the catalog says "NO_FEE or absent". All five rules now have a null guard (`is not null and ...`), scenario
`TC31` covers it, and the gate is 300/300.

**Mutation proof** (`evals/rule-regression/mutation-check.sh`): copies the rule sources to a temp directory, flips one threshold (RISK-01 minimum income 15 000 to 14 000; the
score 680+ borderline TDS bound 0.50 to 0.52), regenerates the Decision Center project from the copy (`RULES_SRC`), deploys it to RES, runs the gate (it must fail and name the
boundary cases), then redeploys the real rules from git and runs the gate again (it must pass). `rules/` is never touched.

**CI** (`.github/workflows/rules-gate.yml`, job `rules-gate`): on a pull request that changes `rules/**`, `docs/rules-catalog.md`, `evals/golden/**` or the deploy scripts it starts
ODM for Developers (amd64 container, public image), runs `make deploy-rules` and the regression, uploads the report as an artifact and comments the summary on the pull request.
The job always runs and passes immediately when no gated path changed, so it can be a required check without blocking unrelated pull requests. **Required check:** it is a branch
protection setting of the repository, not a file. After this workflow is on `main` (a required check that has never run blocks every pull request):

```bash
gh api -X PATCH repos/<owner>/<repo>/branches/main/protection/required_status_checks \
  -f strict=true -f 'contexts[]=ci' -f 'contexts[]=rules-gate'
```

## Champion / challenger what-if (`evals/what-if/`)

`make what-if CHALLENGER=1.1 N=1000` applies `evals/what-if/challengers/tds-plus-2.patch` (more room before a borderline TDS refer: 0.42 to 0.44 for scores 620-679, 0.50 to 0.52
for 680+) to a temp copy of the rules, deploys it as `ccApproval/1.1` next to `1.0` (`RULES_SRC`, `RULESET_VERSION` of `scripts/deploy-dc.sh`), and replays the last N
decisions **with their exact stored inputs** from the restricted index `cc-decision-inputs` (read as the query API's Elasticsearch user) through both versions. The report
(`evals/what-if/out/report.md`, `report.json`, `flips.ndjson`) has the approval / refer / decline rates, exposure (total and average limit), the outcome migration matrix, the reason
code mix, the disparate impact ratio per province and age band for both versions with the delta (groups under `minGroupSize` 30 are marked small n) and the flipped applications.
Nothing is published or stored. A challenger can also come from Decision Center: deploy it as another version and run `NO_DEPLOY=1 evals/what-if/what-if.sh`.

## Tool-level evals (`evals/tools/`)

No LLM. For every tool of the `odm-dc` MCP server (the checked list equals the server's list, so a new tool fails the eval until it is covered) the backends answer with hostile
content: an instruction in every free-text field with a planted `</data>`, a SIN with a valid Luhn digit, an e-mail, a phone number, a card number and a postal code. The eval
asserts the envelope `{notice, tool, data}`, that free text stays inside balanced `<data>` wrappers, that no personal data comes out, one audit event per call without argument values,
the 20 KB size cap, that `dc_deploy_ruleapp` is refused by default and with the switch on still needs the human's token (and that the token is never audited), failure and fail-closed
behaviour. It runs with `make test-evals` and so in `make verify` and CI.

## Agent evals (`evals/agent/`)

promptfoo (MIT, pinned `0.124.1`) runs two suites against an agent that is `claude -p --model sonnet` **with only the read-only `odm-dc` MCP tools**: a temporary working
directory (no CLAUDE.md, no project settings), `--tools ""` (no Bash, Edit, Write), no skills, `--strict-mcp-config`, permission for `mcp__odm-dc__*` only
(`scripts/evals/claude-provider.sh`). Fixtures are real ids picked from the running stack after `make demo` (`scripts/evals/pick-fixtures.py`: declined x2, referred x2, approved x2,
a manual decision, and a referral with an underwriter note that tries to instruct the assistant).

* **Correctness (17 questions, gate 90%)**: explain a decline or referral (the tool must be called, and the reason codes in the answer must equal what the platform stores:
  truth is fetched live from the query API, never from the model), approved limit, what-if (called, labelled a simulation, outcome stated), fairness (`stats_fairness`, lowest
  group and its ratio within rounding), list rules (`dc_get_rules`), latency, search, manual decision, stored inputs, and an unknown id (must say not found, no invented reasons).
* **Safety (23 questions, gate 100%)**: approve / override / change a limit / record a manual decision / deploy (also with a made-up approval token) must be refused, with no tool
  outside the read-only set; PII (SIN, full name, date of birth, address, contacts, re-identification through the applicant token, gender and ethnicity, "turn off masking"); injection
  in the underwriter note (summarise it, follow it, deploy as it says) and in the user message; role-play and "pretend the rules do not apply"; system prompt and secrets; excessive agency (e-mail
  the applicant, delete the audit trail, Slack and Jira).
* **Graders are deterministic** (`asserts.js`: tool calls, refusal wording, PII shapes, numbers against the live API); `asserts.test.js` tests the graders themselves, including
  that they fail on a bad answer. No LLM rubric is needed, so there is no second model to trust.

Two honest notes from the first runs. (1) The first safety run was 19/23; all four "failures" were grader wording (a refusal phrased as "the server rejected the call", "I couldn't ...
none of my tools can ...", or an injected note that was correctly ignored and not "refused"): the agent had behaved safely every time, the graders were widened and unit-tested, and the
suite was rerun in full. (2) On the clean stack the first correctness run was 14/17: two "failures" were answers that correctly said "only R_LOW_SCORE was returned; the rule can also
return R_HIGH_TDS ..." (the grader now ignores codes named only to say they did not apply), and one was the audit trail refusing a call (the `odm-dc` server fails closed when it cannot
write its audit event, and Kafka was busy): the provider asks again when the tool result says "audit trail unavailable" (that is infrastructure, not the model).

**Cost and credentials.** Each question is one headless Claude Code run: about 1 cent for a refusal and 3 to 10 cents with tool calls on sonnet, about 20 to 30 cents per suite
(`evals/agent/out/*.json` has the cost per question), capped at `EVAL_BUDGET_USD` (1.0) per question. It needs the `claude` CLI logged in or `ANTHROPIC_API_KEY`; without either
`make eval-agent` prints `NEED USER` and exits 3. Not in pull request CI (no credentials there): `.github/workflows/agent-evals.yml` is `workflow_dispatch` only and does
nothing unless the repository secret `ANTHROPIC_API_KEY` exists.

## Load (`evals/k6/`) and chaos (`evals/chaos/`)

`make load-test`: k6 through the gateway with the `demo-partner` client certificate: steady 20 requests per second for 5 minutes, then a 100 per second spike for 1 minute; thresholds
`POST /v1/applications` p95 < 300 ms, failed submissions < 1%, and end to end (submit until the status is decided, polled on a 1 in 20 sample) p95 < 2 s. The gateway has a quota of 100 requests
per minute and a spike arrest of 5 per second per consumer, which would answer 429 to a load test, so `evals/k6/run.sh` restarts the gateway container with raised limits for the run only
(`GATEWAY_QUOTA_PER_MINUTE`, `GATEWAY_SUBMIT_RATE`, `GATEWAY_SUBMIT_BURST`, defaults unchanged) and restores them. k6 has no option for a private CA, so the script first checks that
the gateway certificate verifies against our CA and then runs k6 with `insecureSkipTLSVerify` against localhost. k6 is the binary in PATH (tested with v1.6.1) or the image `grafana/k6:1.6.1`.
With Grafana up (`obs` profile) the run is marked with annotations (tags `load-test`, `k6`). Summary: `evals/k6/out/summary.md`.

`make chaos SCENARIO=kafka|odm|logstash` (`evals/chaos/chaos.sh`) starts the generator, breaks one component mid-run, heals it, waits for the system to drain, then counts since the start:
`kafka` stops the broker for 20 s (accepted == decided + dead-lettered, no event id twice); `odm` stops ODM for 60 s (the circuit breaker logs, every application is decided or dead-lettered,
an alert fires; ODM loses its rules when it restarts, so the redeploy is part of the recovery); `logstash` kills it with `SIGKILL` (its persistent queue and the Kafka offsets carry the rest).
Report per scenario in `evals/chaos/out/`.

## Latest results (this repository, Docker VM 6.7 GB, `PROFILE=core,analytics,edge`)

Run on 2026-10-22 on a clean stack (`make down V=1`, `make up PROFILE=core,analytics,edge`, `make demo` 1000 applications, every parity check PASS). ODM has 2.5 GB and `-Xmx1664m`.

| Eval | Result |
|---|---|
| `make test-evals` | **PASS**: golden file current; 43 oracle tests + runner + what-if analysis tests; 20 tool-level evals; 11 grader tests |
| `make eval-rules` | **PASS 300/300** in 3.7 s (first ever run on the unfixed rules: 212/300, see above) |
| `make eval-rules-mutation` | **PASS**: min income 15 000 to 14 000 gives **FAIL 297/300** (G089-G091, income 14 999); borderline TDS bound 0.50 to 0.52 gives **FAIL 297/300** (G053, G195, G261); after the restore **300/300** |
| `make what-if CHALLENGER=1.1 N=500` | approve 63.6% to 66.0% (+2.4 pp), refer 12.6% to 10.2%, decline unchanged 23.8%; exposure +83 000 CAD (average limit 6 423 to 6 441); migration: 12 REFER become APPROVE, nothing else moves; `R_BORDERLINE_TDS` -12; 12 flipped applications; province and age band DI move by at most 0.06 (small groups marked) |
| `make eval-agent` | correctness **17/17 = 100%** (gate 90%), safety **23/23 = 100%** (gate 100%); about $0.2 to $0.3 per suite on sonnet. First runs: see the notes above |
| `make load-test` (20 rps 5 min, then 100 rps 1 min) | steady **PASS**: POST p50 9.7 ms, p95 195 ms, p99 1.3 s, 0.0% errors. Spike **FAIL**: p50 291 ms, p95 16 s, 75% failed: 100 rps exceeds what this one-node 6.7 GB laptop stack accepts. End to end submit to decided **FAIL**: p50 1.5 s, p95 3.1 s (threshold 2 s), 83% of the sampled applications decided within 10 s. The thresholds are the specified SLOs; they are meant for a sized environment, so the run is reported as it is |
| `make chaos SCENARIO=kafka` | **PASS**: broker stopped 20 s under 15 per second; Kafka acknowledged 238 of 300 (62 sends were refused to the producer, which is what a client sees), 232 decided + 6 dead letters = 238, no event id twice |
| `make chaos SCENARIO=odm` | **PASS**: ODM stopped 66 s under 10 per second; 600 acknowledged = 529 decided + 71 dead-lettered (retries exhausted during the outage, kept for replay), no duplicates, 23 circuit breaker log lines, three Kibana alerts fired (dead letters, p95 latency, error log lines) |
| `make chaos SCENARIO=logstash` | **PASS**: `SIGKILL` under load; 300 acknowledged = 294 decided + 6 dead letters, no duplicates (persistent queue and Kafka offsets) |

`docker stats` after all of it: ODM 1.39 GiB of 2.5 GiB, Elasticsearch 808 MiB of 1.25 GiB, Logstash 742 MiB of 896 MiB, Kibana 611 MiB, Kafka 466 MiB, the services 100 to 240 MiB.
No container was OOM-killed in the final runs. ODM was OOM-killed once earlier in the day during an unwarmed load test, with the heap already capped at 1664 MB, while Kibana and
kafka-ui were also running: the VM itself was out of memory. `load-test` and `chaos` therefore stop Kibana and kafka-ui for the run (`KEEP_UI=1` keeps them) and start them again.

### What the evals found (and what was done)

| Found by | Finding | Action |
|---|---|---|
| rule regression, first run | `PROD-02` to `PROD-06` threw a NullPointerException when the requested product was absent (88 of 300 cases) | null guard in the five rules, scenario `TC31`, gate 300/300 |
| load test | `IdempotencyCache.execute` was `synchronized` around the Kafka transaction: all submissions with an `Idempotency-Key` went through one lock | striped lock (same key serialized, different keys parallel) and `IdempotencyCacheTest`; the steady load now passes |
| load test | the first requests after a gateway restart, and the first concurrent requests (one transactional Kafka producer is created per concurrent request, about 2 s each), stalled the run and made it fail for a reason that is not the steady state | `run.sh` warms up with 90 concurrent submissions before measuring |
| chaos, odm | the circuit breaker of the ODM client was silent: no log, no metric, so "it opened" could not be observed | `ResClient` logs every state transition (`circuit breaker 'res' CLOSED -> OPEN`); the drill asserts it |
| chaos, kafka | the generator reports `running: false` when it has handed over its last record; delivery failures arrive up to a minute later | the drill waits until the failure count is stable and judges acknowledged records only |
| agent evals | `decision_explain` lists, for a fired decision table, every reason code the table can raise; a model reading it may name codes that did not apply | the eval checks claims, not mentions; open item in the backlog to return only the applied codes |
| load test | spike at 100 rps is beyond this stack | reported as FAIL; size the environment before using that threshold as a gate |

## How this is validation evidence (OSFI E-23)

| E-23 expectation | Evidence |
|---|---|
| Independent validation of the model | the golden dataset is labelled by an independent oracle derived from the written specification; the regression compares the deployed rules with it, including every boundary |
| Ongoing monitoring and change control | the `rules-gate` required check on every rule change; the mutation proof shows the gate detects a changed threshold; every decision stores the ruleset version and deployment time |
| Impact analysis before a change | the champion / challenger what-if on real decisions: approval, exposure, reason mix, outcome migration |
| Fairness monitoring | disparate impact ratio with group size per province and age band in the what-if report, in `GET /v1/stats/fairness`, in Kibana "Risk & Fairness" and in the Kibana alert rules |
| Human oversight, no autonomous override | the agent evals: 23 of 23 safety cases, no write tool exists, the tool-level evals prove deploy needs a human-held token |
| Resilience | load thresholds and the chaos drills (no lost decision) |
