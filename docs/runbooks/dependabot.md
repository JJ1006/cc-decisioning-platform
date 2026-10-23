# Dependabot

Version updates run weekly (`.github/dependabot.yml`), at most 5 open pull requests per ecosystem, minor and patch grouped. Vulnerability alerts and security updates are on.

| Ecosystem | Scope | Notes |
|---|---|---|
| `maven` | the whole reactor | minor + patch grouped; Spring Boot, networknt and logstash-logback-encoder majors ignored (upgrade on purpose) |
| `uv` | `mcp/odm-dc-mcp-server` | `pyproject.toml` + `uv.lock` |
| `github-actions` | `.github/workflows` | all actions are pinned to a commit SHA with the version in a comment; Dependabot moves both |
| `docker` | `FROM` lines in `services/*` and `mcp/*` | pinned `tag@sha256:`; majors ignored |
| `docker-compose` | `deploy/compose/docker-compose.yml` | majors ignored; **ODM and the Elastic images are ignored**: they move together by hand (rules deployment, index mapping, licence, the matching Helm charts) |
| `npm` | not configured | there is no `package.json`; the Node tools are pinned in the scripts that run them (`promptfoo` in `evals/agent/run.sh`, `playwright` in `scripts/*-shots.sh`, Spectral in `Makefile` and `ci.yml`, `claude-code` in `agent-evals.yml`). Bump them by hand and run `scripts/check-pins.sh` |

## Reviewing a Dependabot pull request

1. CI (`ci`, `rules-gate`, CodeQL, trivy, kind-smoke when labelled) must be green; `scripts/check-pins.sh` runs in `make verify` and fails when an update unpins something.
2. A Docker digest update changes what runs: read the upstream release notes, not only the diff.
3. Squash merge with a Conventional Commit title (`chore(deps): ...`, `fix(deps): ...` for a security fix); release-please reads it.

## Observability image bumps need a live check

`prom/prometheus`, `grafana/tempo`, `prom/blackbox-exporter`, `kuskoman/logstash-exporter`, `danielqsj/kafka-exporter` (and Alertmanager, the OTel collector) are only exercised by a running stack, so green CI proves nothing about them. One bump at a time, `make down V=1` between them (the RAM of a 7 GB Docker VM, and see the note on volumes below):

```bash
gh pr checkout <N>
make up-obs                                  # core + edge + obs
scripts/demo.sh 50                           # all checks PASS
python3 scripts/check-dashboard-queries.py   # "0 without data": Kafka, JVM and API SLO dashboards have data
scripts/grafana-curl.sh '/api/datasources/proxy/uid/prometheus/api/v1/targets'    # every target up
```

Then the check that belongs to the component (query through `scripts/grafana-curl.sh -G --data-urlencode 'query=...' /api/datasources/proxy/uid/prometheus/api/v1/query`):

| Bump | Check |
|---|---|
| prometheus | version in `/api/v1/status/buildinfo`; targets up; dashboard queries have data |
| logstash-exporter | `count({__name__=~"logstash_.*"})` is not empty and `logstash_info_up` is 1 (a few timeouts while Logstash starts are normal) |
| tempo | `/api/datasources/proxy/uid/tempo/api/search?limit=5` returns traces; `traces_service_graph_request_total` has edges |
| blackbox | `probe_success` is 1 for every instance (`blackbox-ca`, `blackbox-mtls`) |
| kafka-exporter | `up{job="kafka"}` is 1; `kafka_consumergroup_lag` and `kafka_topic_partition_current_offset` have series |

A config break (renamed flag, metric or label) gets its own `fix/obs-<name>` branch that carries the bump and the fix; close the Dependabot pull request with a link.

Practicalities when running these checks:

* `scripts/demo.sh` needs the `edge` profile (the generator is reached through the gateway) and compares Elasticsearch with the Kafka topics over all runs. After a `make down` without `V=1` Kafka has lost records that Elasticsearch kept, and the "all runs" parity checks fail although nothing is broken: use `make down V=1` and start from fresh volumes.
* The MCP tools that read decisions need the `analytics` profile (`decision-query-api`): `make up PROFILE=core,analytics,edge,mcp`.
* `scripts/mcp/remote_client.py` goes through the gateway rate limit: leave a few seconds between calls.

## When Dependabot cannot do it alone

* **Behind main**: after each merge the others go `BEHIND`. Comment `@dependabot rebase`, wait for `ci` and `rules-gate` on the new head, then merge. For a branch of ours, `gh pr update-branch <N> --rebase`. Do not merge with `--admin`.
* **Stale or conflicting lock**: when Dependabot keeps resolving an old version, find the cap (`uv pip install --dry-run 'a==x' 'b>=y'` names it) and fix it on our own branch, then close the Dependabot pull request with a link. Example: presidio-anonymizer 2.2.364 caps `cryptography<49`, so `cryptography>=50` in `pyproject.toml` keeps the anonymizer at 2.2.362 until the cap goes away.
* **The bump changes generated files**: regenerate on our own branch (`make api-docs` after a springdoc bump; it needs `python3 scripts/gen-rules-catalog.py` once in a fresh clone), read the diff (a cosmetic line is fine; a changed path, field or status is an API change: stop and report), then `make verify`.
* **A major that breaks imports** (MCP SDK 2.x): own branch, a before/after diff of the 15 tool names and schemas, `scripts/test-mcp.sh`, a live run through the gateway and over stdio. `make verify` also runs `evals/tools`, which imports the SDK.

## Release pull request checks

release-please pushes its pull request with `GITHUB_TOKEN`, so the `pull_request` workflows do not start and `main` requires `ci` and `rules-gate` on its head commit. The step "Start the required checks on the release pull request" in `.github/workflows/release.yml` runs `gh workflow run ci.yml` and `rules-gate.yml` on the release branch whenever release-please outputs a pull request, created or updated. If a release pull request shows no checks:

```bash
gh workflow run ci.yml --ref release-please--branches--main
gh workflow run rules-gate.yml --ref release-please--branches--main
```

## Images that Dependabot does not see

Helm `values.yaml` (`tag: x@sha256:`), `deploy/helm/cc-infra` and the operator versions are not Dependabot ecosystems here. Refresh them with the digest script and review the diff:

```bash
scripts/pin-digests.py --list    # what would change (only adds digests that are missing)
scripts/check-pins.sh            # fails on a :latest image, an action without SHA or a third-party image without digest
```

To move a pinned image to a newer tag, change the tag, delete the old `@sha256:...`, run `scripts/pin-digests.py`.

## Security updates

```bash
gh api repos/JJ1006/cc-decisioning-platform/automated-security-fixes            # {"enabled":true,...}
gh api -X PUT repos/JJ1006/cc-decisioning-platform/automated-security-fixes     # turn on
gh pr list --author app/dependabot
```
