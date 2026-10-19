#!/usr/bin/env bash
# helm dependency build: cc-observability (upstream charts), then the umbrella (file:// subcharts).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
H=$ROOT/deploy/helm
# the repositories of the upstream charts cc-observability wraps (file:// dependencies need none)
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
helm repo add grafana https://grafana.github.io/helm-charts >/dev/null 2>&1 || true
helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts >/dev/null 2>&1 || true
# the workload charts carry cc-lib as copied templates (scripts/helm-sync.sh) and have no dependencies; cc-observability wraps upstream charts
helm dependency build "$H/charts/cc-observability" >/dev/null
echo "deps: deploy/helm/charts/cc-observability"
helm dependency build "$H/cc-platform" >/dev/null
echo "deps: deploy/helm/cc-platform"
