#!/usr/bin/env bash
# PreToolUse (Edit|Write|Bash): deploy/argocd/apps/openshift/ is the production-like environment. Only a promotion pull request (promote.yml) and a
# human change it (CODEOWNERS), so an agent may not edit it unless the human started the session with ALLOW_OCP_EDIT=1. exit 2 + reason on stderr = block.
set -uo pipefail
in=$(cat)
tool=$(jq -r '.tool_name // empty' <<<"$in")
PROTECTED='deploy/argocd/apps/openshift'

block() { echo "BLOCKED by pre_ocp_guard: $1 (human-only, CODEOWNERS; set ALLOW_OCP_EDIT=1 to override)" >&2; exit 2; }
[[ "${ALLOW_OCP_EDIT:-}" == 1 ]] && exit 0

case "$tool" in
  Edit | Write | NotebookEdit)
    p=$(jq -r '.tool_input.file_path // empty' <<<"$in")
    [[ "$p" == *"$PROTECTED"/* || "$p" == *"$PROTECTED" ]] && block "edit of $p"
    ;;
  Bash)
    cmd=$(jq -r '.tool_input.command // empty' <<<"$in")
    # a command that names the folder and writes: redirection, sed -i, tee, cp, mv, rm, git checkout/restore/apply, patch, perl -i, yq -i
    if [[ "$cmd" == *"$PROTECTED"* ]] \
      && [[ "$cmd" =~ (\>|sed[[:space:]]+-[a-zA-Z]*i|tee[[:space:]]|[[:space:]]cp[[:space:]]|[[:space:]]mv[[:space:]]|[[:space:]]rm[[:space:]]|git[[:space:]]+(checkout|restore|apply|rm)|patch[[:space:]]|perl[[:space:]]+-[a-zA-Z]*i|yq[[:space:]]+-i) ]]; then
      block "shell command that writes in $PROTECTED"
    fi
    ;;
esac
exit 0
