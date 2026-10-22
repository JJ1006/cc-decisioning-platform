#!/usr/bin/env bash
# Renders every ```mermaid block of README.md and docs/**/*.md with the pinned mermaid-cli, so a diagram that does not parse fails here and not on GitHub.
#   scripts/check-diagrams.sh        (make check-diagrams; needs node and downloads Chromium once, so it is not part of make verify)
set -uo pipefail
cd "$(dirname "$0")/.."
MMDC_VERSION=${MMDC_VERSION:-12.0.0}
command -v npx >/dev/null || { echo "NEED USER: node/npx is needed for mermaid-cli" >&2; exit 3; }
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
n=0; bad=0
for f in README.md $(find docs -name '*.md' ! -name PLAN.md | sort); do
  python3 - "$f" "$work" <<'PY'
import re, sys, pathlib
src, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
for i, m in enumerate(re.findall(r"```mermaid\n(.*?)```", src.read_text(), re.S), 1):
    (out / f"{src.as_posix().replace('/', '__')}.{i}.mmd").write_text(m)
PY
done
for d in "$work"/*.mmd; do
  [ -e "$d" ] || continue
  n=$((n + 1))
  if out=$(npx --yes "@mermaid-js/mermaid-cli@$MMDC_VERSION" -i "$d" -o "${d%.mmd}.svg" -q 2>&1); then echo "ok    $(basename "$d" | sed 's/__/\//g')"; else echo "FAIL  $(basename "$d" | sed 's/__/\//g')"; echo "$out" | tail -8; bad=$((bad + 1)); fi
done
echo "$n diagram(s), $bad failed"
[ $bad -eq 0 ]
