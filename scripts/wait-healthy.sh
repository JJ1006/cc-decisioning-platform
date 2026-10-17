#!/usr/bin/env bash
# Waits until every container of the selected profiles is healthy and every one-shot job
# (topic-init, es-setup) exited 0. Exit 1 on timeout or a failed job.
#   PROFILE=core,analytics scripts/wait-healthy.sh [timeout-seconds]
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TIMEOUT=${1:-600}
ONESHOT=" topic-init es-setup "
deadline=$((SECONDS + TIMEOUT))
# only the services of the selected profiles: stopped containers of other profiles are not our business
WANTED=" $("$ROOT/scripts/compose.sh" config --services 2>/dev/null | tr '\n' ' ') "

while true; do
  pending=()
  failed=()
  while IFS='|' read -r svc state health code; do
    [[ -z "$svc" || "$WANTED" != *" $svc "* ]] && continue
    if [[ "$ONESHOT" == *" $svc "* ]]; then
      if [[ "$state" == exited ]]; then
        [[ "$code" == 0 ]] || failed+=("$svc(exit $code)")
      else
        pending+=("$svc")
      fi
    elif [[ "$state" != running ]]; then
      failed+=("$svc($state)")
    elif [[ "$health" == unhealthy ]]; then
      pending+=("$svc(unhealthy)")
    elif [[ -n "$health" && "$health" != healthy ]]; then
      pending+=("$svc")
    fi
  done < <("$ROOT/scripts/compose.sh" ps -a --format json 2>/dev/null \
    | jq -r '[.Service, .State, (.Health // ""), (.ExitCode // 0)] | map(tostring) | join("|")')

  if [[ ${#failed[@]} -gt 0 ]]; then
    echo "FAILED: ${failed[*]}" >&2
    exit 1
  fi
  if [[ ${#pending[@]} -eq 0 ]]; then
    echo "all services healthy"
    exit 0
  fi
  if ((SECONDS >= deadline)); then
    echo "TIMEOUT waiting for: ${pending[*]}" >&2
    exit 1
  fi
  sleep 5
done
