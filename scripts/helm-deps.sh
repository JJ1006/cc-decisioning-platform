#!/usr/bin/env bash
# helm dependency build for every chart, in order: the workload charts (cc-lib), cc-observability (upstream charts), then the umbrella.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
H=$ROOT/deploy/helm
for c in "$H"/charts/*/; do
  helm dependency build "$c" >/dev/null
  echo "deps: ${c#"$ROOT"/}"
done
helm dependency build "$H/cc-platform" >/dev/null
echo "deps: deploy/helm/cc-platform"
