#!/usr/bin/env bash
# helm dependency build for every chart, in order: the workload charts (cc-lib), cc-observability (upstream charts), then the umbrella.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
H=$ROOT/deploy/helm
# the repositories of the upstream charts cc-observability wraps (file:// dependencies need none)
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
helm repo add grafana https://grafana.github.io/helm-charts >/dev/null 2>&1 || true
helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts >/dev/null 2>&1 || true
for c in "$H"/charts/*/; do
  helm dependency build "$c" >/dev/null
  echo "deps: ${c#"$ROOT"/}"
done
helm dependency build "$H/cc-platform" >/dev/null
echo "deps: deploy/helm/cc-platform"
