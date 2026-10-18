#!/usr/bin/env bash
# Visual check of the Grafana dashboards, the service graph and a trace waterfall (Playwright, headless Chromium):
#   scripts/grafana-shots.sh [dashboard-uid ...]        (make grafana-shots; writes docs/images/grafana-*.png, OUT_DIR overrides)
# Playwright and its Chromium are installed once into tmp/pw (gitignored), shared with scripts/kibana-shots.sh. Needs a trace through the gateway:
# run make demo (or make api-call CLIENT=demo-partner ...) first.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
PW=$ROOT/tmp/pw
if [[ ! -d "$PW/node_modules/playwright" ]]; then
  echo "installing Playwright into tmp/pw (once)"
  mkdir -p "$PW"
  (cd "$PW" && npm init -y >/dev/null && npm i playwright >/dev/null && npx playwright install chromium)
fi
pw=$(grep -E '^GRAFANA_ADMIN_PASSWORD=' "$ROOT/.env" | tail -1 | cut -d= -f2-)
GRAFANA_PASSWORD=$pw NODE_PATH=$PW/node_modules node observability/grafana/screenshots.cjs "$@"
