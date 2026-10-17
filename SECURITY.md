# Security Policy

This is a demo project with synthetic data only. No real customer data, credentials or keys belong in this repo.

## Reporting a vulnerability

Open a [private security advisory](https://github.com/JJ1006/cc-decisioning-platform/security/advisories/new). Do not file public issues for vulnerabilities.

## Rules

- No secrets or private keys in git (gitleaks runs in pre-commit and CI).
- Certificates are generated locally by `scripts/pki.sh` or by cert-manager.
- An LLM never makes or overrides a credit decision (see ADR-0007).
