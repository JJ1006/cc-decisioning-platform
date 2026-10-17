#!/usr/bin/env bash
# One-shot Elasticsearch bootstrap (compose service es-setup, also run by the es-indexer tests):
#   1. set the kibana_system password
#   2. create the least-privilege role cc_indexer (create_doc on the three write aliases, nothing else)
#      and the user es-indexer
#   3. install the index templates and make sure each type has today's date-suffixed index behind its
#      <type>-write alias (re-run daily to roll the write alias to a new index)
# Env: ELASTIC_PASSWORD, KIBANA_PASSWORD, ES_INDEXER_PASSWORD (required); ES_URL, ES_CACERT, ES_DIR (optional).
set -euo pipefail
ES_URL=${ES_URL:-https://elasticsearch:9200}
ES_CACERT=${ES_CACERT:-/pki/ca.crt}
ES_DIR=${ES_DIR:-/es}
: "${ELASTIC_PASSWORD:?}" "${KIBANA_PASSWORD:?}" "${ES_INDEXER_PASSWORD:?}"

es() { # es METHOD PATH [curl args...]; credentials via a curl config, never on the command line
  local method=$1 path=$2
  shift 2
  curl -sS --cacert "$ES_CACERT" -K <(printf 'user = "elastic:%s"\n' "$ELASTIC_PASSWORD") \
    -X "$method" -H 'Content-Type: application/json' "$@" "$ES_URL$path"
}
must() { # must METHOD PATH BODYFILE|-  -> fails the script unless HTTP 2xx
  local code
  code=$(es "$1" "$2" -o /tmp/es-out.json -w '%{http_code}' ${3:+--data-binary "$3"})
  [[ "$code" == 2* ]] || { echo "es-setup: $1 $2 -> HTTP $code: $(cat /tmp/es-out.json)" >&2; exit 1; }
}

must POST /_security/user/kibana_system/_password "{\"password\":\"$KIBANA_PASSWORD\"}"
echo "kibana_system password set"

must PUT /_security/role/cc_indexer "@$ES_DIR/cc_indexer.role.json"
must PUT /_security/user/es-indexer \
  "{\"password\":\"$ES_INDEXER_PASSWORD\",\"roles\":[\"cc_indexer\"],\"full_name\":\"es-indexer service\"}"
echo "role cc_indexer and user es-indexer ready"

today=$(date -u +%Y.%m.%d)
for type in cc-decisions cc-audit cc-notifications; do
  must PUT "/_index_template/$type" "@$ES_DIR/$type.template.json"
  index="$type-$today"
  code=$(es GET "/_alias/$type-write" -o /tmp/es-alias.json -w '%{http_code}')
  if [[ "$code" == 404 ]]; then
    must PUT "/$index" "{\"aliases\":{\"$type-write\":{\"is_write_index\":true}}}"
    echo "$type: created $index behind $type-write"
  else
    current=$(grep -o '^{"[^"]*"' /tmp/es-alias.json | cut -d'"' -f2)
    if [[ "$current" == "$index" ]]; then
      echo "$type: $type-write already points at $index"
    else
      must PUT "/$index" "{}"
      must POST /_aliases "{\"actions\":[{\"add\":{\"index\":\"$current\",\"alias\":\"$type-write\",\"is_write_index\":false}},{\"add\":{\"index\":\"$index\",\"alias\":\"$type-write\",\"is_write_index\":true}}]}"
      echo "$type: write alias rolled from $current to $index"
    fi
  fi
done
