# C4: system context and containers

## Level 1: system context

```mermaid
flowchart TB
  partner(["Partner / channel<br/>submits applications"])
  uw(["Underwriter<br/>works REFERRED cases"])
  analyst(["Analyst, ops<br/>dashboards, statistics"])
  dev(["Rule author<br/>changes rules in Decision Center"])
  agent(["AI agent (Claude)<br/>explains, simulates; never decides"])

  sys["<b>Credit-card decisioning platform</b><br/>receives applications, decides with ODM,<br/>publishes and explains the outcome"]

  odm[("IBM ODM for Developers<br/>Decision Center + RES<br/>(licence: dev only, never redistributed)")]
  bureau["Credit bureau<br/>(simulated in the application payload)"]
  mail["E-mail / letter provider<br/>(simulated: notification-svc writes a topic)"]

  partner -- "HTTPS + client certificate" --> sys
  uw -- "HTTPS + client certificate" --> sys
  analyst -- "HTTPS (Kibana, Grafana) + API" --> sys
  dev -- "Decision Center" --> odm
  agent -- "MCP over mTLS, read-only tools" --> sys
  sys -- "decide / explain" --> odm
  bureau -. "score arrives in the application" .-> sys
  sys -. "decision notices" .-> mail
```

Everything outside the box is synthetic or simulated: no real bureau, no real mail, no real customer. See [limitations](../limitations.md).

## Level 2: containers

```mermaid
flowchart LR
  subgraph edge["Edge"]
    GW["<b>APISIX gateway</b><br/>:8443 mTLS, CN to role,<br/>quota, spike arrest"]
  end
  subgraph apis["API tier (Spring Boot 3, Java 21)"]
    AA["<b>application-api</b><br/>submit, status,<br/>manual decision, privacy ops"]
    QA["<b>decision-query-api</b><br/>read-only: decisions,<br/>statistics, fairness"]
    GEN["<b>txn-generator</b><br/>synthetic applications"]
  end
  subgraph events["Event tier"]
    ORCH["<b>decision-orchestrator</b><br/>consume, call RES, route"]
    NOT["<b>notification-svc</b><br/>plain-language notices"]
  end
  K[("<b>Kafka</b> (KRaft)<br/>cc.applications.submitted.v1<br/>cc.decisions.*.v1, cc.audit.v1<br/>retry + dlq per topic")]
  subgraph odmz["ODM"]
    RES["<b>RES</b><br/>ruleset ccApproval"]
    DC["<b>Decision Center</b><br/>rules, versions, deploy"]
  end
  subgraph elk["Analytics and logs"]
    FB["Filebeat"]
    LS["<b>Logstash</b><br/>mask PII, route"]
    ES[("<b>Elasticsearch</b><br/>cc-decisions, cc-audit, cc-dlq,<br/>cc-logs, cc-decision-inputs (restricted)")]
    KIB["<b>Kibana</b><br/>dashboards, alert rules"]
  end
  subgraph obs["Observability (LGTM-style)"]
    OT["OTel collector"]
    PR["Prometheus + Alertmanager"]
    TM["Tempo"]
    GR["Grafana"]
  end
  MCP["<b>odm-dc-mcp</b> (Python)<br/>read-only agent tools,<br/>masking, audit"]
  UI["kafka-ui (dev tool)"]

  GW -- mTLS --> AA
  GW -- mTLS --> QA
  GW -- mTLS --> GEN
  GW -- "mTLS /mcp/dc" --> MCP
  AA -- mTLS --> K
  AA -- "mTLS (status)" --> QA
  GEN -- mTLS --> K
  K --> ORCH
  ORCH -- "mTLS + basic auth" --> RES
  ORCH --> K
  K --> NOT --> K
  K --> LS
  FB --> LS
  LS -- "TLS + user" --> ES
  QA -- "TLS + read user" --> ES
  KIB --> ES
  DC -. "deploy RuleApp" .-> RES
  MCP -- "mTLS, via gateway" --> GW
  MCP -- "mTLS" --> DC
  MCP -- "audit events" --> K
  UI --> K
  apis & events -. "OTLP traces, /actuator/prometheus" .-> OT
  OT --> TM
  PR --> GR
  TM --> GR
```

| Container | Tech | Owns | Never does |
|---|---|---|---|
| APISIX | Apache APISIX 3.13, standalone YAML | identity from the client certificate, roles, rate limits | terminate TLS before the certificate is read |
| application-api | Spring Boot | the submit contract, idempotency, manual decisions | decide anything; read personal data back |
| decision-orchestrator | Spring Boot, Kafka, Resilience4j | the only call to RES; retry, DLQ, circuit breaker | override an ODM answer |
| decision-query-api | Spring Boot | read access to Elasticsearch behind roles | write |
| notification-svc | Spring Boot | the notice wording from reason codes | send real mail |
| Logstash | 8.19 | PII masking and routing to indices | keep an unmasked copy outside the DLQ topics |
| odm-dc-mcp | Python 3.13, FastMCP | masked, audited, read-only tools | approve, decline, change a limit, deploy without a human token |
| ODM | IBM ODM 9.5 dev image | rules, versions, the decision | persist rules across restarts (Derby, dev image) |

Detail: [components.md](components.md), [sequence-application.md](sequence-application.md), [trust-boundaries.md](trust-boundaries.md).
