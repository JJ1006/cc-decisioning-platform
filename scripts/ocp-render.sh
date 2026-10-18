#!/usr/bin/env bash
# Render the OpenShift manifests without a cluster: out/openshift/{cc-infra,cc-platform}.yaml (make ocp-render).
# The CA certificate (public) is embedded in the re-encrypt Routes: make ocp-render CA=path/to/ca.crt (default: the one of make pki, if present).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
H=$ROOT/deploy/helm
OUT=${OUT:-$ROOT/out/openshift}
CA=${CA:-$ROOT/security/pki/out/ca/ca.crt}
NS=${NS:-cc}
mkdir -p "$OUT"
"$ROOT/scripts/helm-sync.sh" --check
"$ROOT/scripts/helm-deps.sh" >/dev/null
CAARGS=()
if [[ -f "$CA" ]]; then CAARGS=(--set-file "global.pki.caPem=$CA"); else echo "note: no CA file ($CA): the re-encrypt Routes get no destinationCACertificate; pass CA=<ca.crt> or --set-file global.pki.caPem"; fi
helm template infra "$H/cc-infra" -n "$NS" -f "$H/cc-infra/values-openshift.yaml" ${CAARGS[@]+"${CAARGS[@]/global.pki.caPem/pki.caPem}"} >"$OUT/cc-infra.yaml"
helm template cc "$H/cc-platform" -n "$NS" -f "$H/cc-platform/values-openshift.yaml" ${CAARGS[@]+"${CAARGS[@]}"} >"$OUT/cc-platform.yaml"
for f in "$OUT"/*.yaml; do echo "$f: $(grep -c '^kind:' "$f") objects, $(grep -c '^kind: Route' "$f") Routes"; done
