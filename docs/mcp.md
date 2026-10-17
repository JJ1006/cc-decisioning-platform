# MCP servers for development

`.mcp.json` (project scope, committed) registers dev-time MCP servers for Claude Code. Claude Code asks for approval
the first time a project-scoped server is used.

| Server | Command | Used for |
|---|---|---|
| `playwright` | `npx @playwright/mcp@latest` | Browser checks of local UIs: Kibana dashboards (P7), Grafana (P8), Decision Center Business Console when a change cannot be made through the API. Localhost only. |

Rules of use:

- Dev tool only. It is not part of any service image or of the platform runtime (the platform MCP servers arrive in P9).
- Point it at `https://localhost:...` services only; never enter credentials for anything else.
- `@latest` is unpinned on purpose while we explore. Pin an exact version here and in `.mcp.json` before P15 (hardening).
