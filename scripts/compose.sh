#!/usr/bin/env bash
# docker compose wrapper: picks the compose file, .env and profiles (PROFILE=core,analytics).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
if [[ ! -f "$ROOT/.env" ]]; then
  cp "$ROOT/.env.example" "$ROOT/.env"
  echo "created .env from .env.example - edit the placeholder passwords" >&2
fi
# Container-side credential names differ from .env keys, so derive them into a gitignored
# env_file (deploy/compose/.env.derived) instead of putting *_PASSWORD keys in the compose file.
set -a; . "$ROOT/.env"; set +a
: "${PKI_PASSWORD:?set PKI_PASSWORD in .env}" "${ELASTIC_PASSWORD:?set ELASTIC_PASSWORD in .env}" "${KIBANA_PASSWORD:?set KIBANA_PASSWORD in .env}"
if [[ -z "${LOGSTASH_WRITER_PASSWORD:-}" ]]; then # password of the least-privilege logstash_writer user, generated once
  LOGSTASH_WRITER_PASSWORD=$(openssl rand -hex 16)
  printf '\nLOGSTASH_WRITER_PASSWORD=%s\n' "$LOGSTASH_WRITER_PASSWORD" >>"$ROOT/.env"
  echo "generated LOGSTASH_WRITER_PASSWORD in .env" >&2
fi
# ODM for Developers demo users (not secrets); override in .env for a different server
RES_USERNAME=${RES_USERNAME:-resAdmin}
RES_PASSWORD=${RES_PASSWORD:-$RES_USERNAME}
umask 077
cat >"$ROOT/deploy/compose/.env.derived" <<EOV
KAFKA_SSL_KEYSTORE_PASSWORD=$PKI_PASSWORD
KAFKA_SSL_KEY_PASSWORD=$PKI_PASSWORD
KAFKA_SSL_TRUSTSTORE_PASSWORD=$PKI_PASSWORD
KEYSTORE_PASSWORD=$PKI_PASSWORD
TRUSTSTORE_PASSWORD=$PKI_PASSWORD
KAFKA_CLUSTERS_0_PROPERTIES_SSL_KEYSTORE_PASSWORD=$PKI_PASSWORD
KAFKA_CLUSTERS_0_PROPERTIES_SSL_TRUSTSTORE_PASSWORD=$PKI_PASSWORD
ELASTIC_PASSWORD=$ELASTIC_PASSWORD
KIBANA_PASSWORD=$KIBANA_PASSWORD
ELASTICSEARCH_PASSWORD=$KIBANA_PASSWORD
KAFKA_STORE_PASSWORD=$PKI_PASSWORD
RES_TRUSTSTORE_PASSWORD=$PKI_PASSWORD
ES_TRUSTSTORE_PASSWORD=$PKI_PASSWORD
LOGSTASH_WRITER_PASSWORD=$LOGSTASH_WRITER_PASSWORD
RES_USERNAME=$RES_USERNAME
RES_PASSWORD=$RES_PASSWORD
EOV
profiles=()
IFS=, read -ra wanted <<<"${PROFILE:-core}"
for p in "${wanted[@]}"; do profiles+=(--profile "$p"); done
exec docker compose -f "$ROOT/deploy/compose/docker-compose.yml" --env-file "$ROOT/.env" \
  "${profiles[@]}" "$@"
