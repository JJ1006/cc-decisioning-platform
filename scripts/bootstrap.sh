#!/usr/bin/env bash
# Preflight: tools, Docker memory, Rosetta. Exit 1 when something blocking is missing.
#   MIN_DOCKER_GB=6 scripts/bootstrap.sh
set -uo pipefail
MIN_GB=${MIN_DOCKER_GB:-6}
fail=0
ok() { printf '  ok    %s\n' "$1"; }
warn() { printf '  WARN  %s\n' "$1"; }
bad() { printf '  FAIL  %s\n' "$1"; fail=1; }

echo "tools"
for t in docker git gh jq openssl curl uv mvn pre-commit; do
  command -v "$t" >/dev/null && ok "$t" || bad "$t not found (brew install ...)"
done
docker compose version >/dev/null 2>&1 && ok "docker compose" || bad "docker compose plugin missing"

jhome=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
if [[ -x "$jhome/bin/java" ]] || /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
  ok "JDK 21"
else
  bad "JDK 21 not found (brew install openjdk@21)"
fi

echo "docker"
if bytes=$(docker info --format '{{.MemTotal}}' 2>/dev/null) && [[ -n "$bytes" ]]; then
  gb=$(awk -v b="$bytes" 'BEGIN { printf "%.1f", b / 1073741824 }')
  if awk -v g="$gb" -v m="$MIN_GB" 'BEGIN { exit !(g + 0 >= m + 0) }'; then
    ok "Docker memory ${gb} GB (>= ${MIN_GB})"
  else
    bad "Docker memory ${gb} GB < ${MIN_GB} GB: Docker Desktop > Settings > Resources > Memory"
  fi
else
  bad "Docker daemon not reachable (start Docker Desktop)"
fi

if [[ "$(uname -m)" == arm64 ]]; then
  echo "apple silicon"
  # Every image used here is multi-arch (native arm64). Rosetta only matters for amd64-only images.
  if [[ -d /Library/Apple/usr/share/rosetta ]]; then
    ok "Rosetta installed (Docker Desktop > Settings > General > 'Use Rosetta' for amd64 images)"
  else
    warn "Rosetta not installed (only needed for amd64-only images): softwareupdate --install-rosetta"
  fi
fi

if [[ $fail -eq 0 ]]; then echo "bootstrap OK"; else echo "bootstrap: fix the FAIL items above"; fi
exit $fail
