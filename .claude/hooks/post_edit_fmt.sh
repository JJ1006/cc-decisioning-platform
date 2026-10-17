#!/usr/bin/env bash
# PostToolUse (Edit|Write): format/lint the edited file. Non-blocking except schema failures.
set -uo pipefail
in=$(cat)
f=$(jq -r '.tool_input.file_path // empty' <<<"$in")
[[ -f "$f" ]] || exit 0
root=$(cd "$(dirname "$f")" && git rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$root" || exit 0
rel=${f#"$root"/}
case "$f" in
  *.java)
    mod=$(dirname "$rel"); while [[ "$mod" != "." && ! -f "$mod/pom.xml" ]]; do mod=$(dirname "$mod"); done
    if [[ -f "$mod/pom.xml" && "$mod" != "." ]]; then
      mvn -q -f "$mod/pom.xml" spotless:apply -DspotlessFiles="$(printf '%s' "$f" | sed 's/[.[\*^$]/\\&/g')" >&2 || true
    fi ;;
  *.py)
    command -v ruff >/dev/null && ruff format "$f" >&2 ;;
  contracts/*.json|*/contracts/*.json)
    if [[ "$f" == *.example.json ]]; then exit 0; fi
    python3 - "$f" <<'PY' >&2 || { echo "schema invalid: $f" >&2; exit 2; }
import json, sys
try:
    from jsonschema import Draft202012Validator as V
except ImportError:
    json.load(open(sys.argv[1])); sys.exit(0)  # no jsonschema: just check JSON parses
V.check_schema(json.load(open(sys.argv[1])))
PY
    ;;
  *.yaml|*.yml)
    [[ "$rel" == deploy/helm/*/templates/* ]] && exit 0
    command -v yamllint >/dev/null && yamllint -c .yamllint.yaml "$f" >&2 || true ;;
esac
exit 0
