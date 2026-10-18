# Backlog (known tech debt)

| Item | Why it is debt | Planned |
|---|---|---|
| `whatif` by application id is indicative | The decision event does not carry the non-PII inputs (income band, debt, score, TDS, requested limit), so `odm-dc` rebuilds the application from the masked summary plus listed assumptions. | P12: add those inputs to the decision event/contract and the stored decision, then make `whatif(application_id)` exact. |
| Audit `eventType` for MCP calls | The audit contract fixes `eventType: cc.audit.v1`, so MCP calls are marked only by `payload.action = MCP_TOOL_CALL`. | Revisit with the next audit contract version (add an enum value or a `source` field). |
| Official RES MCP server has no masking or audit | It runs rulesets as given, and its remote mode is plain HTTP. | Local, synthetic-data use only; not exposed through the gateway. Reconsider when upstream adds TLS/hooks. |
