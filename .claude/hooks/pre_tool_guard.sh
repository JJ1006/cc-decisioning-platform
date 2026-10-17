#!/usr/bin/env bash
# PreToolUse guard. Reads hook JSON on stdin. exit 2 + reason on stderr = block.
set -uo pipefail
in=$(cat)
tool=$(jq -r '.tool_name // empty' <<<"$in")
cwd=$(jq -r '.cwd // empty' <<<"$in")

block() { echo "BLOCKED by pre_tool_guard: $1" >&2; exit 2; }

secret_path() {
  case "$1" in
    *.pub.pem|*.env.example) return 1 ;;
    *.key|*.p12|*.jks|*.pfx|*.pem) return 0 ;;
    */.env|.env|*/.env.*|.env.*) return 0 ;;
    */security/pki/out/*|security/pki/out/*) return 0 ;;
  esac
  return 1
}

case "$tool" in
  Read|Edit|Write)
    p=$(jq -r '.tool_input.file_path // empty' <<<"$in")
    secret_path "$p" && block "secret/key file: $p"
    ;;
  Bash)
    cmd=$(jq -r '.tool_input.command // empty' <<<"$in")
    # secret files referenced in the command (read/copy/cat etc.)
    for tok in $cmd; do
      t=$(printf %s "$tok" | tr -cd "[:alnum:]/._-")
      secret_path "$t" && block "command touches secret/key file: $t"
    done
    [[ "$cmd" =~ git[[:space:]]+push.*(--force|[[:space:]]-f([[:space:]]|$)|--force-with-lease) ]] && block "git push --force"
    [[ "$cmd" =~ rm[[:space:]]+-[a-zA-Z]*r[a-zA-Z]*f?[[:space:]]+(/|~|\$HOME)([[:space:]]|$) ]] && block "rm -rf on / or home"
    [[ "$cmd" =~ kubectl[[:space:]]+delete[[:space:]]+(ns|namespace) ]] && block "kubectl delete ns"
    if [[ "$cmd" =~ git[[:space:]]+commit ]]; then
      branch=$(git -C "${cwd:-.}" rev-parse --abbrev-ref HEAD 2>/dev/null || true)
      # allow the very first scaffold commit (no commits yet)
      if [[ "$branch" == "main" ]] && git -C "${cwd:-.}" rev-parse HEAD >/dev/null 2>&1; then
        block "commit on main - use a feature branch"
      fi
      # gitleaks on staged content
      if command -v gitleaks >/dev/null; then
        (cd "${cwd:-.}" && gitleaks git --staged --no-banner --redact >/dev/null 2>&1) \
          || { (cd "${cwd:-.}" && gitleaks protect --staged --no-banner --redact >/dev/null 2>&1) || block "gitleaks found secrets in staged content"; }
      fi
    fi
    ;;
esac
exit 0
