#!/usr/bin/env bash
# Quality gate of the MCP server we wrote (mcp/odm-dc-mcp-server): ruff (lint and format), mypy --strict, pytest. Needs uv; skipped without it
# unless REQUIRE_UV=1. The first run downloads Python 3.13 and the dependencies (spaCy and Presidio are large).
#   scripts/test-mcp.sh        (make test-mcp; part of make verify and CI)
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT/mcp/odm-dc-mcp-server"
if ! command -v uv >/dev/null; then
  if [[ -n "${REQUIRE_UV:-}" ]]; then echo "FAIL  uv not found"; exit 1; fi
  echo "SKIP  uv not found: MCP server tests not run"
  exit 0
fi
fail=0
step() { printf '%s ... ' "$1"; shift; if out=$("$@" 2>&1); then echo ok; else echo FAIL; echo "$out" | tail -25; fail=1; fi; }
step "uv sync" uv sync --frozen --quiet
step "ruff check" uv run --frozen ruff check .
step "ruff format --check" uv run --frozen ruff format --check .
step "mypy --strict" uv run --frozen mypy
echo "pytest:"
uv run --frozen pytest -q || fail=1
exit $fail
