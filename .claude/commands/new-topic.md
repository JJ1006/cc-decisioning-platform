---
description: Add a Kafka topic with contract, ACL, Logstash route and tests
argument-hint: <topic name cc.<noun>.<verb>.v1> <producer> <consumer>
---

Use the `add-kafka-flow` skill for the topic and flow: `$ARGUMENTS`.

Work through the skill's steps in order (topic table, contract + example, ACL table + `scripts/gen-k8s-kafka.py` + `scripts/helm-sync.sh`, code with idempotent producer / manual commit / retry / DLQ, Logstash route and fixture, data classification, Testcontainers test).
Run the privacy-reviewer and security-reviewer agents on the diff. Finish with `make verify` and show its tail. Do not merge.
