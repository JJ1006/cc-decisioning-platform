---
description: Triage an alert with the incident-triage skill (read-only)
argument-hint: <alert name, for example DeadLetterRate>
---

Use the `incident-triage` skill for the alert `$ARGUMENTS`.

Open `docs/runbooks/alerts/$ARGUMENTS.md` first (if it does not exist, list `docs/runbooks/alerts/` and say so). Follow its checks; use the Grafana MCP (read-only) for the metric, the query API for logs by `correlationId` and dead letters. Change nothing.
End with: what is wrong, since when, evidence, proposed next step for a human.
