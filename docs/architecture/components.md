# C4: components

## decision-orchestrator

The only service that asks ODM for a decision. One input topic, three output topics, one audit topic.

```mermaid
flowchart LR
  IN[("cc.applications.submitted.v1")]
  subgraph orch["decision-orchestrator"]
    SL["<b>SubmittedListener</b><br/>@KafkaListener, manual ack,<br/>read_committed"]
    RC["<b>ResClient</b><br/>mTLS + basic auth, timeouts,<br/>Resilience4j circuit breaker + retry<br/>(logs every state change)"]
    RP["<b>ResProperties</b><br/>URL, ruleset path, credentials"]
    RD["<b>RuleDecision</b><br/>outcome, limit, product,<br/>APR tier, reason codes"]
    DR["<b>DecisionRouter</b><br/>build ApplicantSummary (bands only),<br/>ApplicantToken, DecisionInputs,<br/>choose topic by outcome"]
    EH["<b>ErrorHandling</b> (common)<br/>backoff, .retry, DlqRecoverer"]
  end
  RES["ODM RES<br/>ccApproval"]
  OUT[("cc.decisions.approved / declined / referred.v1")]
  AUD[("cc.audit.v1")]
  RETRY[("*.retry / *.dlq")]

  IN --> SL --> RC
  RP --> RC
  RC -- "POST /DecisionService/rest/..." --> RES
  RES --> RC --> RD --> DR
  DR -- "idempotent producer, acks=all, transaction" --> OUT
  DR --> AUD
  SL -. "poison message or retries exhausted" .-> EH --> RETRY
  RC -. "circuit open" .-> EH
```

Rules of the box: the listener acknowledges only after the decision events are committed; the same `applicationId` always lands on the same partition; the decision event carries the ruleset version and deployment time; exact inputs go to the restricted `cc-decision-inputs` index only (`applicantSummary` has bands).

## odm-dc MCP server

Our MCP server for AI agents. Every tool passes through the same guard; nothing in it can write a decision.

```mermaid
flowchart TB
  CLIENT["MCP client (Claude Code / Desktop, inspector)<br/>stdio locally, streamable HTTP through the gateway"]
  subgraph mcp["odm-dc-mcp-server"]
    SRV["<b>server.py</b><br/>MCPServer, transport, identity from X-Consumer<br/>(believed only from CN=apisix)"]
    TL["<b>tools.py</b><br/>decision_get, decision_explain, decision_inputs,<br/>decisions_search, stats_*, whatif,<br/>dc_get_rules, dc_list_*, dc_run_tests, dc_build_report,<br/>dc_deploy_ruleapp (refused by default)"]
    GD["<b>guard.py</b><br/>rate limit, size cap 20 KB,<br/>result envelope {notice, tool, data}"]
    UT["<b>untrusted.py</b><br/>strip hidden characters, cap length,<br/>wrap free text as &lt;data&gt;"]
    MK["<b>masking.py</b><br/>Presidio + patterns:<br/>SIN, e-mail, phone, card, postal code"]
    AU["<b>audit.py</b><br/>one cc.audit.mcp.v1 event per call,<br/>argument names and hashes only,<br/>FAIL CLOSED if Kafka is down"]
    CL["<b>clients.py / mtls.py</b><br/>one client certificate per backend"]
  end
  GWAPI["APISIX (decisions, statistics)<br/>role AGENT: GET only"]
  DCAPI["Decision Center REST (read-only)"]
  RESAPI["RES (what-if simulation)"]
  K[("cc.audit.v1")]

  CLIENT --> SRV --> GD --> TL
  TL --> CL
  CL -- "mTLS mcp-agent" --> GWAPI
  CL -- "mTLS mcp-res + ODM user" --> DCAPI
  CL -- "mTLS mcp-res + ODM user" --> RESAPI
  TL --> UT --> MK --> GD
  GD --> AU --> K
```

The guardrail is structural, not a prompt: there is no tool that approves, declines or edits a decision, and `dc_deploy_ruleapp` additionally needs a token only a human holds ([ADR-0007](../adr/0007-llm-never-decides.md), [ADR-0012](../adr/0012-agent-access.md)). The eval proof is in [docs/evals.md](../evals.md) (23/23 safety cases).
