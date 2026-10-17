#!/usr/bin/env bash
# Runs every ```bash block of docs/api/README.md, in order, in one shell, against the running stack
# through the mutual-TLS gateway (make up PROFILE=core,analytics,edge; make demo first so there are referrals to decide).
# The blocks carry their own assertions (test ...); the first failing command stops the run.
#   scripts/run-api-docs.sh        (make test-api-docs)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
README=docs/api/README.md

# the gateway answers an analyst (mutual TLS) and the query API behind it is up
scripts/api-call.sh analyst -f -o /dev/null /v1/stats/latency 2>/dev/null \
  || { echo "NEED: the gateway is not answering (make up PROFILE=core,analytics,edge; make pki)" >&2; exit 1; }

script=$(mktemp)
trap 'rm -f "$script"' EXIT
awk '/^```bash$/ {on=1; next} /^```$/ {on=0; next} on' "$README" >"$script"
echo "running $(grep -c '' "$script") lines from $README"
PS4='$ ' bash -euxo pipefail "$script"
echo "PASS  every example of $README ran"
