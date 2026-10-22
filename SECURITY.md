# Security Policy

This is a demo project with synthetic data only. No real customer data, credentials or keys belong in this repo.

## Reporting a vulnerability

Open a [private security advisory](https://github.com/JJ1006/cc-decisioning-platform/security/advisories/new) (preferred), or write to the maintainer, [@JJ1006](https://github.com/JJ1006), through the contact on their GitHub profile. Do not file public issues for vulnerabilities. Expect an acknowledgement within 7 days; this is a demo project maintained in spare time, so there is no bounty and no SLA beyond best effort.

Supported: the latest release and `main`. Known weaknesses are listed, not hidden: [docs/limitations.md](docs/limitations.md), [docs/backlog.md](docs/backlog.md), and accepted scanner findings with an expiry in `.trivyignore`.

## Rules

- No secrets or private keys in git (gitleaks runs in pre-commit and CI).
- Certificates are generated locally by `scripts/pki.sh` or by cert-manager.
- An LLM never makes or overrides a credit decision (see ADR-0007).

## Secret scanning

Three scanners run on every pull request: gitleaks (pre-commit and CI), GitGuardian and CodeQL. Nothing secret is committed: passwords are generated into
gitignored files or Kubernetes secrets (`scripts/k8s-secrets.sh`), and the charts only hold the *names* of secrets (for example `passwordSecret: cc-pki-password`).
Those names look like credentials to GitGuardian, so `.gitguardian.yaml` lists them as ignored matches. It scans every path (no `paths-ignore`) and ignores only
exact strings, since GitGuardian does not take patterns; a new secret name that trips the check is added there in the same pull request, with the reason.
An environment-variable reference such as `${DB_PASSWORD}` is not a secret and is not reported; a real value never goes in the ignore list.
