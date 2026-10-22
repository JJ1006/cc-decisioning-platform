#!/usr/bin/env bash
# Fails on anything that floats: a GitHub Action not pinned to a commit SHA, a :latest image, a third-party image without @sha256, an unpinned npx/npm/pip/uv tool.
# Part of make verify. Bring a pin up to date with scripts/pin-digests.py (images) or Dependabot (actions, docker, pip, npm).
# bash 3.2 and BSD tools safe.
set -uo pipefail
cd "$(dirname "$0")/.."
fail=0
err() { echo "UNPINNED  $*"; fail=1; }

# 1. Actions: uses: owner/repo@<40 hex> (local ./ actions and docker:// are not used)
while IFS= read -r l; do
  err "action not pinned to a SHA: $l"
done < <(grep -rnE '^\s*-?\s*uses:' .github 2>/dev/null | grep -vE 'uses:\s+\./' | grep -vE '@[0-9a-f]{40}(\s|$)')

# 2. Images: no :latest anywhere an image is named
while IFS= read -r l; do
  err ":latest image: $l"
done < <(git ls-files 'deploy/*' '*Dockerfile*' '.github/*' 'scripts/*' 'Makefile' 'evals/*' 'mcp/*' 'gateway/*' | grep -v '^scripts/check-pins.sh$' | xargs grep -nE '(image:|FROM |image=|docker (run|pull)).*:latest\b' 2>/dev/null)

# 3. Third-party images carry a digest: compose, Dockerfiles, Helm values (our own ccplatform/*:dev and jj1006/* are built or signed by us)
check_img() { # file line ref
  case "$3" in
    ccplatform/*|jj1006/*|ghcr.io/jj1006/*|*'{{'*|*'$'*|scratch) return ;;
  esac
  case "$3" in *@sha256:*) ;; *) err "image without digest: $1:$2 $3" ;; esac
}
for f in deploy/compose/docker-compose.yml $(git ls-files '*Dockerfile*'); do
  while IFS=: read -r n rest; do
    ref=$(echo "$rest" | perl -ne 'if (/^\s*(?:-\s+)?image:\s+(\S+)/) {print $1} elsif (/^\s*FROM\s+(?:--platform=\S+\s+)?(\S+)/) {print $1}')
    [ -n "$ref" ] && check_img "$f" "$n" "$ref"
  done < <(grep -nE '^\s*(-\s+)?image:|^\s*FROM ' "$f")
done
for f in $(git ls-files 'deploy/helm/**/values*.yaml'); do
  perl -ne '
    $repo = $1 if /^\s*repository:\s+(\S+)/ && $1 !~ m{^https?://};
    if (/^\s*tag:\s+(\S+)/ && defined $repo) { print "$.:$repo:$1\n"; undef $repo }
    print "$.:$1\n" if /^\s*image:\s+([^\s{#]+)/;
  ' "$f" | while IFS=: read -r n a b c; do
    ref="$a"; [ -n "${b:-}" ] && ref="$a:$b${c:+:$c}"
    case "$ref" in *'@sha256'*) ;; esac
    check_img "$f" "$n" "$ref"
  done
done

# 4. Tools fetched at run time must name a version
while IFS= read -r l; do err "npx/npm tool without a version: $l"
done < <(git ls-files '.github/*' 'scripts/*' 'Makefile' 'evals/*' | grep -v '^scripts/check-pins.sh$' | xargs grep -nE 'npx( --yes| -y)? +[@a-z]' 2>/dev/null | grep -vE 'npx( --yes| -y)? +"?[@a-z0-9/._-]+@[\$0-9"{]|command -v|#|SKIP|NEED USER|npx playwright|npx\)')
while IFS= read -r l; do err "pip install without ==: $l"
done < <(git ls-files '.github/*' 'scripts/*' 'Makefile' | grep -v '^scripts/check-pins.sh$' | xargs grep -nE 'pip install' 2>/dev/null | grep -vE '==|-r |#')
while IFS= read -r l; do err "uv --with without ==: $l"
done < <(git ls-files '.github/*' 'scripts/*' 'Makefile' 'evals/*' | grep -v '^scripts/check-pins.sh$' | xargs grep -nE '\-\-with [a-z]' 2>/dev/null | grep -vE '\-\-with [a-z0-9_.-]+==')
while IFS= read -r l; do err "npm install -g without version: $l"
done < <(git ls-files '.github/*' 'scripts/*' | grep -v '^scripts/check-pins.sh$' | xargs grep -nE 'npm (install -g|i -g)' 2>/dev/null | grep -vE '@[0-9]+\.[0-9]+')

if [ $fail -eq 0 ]; then echo "OK  actions by SHA, images by digest, tools by version"; else echo "check-pins: FAILED"; exit 1; fi
