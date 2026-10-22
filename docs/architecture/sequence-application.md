# Sequence: one application, end to end

A partner submits one application; the platform decides it, tells the applicant, makes it visible to analysts, and answers the status question. Every arrow between boxes is mutual TLS (the elastic hops use TLS plus a least-privilege user). Topic names: [kafka-topics](../kafka-topics.md).

```mermaid
sequenceDiagram
  autonumber
  actor P as Partner (cert CN=demo-partner)
  participant GW as APISIX gateway
  participant AA as application-api
  participant K as Kafka
  participant O as decision-orchestrator
  participant R as ODM RES (ccApproval)
  participant N as notification-svc
  participant L as Logstash
  participant E as Elasticsearch
  participant Q as decision-query-api

  P->>GW: POST /v1/applications (Idempotency-Key)
  Note over GW: client cert verified against our CA, CN to role PARTNER,<br/>quota and spike arrest, then X-Consumer / X-Role set from the certificate
  GW->>AA: mTLS (CN=apisix)
  AA->>K: cc.applications.submitted.v1 (key = applicationId, idempotent, transactional)
  AA-->>P: 202 Accepted {applicationId}
  K->>O: consume (read_committed)
  O->>R: POST ccApproval (mTLS + basic auth, circuit breaker)
  R-->>O: outcome, limit, product, APR tier, reason codes, ruleset version
  O->>K: cc.decisions.approved / declined / referred.v1 + cc.audit.v1 (same transaction as the offset)
  par notices
    K->>N: consume decision
    N->>K: cc.notifications.v1 (plain-language reasons, opaque recipient)
  and analytics
    K->>L: decisions, notifications, audit, dlq
    L->>E: masked documents to cc-decisions, cc-audit, cc-notifications, cc-dlq
    L->>E: exact inputs to cc-decision-inputs (restricted index)
  end
  Note over E: Kibana reads cc-decisions (analyst role) and shows executive, risk and fairness dashboards
  P->>GW: GET /v1/applications/{id}/status
  GW->>AA: mTLS
  AA->>Q: mTLS (CN=application-api, role SERVICE)
  Q->>E: read alias (user decision-query-api, read only)
  E-->>Q: decision document
  Q-->>AA: status
  AA-->>P: 200 {status: DECIDED, outcome, reasons in plain words}
```

## When something goes wrong

| Step | Failure | What happens |
|---|---|---|
| 3 | Kafka down | `application-api` cannot publish and does not accept; nothing is acknowledged that could be lost (chaos drill: acknowledged == decided + dead-lettered) |
| 6 | ODM down or slow | retries with backoff, then the circuit breaker opens (logged on every state change); records go to `.retry`, then `cc.applications.submitted.v1.dlq`; an alert fires ([DeadLetterRate](../runbooks/alerts/DeadLetterRate.md)) |
| 6 | ruleset missing after an ODM restart | RES answers 404; dead letters; `make up` runs `scripts/ensure-rules.sh` |
| 8 | poison message (bad JSON, contract violation) | skips retries, goes straight to the dead letter topic |
| 11 | Logstash killed | its persistent queue and the Kafka offsets carry the rest (chaos drill) |

A decision that needs a human (REFER) is later settled by an underwriter through `application-api` (`cc.decisions.manual.v1`); the AI copilot may explain it through the MCP server but cannot record or change it ([underwriter-copilot](../underwriter-copilot.md)).
