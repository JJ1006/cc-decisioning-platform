# Maple Northern Bank — Credit Card Decisioning Platform

[![CI](https://github.com/JJ1006/cc-decisioning-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/JJ1006/cc-decisioning-platform/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

A portfolio-grade, free-to-run demo of a credit-card approval system for a (fictitious) large Canadian bank.
**IBM ODM** makes the rule decisions, **Kafka** connects every step, decisions land in **Elasticsearch**, and **Kibana** shows business analytics.
An **APISIX** gateway with mTLS fronts the APIs; **Grafana** (metrics, traces) and the **ELK** stack (Logstash, Filebeat, Elasticsearch, Kibana: events, logs, masking) provide observability; **Helm + ArgoCD** deploy to kind or OpenShift; two **MCP servers** let AI agents explain decisions.

> **Principle:** ODM is the deterministic system of record. An LLM never makes or overrides a credit decision.
>
> ⚠ **ODM licence:** IBM ODM for Developers is *not* open source and is for development/evaluation only. It is pulled at runtime and never redistributed. See [NOTICE](NOTICE).
>
> All data is synthetic. "Maple Northern Bank" is fictitious.

## Architecture

```mermaid
flowchart LR
  C[Clients / Partners] -- mTLS --> GW[APISIX gateway]
  GW -- mTLS --> AA[application-api]
  GW -- mTLS --> QA[decision-query-api]
  AA --> K1[(cc.applications.submitted.v1)]
  GEN[txn-generator] --> K1
  K1 --> ORCH[decision-orchestrator]
  ORCH -- mTLS --> RES[ODM RES: ccApproval]
  ORCH --> K2[(approved / declined / referred)]
  K2 --> NOT[notification-svc]
  K2 --> LS[Logstash: mask PII]
  LS --> ES[(Elasticsearch)] --> KIB[Kibana]
  FB[Filebeat] --> LS
  QA --> ES
  DC[Decision Center] -. RuleApp .-> RES
  MCP[MCP servers] --> GW
  OTEL[OTel] --> LGTM[Grafana: metrics + traces]
```

Full plan: [docs/PLAN.md](docs/PLAN.md). Decisions: [docs/adr](docs/adr).

## Quickstart

Prerequisites: Docker Desktop (6.5 GB RAM, Rosetta on), JDK 21, Maven, uv, pre-commit, gh.

```bash
make bootstrap                        # tools, Docker memory (6 GB or more), Rosetta
make pki                              # private CA and certificates (not committed)
make up PROFILE=core,analytics,edge   # creates the local env file on first use, waits until healthy
make demo                             # deploys the rules if missing, sends 1000 synthetic applications, checks counts
make test-api-docs                    # runs every curl example of docs/api/README.md
make verify                           # build, tests, contracts, Logstash pipeline tests, OpenAPI lint
```

`make help` lists every target. `make up` hints `run make down V=1` when it finds Elasticsearch indices with an older mapping.
Stop with `make down` (`make down V=1` also deletes the volumes).

## Compose profiles (8 GB Mac — never run everything at once)

| Profile | Contents | ~RAM |
|---|---|---|
| `core` | Kafka, ODM, orchestrator, generator, notifier, ES, Logstash, Filebeat | 5.5 GB |
| `analytics` | + Kibana, query-api | +1.0 GB |
| `edge` | + APISIX, application-api | +0.5 GB |
| `obs` | + otel-lgtm | +1.0 GB |
| `mcp` | 2 MCP servers | +0.2 GB |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).
