# ADR-0006: MCP strategy

* Status: accepted
* Date: 2026-10-17

## Context and problem statement

AI agents need controlled access to ODM and decisions.

## Decision

Use the official ibm-odm-decision-mcp-server for RES, build our own odm-dc-mcp-server for Decision Center and decision queries (read-only by default, PII-masked, audited). Vet third-party dev MCPs in Phase 13.

## Consequences

Deploy tools disabled unless explicitly enabled with an approval token.
