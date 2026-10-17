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
