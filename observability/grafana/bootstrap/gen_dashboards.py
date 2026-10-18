#!/usr/bin/env python3
"""Generates the Grafana dashboards in observability/grafana/dashboards/*.json (provisioned as code).

The JSON files are what Grafana loads. They can be edited in the Grafana UI (Export > Save to file) and committed; this generator is the
starting point and keeps the eight dashboards consistent (same colours, units, layout, descriptions).

    python3 observability/grafana/bootstrap/gen_dashboards.py
"""

import json
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "dashboards"
PROM = {"type": "prometheus", "uid": "prometheus"}
TEMPO = {"type": "tempo", "uid": "tempo"}

GREEN, RED, AMBER, BLUE, PURPLE, GREY = (
    "green",
    "red",
    "orange",
    "blue",
    "purple",
    "text",
)


class Dash:
    def __init__(self, uid, title, description, tags=None, time_from="now-1h"):
        self.uid, self.title, self.description = uid, title, description
        self.tags = ["maple-northern"] + (tags or [])
        self.time_from = time_from
        self.panels = []
        self.next_id = 1
        self.x = self.y = self.row_h = 0

    def _place(self, w, h):
        if self.x + w > 24:
            self.x = 0
            self.y += self.row_h
            self.row_h = 0
        pos = {"x": self.x, "y": self.y, "w": w, "h": h}
        self.x += w
        self.row_h = max(self.row_h, h)
        return pos

    def row(self, title):
        self.x = 0
        self.y += self.row_h
        self.row_h = 0
        self.panels.append(
            {
                "id": self._id(),
                "type": "row",
                "title": title,
                "collapsed": False,
                "gridPos": {"x": 0, "y": self.y, "w": 24, "h": 1},
                "panels": [],
            }
        )
        self.y += 1

    def _id(self):
        i = self.next_id
        self.next_id += 1
        return i

    def add(self, panel, w, h):
        panel["id"] = self._id()
        panel["gridPos"] = self._place(w, h)
        self.panels.append(panel)

    def timeseries(
        self,
        title,
        description,
        targets,
        unit="short",
        w=8,
        h=8,
        stack=False,
        thresholds=None,
        decimals=None,
        ds=PROM,
        soft_max=None,
    ):
        defaults = {
            "unit": unit,
            "custom": {
                "drawStyle": "line",
                "lineWidth": 1,
                "fillOpacity": 18 if stack else 8,
                "showPoints": "never",
                "stacking": {"mode": "normal" if stack else "none"},
                "spanNulls": True,
            },
        }
        if decimals is not None:
            defaults["decimals"] = decimals
        if soft_max is not None:
            defaults["custom"]["axisSoftMax"] = soft_max
        if thresholds:
            defaults["thresholds"] = {
                "mode": "absolute",
                "steps": [{"color": GREEN, "value": None}]
                + [{"color": c, "value": v} for v, c in thresholds],
            }
            defaults["custom"]["thresholdsStyle"] = {"mode": "line"}
        self.add(
            {
                "type": "timeseries",
                "title": title,
                "description": description,
                "datasource": ds,
                "fieldConfig": {"defaults": defaults, "overrides": []},
                "options": {
                    "legend": {
                        "displayMode": "table" if len(targets) > 4 else "list",
                        "placement": "bottom",
                        "calcs": ["lastNotNull"] if len(targets) > 4 else [],
                    },
                    "tooltip": {"mode": "multi", "sort": "desc"},
                },
                "targets": [_t(i, e, l) for i, (e, l) in enumerate(targets)],
            },
            w,
            h,
        )

    def stat(
        self,
        title,
        description,
        expr,
        unit="short",
        w=4,
        h=4,
        thresholds=None,
        color=BLUE,
        decimals=None,
        mappings=None,
        instant=True,
    ):
        steps = (
            [{"color": color, "value": None}]
            if not thresholds
            else [{"color": thresholds[0][1], "value": None}]
            + [{"color": c, "value": v} for v, c in thresholds[1:]]
        )
        d = {
            "unit": unit,
            "thresholds": {"mode": "absolute", "steps": steps},
            "color": {"mode": "thresholds"},
        }
        if decimals is not None:
            d["decimals"] = decimals
        if mappings:
            d["mappings"] = mappings
        self.add(
            {
                "type": "stat",
                "title": title,
                "description": description,
                "datasource": PROM,
                "fieldConfig": {"defaults": d, "overrides": []},
                "options": {
                    "reduceOptions": {
                        "calcs": ["lastNotNull"],
                        "fields": "",
                        "values": False,
                    },
                    "colorMode": "background",
                    "graphMode": "none",
                    "textMode": "auto",
                },
                "targets": [
                    {**_t(0, expr, ""), "instant": instant, "range": not instant}
                ],
            },
            w,
            h,
        )

    def table(
        self, title, description, expr, w=12, h=8, unit="short", hide=None, ds=PROM
    ):
        overrides = [
            {
                "matcher": {"id": "byName", "options": n},
                "properties": [{"id": "custom.hidden", "value": True}],
            }
            for n in (hide or [])
        ]
        self.add(
            {
                "type": "table",
                "title": title,
                "description": description,
                "datasource": ds,
                "fieldConfig": {"defaults": {"unit": unit}, "overrides": overrides},
                "options": {"showHeader": True},
                "transformations": [
                    {"id": "organize", "options": {"excludeByName": {"Time": True}}}
                ],
                "targets": [
                    {
                        **_t(0, expr, ""),
                        "instant": True,
                        "range": False,
                        "format": "table",
                    }
                ],
            },
            w,
            h,
        )

    def nodegraph(self, title, description, w=12, h=10):
        self.add(
            {
                "type": "nodeGraph",
                "title": title,
                "description": description,
                "datasource": TEMPO,
                "targets": [
                    {"refId": "A", "queryType": "serviceMap", "datasource": TEMPO}
                ],
            },
            w,
            h,
        )

    def traces(self, title, description, traceql, w=12, h=10):
        self.add(
            {
                "type": "table",
                "title": title,
                "description": description,
                "datasource": TEMPO,
                "targets": [
                    {
                        "refId": "A",
                        "datasource": TEMPO,
                        "queryType": "traceql",
                        "query": traceql,
                        "limit": 20,
                        "tableType": "traces",
                    }
                ],
            },
            w,
            h,
        )

    def write(self):
        OUT.mkdir(parents=True, exist_ok=True)
        doc = {
            "uid": self.uid,
            "title": self.title,
            "description": self.description,
            "tags": self.tags,
            "timezone": "browser",
            "schemaVersion": 39,
            "version": 1,
            "editable": True,
            "graphTooltip": 1,
            "refresh": "30s",
            "time": {"from": self.time_from, "to": "now"},
            "templating": {"list": []},
            "annotations": {"list": []},
            "links": [
                {
                    "title": "Observability guide",
                    "type": "link",
                    "url": "https://localhost:3000/dashboards?tag=maple-northern",
                    "icon": "dashboard",
                },
            ],
            "panels": self.panels,
        }
        path = OUT / f"{self.uid}.json"
        path.write_text(json.dumps(doc, indent=2, sort_keys=False) + "\n")
        print(
            f"{path.name}: {sum(1 for p in self.panels if p['type'] != 'row')} panels"
        )


def _t(i, expr, legend):
    t = {
        "refId": chr(65 + i),
        "datasource": PROM,
        "expr": expr,
        "legendFormat": legend,
        "editorMode": "code",
    }
    return t


SVC = 'job="spring"'
SERVER = 'span_kind="SPAN_KIND_SERVER"'

# =============================================================================================== Platform overview
d = Dash(
    "cc-overview",
    "Platform overview",
    "Golden signals per service (from the traces), what is up, the service graph and the business pulse.",
    ["overview"],
)
d.row("Is it up")
d.stat(
    "Targets up",
    "Prometheus targets that answer their scrape.",
    "sum(up)",
    w=4,
    thresholds=[(None, RED), (1, GREEN)],
    color=GREEN,
)
d.stat(
    "Targets down",
    "Targets Prometheus cannot scrape. Any number above 0 pages (TargetDown).",
    "count(up == 0) or vector(0)",
    w=4,
    thresholds=[(None, GREEN), (1, RED)],
    color=GREEN,
)
d.stat(
    "Requests per second (gateway)",
    "Requests the API gateway serves.",
    "slo:gateway_requests:rate5m",
    unit="reqps",
    w=4,
    decimals=2,
)
d.stat(
    "Gateway error ratio",
    "Share of gateway requests answered with 5xx (limit 1%).",
    "slo:gateway_error_ratio:rate5m or vector(0)",
    unit="percentunit",
    w=4,
    decimals=2,
    thresholds=[(None, GREEN), (0.005, AMBER), (0.01, RED)],
    color=GREEN,
)
d.stat(
    "Decision p95",
    "p95 of the ODM call (limit 300 ms).",
    "slo:decision_latency_seconds:p95_5m",
    unit="s",
    w=4,
    decimals=3,
    thresholds=[(None, GREEN), (0.2, AMBER), (0.3, RED)],
    color=GREEN,
)
d.stat(
    "Approval rate",
    "Approved divided by all automated decisions, last hour.",
    'sum(increase(decisions_total{outcome="APPROVE"}[1h])) / sum(increase(decisions_total[1h]))',
    unit="percentunit",
    w=4,
    decimals=1,
    color=GREEN,
)
d.row("Golden signals per service (spans)")
d.timeseries(
    "Request rate by service",
    "Server spans per second, by service (traces_span_metrics).",
    [
        (
            f"sum by (service_name) (rate(traces_span_metrics_calls_total{{{SERVER}}}[5m]))",
            "{{service_name}}",
        )
    ],
    unit="reqps",
)
d.timeseries(
    "Error ratio by service",
    "Server spans that ended in error, divided by all.",
    [
        (
            f'sum by (service_name) (rate(traces_span_metrics_calls_total{{{SERVER},status_code="STATUS_CODE_ERROR"}}[5m])) / sum by (service_name) (rate(traces_span_metrics_calls_total{{{SERVER}}}[5m]))',
            "{{service_name}}",
        )
    ],
    unit="percentunit",
    thresholds=[(0.01, RED)],
    soft_max=0.05,
)
d.timeseries(
    "Duration p95 by service",
    "p95 of server spans by service.",
    [
        (
            f"histogram_quantile(0.95, sum by (le, service_name) (rate(traces_span_metrics_duration_milliseconds_bucket{{{SERVER}}}[5m])))",
            "{{service_name}}",
        )
    ],
    unit="ms",
)
d.row("Who calls whom")
d.nodegraph(
    "Service graph",
    "Services and the calls between them, from the traces (Tempo, spanmetrics). Open Explore > Tempo for a single trace.",
    w=12,
    h=10,
)
d.timeseries(
    "Calls per second between services",
    "Service graph edges: client to server requests per second.",
    [
        (
            "sum by (client, server) (rate(traces_service_graph_request_total[5m]))",
            "{{client}} -> {{server}}",
        )
    ],
    unit="reqps",
    w=12,
    h=10,
)
d.row("Business pulse")
d.timeseries(
    "Decisions per minute by outcome",
    "Automated decisions (orchestrator counter decisions_total).",
    [("sum by (outcome) (rate(decisions_total[1m])) * 60", "{{outcome}}")],
    stack=True,
    w=12,
)
d.timeseries(
    "Consumer lag",
    "Messages behind, all consumer groups (alert above 500 for 5 minutes).",
    [
        (
            'sum by (consumergroup) (kafka_consumergroup_lag{consumergroup!~"console-consumer.*|count-.*"})',
            "{{consumergroup}}",
        )
    ],
    w=12,
    thresholds=[(500, RED)],
)
d.write()

# =============================================================================================== Kafka
d = Dash(
    "cc-kafka",
    "Kafka",
    "Consumer lag, throughput and dead letters per group and topic (kafka-exporter).",
    ["kafka"],
)
d.row("Lag")
d.stat(
    "Total consumer lag",
    "Messages behind, all consumer groups of the platform (alert above 500 for 5 minutes).",
    'sum(kafka_consumergroup_lag{consumergroup!~"console-consumer.*|count-.*"})',
    w=6,
    thresholds=[(None, GREEN), (200, AMBER), (500, RED)],
    color=GREEN,
)
d.stat(
    "Dead letters (all time)",
    "Messages on all *.dlq topics.",
    'sum(kafka_topic_partition_current_offset{topic=~".*\\\\.dlq"}) or vector(0)',
    w=6,
    thresholds=[(None, GREEN), (1, AMBER)],
    color=GREEN,
)
d.stat(
    "Consumer groups",
    "Groups with committed offsets.",
    "count(count by (consumergroup) (kafka_consumergroup_current_offset))",
    w=6,
)
d.stat(
    "Topics",
    "Topics the exporter sees.",
    "count(count by (topic) (kafka_topic_partitions))",
    w=6,
)
d.timeseries(
    "Lag by group and topic",
    "Messages behind per consumer group and topic.",
    [
        (
            'sum by (consumergroup, topic) (kafka_consumergroup_lag{consumergroup!~"console-consumer.*|count-.*"})',
            "{{consumergroup}} / {{topic}}",
        )
    ],
    w=24,
    h=9,
    thresholds=[(500, RED)],
)
d.row("Throughput")
d.timeseries(
    "Messages per second by topic",
    "Produce rate: growth of the topic offsets.",
    [
        (
            'sum by (topic) (rate(kafka_topic_partition_current_offset{topic!~"__.*"}[1m]))',
            "{{topic}}",
        )
    ],
    unit="ops",
    w=12,
    h=9,
)
d.timeseries(
    "Consumption per second by group",
    "Commit rate: growth of the committed offsets.",
    [
        (
            'sum by (consumergroup) (rate(kafka_consumergroup_current_offset{consumergroup!~"console-consumer.*|count-.*"}[1m]))',
            "{{consumergroup}}",
        )
    ],
    unit="ops",
    w=12,
    h=9,
)
d.row("Dead letters")
d.timeseries(
    "Dead letters per minute",
    "Increase of the *.dlq topic offsets.",
    [
        (
            'sum by (topic) (increase(kafka_topic_partition_current_offset{topic=~".*\\\\.dlq"}[1m]))',
            "{{topic}}",
        )
    ],
    w=12,
)
d.timeseries(
    "Dead letter topics, total",
    "Messages on each dead letter topic.",
    [
        (
            'sum by (topic) (kafka_topic_partition_current_offset{topic=~".*\\\\.dlq"})',
            "{{topic}}",
        )
    ],
    w=12,
)
d.write()

# =============================================================================================== JVM
d = Dash(
    "cc-jvm",
    "JVM",
    "Heap, garbage collection, threads and CPU of the five Spring Boot services (Micrometer).",
    ["jvm"],
)
d.row("Heap")
d.timeseries(
    "Heap used ratio",
    "Used heap divided by the maximum (alert above 85%).",
    [("job:jvm_heap_used_ratio", "{{service}}")],
    unit="percentunit",
    w=12,
    h=9,
    thresholds=[(0.85, RED)],
    soft_max=1,
)
d.timeseries(
    "Heap used",
    "Bytes of heap in use.",
    [(f'sum by (service) (jvm_memory_used_bytes{{{SVC},area="heap"}})', "{{service}}")],
    unit="bytes",
    w=12,
    h=9,
)
d.row("Garbage collection and threads")
d.timeseries(
    "GC pause time",
    "Seconds spent in GC pauses per second.",
    [
        (
            f"sum by (service) (rate(jvm_gc_pause_seconds_sum{{{SVC}}}[5m]))",
            "{{service}}",
        )
    ],
    unit="s",
    w=8,
)
d.timeseries(
    "Live threads",
    "Threads alive.",
    [(f"jvm_threads_live_threads{{{SVC}}}", "{{service}}")],
    w=8,
)
d.timeseries(
    "Process CPU",
    "CPU used by the process, share of one core.",
    [(f"process_cpu_usage{{{SVC}}}", "{{service}}")],
    unit="percentunit",
    w=8,
)
d.row("Memory and classes")
d.timeseries(
    "Non-heap used",
    "Metaspace, code cache and friends.",
    [
        (
            f'sum by (service) (jvm_memory_used_bytes{{{SVC},area="nonheap"}})',
            "{{service}}",
        )
    ],
    unit="bytes",
    w=12,
)
d.timeseries(
    "Loaded classes",
    "Classes loaded in the JVM.",
    [(f"jvm_classes_loaded_classes{{{SVC}}}", "{{service}}")],
    w=12,
)
d.write()

# =============================================================================================== API SLO
d = Dash(
    "cc-api-slo",
    "API SLO",
    "Availability and latency of the API at the gateway against the objective: 99.5% of requests are not a 5xx (error budget 0.5%).",
    ["slo"],
)
d.row("Objective")
d.stat(
    "Availability (5m)",
    "Requests that are not a 5xx, last 5 minutes. Objective 99.5%.",
    "1 - (slo:gateway_error_ratio:rate5m or vector(0))",
    unit="percentunit",
    w=6,
    decimals=3,
    thresholds=[(None, RED), (0.995, GREEN)],
    color=GREEN,
)
d.stat(
    "Error budget burn (1h)",
    "1 means the budget is consumed exactly at the pace the objective allows; above 1 it runs out early.",
    "slo:gateway_error_budget_burn:rate1h",
    w=6,
    decimals=2,
    thresholds=[(None, GREEN), (1, AMBER), (2, RED)],
    color=GREEN,
)
d.stat(
    "Latency p95",
    "Gateway request latency p95.",
    "slo:gateway_latency_seconds:p95_5m / 1000",
    unit="s",
    w=6,
    decimals=3,
    color=BLUE,
)
d.stat(
    "Latency p99",
    "Gateway request latency p99.",
    "slo:gateway_latency_seconds:p99_5m / 1000",
    unit="s",
    w=6,
    decimals=3,
    color=BLUE,
)
d.timeseries(
    "Error ratio and the objective",
    "5xx share of the gateway's requests; the red line is the 0.5% budget.",
    [
        ("slo:gateway_error_ratio:rate5m or vector(0)", "error ratio"),
        ("slo:gateway_error_ratio:rate1h or vector(0)", "error ratio (1h)"),
    ],
    unit="percentunit",
    w=12,
    h=9,
    thresholds=[(0.005, RED)],
)
d.timeseries(
    "Latency p95 / p99",
    "Request latency at the gateway.",
    [
        ("slo:gateway_latency_seconds:p95_5m / 1000", "p95"),
        ("slo:gateway_latency_seconds:p99_5m / 1000", "p99"),
    ],
    unit="s",
    w=12,
    h=9,
)
d.timeseries(
    "Requests by status",
    "Gateway requests per second by HTTP status.",
    [("sum by (code) (rate(apisix_http_status[1m]))", "{{code}}")],
    unit="reqps",
    stack=True,
    w=12,
)
d.timeseries(
    "Requests by route",
    "Gateway requests per second by route.",
    [("sum by (route) (rate(apisix_http_status[1m]))", "{{route}}")],
    unit="reqps",
    stack=True,
    w=12,
)
d.write()

# =============================================================================================== APISIX
d = Dash(
    "cc-apisix",
    "APISIX gateway",
    "The API gateway: traffic, status codes, latency, connections and bandwidth.",
    ["apisix"],
)
d.row("Traffic")
d.timeseries(
    "Requests per second by status code",
    "Per HTTP status.",
    [("sum by (code) (rate(apisix_http_status[1m]))", "{{code}}")],
    unit="reqps",
    stack=True,
    w=12,
    h=9,
)
d.timeseries(
    "429 (quota, spike arrest) and 403 (role)",
    "What the gateway refuses: rate limits and roles.",
    [
        (
            'sum(rate(apisix_http_status{code="429"}[1m])) or vector(0)',
            "429 too many requests",
        ),
        ('sum(rate(apisix_http_status{code="403"}[1m])) or vector(0)', "403 forbidden"),
    ],
    unit="reqps",
    w=12,
    h=9,
)
d.row("Latency")
d.timeseries(
    "Request latency p50 / p95 / p99",
    "Total time of a request through the gateway.",
    [
        (
            f'histogram_quantile({q}, sum by (le) (rate(apisix_http_latency_bucket{{type="request"}}[5m]))) / 1000',
            l,
        )
        for q, l in ((0.5, "p50"), (0.95, "p95"), (0.99, "p99"))
    ],
    unit="s",
    w=12,
    h=9,
)
d.timeseries(
    "Upstream latency p95 by route",
    "Time the services take, per route.",
    [
        (
            'histogram_quantile(0.95, sum by (le, route) (rate(apisix_http_latency_bucket{type="upstream"}[5m]))) / 1000',
            "{{route}}",
        )
    ],
    unit="s",
    w=12,
    h=9,
)
d.row("Connections and bandwidth")
d.timeseries(
    "Connections",
    "Nginx connections by state.",
    [("sum by (state) (apisix_nginx_http_current_connections)", "{{state}}")],
    w=8,
)
d.timeseries(
    "Bandwidth",
    "Bytes per second by direction.",
    [("sum by (type) (rate(apisix_bandwidth[1m]))", "{{type}}")],
    unit="Bps",
    w=8,
)
d.timeseries(
    "Shared dictionary use",
    "Gateway memory zones in use (bytes).",
    [
        (
            "sum by (name) (apisix_shared_dict_capacity_bytes - apisix_shared_dict_free_space_bytes)",
            "{{name}}",
        )
    ],
    unit="bytes",
    w=8,
)
d.write()

# =============================================================================================== Elasticsearch and Logstash
d = Dash(
    "cc-elk",
    "Elasticsearch and Logstash",
    "Heap, indexing and shards of Elasticsearch (elasticsearch-exporter) and the pipelines of Logstash (logstash-exporter).",
    ["elk"],
)
d.row("Elasticsearch")
d.stat(
    "Cluster status",
    "green 0, yellow 1, red 2.",
    'max(elasticsearch_cluster_health_status{color="red"}) * 2 + max(elasticsearch_cluster_health_status{color="yellow"})',
    w=4,
    thresholds=[(None, GREEN), (1, AMBER), (2, RED)],
    color=GREEN,
    mappings=[
        {
            "type": "value",
            "options": {
                "0": {"text": "green"},
                "1": {"text": "yellow"},
                "2": {"text": "red"},
            },
        }
    ],
)
d.stat(
    "Heap used",
    "Elasticsearch JVM heap used divided by max (alert above 85%).",
    'max(elasticsearch_jvm_memory_used_bytes{area="heap"}) / max(elasticsearch_jvm_memory_max_bytes{area="heap"})',
    unit="percentunit",
    w=4,
    thresholds=[(None, GREEN), (0.75, AMBER), (0.85, RED)],
    color=GREEN,
    decimals=1,
)
d.stat("Documents", "Documents in all indices.", "sum(elasticsearch_indices_docs)", w=4)
d.stat(
    "Active shards",
    "Shards of the cluster.",
    "max(elasticsearch_cluster_health_active_shards)",
    w=4,
)
d.stat(
    "Store size",
    "Bytes on disk.",
    "sum(elasticsearch_indices_store_size_bytes)",
    unit="bytes",
    w=4,
)
d.stat(
    "Unassigned shards",
    "Shards without a node.",
    "max(elasticsearch_cluster_health_unassigned_shards)",
    w=4,
    thresholds=[(None, GREEN), (1, AMBER)],
    color=GREEN,
)
d.timeseries(
    "Heap used",
    "JVM heap in use and its maximum.",
    [
        ('max(elasticsearch_jvm_memory_used_bytes{area="heap"})', "used"),
        ('max(elasticsearch_jvm_memory_max_bytes{area="heap"})', "max"),
    ],
    unit="bytes",
    w=8,
    h=8,
)
d.timeseries(
    "Indexing rate",
    "Documents indexed per second.",
    [("sum(rate(elasticsearch_indices_indexing_index_total[1m]))", "indexed")],
    unit="ops",
    w=8,
    h=8,
)
d.timeseries(
    "Search rate",
    "Queries per second.",
    [("sum(rate(elasticsearch_indices_search_query_total[1m]))", "queries")],
    unit="ops",
    w=8,
    h=8,
)
d.timeseries(
    "Documents per index",
    "Documents by index.",
    [("sum by (index) (elasticsearch_indices_docs)", "{{index}}")],
    w=12,
)
d.timeseries(
    "GC time",
    "Seconds in JVM garbage collection per second.",
    [("sum by (gc) (rate(elasticsearch_jvm_gc_collection_seconds_sum[5m]))", "{{gc}}")],
    unit="s",
    w=12,
)
d.row("Logstash")
d.timeseries(
    "Events in / out per pipeline",
    "Events per second entering and leaving each pipeline.",
    [
        (
            "sum by (pipeline) (rate(logstash_stats_pipeline_events_in[1m]))",
            "in {{pipeline}}",
        ),
        (
            "sum by (pipeline) (rate(logstash_stats_pipeline_events_out[1m]))",
            "out {{pipeline}}",
        ),
    ],
    unit="ops",
    w=12,
    h=9,
)
d.timeseries(
    "Events filtered per pipeline",
    "Events per second through the filters (masking).",
    [
        (
            "sum by (pipeline) (rate(logstash_stats_pipeline_events_filtered[1m]))",
            "{{pipeline}}",
        )
    ],
    unit="ops",
    w=12,
    h=9,
)
d.timeseries(
    "Queue size per pipeline",
    "Events waiting in the persistent queue.",
    [
        (
            "sum by (pipeline) (logstash_stats_pipeline_queue_events_count)",
            "{{pipeline}}",
        )
    ],
    w=8,
)
d.timeseries(
    "Logstash heap",
    "JVM heap used (percent).",
    [("logstash_stats_jvm_mem_heap_used_percent", "heap used")],
    unit="percent",
    w=8,
)
d.timeseries(
    "Dead letter queue",
    "Events in Logstash's dead letter queue (documents Elasticsearch refused).",
    [
        (
            "sum by (pipeline) (logstash_stats_pipeline_dead_letter_queue_size_in_bytes)",
            "{{pipeline}}",
        )
    ],
    unit="bytes",
    w=8,
)
d.write()

# =============================================================================================== ODM decision latency
d = Dash(
    "cc-odm",
    "ODM decision latency",
    "How long the rules call takes: the orchestrator's timer (res_call_seconds), the client spans of the traces, the circuit breaker and the slowest traces.",
    ["odm"],
)
d.row("Latency")
d.stat(
    "p95 (5m)",
    "p95 of the RES call. Alert above 300 ms for 5 minutes.",
    "slo:decision_latency_seconds:p95_5m",
    unit="s",
    w=6,
    decimals=3,
    thresholds=[(None, GREEN), (0.2, AMBER), (0.3, RED)],
    color=GREEN,
)
d.stat(
    "p99 (5m)",
    "p99 of the RES call.",
    "slo:decision_latency_seconds:p99_5m",
    unit="s",
    w=6,
    decimals=3,
    color=BLUE,
)
d.stat(
    "Circuit breaker",
    "0 closed (healthy), 1 half open, 2 open (ODM calls are refused).",
    "max(res_circuitbreaker_state)",
    w=6,
    thresholds=[(None, GREEN), (1, AMBER), (2, RED)],
    color=GREEN,
    mappings=[
        {
            "type": "value",
            "options": {
                "0": {"text": "closed"},
                "1": {"text": "half open"},
                "2": {"text": "open"},
            },
        }
    ],
)
d.stat(
    "Calls per second",
    "RES calls per second.",
    "sum(rate(res_call_seconds_count[1m]))",
    unit="ops",
    w=6,
    decimals=1,
)
d.timeseries(
    "RES call latency p50 / p95 / p99",
    "Orchestrator timer res_call_seconds.",
    [
        (f"histogram_quantile({q}, sum by (le) (rate(res_call_seconds_bucket[5m])))", l)
        for q, l in ((0.5, "p50"), (0.95, "p95"), (0.99, "p99"))
    ],
    unit="s",
    w=12,
    h=9,
    thresholds=[(0.3, RED)],
)
d.timeseries(
    "Same call seen in the traces",
    "Client spans of the orchestrator towards ODM (spanmetrics): p95.",
    [
        (
            'histogram_quantile(0.95, sum by (le) (rate(traces_span_metrics_duration_milliseconds_bucket{service_name="decision-orchestrator", span_kind="SPAN_KIND_CLIENT", http_request_method="POST"}[5m]))) / 1000',
            "p95 client span",
        )
    ],
    unit="s",
    w=12,
    h=9,
)
d.timeseries(
    "Calls and failures",
    "RES calls per second, and the circuit breaker state.",
    [
        ("sum(rate(res_call_seconds_count[1m]))", "calls"),
        ("max(res_circuitbreaker_state)", "breaker state"),
    ],
    w=12,
)
d.timeseries(
    "Decision latency recorded by the orchestrator",
    "decision.latency timer (millisecond figure published in the events).",
    [
        (
            "histogram_quantile(0.95, sum by (le) (rate(decision_latency_seconds_bucket[5m])))",
            "p95",
        )
    ],
    unit="s",
    w=12,
)
d.row("ODM server (Liberty metrics, deploy/odm/metrics.xml)")
d.timeseries(
    "ODM heap used",
    "Liberty heap used divided by the maximum (JVM heap alert above 85%).",
    [
        (
            'memory_usedHeap_bytes{job="odm"} / memory_maxHeap_bytes{job="odm"}',
            "heap used",
        )
    ],
    unit="percentunit",
    w=6,
    soft_max=1,
    thresholds=[(0.85, RED)],
)
d.timeseries(
    "ODM process CPU",
    "CPU used by the ODM process.",
    [('cpu_processCpuLoad_percent{job="odm"} / 100', "cpu")],
    unit="percentunit",
    w=6,
)
d.timeseries(
    "ODM requests per second by route",
    "HTTPS requests to ODM (rule execution is /DecisionService).",
    [
        (
            'sum by (http_route) (rate(http_server_request_duration_seconds_count{job="odm",server_port="9453"}[1m]))',
            "{{http_route}}",
        )
    ],
    unit="reqps",
    w=6,
)
d.timeseries(
    "ODM mean request time by route",
    "Seconds per request, mean over 5 minutes.",
    [
        (
            'sum by (http_route) (rate(http_server_request_duration_seconds_sum{job="odm",server_port="9453"}[5m])) / sum by (http_route) (rate(http_server_request_duration_seconds_count{job="odm",server_port="9453"}[5m]))',
            "{{http_route}}",
        )
    ],
    unit="s",
    w=6,
)
d.row("Slowest traces")
d.traces(
    "Traces of the orchestrator slower than 300 ms",
    "TraceQL: click a trace id to open the waterfall (gateway, application-api, Kafka, orchestrator, ODM, notification).",
    '{ resource.service.name = "decision-orchestrator" && duration > 300ms }',
    w=24,
    h=9,
)
d.write()

# =============================================================================================== Business mini
d = Dash(
    "cc-business",
    "Business metrics (mini)",
    "A small business view from the orchestrator's Micrometer counters: no personal data, labels are outcome, product and province only. The full business analytics are in Kibana.",
    ["business"],
)
d.stat(
    "Approval rate (1h)",
    "Approved divided by all automated decisions, last hour.",
    'sum(increase(decisions_total{outcome="APPROVE"}[1h])) / sum(increase(decisions_total[1h]))',
    unit="percentunit",
    w=6,
    decimals=1,
    color=GREEN,
)
d.stat(
    "Referral rate (1h)",
    "Share sent to an underwriter.",
    'sum(increase(decisions_total{outcome="REFER"}[1h])) / sum(increase(decisions_total[1h]))',
    unit="percentunit",
    w=6,
    decimals=1,
    color=AMBER,
)
d.stat(
    "Decline rate (1h)",
    "Share declined.",
    'sum(increase(decisions_total{outcome="DECLINE"}[1h])) / sum(increase(decisions_total[1h]))',
    unit="percentunit",
    w=6,
    decimals=1,
    color=RED,
)
d.stat(
    "Decisions (1h)",
    "Automated decisions in the last hour.",
    "sum(increase(decisions_total[1h]))",
    w=6,
    decimals=0,
    color=BLUE,
)
d.timeseries(
    "Decisions per minute by outcome",
    "Automated decisions per minute.",
    [("sum by (outcome) (rate(decisions_total[1m])) * 60", "{{outcome}}")],
    stack=True,
    w=12,
    h=9,
)
d.timeseries(
    "Approval rate over time",
    "5-minute approval rate.",
    [
        (
            'sum(rate(decisions_total{outcome="APPROVE"}[5m])) / sum(rate(decisions_total[5m]))',
            "approval rate",
        )
    ],
    unit="percentunit",
    w=12,
    h=9,
    soft_max=1,
)
d.timeseries(
    "Decisions by province",
    "Decisions in the last hour per province.",
    [("sum by (province) (increase(decisions_total[1h]))", "{{province}}")],
    w=12,
)
d.timeseries(
    "Approved by product",
    "Approvals in the last hour per product.",
    [
        (
            'sum by (product) (increase(decisions_total{outcome="APPROVE"}[1h]))',
            "{{product}}",
        )
    ],
    w=12,
)
d.write()
