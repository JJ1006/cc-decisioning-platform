#!/usr/bin/env bash
# Static checks of deploy/argocd (make lint-argocd, CI argocd.yml). Needs kubectl (kustomize), helm, yq, kubeconform.
#   1. every kustomization builds; the Application / AppProject manifests pass kubeconform --strict (CRD schemas from the datreeio catalog)
#   2. policy per environment: project, pinned targetRevision, sync mode (kind: automated + prune + selfHeal, openshift: NO automated sync),
#      sync waves (operators < infra < platform < observability), project restrictions
#   3. every Helm application is rendered with the value files it names (resolving $values) and the manifests pass kubeconform --strict
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
A=$ROOT/deploy/argocd
OUT=${OUT:-$ROOT/out/argocd-lint}
rm -rf "$OUT" && mkdir -p "$OUT"
for tool in kubectl helm yq kubeconform; do
  if ! command -v "$tool" >/dev/null; then
    [[ -n ${REQUIRE_ARGOCD_TOOLS:-} ]] && { echo "FAIL  $tool not found" >&2; exit 1; }
    echo "SKIP  $tool not found (brew install $tool): the Argo CD manifests were not checked"
    exit 0
  fi
done
fail=0
bad() { echo "FAIL  $*" >&2; fail=1; }
KC=(kubeconform -strict -summary -ignore-missing-schemas -kubernetes-version 1.30.0 -schema-location default
  -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
  -schema-location "$ROOT/deploy/helm/schemas/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json")
cd "$ROOT"

echo "== kustomize build + kubeconform"
for d in projects/kind projects/openshift apps/kind-lite apps/kind-full apps/openshift; do
  kubectl kustomize "$A/$d" >"$OUT/$(echo "$d" | tr / -).yaml"
done
cp bootstrap/root/*.yaml "$OUT/" 2>/dev/null || cp "$A"/bootstrap/root/*.yaml "$OUT/"
cp "$A/operators/openshift/operators.yaml" "$OUT/operators-openshift.yaml"
kubectl kustomize "$A/bootstrap/argocd" >"$OUT/argocd-install.yaml"
for f in "$OUT"/*.yaml; do "${KC[@]}" "$f" | tail -1; done

echo "== policy"
for env in kind-lite kind-full openshift; do
  f=$OUT/apps-$env.yaml
  n=$(yq e 'select(.kind=="Application")|.metadata.name' "$f" | grep -c .)
  [[ $n -ge 3 ]] || bad "$env: only $n applications"
  for name in $(yq e 'select(.kind=="Application")|.metadata.name' "$f" | grep -v '^---'); do
    q() { yq e "select(.kind==\"Application\" and .metadata.name==\"$name\")|$1" "$f"; }
    proj=$(q .spec.project)
    [[ $proj == platform || $proj == apps ]] || bad "$env/$name: project '$proj'"
    [[ $(q '.metadata.annotations["argocd.argoproj.io/sync-wave"]') != null ]] || bad "$env/$name: no sync wave"
    auto=$(q '.spec.syncPolicy.automated')
    if [[ $env == openshift ]]; then
      [[ $auto == null ]] || bad "$env/$name: automated sync on the manual-approval environment"
    else
      [[ $(q '.spec.syncPolicy.automated.prune') == true && $(q '.spec.syncPolicy.automated.selfHeal') == true ]] || bad "$env/$name: kind needs automated prune and selfHeal"
    fi
    # pinned revisions: charts by version, repository sources by branch main (kind) or a release tag (openshift)
    for rev in $(q '(.spec.source.targetRevision, .spec.sources[].targetRevision) | select(. != null)'); do
      case "$env:$rev" in
        kind-*:main | openshift:v[0-9]*.[0-9]*.[0-9]* | *:v[0-9]*.[0-9]*.[0-9]* | *:[0-9]*.[0-9]*.[0-9]*) ;;
        *) bad "$env/$name: targetRevision '$rev' is not pinned for this environment" ;;
      esac
    done
    [[ $env != openshift ]] || [[ $(q '.spec.sources[0].targetRevision // .spec.source.targetRevision') != main ]] || bad "$env/$name: openshift must not track main"
  done
  # order: operators < infra < platform < observability
  w() { yq e "select(.kind==\"Application\" and .metadata.name==\"$1\")|.metadata.annotations[\"argocd.argoproj.io/sync-wave\"]" "$f"; }
  infra=$(w cc-infra); plat=$(w cc-platform)
  [[ $infra -lt $plat ]] || bad "$env: cc-infra wave $infra is not before cc-platform $plat"
  for op in cert-manager strimzi eck operators; do
    ow=$(w $op | grep -v null || true)
    [[ -z $ow || $ow -lt $infra ]] || bad "$env: operator $op wave $ow is not before cc-infra $infra"
  done
  echo "ok  $env ($n applications)"
done
# projects: apps = namespace cc only, no cluster-scoped kinds, only this repository
p=$OUT/projects-kind.yaml
[[ $(yq e 'select(.metadata.name=="apps")|.spec.clusterResourceWhitelist|length' "$p") == 0 ]] || bad "project apps allows cluster-scoped resources"
[[ $(yq e 'select(.metadata.name=="apps")|.spec.destinations|length' "$p") == 1 ]] || bad "project apps has more than one destination"
[[ $(yq e 'select(.metadata.name=="apps")|.spec.destinations[0].namespace' "$p") == cc ]] || bad "project apps destination is not cc"
[[ $(yq e 'select(.metadata.name=="apps")|.spec.sourceRepos|length' "$p") == 1 ]] || bad "project apps allows more than one source repository"
echo "ok  projects"

echo "== helm render of every repository application"
for env in kind-lite kind-full openshift; do
  f=$OUT/apps-$env.yaml
  for name in $(yq e 'select(.kind=="Application")|select(.spec.sources[0].helm != null)|.metadata.name' "$f" | grep -v '^---'); do
    path=$(yq e "select(.kind==\"Application\" and .metadata.name==\"$name\")|.spec.sources[0].path" "$f")
    rel=$(yq e "select(.kind==\"Application\" and .metadata.name==\"$name\")|.spec.sources[0].helm.releaseName" "$f")
    args=()
    for vf in $(yq e "select(.kind==\"Application\" and .metadata.name==\"$name\")|.spec.sources[0].helm.valueFiles[]" "$f"); do
      case $vf in \$values/*) args+=(-f "$ROOT/${vf#\$values/}") ;; *) args+=(-f "$ROOT/$path/$vf") ;; esac
    done
    [[ $name != cc-observability ]] || "$ROOT/scripts/helm-deps.sh" >/dev/null
    [[ $name != cc-platform ]] || "$ROOT/scripts/helm-deps.sh" >/dev/null
    helm template "$rel" "$ROOT/$path" -n cc "${args[@]}" >"$OUT/render-$env-$name.yaml" || bad "$env/$name: helm template failed"
    "${KC[@]}" "$OUT/render-$env-$name.yaml" | tail -1 | sed "s/^/$env\/$name: /"
  done
done
[[ $fail == 0 ]] && echo "argocd manifests ok"
exit $fail
