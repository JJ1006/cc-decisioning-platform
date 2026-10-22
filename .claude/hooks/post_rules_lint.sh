#!/usr/bin/env bash
# PostToolUse (Edit|Write): after an edit under rules/ run the prohibited-grounds lint (scripts/lint-prohibited-grounds.sh). A hit exits 2 so
# the output reaches Claude; fix the rule, do not argue with the lint.
set -uo pipefail
in=$(cat)
f=$(jq -r '.tool_input.file_path // empty' <<<"$in")
root=${CLAUDE_PROJECT_DIR:-$(git -C "$(dirname "${f:-.}")" rev-parse --show-toplevel 2>/dev/null)}
[[ -n "$root" ]] || exit 0
rel=${f#"$root"/}
case "$rel" in rules/*) ;; *) exit 0 ;; esac

if ! out=$(cd "$root" && scripts/lint-prohibited-grounds.sh 2>&1); then
  echo "prohibited-grounds lint failed after editing $rel:" >&2
  echo "$out" | tail -25 >&2
  exit 2
fi
exit 0
