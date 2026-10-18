# odm-res-mcp: the official IBM ODM Decision MCP server

Not our code: [DecisionsDev/ibm-odm-decision-mcp-server](https://github.com/DecisionsDev/ibm-odm-decision-mcp-server) (Apache-2.0), **pinned to commit
`c08180cea3013d9e57cafa3085b288ed596e668c`** (2026-09-21). Read its README before changing the pin; the settings used here are taken from it.

* Local (Claude Code, Claude Desktop): `scripts/mcp/odm-res.sh` runs `uvx --from git+https://github.com/DecisionsDev/ibm-odm-decision-mcp-server@<SHA> start`
  over stdio. `ODM_SHA` can be overridden with `ODM_RES_MCP_SHA`.
* Remote: `Dockerfile` and `entrypoint.sh` build the same commit and start `start --transport streamable-http --port 3000` (compose profile `mcp`, 192 MB, not published).
* Settings (README, "Configuration Parameters"): `ODM_URL`, `ODM_RUNTIME_URL`, `ODM_USERNAME`, `ODM_PASSWORD` (the ODM for Developers demo user), `SSL_CERT_PATH` (our CA),
  `VERIFY_SSL_HOSTNAME=True`, `MTLS_CERT_PATH` / `MTLS_KEY_PATH` (certificate `mcp-res` from `scripts/pki.sh`; ODM requires a client certificate).
* It exposes every ruleset whose properties have `agent.enabled=true`; ours is `ccapprovaloperation` (`rules/cc-approval-ds/ruleset.properties`, set by `scripts/gen_dc_project.py`).
* It does not mask personal data or write an audit trail: synthetic data only. See [docs/mcp.md](../../docs/mcp.md).

Check: `npx @modelcontextprotocol/inspector --cli scripts/mcp/odm-res.sh --method tools/list`.
