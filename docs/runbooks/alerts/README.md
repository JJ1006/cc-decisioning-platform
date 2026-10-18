# Alert runbooks

One runbook per Prometheus alert (infrastructure and SLO). Business alerts are Kibana rules: [docs/kibana.md](../../kibana.md#alert-rules).

| Alert | Severity | What |
|---|---|---|
| [KafkaConsumerLagHigh](KafkaConsumerLagHigh.md) | warning | A consumer group is more than 500 messages behind on a topic for 5 minutes (`kafka_consumergroup_lag`, kafka-exporter). |
| [DeadLetterRate](DeadLetterRate.md) | warning | Messages were written to a `*.dlq` topic in the last 5 minutes (`kafka_topic_partition_current_offset`). |
| [DecisionLatencyP95High](DecisionLatencyP95High.md) | warning | p95 of the ODM RES call (`res_call_seconds`, orchestrator) is above 300 ms for 5 minutes. |
| [ApiErrorRateHigh](ApiErrorRateHigh.md) | critical | More than 1% of the gateway's requests answered with 5xx for 5 minutes (at least 0.05 requests per second). |
| [TargetDown](TargetDown.md) | critical | Prometheus cannot scrape a target for 2 minutes (`up == 0`), or a TLS endpoint fails its blackbox probe for 3 minutes (EndpointProbeFailing). |
| [JvmHeapHigh](JvmHeapHigh.md) | warning | A Spring Boot service uses more than 85% of its maximum heap for 5 minutes. |
| [ElasticsearchHeapHigh](ElasticsearchHeapHigh.md) | warning | The Elasticsearch JVM heap is above 85% for 5 minutes (elasticsearch-exporter). |
| [CertificateExpiringSoon](CertificateExpiringSoon.md) | warning | The certificate presented by the gateway, ODM or Elasticsearch expires in under 14 days (`probe_ssl_earliest_cert_expiry`, blackbox-exporter). |

`EndpointProbeFailing` (a TLS probe fails for 3 minutes) uses the [TargetDown](TargetDown.md) runbook.
