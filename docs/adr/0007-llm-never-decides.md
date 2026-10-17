# ADR-0007: An LLM never decides or overrides a credit decision

* Status: accepted
* Date: 2026-10-17

## Context and problem statement

Credit decisions are regulated (OSFI E-23, FCAC adverse action).

## Decision

ODM is the deterministic system of record. Agents may explain, assist and draft; a human approves any change and records manual decisions via application-api.

## Consequences

Agent evals include refusal and prompt-injection tests.
