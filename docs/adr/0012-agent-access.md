# ADR-0012: How AI agents reach the platform

* Status: accepted
* Date: 2026-10-18
* Refines: [ADR-0006](0006-mcp-strategy.md), [ADR-0007](0007-llm-never-decides.md), [ADR-0011](0011-gateway-identity.md)

## Context and problem statement

P9 gives AI agents tools. They must be able to explain and simulate, never decide, and every use must be attributable and free of personal data.

## Decision

* **Two MCP servers.** The official IBM ODM Decision MCP server (pinned commit) runs rulesets as tools; our `odm-dc-mcp-server` reads Decision Center,
  decisions, explanations and statistics and simulates what-ifs. Ours is the guarded one: output masking, audit, rate limit, size cap.
* **Agents get consumers, not a back door.** Decisions and statistics are read through the API gateway as consumer `mcp-agent` (role AGENT) that may call
  `GET /v1/decisions*` and `GET /v1/stats/*` only: no manual decisions, no operations endpoints, no Elasticsearch. The remote server is reached at `/mcp/dc` by
  consumer `mcp-client` (role MCP_CLIENT), which can call nothing else. Distinct certificates: `mcp-client`, `mcp-agent`, `mcp-res` (RES and Decision Center).
* **No tool decides.** There is no manual-decision tool and no tool that publishes an event. `whatif` runs the ruleset on a copy and says SIMULATION.
  `dc_deploy_ruleapp` changes what ODM decides: off by default, and then it needs `ALLOW_DEPLOY=true` plus a human-held approval token, plan before execute.
* **Audit fails closed.** Every call is an event on `cc.audit.v1` (action `MCP_TOOL_CALL`, argument names and a hash, never values); without the audit trail the
  call returns no data.
* **Masking without a model.** Presidio with pattern recognizers (Luhn-checked SIN, postal code, e-mail, phone, card) and key-based masking of structured personal
  fields; a spaCy model would not fit the container. Names inside free text are not detectable this way: the stored data is already masked by Logstash and tests
  document the limit.
* **Strict TLS for Python.** Python 3.13 verifies certificates strictly, so the PKI now issues leaf certificates with subject and authority key identifiers.

## Consequences

* The official server cannot be wrapped by our guardrails (it runs rulesets as given), so it is for local, synthetic-data use; its remote mode has no TLS and stays internal.
* Eval of refusals and prompt injection against these tools is part of the later evaluation phase (P14).
