---
name: release
description: "Cut a release and promote it to OpenShift. Use when asked to release, tag, version, promote, ship to OpenShift, or \"what is in the next release\"."
---

# Release

Conventional Commits drive the version (release-please). OpenShift only moves by a reviewed pull request and a human Argo CD sync.

## Steps

1. `main` is green; `gh pr list --label "autorelease: pending"` shows the release-please PR (`chore(main): release X.Y.Z`: changelog + version). Read the changelog; it must match what you expect.
2. A human merges that PR. `release.yml` creates the tag `vX.Y.Z` and the GitHub release, `images.yml` builds, signs and publishes the images (SBOM) for the tag.
3. `release.yml` dispatches `promote.yml` (or run it by hand with the tag: `gh workflow run promote.yml -f tag=vX.Y.Z`). It opens a PR that sets `targetRevision` and the image tag in `deploy/argocd/apps/openshift/`. **CODEOWNERS: human review only**; do not edit that folder (the guard hook blocks it unless `ALLOW_OCP_EDIT=1`).
4. A human merges the promotion PR, then presses **Sync** in OpenShift GitOps (manual by design). Agents may read status (Argo CD MCP, read-only), never sync.
5. Verify after the sync: `docs/runbooks/gitops.md` (health of every Application, `make verify-mtls` equivalent checks).

## Verify

```bash
gh release view vX.Y.Z && gh pr list --search "promote vX.Y.Z"
make lint-argocd
```
