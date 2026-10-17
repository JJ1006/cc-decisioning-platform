# CLAUDE.md — cc-decisioning-platform

Talk caveman. Read `docs/PLAN.md` section for current phase only.

## Rules

- Bank = "Maple Northern Bank", fictitious. Synthetic data only. No real PII. No secrets in git.
- LLM never decides/overrides credit. ODM = system of record.
- Java 21 + Spring Boot 3 + Maven module under `services/`. Python 3.13 + uv.
- Every service: multi-stage Dockerfile, non-root, arbitrary UID ok (OCP), `/actuator/health` liveness+readiness, Micrometer prometheus, OTel-ready, mem limit in compose, JSON logs with `correlationId`.
- Every Kafka consumer: manual commit, idempotent producer, retry topic + DLQ, Testcontainers test.
- mTLS where PLAN says. Certs come from `scripts/pki.sh` only.
- Commits: Conventional Commits.

## PR flow

`git switch -c <type>/pN-<slug>` -> small conventional commits -> `make verify` green -> push -> `gh pr create` with template filled -> STOP. No merge.

## Done

Done = show verify command output. No claim without proof.

## Blocked

GUI, licence, tool missing, RAM -> print `NEED USER: <what>` and stop.

## Build env

Use JDK 21 (`make` sets JAVA_HOME). Default `mvn` JDK may be newer.

## Guardrail hooks (`.claude/hooks/`)

- `pre_tool_guard.sh`: blocks key/secret files, force-push, `rm -rf /`, `kubectl delete ns`, commits on `main`.
- `post_edit_fmt.sh`: format/lint after edits.
- `stop_tests.sh`: quick tests of changed modules.
