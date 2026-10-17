#!/usr/bin/env bash
# Stop hook: quick unit tests of changed Maven modules. Report failures, never loop.
set -uo pipefail
in=$(cat)
[[ "$(jq -r '.stop_hook_active // false' <<<"$in")" == "true" ]] && exit 0
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0
mods=$( { git diff --name-only HEAD 2>/dev/null; git ls-files --others --exclude-standard; } \
  | grep -E '\.java$|pom\.xml$' | while read -r f; do
      d=$(dirname "$f"); while [[ "$d" != "." && ! -f "$d/pom.xml" ]]; do d=$(dirname "$d"); done
      [[ "$d" != "." ]] && echo "$d"; done | sort -u | paste -sd, -)
[[ -z "$mods" ]] && exit 0
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null)}
if ! out=$(mvn -q -B -o test -pl "$mods" -DskipITs 2>&1); then
  echo "Unit tests failed in: $mods" >&2
  echo "$out" | tail -40 >&2
  exit 2
fi
exit 0
