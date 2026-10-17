# Dependabot (paused)

Dependabot version updates and security-fix PRs are paused. Vulnerability alerts stay on.

## What is paused

- `.github/dependabot.yml`: every ecosystem has `open-pull-requests-limit: 0` (comment `# PAUSED - remove limit to re-enable`). Groups and ignores are kept.
- Repo setting "Dependabot security updates" (automated security fixes) is off.

## Re-enable

1. Delete the three `open-pull-requests-limit: 0` lines in `.github/dependabot.yml`, open a PR, merge.
2. Turn security updates back on:

```bash
gh api -X PUT repos/JJ1006/cc-decisioning-platform/automated-security-fixes
```

## Check state

```bash
gh api repos/JJ1006/cc-decisioning-platform/automated-security-fixes   # {"enabled":false,...} while paused
gh pr list --author app/dependabot
```
