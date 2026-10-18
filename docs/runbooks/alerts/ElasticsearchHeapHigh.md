# ElasticsearchHeapHigh

Severity: warning. Rule: `observability/prometheus/rules/alerts.yml`. Alertmanager UI: <http://localhost:9097>. Overview: [docs/observability.md](../../observability.md).

## What fired

The Elasticsearch JVM heap is above 85% for 5 minutes (elasticsearch-exporter).

## Impact

Indexing and searches slow down, then circuit breakers reject requests; Logstash backs up (KafkaConsumerLagHigh on `logstash-indexer`), Kibana and the query API time out.

## Look at

1. Grafana > Elasticsearch and Logstash: heap, indexing rate, documents, GC time.
2. Many indices or large aggregations: the dashboards' statistics queries against a big time range.
3. The heap is 640 MB of a 1.25 GB container (`ES_JAVA_OPTS`, `mem_limit`).

## Fix

* Narrow the time range of dashboards; delete old demo data (`make down V=1` for a clean slate); on a larger Docker VM raise `mem_limit` and `ES_JAVA_OPTS` together (heap at most half of the container).
