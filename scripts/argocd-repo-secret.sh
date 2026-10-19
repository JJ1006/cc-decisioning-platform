#!/usr/bin/env bash
# Only for a PRIVATE repository: a read-only deploy key and the Argo CD repository secret. Nothing is committed; the private key lives in the
# cluster secret only (a temporary directory holds it for a moment). A public repository needs none of this.
# The Applications use https URLs; for a private repository change repoURL to the ssh form in deploy/argocd (bootstrap/root, apps, projects).
set -euo pipefail
CTX=${CTX:-kind-cc}
repo=$(gh repo view --json nameWithOwner -q .nameWithOwner)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
ssh-keygen -t ed25519 -N '' -C "argocd-$(date +%Y%m%d)" -f "$tmp/k" >/dev/null
gh repo deploy-key add "$tmp/k.pub" --repo "$repo" --title "argocd-kind-$(date +%Y%m%d)" # read-only by default
kubectl --context "$CTX" -n argocd create secret generic repo-cc --from-literal=type=git --from-literal=url="git@github.com:$repo.git" \
  --from-file=sshPrivateKey="$tmp/k" --dry-run=client -o yaml | kubectl --context "$CTX" apply -f - >/dev/null
kubectl --context "$CTX" -n argocd label secret repo-cc argocd.argoproj.io/secret-type=repository --overwrite >/dev/null
echo "deploy key added to $repo (read-only) and secret argocd/repo-cc created"
