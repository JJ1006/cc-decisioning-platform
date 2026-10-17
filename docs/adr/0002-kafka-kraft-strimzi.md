# ADR-0002: Kafka in KRaft mode, Strimzi on Kubernetes

* Status: accepted
* Date: 2026-10-17

## Context and problem statement

We need an event backbone without ZooKeeper that runs in Compose and on OpenShift.

## Decision

Apache Kafka 3.9+ in KRaft mode; Compose uses a single node, Kubernetes/OCP uses Strimzi operator with KafkaUser/KafkaTopic CRs.

## Consequences

Declarative topics and ACLs via GitOps; single-node compose is not HA.
