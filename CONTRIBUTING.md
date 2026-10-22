# Contributing

## Flow

1. Branch from `main`: `git switch -c <type>/<slug>` (`feat/`, `fix/`, `docs/`, `chore/`).
2. Small commits using [Conventional Commits](https://www.conventionalcommits.org/) (`feat(orchestrator): add retry topic`).
3. `make verify` must be green. Install hooks once: `pre-commit install --hook-type pre-commit --hook-type commit-msg`.
4. Push and open a PR using the template. CI must pass. Squash-merge only.

## Rules

- Synthetic data only. No secrets, keys or real PII in git.
- Fictitious bank: "Maple Northern Bank".
- Rules must not reference prohibited grounds (gender, ethnicity, marital status, religion; age only for age-of-majority).
- Every Kafka consumer: manual commit, retry topic, DLQ, Testcontainers test.
- Nothing floats: GitHub Actions are pinned to a commit SHA, third-party images to `tag@sha256:...` (`scripts/pin-digests.py`), tools to a version. `scripts/check-pins.sh` is part of `make verify`.
- Every alert (Prometheus or Kibana) has a runbook under `docs/runbooks/`, listed in its index. `scripts/check-runbooks.sh` is part of `make verify`.
- A change to `rules/` is gated by `make eval-rules` (300 golden cases); a threshold change needs its golden cases changed in the same pull request ([docs/evals.md](docs/evals.md)).
- Diagrams are Mermaid; `make check-diagrams` renders them.

## Versions and the changelog

Do not edit `CHANGELOG.md`, `.release-please-manifest.json` or version numbers by hand: release-please opens a release pull request from the Conventional Commits on `main`. The squash-merge message of your pull request is what it reads, so write it as a Conventional Commit (`feat(scope): ...`, `fix(scope): ...`, `feat!:` or a `BREAKING CHANGE:` footer for a breaking change). Merging the release pull request tags the release and starts the image build (signed, SBOM attached).

## Dependencies

Dependabot proposes weekly updates ([runbook](docs/runbooks/dependabot.md)); review them like any pull request. Known and accepted vulnerabilities are listed with a reason and an expiry date in `.trivyignore`.
