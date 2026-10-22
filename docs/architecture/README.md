# Architecture

Maple Northern Bank (fictitious) credit-card decisioning platform. ODM decides; Kafka connects; Elasticsearch remembers; an LLM only explains.
All diagrams are Mermaid and render on GitHub; `make check-diagrams` renders them with a pinned `mermaid-cli` to prove they parse.

| Document | Shows |
|---|---|
| [context-containers.md](context-containers.md) | C4 level 1 (system context) and level 2 (containers) |
| [components.md](components.md) | C4 level 3: `decision-orchestrator` and the `odm-dc` MCP server |
| [sequence-application.md](sequence-application.md) | one application from the partner's `POST` to the status read, through every hop |
| [deployment.md](deployment.md) | the same platform on Docker Compose, on kind with Argo CD, and on OpenShift |
| [trust-boundaries.md](trust-boundaries.md) | trust zones and every mutual-TLS hop |

Decisions behind them: [docs/adr](../adr). Data and topics: [data-model](../data-model.md), [kafka-topics](../kafka-topics.md). Threats: [threat-model](../security/threat-model.md).

## Three rules the diagrams enforce

1. **ODM is the system of record.** Only `decision-orchestrator` calls RES for a decision; no other box can change one ([ADR-0007](../adr/0007-llm-never-decides.md)).
2. **Every arrow is mutual TLS** unless the diagram says otherwise ([ADR-0008](../adr/0008-mtls-everywhere.md)).
3. **Personal data stops at Logstash.** Topics before it carry the application; everything after it, and every MCP tool result, is masked ([data classification](../privacy/data-classification.md)).
