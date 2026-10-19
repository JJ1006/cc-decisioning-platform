#!/usr/bin/env bash
# Chart quality gate (make lint-helm, CI helm.yml): helm lint every chart, render cc-infra and cc-platform with each values file,
# validate the manifests with kubeconform (strict, Kubernetes 1.30, CRD schemas from the datreeio catalog) and run kube-linter.
# Needs: helm, kubeconform, kube-linter (CI installs them and sets REQUIRE_HELM_TOOLS=1; locally a missing tool is skipped). `scripts/lint-helm.sh quick` skips kubeconform and kube-linter.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
H=$ROOT/deploy/helm
OUT=${OUT:-$ROOT/out/helm-lint}
K8S_VERSION=${K8S_VERSION:-1.30.0}
rm -rf "$OUT" && mkdir -p "$OUT"
cd "$ROOT"

"$ROOT/scripts/helm-sync.sh" --check
"$ROOT/scripts/helm-deps.sh" >/dev/null

echo "== helm lint"
for c in "$H"/cc-lib "$H"/cc-infra "$H"/charts/*/ "$H"/cc-platform; do
  helm lint "$c" --namespace cc --set odm.license=accept --set license=accept >/dev/null || { helm lint "$c" --namespace cc --set odm.license=accept >&2; exit 1; }
  echo "ok  ${c#"$ROOT"/}"
done

echo "== helm template"
helm template filebeat "$H/charts/filebeat" --namespace cc >"$OUT/filebeat.yaml"
helm template obs "$H/charts/cc-observability" -f "$H/charts/cc-observability/values-kind-full.yaml" --namespace cc >"$OUT/obs-kind-full.yaml"
for v in kind-lite kind-full openshift; do
  helm template infra "$H/cc-infra" -f "$H/cc-infra/values-$v.yaml" --namespace cc >"$OUT/infra-$v.yaml"
  helm template cc "$H/cc-platform" -f "$H/cc-platform/values-$v.yaml" --namespace cc >"$OUT/platform-$v.yaml"
  # kube-linter looks at our own charts only: the upstream observability charts are the vendors' (kubeconform still checks them)
  helm template cc "$H/cc-platform" -f "$H/cc-platform/values-$v.yaml" --namespace cc --set cc-observability.enabled=false >"$OUT/own-$v.yaml"
  echo "ok  $v ($(grep -c '^kind:' "$OUT/infra-$v.yaml") + $(grep -c '^kind:' "$OUT/platform-$v.yaml") objects)"
done
[[ ${1:-} == quick ]] && exit 0
for tool in kubeconform kube-linter; do
  if ! command -v "$tool" >/dev/null; then
    [[ -n ${REQUIRE_HELM_TOOLS:-} ]] && { echo "FAIL  $tool not found" >&2; exit 1; }
    echo "SKIP  $tool not found (brew install $tool): manifests rendered but not validated"
    exit 0
  fi
done

echo "== kubeconform (strict, Kubernetes $K8S_VERSION)"
for f in "$OUT"/*.yaml; do
  kubeconform -strict -summary -kubernetes-version "$K8S_VERSION" \
    -schema-location default \
    -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
    -schema-location "$H/schemas/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json" \
    ${KUBECONFORM_ARGS:-} "$f"
done

echo "== kube-linter"
kube-linter lint --config "$ROOT/.kube-linter.yaml" "$OUT"/infra-*.yaml "$OUT"/own-*.yaml "$OUT"/filebeat.yaml
