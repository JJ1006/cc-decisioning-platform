# ADR-0004: Apache APISIX instead of Apigee

* Status: accepted
* Date: 2026-10-17

## Context and problem statement

Apigee is paid (60-day eval); Kong's mTLS is Enterprise-only.

## Decision

Use Apache APISIX (Apache-2.0) in standalone mode: mTLS, rate limits, API keys/JWT, Prometheus metrics.

## Consequences

docs/apigee-mapping.md maps concepts. Apigee-specific policies (JS callouts) do not port.
