#!/usr/bin/env bash
# Argo CD on the kind cluster (docs/runbooks/gitops.md). Make targets call this:
#   scripts/argocd.sh up [kind-lite|kind-full]   cluster, secrets, Argo CD, projects, root application (make argocd-up ENV=kind-lite)
#   scripts/argocd.sh ui                         port-forward https://localhost:8080 and print the admin login   (make argocd-ui)
#   scripts/argocd.sh status                     applications, sync and health                                    (make argocd-status)
#   scripts/argocd.sh down                       delete the kind cluster                                          (make argocd-down)
# Environment: REVISION=<branch|tag|sha> points every application of the repository at it (default main; test a branch before merging)
#              IMAGES=local   use the images built here (tag dev, kind load) instead of the GHCR tag in values/cc-platform.yaml
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
CTX=kind-cc
ARGO_NS=argocd
ENVNAME=${2:-${ENV:-kind-lite}}
REVISION=${REVISION:-main}
k() { kubectl --context "$CTX" "$@"; }
say() { printf '\n==> %s\n' "$*"; }

root_manifest() { # the root application of ENVNAME, with the revision / image overrides of this run applied
  python3 - "$ENVNAME" "$REVISION" "${IMAGES:-ghcr}" <<'PY'
import json, subprocess, sys
env, rev, images = sys.argv[1:4]
doc = json.loads(subprocess.check_output(["kubectl", "create", "--dry-run=client", "-o", "json", "-f", f"deploy/argocd/bootstrap/root/{env}.yaml"]))
src = doc["spec"]["sources"]
for s in src:
    s["targetRevision"] = rev
patches = []
if rev != "main":  # the labelled applications follow the same revision (their two sources: chart path and values ref)
    patches.append({"target": {"kind": "Application", "labelSelector": "cc/revision=follow"}, "patch":
        "- {op: replace, path: /spec/sources/0/targetRevision, value: %s}\n- {op: replace, path: /spec/sources/1/targetRevision, value: %s}\n" % (rev, rev)})
if images == "local":  # kind-loaded images ghcr.io/jj1006/cc-<service>:dev
    patches.append({"target": {"kind": "Application", "name": "cc-platform"}, "patch":
        "- {op: add, path: /spec/sources/0/helm/parameters, value: [{name: global.image.tag, value: dev}]}\n"})
if patches:
    src[0]["kustomize"] = {"patches": patches}
print(json.dumps(doc))
PY
}

up() {
  [[ $ENVNAME == kind-lite || $ENVNAME == kind-full ]] || { echo "ENV must be kind-lite or kind-full (OpenShift: docs/runbooks/gitops.md)" >&2; exit 2; }
  local profile=${ENVNAME#kind-}
  command -v kubectl >/dev/null && command -v python3 >/dev/null || { echo "NEED USER: kubectl and python3"; exit 2; }
  say "kind cluster ($profile)"
  scripts/kind.sh cluster "$profile"
  say "secrets (outside Argo CD: never in git, never pruned)"
  scripts/k8s-secrets.sh "$CTX" cc "$profile"
  say "Argo CD v3.5.5 (minimal)"
  k apply -k deploy/argocd/bootstrap/argocd --server-side --force-conflicts >/dev/null
  for d in argocd-server argocd-repo-server argocd-redis; do k -n $ARGO_NS rollout status deploy/$d --timeout=300s; done
  k -n $ARGO_NS rollout status statefulset/argocd-application-controller --timeout=300s
  if [[ $(gh repo view --json visibility -q .visibility 2>/dev/null || echo PUBLIC) == PRIVATE ]]; then
    say "the repository is private: deploy key"
    scripts/argocd-repo-secret.sh
  fi
  say "projects and the root application ($ENVNAME @ $REVISION${IMAGES:+, images: $IMAGES})"
  k apply -k deploy/argocd/projects/kind --server-side --force-conflicts >/dev/null
  root_manifest | k apply --server-side --force-conflicts -f - >/dev/null
  say "waiting for the applications (wave by wave, up to 40 min)"
  local i
  for i in $(seq 1 240); do
    sleep 10
    out=$(k -n $ARGO_NS get applications -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.sync.status}{" "}{.status.health.status}{"\n"}{end}' 2>/dev/null || true)
    n=$(printf '%s\n' "$out" | grep -c . || true)
    ok=$(printf '%s\n' "$out" | grep -c " Synced Healthy" || true)
    printf '\r%s of %s applications Synced and Healthy ' "$ok" "$n"
    [[ $n -ge 5 && $ok == "$n" ]] && break
  done
  echo
  status
}

status() { k -n $ARGO_NS get applications -o custom-columns='NAME:.metadata.name,WAVE:.metadata.annotations.argocd\.argoproj\.io/sync-wave,PROJECT:.spec.project,SYNC:.status.sync.status,HEALTH:.status.health.status,REVISION:.status.sync.revision'; }

ui() {
  echo "Argo CD UI: https://localhost:8080  user: admin  password: $(k -n $ARGO_NS get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d)"
  echo "(Ctrl-C stops the port-forward)"
  k -n $ARGO_NS port-forward svc/argocd-server 8080:443
}

case "${1:-}" in
  up) up ;;
  ui) ui ;;
  status) status ;;
  down) kind delete cluster --name cc ;;
  *) sed -n '2,9p' "$0" >&2; exit 2 ;;
esac
