# JvmHeapHigh

Severity: warning. Rule: `observability/prometheus/rules/alerts.yml`. Alertmanager UI: <http://localhost:9097>. Overview: [docs/observability.md](../../observability.md).

## What fired

A Spring Boot service uses more than 85% of its maximum heap for 5 minutes.

## Impact

Long garbage collections, then slow responses and finally OutOfMemoryError (the container exits and restarts, `-XX:+ExitOnOutOfMemoryError`).

## Look at

1. Grafana > JVM: heap, GC pause time, threads for that service.
2. Is it load (a generator run) or a leak (heap never returns to its baseline after the load stops)?
3. `docker stats`: the container limit against the heap (`MaxRAMPercentage=70`).

## Fix

* Load: wait for the run to end. Leak or undersized: raise the service's `mem_limit` (heap is 70% of it), restart, and open an issue with a heap histogram (`jcmd <pid> GC.class_histogram`).
