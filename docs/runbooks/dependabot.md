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
