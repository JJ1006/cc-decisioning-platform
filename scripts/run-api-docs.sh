#!/usr/bin/env bash
# Runs every ```bash block of docs/api/README.md, in order, in one shell, against the running stack
# (make up PROFILE=core,analytics,edge; make demo first so there are referrals to decide).
# The blocks carry their own assertions (test ...); the first failing command stops the run.
#   scripts/run-api-docs.sh        (make test-api-docs)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
README=docs/api/README.md

for url in http://localhost:8084/actuator/health http://localhost:8085/actuator/health; do
  curl -sf "$url" >/dev/null || { echo "NEED: $url is not up (make up PROFILE=core,analytics,edge)" >&2; exit 1; }
done

script=$(mktemp)
trap 'rm -f "$script"' EXIT
awk '/^```bash$/ {on=1; next} /^```$/ {on=0; next} on' "$README" >"$script"
echo "running $(grep -c '' "$script") lines from $README"
PS4='$ ' bash -euxo pipefail "$script"
echo "PASS  every example of $README ran"
