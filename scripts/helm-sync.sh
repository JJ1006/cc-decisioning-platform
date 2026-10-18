#!/usr/bin/env bash
# The Helm charts carry the same configuration files as the compose stack. Helm cannot read outside a chart, so this script copies them
# in (never edit the copies) and regenerates the Kafka topics and users. `--check` fails when a copy is stale (CI).
#   scripts/helm-sync.sh            copy + generate (make helm-sync)
#   scripts/helm-sync.sh --check    exit 1 when anything differs
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
H=$ROOT/deploy/helm
MODE=${1:-sync}

copy() { # copy SRC DEST  (file or directory; DEST is replaced)
  local src=$1 dest=$2
  if [[ "$MODE" == --check ]]; then
    diff -r -q "$src" "$dest" >/dev/null 2>&1 || { echo "stale: ${dest#"$ROOT"/} (copy of ${src#"$ROOT"/})" >&2; STALE=1; }
  else
    rm -rf "$dest"
    mkdir -p "$(dirname "$dest")"
    cp -R "$src" "$dest"
  fi
}
STALE=0

copy "$ROOT/gateway/apisix/config.yaml" "$H/charts/apisix/files/config.yaml"
copy "$ROOT/gateway/apisix/apisix.yaml.tmpl" "$H/charts/apisix/files/apisix.yaml.tmpl"
copy "$ROOT/gateway/apisix/entrypoint.sh" "$H/charts/apisix/files/entrypoint.sh"
copy "$ROOT/deploy/odm/mtls.xml" "$H/charts/odm/files/mtls.xml"
copy "$ROOT/deploy/odm/metrics.xml" "$H/charts/odm/files/metrics.xml"
copy "$ROOT/deploy/logstash/logstash.yml" "$H/charts/logstash/files/logstash.yml"
copy "$ROOT/deploy/logstash/pipelines.yml" "$H/charts/logstash/files/pipelines.yml"
copy "$ROOT/deploy/logstash/pipelines" "$H/charts/logstash/files/pipelines"
copy "$ROOT/deploy/logstash/ruby" "$H/charts/logstash/files/ruby"
copy "$ROOT/deploy/filebeat/filebeat.k8s.yml" "$H/charts/filebeat/files/filebeat.k8s.yml"
copy "$ROOT/scripts/es-setup.sh" "$H/cc-infra/files/es-setup.sh"
copy "$ROOT/deploy/es" "$H/cc-infra/files/es"
copy "$ROOT/observability/grafana/dashboards" "$H/charts/cc-observability/files/dashboards"
copy "$ROOT/observability/prometheus/rules" "$H/charts/cc-observability/files/rules"
copy "$ROOT/observability/otel/collector.yaml" "$H/charts/cc-observability/files/collector.yaml"
copy "$ROOT/observability/alertmanager/alertmanager.yml" "$H/charts/cc-observability/files/alertmanager.yml"

if [[ "$MODE" == --check ]]; then
  python3 "$ROOT/scripts/gen-k8s-kafka.py" --check || STALE=1
  [[ $STALE == 0 ]] && echo "helm charts are in sync with the compose configuration"
  exit "$STALE"
fi
python3 "$ROOT/scripts/gen-k8s-kafka.py"
echo "helm charts synced from the compose configuration"
