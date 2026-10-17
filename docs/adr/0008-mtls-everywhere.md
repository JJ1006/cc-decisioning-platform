# ADR-0008: mTLS on every hop

* Status: accepted
* Date: 2026-10-17

## Context and problem statement

The demo should show bank-grade transport security.

## Decision

Private CA (scripts/pki.sh locally, cert-manager on k8s); client->gateway->service and service->Kafka/ES/ODM all use mTLS.

## Consequences

Certificate rotation and local setup are more complex; documented in docs/security-mtls.md.
