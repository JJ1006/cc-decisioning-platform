#!/usr/bin/env bash
# Visual check of the Kibana dashboards (Playwright, headless Chromium): scripts/kibana-shots.sh [analyst|ops|elastic] [dashboard-id ...]
# Writes docs/images/kibana-*.png (OUT_DIR / SHOT_PREFIX override) and lists panels that show an error or "No results".
# Playwright and its Chromium are installed once into tmp/pw (gitignored). The password comes from the root env file.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
user=${1:-analyst}
[[ $# -gt 0 ]] && shift
PW=$ROOT/tmp/pw
if [[ ! -d "$PW/node_modules/playwright" ]]; then
  echo "installing Playwright into tmp/pw (once)"
  mkdir -p "$PW"
  (cd "$PW" && npm init -y >/dev/null && npm i playwright@1.64.1 >/dev/null && npx playwright install chromium)
fi
set -a
. "$ROOT/.env"
set +a
case $user in
  analyst) pass=$ANALYST_PASSWORD ;;
  ops) pass=$OPS_PASSWORD ;;
  elastic) pass=$ELASTIC_PASSWORD ;;
  *) echo "unknown user $user" >&2; exit 2 ;;
esac
KIBANA_USER=$user KIBANA_PASSWORD=$pass NODE_PATH=$PW/node_modules node observability/kibana/screenshots.cjs "$@"
