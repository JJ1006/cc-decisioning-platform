#!/usr/bin/env bash
# Pipeline tests: feeds fixture events through the real Logstash filters (a real Logstash container, the
# same filter files and ruby scripts the compose stack uses) and diffs the output against the expected
# documents in deploy/logstash/test/cases/*.json. Covers PII masking, dead-letter documents and log parsing.
#   scripts/test-logstash.sh            (also part of make verify; skips when Docker is not available)
#   REQUIRE_DOCKER=1 scripts/test-logstash.sh   fail instead of skip
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
IMAGE=${LOGSTASH_IMAGE:-docker.elastic.co/logstash/logstash:8.19.23}
LS=$ROOT/deploy/logstash

if ! docker info >/dev/null 2>&1; then
  if [[ -n "${REQUIRE_DOCKER:-}" ]]; then echo "FAIL  docker not available" >&2; exit 1; fi
  echo "SKIP  docker not available: Logstash pipeline tests not run"
  exit 0
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/in" "$WORK/out" "$WORK/data"
chmod 755 "$WORK" "$WORK/in"
chmod 777 "$WORK/out" "$WORK/data" # the container runs as uid 1000 (mktemp -d is 700)

# kind -> filter file of the real pipeline, plus the glue that stands in for the Kafka / Filebeat / DLQ input
# (functions, not associative arrays: macOS ships bash 3.2)
filter_of() {
  case $1 in events) echo kafka-events ;; dlq) echo kafka-dlq ;; logs) echo logs ;; es-reject) echo events-dlq ;; esac
}
pre_of() {
  case $1 in
    events | dlq) echo 'mutate { rename => { "topic" => "[@metadata][kafka][topic]" } }' ;;
    es-reject) echo 'mutate { add_field => { "[@metadata][dead_letter_queue][reason]" => "%{_reason}" } remove_field => ["_reason"] }' ;;
    *) echo '' ;;
  esac
}

: >"$WORK/pipelines.yml"
for kind in events dlq logs es-reject; do
  d="$WORK/pipe/$kind"
  mkdir -p "$d"
  # fixture file: one JSON object per line; "raw" objects are sent as the string field "message"
  jq -c '.[] | .input | if has("raw") then (.message = (.raw | tojson)) | del(.raw) else . end' \
    "$LS/test/cases/$kind.json" >"$WORK/in/$kind.jsonl"
  cat >"$d/00-in.conf" <<CONF
input { file { path => "/work/in/$kind.jsonl" mode => "read" codec => "json" sincedb_path => "/dev/null"
  exit_after_read => true file_completed_action => "log" file_completed_log_path => "/work/data/$kind.completed" } }
filter { $(pre_of "$kind") }
CONF
  cp "$LS/pipelines/$(filter_of "$kind")/50-filter.conf" "$d/50-filter.conf"
  cat >"$d/99-out.conf" <<CONF
filter { mutate { add_field => { "_alias" => "%{[@metadata][target_alias]}" } } }
output { file { path => "/work/out/$kind.jsonl" codec => "json_lines" } }
CONF
  printf -- '- pipeline.id: %s\n  path.config: "/work/pipe/%s/*.conf"\n  pipeline.workers: 1\n' "$kind" "$kind" >>"$WORK/pipelines.yml"
done

echo "running Logstash ($IMAGE) over the fixtures ..."
docker run --rm \
  -e CC_TODAY=2026-10-17 -e LS_JAVA_OPTS="-Xms256m -Xmx256m" \
  -v "$WORK:/work" -v "$LS/ruby:/usr/share/logstash/ruby:ro" \
  -v "$WORK/pipelines.yml:/usr/share/logstash/config/pipelines.yml:ro" \
  "$IMAGE" logstash --path.data /work/data/ls --pipeline.ecs_compatibility disabled --log.level warn \
  >"$WORK/logstash.log" 2>&1
rc=$?

python3 - "$LS/test/cases" "$WORK/out" "$WORK/logstash.log" "$rc" <<'PY'
import json, pathlib, re, sys

cases_dir, out_dir, log, rc = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3], sys.argv[4]
failed = total = 0


def drop(d, keys):
    return {k: v for k, v in d.items() if k not in keys}


for kind in ("events", "dlq", "logs", "es-reject"):
    cases = json.loads((cases_dir / f"{kind}.json").read_text())
    f = out_dir / f"{kind}.jsonl"
    actual = [json.loads(l) for l in f.read_text().splitlines() if l.strip()] if f.exists() else []
    if len(actual) != len(cases):
        print(f"FAIL  {kind}: expected {len(cases)} documents, got {len(actual)} (logstash exit {rc}, see log below)")
        failed += len(cases)
        total += len(cases)
        continue
    for case, got in zip(cases, actual):
        total += 1
        ignore = set(case.get("ignore", [])) | {"@version"}
        want = drop(case["expected"], ignore)
        have = drop(got, ignore)
        problems = []
        if want != have:
            problems.append(f"expected {json.dumps(want, sort_keys=True)}\n        got      {json.dumps(have, sort_keys=True)}")
        if case.get("deadLetter") and not re.fullmatch(r"[0-9a-f]{40}", str(got.get("eventId", ""))):
            problems.append("dead letter eventId is not a sha1 of the raw message")
        for field, needles in case.get("contains", {}).items():
            for n in needles:
                if n not in str(got.get(field, "")):
                    problems.append(f"{field} does not contain {n!r}")
        if problems:
            failed += 1
            print(f"FAIL  {kind}: {case['name']}")
            for p in problems:
                print("      " + p)
        else:
            print(f"PASS  {kind}: {case['name']}")

print(f"\n{total - failed}/{total} pipeline cases passed")
if failed:
    print("--- logstash log (tail) ---")
    print("".join(open(log).readlines()[-40:]))
sys.exit(1 if failed else 0)
PY
