# AI pull request review

A read-only Claude review of a pull request, run by GitHub Actions ([`claude-review.yml`](../.github/workflows/claude-review.yml)). It is an extra pair of eyes, not a gate: it is not a required check and it never decides anything.

## What it does

- Reads the diff against `main`, picks the reviewers that match the changed paths (`rules-`, `security-`, `sre-`, `compliance-`, `privacy-reviewer`, see [`.claude/agents`](../.claude/agents)) and applies their checklists. The checklists are read from `main`, not from the pull request.
- Posts **one** comment: findings ranked blocker, major, minor, nit, each with `file:line` and a fix.
- Never approves or rejects a credit, rule or decision outcome. ODM is the system of record and the rules-gate decides ([ADR-0007](adr/0007-llm-never-decides.md)).

| Trigger | Who may use it |
|---|---|
| Comment `@claude review` on a pull request (conversation or a line comment) | the repository owner only |
| Label `claude-review` on a pull request | the repository owner only |

Skipped: bots, forks, anyone but the owner. A new trigger on the same pull request cancels the review still running.

Limits: tools are `Read`, `Grep`, `Glob`, `git diff`, `git log`, `git show`; no `Edit`, `Write`, push or merge. The token is `contents: read` (plus `pull-requests: write` to post the comment). 15 turns, 15 minutes.

## Cost: $0 extra

It authenticates with `CLAUDE_CODE_OAUTH_TOKEN`, a token of your Claude subscription. The usage counts against the subscription limits, there is no per-token bill, and no API key is used or needed anywhere in this workflow. Do not add one.

## Enable

```sh
claude setup-token                      # prints a long-lived token; log in with the subscription account
gh secret set CLAUDE_CODE_OAUTH_TOKEN   # paste it when asked
```

Then comment `@claude review` on any open pull request, or add the label `claude-review`.

Without the secret the job passes and prints a notice, so the workflow is safe to merge before the secret exists.

## Disable

```sh
gh secret delete CLAUDE_CODE_OAUTH_TOKEN
```

The workflow keeps passing with the notice. To remove it fully, delete the workflow file. Revoke the token in your Claude account settings if it leaked.

## Safety notes

- Pull request text and files are untrusted input to a model. The owner-only and same-repository guards and the read-only tools limit what a prompt injection can do; the output is a comment you read, nothing more.
- Synthetic data only: do not paste real personal data in a pull request, the diff goes to the model.
- The action is pinned to a commit SHA ([`scripts/check-pins.sh`](../scripts/check-pins.sh)); Dependabot moves it.
