# ADR-0001: Use IBM ODM for Developers (not open source)

* Status: accepted
* Date: 2026-10-17

## Context and problem statement

The rules engine must be IBM ODM. ODM for Developers is free for dev/eval but is not OSS and not for production.

## Decision

Pull the ODM image at runtime (LICENSE=accept by the user), never redistribute it, document it in NOTICE and the README.

## Consequences

Everything else is open source. The demo cannot be used in production as-is. ODM is amd64-only, so it runs under emulation on Apple Silicon.
