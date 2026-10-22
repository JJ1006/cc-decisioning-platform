#!/usr/bin/env bash
# promptfoo exec provider (evals/agent/promptfooconfig.yaml): the prompt is $1; stdout is one JSON document (answer + tool calls), see claude_provider.py.
# Needs the claude CLI (logged in, or ANTHROPIC_API_KEY) and the running stack (make up PROFILE=core,analytics,edge; make demo).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
command -v claude >/dev/null || { echo '{"text":"","toolCalls":[],"error":"claude CLI not found"}'; exit 0; }
exec python3 "$ROOT/scripts/evals/claude_provider.py" "$@"
