#!/usr/bin/env bash
# Evals that need no ODM and no LLM: oracle unit tests, the golden file is current, the regression and what-if analysis code, the tool-level evals of the
# odm-dc MCP server (hostile fixtures). Part of make verify; skipped without uv unless REQUIRE_UV=1.
#   scripts/test-evals.sh        (make test-evals)
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
if ! command -v uv >/dev/null; then
  if [[ -n "${REQUIRE_UV:-}" ]]; then echo "FAIL  uv not found"; exit 1; fi
  echo "SKIP  uv not found: evals tests not run"
  exit 0
fi
fail=0
step() { printf '%s ... ' "$1"; shift; if out=$("$@" 2>&1); then echo ok; else echo FAIL; echo "$out" | tail -25; fail=1; fi; }
step "golden.json is current (oracle + generator)" python3 evals/golden/generate.py --check
step "oracle, regression runner and what-if analysis tests" uv run --quiet --with pytest python -m pytest evals/golden evals/rule-regression evals/what-if -q -p no:cacheprovider
step "tool-level evals of the odm-dc MCP server" bash -c "cd mcp/odm-dc-mcp-server && uv run --frozen pytest ../../evals/tools -q -p no:cacheprovider"
command -v node >/dev/null && step "graders of the agent evals (node --test)" node --test evals/agent/asserts.test.js
exit $fail
