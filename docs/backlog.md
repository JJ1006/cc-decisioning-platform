# Backlog (known tech debt)

| Item | Why it is debt | Planned |
|---|---|---|
| `whatif` by application id is indicative | The decision event does not carry the non-PII inputs (income band, debt, score, TDS, requested limit), so `odm-dc` rebuilds the application from the masked summary plus listed assumptions. | P12: add those inputs to the decision event/contract and the stored decision, then make `whatif(application_id)` exact. |
| Audit `eventType` for MCP calls | The audit contract fixes `eventType: cc.audit.v1`, so MCP calls are marked only by `payload.action = MCP_TOOL_CALL`. | Revisit with the next audit contract version (add an enum value or a `source` field). |
| Official RES MCP server has no masking or audit | It runs rulesets as given, and its remote mode is plain HTTP. | Local, synthetic-data use only; not exposed through the gateway. Reconsider when upstream adds TLS/hooks. |
| Kubernetes: Kibana saved objects, rules and roles | `make seed-kibana` is compose-only; the charts create Kibana but not the space, dashboards or alert rules | P11/P12: a Job like es-setup that runs `scripts/seed-kibana.sh` |
| Kubernetes: Elasticsearch, Logstash and blackbox exporters | the ELK dashboard and a few alerts of the obs profile have no data on Kubernetes (Kafka lag comes from the Strimzi `kafkaExporter` only when enabled) | P11 |
| Kubernetes: ECK pods under Pod Security `restricted` | ECK's own init containers need explicit security contexts; kind-full runs the namespace as `privileged` (Filebeat) and OpenShift relies on the SCC | verify on OpenShift, then add the contexts |
| Kafka 4.3 on Kubernetes vs 3.9.2 in compose | Strimzi 1.x ships Kafka 4.x only | move the compose broker to a 4.x image |
