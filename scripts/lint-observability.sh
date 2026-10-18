#!/usr/bin/env bash
# Static checks of the observability configuration, no stack needed (Docker for the tools; skipped without it unless REQUIRE_DOCKER=1):
#   1. the Grafana dashboards in observability/grafana/dashboards are exactly what gen_dashboards.py generates (no hand edits left behind)
#   2. promtool: Prometheus configuration and rules; amtool: Alertmanager configuration; the collector validates its own configuration
#   3. every alert has a runbook file, and every runbook belongs to an alert
#   scripts/lint-observability.sh        (part of make verify and CI)
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OBS=$ROOT/observability
fail=0
ok() { printf 'PASS  %s\n' "$1"; }
bad() { printf 'FAIL  %s\n' "$1"; fail=1; }

# 1. dashboards = generator output
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
if DASHBOARD_OUT="$tmp" python3 "$OBS/grafana/bootstrap/gen_dashboards.py" >/dev/null && diff -r "$tmp" "$OBS/grafana/dashboards" >/dev/null; then
  ok "grafana dashboards match gen_dashboards.py ($(ls "$OBS/grafana/dashboards" | wc -l | tr -d ' ') files)"
else
  bad "grafana dashboards differ from gen_dashboards.py: run python3 observability/grafana/bootstrap/gen_dashboards.py"
fi

# 3. runbooks
python3 - "$ROOT" <<'PY' && ok "every alert has a runbook" || bad "alert and runbook files do not match"
import pathlib, re, sys
root = pathlib.Path(sys.argv[1])
text = (root / "observability/prometheus/rules/alerts.yml").read_text()
alerts = re.findall(r"- alert: (\w+)", text)
urls = re.findall(r"runbook_url: (\S+)", text)
missing = [u for u in urls if not (root / u).exists()]
runbooks = {p.stem for p in (root / "docs/runbooks/alerts").glob("*.md")} - {"README"}
orphans = runbooks - {pathlib.Path(u).stem for u in urls}
if missing or orphans or len(alerts) != len(urls):
    print("missing:", missing, "orphans:", orphans, "alerts:", len(alerts), "urls:", len(urls))
    sys.exit(1)
PY

# 2. the tools
if ! docker info >/dev/null 2>&1; then
  if [[ -n "${REQUIRE_DOCKER:-}" ]]; then bad "docker not available"; exit 1; fi
  echo "SKIP  docker not available: promtool, amtool and collector validation not run"
  exit $fail
fi
run() { docker run --rm "$@" 2>&1; }
# promtool wants the TLS files of the scrape configs to exist: empty stand-ins
mkdir -p "$tmp/pki" && : >"$tmp/pki/ca.crt" && : >"$tmp/pki/odm-client.crt" && : >"$tmp/pki/odm-client.key"
out=$(run --entrypoint promtool -v "$OBS/prometheus:/p:ro" prom/prometheus:v3.2.1 check rules /p/rules/alerts.yml /p/rules/slo.yml) && ok "promtool: rules" || bad "promtool rules: $out"
out=$(run --entrypoint promtool -v "$OBS/prometheus:/p:ro" -v "$tmp/pki:/pki:ro" prom/prometheus:v3.2.1 check config /p/prometheus.yml) && ok "promtool: prometheus.yml" || bad "promtool config: $out"
out=$(run --entrypoint amtool -v "$OBS/alertmanager:/a:ro" prom/alertmanager:v0.28.1 check-config /a/alertmanager.yml) && ok "amtool: alertmanager.yml" || bad "amtool: $out"
out=$(run -v "$OBS/otel:/c:ro" otel/opentelemetry-collector-contrib:0.123.0 validate --config=/c/collector.yaml) && ok "collector: validate collector.yaml" || bad "collector: $out"
exit $fail
