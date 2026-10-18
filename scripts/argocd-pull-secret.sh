#!/usr/bin/env bash
# Only when the GHCR packages are private (they are public while the repository is public: anonymous pulls work).
# Creates the image pull secret ghcr-pull in namespace cc from your GitHub token (read:packages). Never committed.
#   gh auth refresh -s read:packages && scripts/argocd-pull-secret.sh
# Then set global.imagePullSecrets: [{name: ghcr-pull}] in deploy/argocd/apps/<env>/values/cc-platform.yaml.
set -euo pipefail
CTX=${CTX:-kind-cc}
NS=${NS:-cc}
user=$(gh api user -q .login)
kubectl --context "$CTX" -n "$NS" create secret docker-registry ghcr-pull --docker-server=ghcr.io --docker-username="$user" \
  --docker-password="$(gh auth token)" --dry-run=client -o yaml | kubectl --context "$CTX" apply -f - >/dev/null
echo "secret $NS/ghcr-pull created for $user"
