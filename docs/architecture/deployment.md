# Deployment views

One set of images and one set of configuration files, three ways to run them. Sizing: [README](../../README.md#compose-profiles-8-gb-mac-never-run-everything-at-once).

## 1. Docker Compose (the laptop)

```mermaid
flowchart TB
  dev(["developer: make up PROFILE=core,analytics,edge"])
  subgraph host["Docker Desktop VM (7 GB), one bridge network"]
    subgraph core["profile core"]
      K[("Kafka KRaft :9093 mTLS")]
      ODM["ODM dev image :9453 / :9443"]
      ORCH["decision-orchestrator"]
      GEN["txn-generator"]
      NOT["notification-svc"]
      ES[("Elasticsearch")]
      LS["Logstash"]
      FB["Filebeat"]
    end
    subgraph analytics["profile analytics"]
      KIB["Kibana :5601"]
      KUI["kafka-ui 127.0.0.1:8080"]
      QA["decision-query-api"]
    end
    subgraph edgep["profile edge"]
      GW["APISIX :8443 (only published API port)"]
      AA["application-api"]
    end
    subgraph obsp["profile obs"]
      GR["Grafana :3000"]
      PR["Prometheus, Alertmanager"]
      TM["Tempo, OTel collector"]
    end
    subgraph mcpp["profile mcp"]
      MD["odm-dc-mcp"]
      MR["odm-res-mcp (official IBM server)"]
    end
  end
  pki["certificates from make pki<br/>(gitignored, mounted read-only)"]
  dev --> GW
  pki -.-> host
```

Start/stop, profiles and memory limits (`mem_limit` on every service): [local-setup](../runbooks/local-setup.md). Never run everything at once on 8 GB.

## 2. kind with Argo CD (GitOps rehearsal)

```mermaid
flowchart LR
  subgraph git["GitHub repository"]
    main["main"]
    tag["tag vX.Y.Z"]
  end
  subgraph ghcr["GHCR"]
    img["signed multi-arch images + SBOM"]
  end
  subgraph kind["kind cluster (PROFILE=lite 7 GB, full 12 GB+)"]
    argo["Argo CD<br/>app-of-apps, sync waves"]
    subgraph ops["operators (first waves)"]
      cm["cert-manager"]
      sz["Strimzi"]
      eck["ECK"]
    end
    infra["cc-infra<br/>Kafka, Elasticsearch, Kibana,<br/>ClusterIssuer from our CA, NetworkPolicies"]
    plat["cc-platform<br/>gateway, services, ODM, Logstash, observability"]
  end
  main -- "watched" --> argo
  img --> plat
  argo --> ops --> infra --> plat
  tag -. "promotion PR bumps the image tag" .-> main
```

`kind` takes the laptop, so the compose stack must be down first. Runbook: [gitops](../runbooks/gitops.md); decisions: [ADR-0013](../adr/0013-kubernetes-deployment.md), [ADR-0014](../adr/0014-gitops-argocd.md). CI proves it on every pull request labelled `kind` (`kind-smoke`).

## 3. OpenShift (the target)

```mermaid
flowchart TB
  subgraph git["GitHub"]
    tag["release tag vX.Y.Z"]
  end
  subgraph ocp["OpenShift 4.14+ (restricted-v2 SCC, arbitrary UID)"]
    argo["OpenShift GitOps (Argo CD)<br/>MANUAL sync gate"]
    route["passthrough Route :443<br/>(keeps the client certificate)"]
    gw["apisix"]
    svc["application-api, query-api, orchestrator,<br/>notification-svc, odm-dc-mcp"]
    odm["ODM: dev image here;<br/>IBM operator or chart in production"]
    infra["Strimzi Kafka, ECK Elasticsearch + Kibana,<br/>cert-manager, default-deny NetworkPolicy"]
    obs["Prometheus, Tempo,<br/>Grafana via re-encrypt Route"]
  end
  tag --> argo
  argo --> gw & svc & odm & infra & obs
  route --> gw --> svc
```

Never synced on a real cluster yet: the manifests are rendered (`make ocp-render`), schema-checked and policy-linted. Honest status in [limitations](../limitations.md).

## Differences that matter

| | Compose | kind + Argo CD | OpenShift |
|---|---|---|---|
| Certificates | `make pki` files | cert-manager signs with the same CA; Kafka uses Strimzi's own CAs | same as kind; operator `startingCSV` not yet pinned |
| Kafka | Apache Kafka 3.9.2 | Strimzi, Kafka 4.x | Strimzi, Kafka 4.x |
| Secrets | generated, gitignored files | `scripts/k8s-secrets.sh` | by hand; External Secrets is backlog |
| Gateway exposure | published port 8443 | NodePort 30443 | passthrough Route |
| Pod security | container defaults | `restricted` (Filebeat namespace `privileged`) | SCC; Filebeat off |
| Promotion | none | automatic from `main` | manual sync of a release tag |
