# ADR-0013: How the platform is deployed on Kubernetes and OpenShift

* Status: accepted
* Date: 2026-10-18
* Refines: [ADR-0002](0002-kafka-kraft-strimzi.md), [ADR-0008](0008-mtls-everywhere.md), [ADR-0011](0011-gateway-identity.md)

## Context and problem statement

P10 moves the compose stack to Kubernetes (kind for development, OpenShift as the target) without a second set of configuration files, without weakening the
mutual TLS of every hop, and with pods that OpenShift's `restricted-v2` SCC (and Pod Security `restricted`) accept.

## Decision

* **One library chart, thin workload charts, one umbrella.** `cc-lib` renders deployment, service, configmap, certificate, hpa, pdb, networkpolicy, servicemonitor and
  route or ingress; each workload chart is values only. The compose configuration files (Logstash pipelines, APISIX routes, Liberty overrides, Elasticsearch
  roles and templates, `es-setup.sh`, dashboards, rules) are **copied into the charts by `scripts/helm-sync.sh`**, and CI fails on drift. Topics and users with
  ACLs are generated from the docs by `scripts/gen-k8s-kafka.py`. No file exists twice by hand.
* **Operators for stateful infrastructure** (`cc-infra`): Strimzi for Kafka (KRaft, TLS listener with client authentication, KafkaUser ACLs), ECK for
  Elasticsearch and Kibana, cert-manager for certificates. They are installed first by a cluster administrator; the charts only create the custom resources.
* **Two certificate authorities, on purpose.** cert-manager's `ClusterIssuer` uses the CA of `make pki` (the CA trust of the gateway, services, ODM, Elasticsearch and
  Grafana is unchanged). Kafka keeps **Strimzi's own CAs** so the operator can rotate broker and user certificates; the services therefore take their Kafka stores
  from the KafkaUser and cluster CA secrets. The price is a second password (`KAFKA_TRUSTSTORE_PASSWORD`) and PEM settings for the Python server, both done in
  configuration.
* **Pod security is the same everywhere**: non-root, no fixed UID/GID/fsGroup, all capabilities dropped, read-only root file system, seccomp RuntimeDefault, no
  service account token, default-deny network policy. Exceptions are listed, annotated and reviewed (`odm` writes `/config`; `filebeat` reads node logs and is off on
  OpenShift; `apisix` needs a numeric UID on plain Kubernetes).
* **The gateway is exposed untouched.** A NodePort on kind and a **passthrough** Route on OpenShift: terminating TLS in front would drop the client certificate that
  carries the consumer and role (ADR-0011). Kibana and Grafana, which have logins, use re-encrypt Routes or Ingress.
* **Own APISIX chart.** The upstream chart brings etcd and an ingress controller; the standalone, declarative gateway is the compose file as is.
* **ODM stays the dev image** (Derby, [ADR-0001](0001-odm-dev-licence.md)): enough for a lab, documented as not for production, where the IBM operator or chart replaces it.
* **Images are built once in CI**: multi-arch to GHCR, cosign keyless signature and a Syft SBOM attestation per image.

## Consequences

* Kafka is 4.3 on Kubernetes and 3.9.2 in compose (Strimzi 1.x). The Java 3.9 clients work with both; the local stack could move to a 4.x image later.
* A chart change in `cc-lib` reaches all workloads, which is the point and the risk: `helm lint`, `kubeconform --strict` and `kube-linter` run on three value sets in CI.
* The observability chart wraps upstream charts; the Elasticsearch, Logstash and blackbox exporters of compose, and the Kibana saved objects, are not on Kubernetes yet
  ([backlog](../backlog.md)).
