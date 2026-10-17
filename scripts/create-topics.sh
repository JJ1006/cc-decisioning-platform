#!/usr/bin/env bash
# Creates every topic listed in docs/kafka-topics.md (business topics + their .retry and .dlq).
# Runs inside the kafka image (topic-init service). Idempotent: --if-not-exists.
set -euo pipefail

BOOTSTRAP=${BOOTSTRAP:-kafka:9093}
COMMAND_CONFIG=${COMMAND_CONFIG:-/pki/kafka-client.properties}
DOC=${TOPICS_DOC:-/docs/kafka-topics.md}
KT=${KAFKA_TOPICS:-/opt/kafka/bin/kafka-topics.sh}

ms() { # 7d -> ms
  local n=${1%d}
  echo $((n * 86400000))
}

create() { # topic partitions retention-days
  "$KT" --bootstrap-server "$BOOTSTRAP" --command-config "$COMMAND_CONFIG" --create --if-not-exists \
    --topic "$1" --partitions "$2" --replication-factor 1 --config "retention.ms=$(ms "$3")" \
    </dev/null # the JVM would otherwise swallow the table rows read by the caller's loop
}

# Table rows: | `topic` | key | partitions | retention | producer | consumers |
business=()
while IFS='|' read -r _ topic _ partitions retention _; do
  topic=$(echo "$topic" | tr -d ' `')
  partitions=$(echo "$partitions" | tr -d ' ')
  retention=$(echo "$retention" | tr -d ' ')
  [[ "$topic" == cc.* ]] || continue
  create "$topic" "$partitions" "$retention"
  business+=("$topic")
done < <(grep -E '^\| `cc\.' "$DOC")

# Every business topic also gets a retry (3 partitions, 1d) and a dlq (1 partition, 30d).
for t in "${business[@]}"; do
  create "$t.retry" 3 1d
  create "$t.dlq" 1 30d
done

echo "topics created: $((${#business[@]} * 3))"
"$KT" --bootstrap-server "$BOOTSTRAP" --command-config "$COMMAND_CONFIG" --list
