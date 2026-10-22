---
name: privacy-request
description: "Handle a privacy request about an application: access/export or erasure (delete my data), PIPEDA access request, right to be forgotten. Use when asked to export, erase, delete or look up personal data of an application id."
---

# Privacy request

Synthetic data only in this repo. A human (role OPS) confirms every erasure; agents prepare and verify, they do not erase on their own.

## Steps

1. Identify: the application id (`APP-...`). Check it exists: `scripts/api-call.sh ops-admin /v1/decisions/<id>`.
2. **Export** (access request): `scripts/api-call.sh ops-admin /v1/ops/privacy/export/<id>` returns what is stored (decisions, inputs, notifications, audit, dead letters; masked where masked at rest).
3. **Erase** (after the human confirms): `scripts/api-call.sh ops-admin -X POST -H 'Content-Type: application/json' --data '{"applicationId":"<id>"}' /v1/ops/privacy/erase`. It deletes in Elasticsearch (decisions, inputs, notifications, audit, dead letters, log lines) and writes `PRIVACY_ERASURE` to the audit trail with a **hash** of the id.
4. **Audit check**: `scripts/es-curl.sh '/cc-audit/_search?q=payload.action:PRIVACY_ERASURE'` shows the new record; repeating the export returns nothing for the id; `GET /v1/decisions/<id>` is 404.
5. Say what remains: Kafka topics keep events until retention (7 to 30 days; `*.dlq` 7 days) - crypto-shredding is in `docs/backlog.md`. State it in the answer.
6. Docs: `docs/privacy/data-classification.md`, `docs/compliance.md` (PIPEDA).

## Verify

Show the export before, the erase response, the audit record and the empty export after.
