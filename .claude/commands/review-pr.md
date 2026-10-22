---
description: Review a pull request with the subagents that match the changed paths
argument-hint: <PR number>
---

Review PR `$ARGUMENTS`:

1. `gh pr view $ARGUMENTS --json title,body,files` and `gh pr diff $ARGUMENTS --name-only`.
2. Pick agents by changed path (run in parallel, give each the PR number and its files):
   - `rules/**`, `docs/rules-catalog.md` -> **rules-reviewer** and **compliance-reviewer**
   - `services/**`, `gateway/**`, `mcp/**`, `scripts/**`, `deploy/compose/**`, `security/**` -> **security-reviewer**
   - `deploy/**`, `observability/**`, `docs/runbooks/**`, Dockerfiles, `.github/**` -> **sre-reviewer**
   - `contracts/**`, `deploy/es/**`, `deploy/logstash/**`, `observability/kibana/**`, DTOs -> **privacy-reviewer**
   - notices, explanations, fairness code or docs -> **compliance-reviewer**
3. Merge the findings (dedupe, rank by severity). Do not post comments, approve or merge: print the review for the human.
