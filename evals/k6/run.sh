#!/usr/bin/env bash
# Load test through the gateway with k6 (evals/k6/load.js): steady 20 rps for 5 minutes, then a spike of 100 rps for a minute.
#   evals/k6/run.sh [-- extra k6 args]                      (make load-test; STEADY_DURATION=1m SPIKE_DURATION=30s for a short run)
# What it does: checks that the gateway certificate verifies against our CA (k6 cannot), raises the gateway quota and spike arrest FOR THE RUN ONLY by
# restarting the gateway container with GATEWAY_* settings (the defaults, 100 per minute and 5 per second, would answer 429 to a load test), runs k6, restores the
# gateway, and (when Grafana is up) marks start and end as annotations. Compose only: never raise those limits on a shared environment.
# k6: the binary in PATH, tested with v1.6.1; without it the pinned image grafana/k6:1.6.1 (Docker). Summary: evals/k6/out/summary.md.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
K6_VERSION=1.6.1
OUT=$ROOT/evals/k6/out
PROFILE=${PROFILE:-core,analytics,edge}
export PROFILE
mkdir -p "$OUT"
[[ ${1:-} == -- ]] && shift

PKI=${PKI_OUT:-$ROOT/security/pki/out}
for f in "$PKI/demo-partner/demo-partner.crt" "$PKI/ca/ca.crt"; do [[ -f "$f" ]] || { echo "NEED USER: $f missing: run make pki" >&2; exit 3; }; done
gateway_verifies() { # captured first: with pipefail, grep -q closing the pipe early would make openssl fail
  local out
  out=$(openssl s_client -connect localhost:8443 -CAfile "$PKI/ca/ca.crt" -verify_return_error </dev/null 2>&1)
  grep -q "Verification: OK" <<<"$out"
}
if ! gateway_verifies; then
  echo "NEED USER: the gateway at localhost:8443 is not up or its certificate does not verify (make up PROFILE=core,analytics,edge)" >&2
  exit 3
fi

if command -v k6 >/dev/null; then
  K6=(k6)
  echo "k6: $(k6 version | head -1) (tested with v$K6_VERSION)"
elif docker info >/dev/null 2>&1; then
  K6=(docker run --rm -i --network host -e GATEWAY -e STEADY_RATE -e STEADY_DURATION -e SPIKE_RATE -e SPIKE_DURATION -e SAMPLE_EVERY -e CERT_DIR=/pki -e OUT_DIR=/out
    -v "$PKI:/pki:ro" -v "$ROOT/evals/k6:/work" -v "$ROOT/contracts:/contracts:ro" -v "$OUT:/out" -w /work "grafana/k6:$K6_VERSION")
  echo "k6: image grafana/k6:$K6_VERSION"
else
  echo "NEED USER: install k6 (brew install k6) or start Docker" >&2
  exit 3
fi

annotate() { # annotate TEXT : a Grafana annotation (best effort; needs the obs profile)
  scripts/grafana-curl.sh -X POST -H 'Content-Type: application/json' -d "{\"tags\":[\"load-test\",\"k6\"],\"text\":\"$1\"}" /api/annotations >/dev/null 2>&1 || true
}
gateway() { # gateway QUOTA RATE BURST
  GATEWAY_QUOTA_PER_MINUTE=$1 GATEWAY_SUBMIT_RATE=$2 GATEWAY_SUBMIT_BURST=$3 scripts/compose.sh up -d --force-recreate --no-deps apisix >/dev/null 2>&1
  for _ in $(seq 1 30); do
    gateway_verifies && return 0
    sleep 2
  done
  return 1
}
# A 7 GB Docker VM cannot hold the whole stack under load: ODM was OOM-killed (exit 137) when Kibana and kafka-ui were running too. They are not part of the
# submit path, so they are stopped for the run (KEEP_UI=1 to keep them) and started again afterwards.
FREED=()
if [[ -z "${KEEP_UI:-}" ]]; then
  for svc in kibana kafka-ui; do
    if [[ -n "$(scripts/compose.sh ps --status running --services 2>/dev/null | grep -x "$svc")" ]]; then scripts/compose.sh stop "$svc" >/dev/null 2>&1 && FREED+=("$svc"); fi
  done
  [[ ${#FREED[@]} -eq 0 ]] || echo "stopped for the run (memory): ${FREED[*]}"
fi
restore() {
  echo "restoring the gateway limits (100 per minute, 5 per second)"
  gateway 100 5 5 || echo "WARNING: gateway did not come back: make up PROFILE=$PROFILE" >&2
  [[ ${#FREED[@]} -eq 0 ]] || { scripts/compose.sh start "${FREED[@]}" >/dev/null 2>&1; echo "started again: ${FREED[*]}"; }
}
trap restore EXIT

echo "raising the gateway limits for the run"
gateway 10000000 2000 2000 || { echo "gateway did not restart" >&2; exit 1; }
# warm-up, not measured: the first requests after a gateway restart pay for TLS, class loading and JIT (1 to 2 s each); at 20 requests per second that stall
# makes the queue of virtual users grow and the whole run fails for a reason that is not the steady state
echo "warm-up: 90 submissions, 30 at a time (the application API creates a transactional Kafka producer per concurrent request the first time)"
jq '.payload.application | del(.applicationId)' contracts/examples/application-submitted.example.json >"$OUT/warmup.json"
export OUT PKI
seq 1 90 | xargs -P 30 -I{} bash -c 'scripts/api-call.sh demo-partner -o /dev/null -X POST -H "Content-Type: application/json" -H "Idempotency-Key: $(uuidgen)" --data @"$OUT/warmup.json" /v1/applications' || true
sleep 10
annotate "load test start: steady ${STEADY_RATE:-20} rps then spike ${SPIKE_RATE:-100} rps"
if [[ ${K6[0]} == k6 ]]; then
  (cd evals/k6 && OUT_DIR="$OUT" CERT_DIR="$PKI" k6 run "$@" load.js)
else
  "${K6[@]}" run "$@" load.js
fi
rc=$?
annotate "load test end (k6 exit $rc)"
exit $rc
