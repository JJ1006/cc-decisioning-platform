#!/usr/bin/env python3
"""Generates observability/kibana/saved-objects.ndjson: data views, saved searches, Lens visualizations and the four dashboards of
the Kibana space maple-northern.

This is the BOOTSTRAP of the Kibana content. After the first import the ndjson is owned by Kibana: edit in the UI, then run
`make export-kibana` to write the exported objects back to observability/kibana/saved-objects.ndjson (that file is the source of truth;
re-running this generator would overwrite those edits).

    python3 observability/kibana/bootstrap/gen_saved_objects.py
"""

import json
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "saved-objects.ndjson"
KIBANA = "8.19.23"

GREEN, RED, AMBER, BLUE, GREY, PURPLE = (
    "#54B399",
    "#E7664C",
    "#F5A700",
    "#6092C0",
    "#98A2B3",
    "#9170B8",
)
OUTCOME_COLORS = {"APPROVE": GREEN, "DECLINE": RED, "REFER": AMBER}

objects = []


def dv(name):
    return f"mn-dv-{name}"


# --------------------------------------------------------------------------------------------------------------- data views
def data_view(index, title, runtime=None, time_field="@timestamp", formats=None):
    attrs = {
        "title": index,
        "name": title,
        "timeFieldName": time_field,
        "sourceFilters": "[]",
        "fields": "[]",
        "fieldAttrs": "{}",
        "runtimeFieldMap": json.dumps(runtime or {}),
        "fieldFormatMap": json.dumps(formats or {}),
        "typeMeta": "{}",
        "allowNoIndex": False,
    }
    objects.append(
        {
            "type": "index-pattern",
            "id": dv(index),
            "attributes": attrs,
            "references": [],
            "coreMigrationVersion": "8.8.0",
            "typeMigrationVersion": "8.0.0",
        }
    )


def outcome_flag(value):
    return {
        "type": "long",
        "script": {
            "source": f"emit(doc['payload.outcome'].size() > 0 && doc['payload.outcome'].value == '{value}' ? 1 : 0)"
        },
    }


data_view(
    "cc-decisions",
    "Decisions (cc-decisions)",
    runtime={
        "isApproved": outcome_flag("APPROVE"),
        "isDeclined": outcome_flag("DECLINE"),
        "isReferred": outcome_flag("REFER"),
        # buckets as sortable text: Lens interval buckets label themselves badly in bars, text buckets sort and read well
        "limitBand": {
            "type": "keyword",
            "script": {
                "source": "if (doc['payload.creditLimit'].size() == 0 || doc['payload.creditLimit'].value <= 0) return; "
                "long lo = ((long) (doc['payload.creditLimit'].value / 2500)) * 2500; "
                "String a = Long.toString(lo); while (a.length() < 5) { a = '0' + a; } "
                "String b = Long.toString(lo + 2499); while (b.length() < 5) { b = '0' + b; } emit(a + '-' + b);"
            },
        },
    },
)
data_view("cc-audit", "Audit events (cc-audit)")
data_view("cc-notifications", "Notifications (cc-notifications)")
data_view("cc-dlq", "Dead letters (cc-dlq)")
data_view("cc-logs", "Service logs (cc-logs)")
data_view("cc-alerts", "Alerts (cc-alerts)")

# ------------------------------------------------------------------------------------------------------------ Lens helpers
P = "payload."
S = P + "applicantSummary."


def kql(q):
    return {"query": q, "language": "kuery"}


def col_count(label, q=None, fmt=None):
    c = {
        "label": label,
        "dataType": "number",
        "operationType": "count",
        "isBucketed": False,
        "scale": "ratio",
        "sourceField": "___records___",
        "customLabel": True,
        "params": {"emptyAsNull": True},
    }
    if q:
        c["filter"] = kql(q)
    if fmt:
        c["params"]["format"] = fmt
    return c


def col_metric(op, field, label, q=None, fmt=None, **params):
    c = {
        "label": label,
        "dataType": "number",
        "operationType": op,
        "isBucketed": False,
        "scale": "ratio",
        "sourceField": field,
        "customLabel": True,
        "params": {"emptyAsNull": True, **params},
    }
    if q:
        c["filter"] = kql(q)
    if fmt:
        c["params"]["format"] = fmt
    return c


PERCENT = {"id": "percent", "params": {"decimals": 1}}
NUMBER0 = {"id": "number", "params": {"decimals": 0}}
CAD = {"id": "number", "params": {"decimals": 0, "suffix": " CAD"}}
MS = {"id": "number", "params": {"decimals": 0, "suffix": " ms"}}
RATIO = {"id": "number", "params": {"decimals": 2}}


def col_terms(field, label, order_col=None, size=10, direction="desc", numeric=False):
    order = (
        {"type": "column", "columnId": order_col}
        if order_col
        else {"type": "alphabetical", "fallback": True}
    )
    return {
        "label": label,
        "dataType": "number" if numeric else "string",
        "operationType": "terms",
        "scale": "ordinal",
        "sourceField": field,
        "isBucketed": True,
        "customLabel": True,
        "params": {
            "size": size,
            "orderBy": order,
            "orderDirection": direction if order_col else "asc",
            "otherBucket": False,
            "missingBucket": False,
            "parentFormat": {"id": "terms"},
            "include": [],
            "exclude": [],
            "includeIsRegex": False,
            "excludeIsRegex": False,
        },
    }


def col_date(label="Time", interval="auto", field="@timestamp"):
    return {
        "label": label,
        "dataType": "date",
        "operationType": "date_histogram",
        "sourceField": field,
        "isBucketed": True,
        "scale": "interval",
        "customLabel": True,
        "params": {
            "interval": interval,
            "includeEmptyRows": True,
            "dropPartials": False,
        },
    }


def col_histogram(field, label, interval):
    return {
        "label": label,
        "dataType": "number",
        "operationType": "range",
        "sourceField": field,
        "isBucketed": True,
        "scale": "interval",
        "customLabel": True,
        "params": {
            "type": "histogram",
            "ranges": [{"from": 0, "to": 1000, "label": ""}],
            "maxBars": "auto",
            "includeEmptyRows": False,
            "parentFormat": {
                "id": "range",
                "params": {"template": "arrow_right", "replaceInfinity": True},
            },
        },
    }


def col_histogram_fixed(field, label, interval):
    c = col_histogram(field, label, interval)
    c["params"]["maxBars"] = interval
    return c


def formula_columns(prefix, label, text, tree, leaves, fmt=None):
    """A formula column: `leaves` maps helper column id -> column definition, `tree` is the tinymath AST over those ids.
    Like Lens writes them: the formula column first, then its helper columns (X0, X1, ...) and the math column last."""
    ids = {k: f"{prefix}X{i}" for i, k in enumerate(leaves)}
    math_id = f"{prefix}X{len(leaves)}"

    def rename(node):
        if isinstance(node, str):
            return ids[node]
        if isinstance(node, (int, float)):
            return node
        return {**node, "args": [rename(a) for a in node["args"]]}

    cols = {
        prefix: {
            "label": label,
            "dataType": "number",
            "operationType": "formula",
            "isBucketed": False,
            "scale": "ratio",
            "customLabel": True,
            "params": {
                "formula": text,
                "isFormulaBroken": False,
                **({"format": fmt} if fmt else {}),
            },
            "references": [math_id],
        }
    }
    for k, definition in leaves.items():
        d = dict(definition)
        if "references" in d:
            d["references"] = [ids[r] for r in d["references"]]
        cols[ids[k]] = d
    cols[math_id] = {
        "label": f"Part of {label}",
        "dataType": "number",
        "operationType": "math",
        "isBucketed": False,
        "scale": "ratio",
        "params": {"tinymathAst": rename(tree)},
        "references": [ids[k] for k in leaves if k in _used(tree)],
    }
    return cols


def _used(node):
    if isinstance(node, str):
        return {node}
    if isinstance(node, (int, float)):
        return set()
    out = set()
    for a in node["args"]:
        out |= _used(a)
    return out


def fn(name, *args):
    return {"type": "function", "name": name, "args": list(args)}


def lens(id_, title, description, vis_type, visualization, layers, query=None):
    """layers: {layer_id: (data_view_name, {column_id: column}, [column_order])}"""
    form = {}
    refs = []
    for layer_id, (view, columns, order) in layers.items():
        form[layer_id] = {
            "columns": columns,
            "columnOrder": order,
            "incompleteColumns": {},
            "sampling": 1,
        }
        refs.append(
            {
                "type": "index-pattern",
                "id": dv(view),
                "name": f"indexpattern-datasource-layer-{layer_id}",
            }
        )
    state = {
        "visualization": visualization,
        "query": query or kql(""),
        "filters": [],
        "datasourceStates": {
            "formBased": {"layers": form},
            "indexpattern": {"layers": {}},
            "textBased": {"layers": {}},
        },
        "internalReferences": [],
        "adHocDataViews": {},
    }
    objects.append(
        {
            "type": "lens",
            "id": id_,
            "attributes": {
                "title": title,
                "description": description,
                "visualizationType": vis_type,
                "state": state,
            },
            "references": refs,
            "coreMigrationVersion": "8.8.0",
            "typeMigrationVersion": "8.9.0",
        }
    )
    return id_


def ordered(columns):
    """Bucket columns first, then metrics (the Lens expression builder wants it): keeps the order inside each group."""
    keys = list(columns.keys())
    return [k for k in keys if columns[k].get("isBucketed")] + [
        k for k in keys if not columns[k].get("isBucketed")
    ]


def xy(
    id_,
    title,
    description,
    view,
    columns,
    x,
    ys,
    series="bar_stacked",
    split=None,
    colors=None,
    legend=True,
    extra_layers=None,
    query=None,
):
    """One or more data layers. ys: list of column ids; colors: {column id: color}."""
    layer = {
        "layerId": "l1",
        "accessors": ys,
        "position": "top",
        "seriesType": series,
        "showGridlines": False,
        "layerType": "data",
        "xAccessor": x,
    }
    if split:
        layer["splitAccessor"] = split
    if colors:
        layer["yConfig"] = [{"forAccessor": y, "color": c} for y, c in colors.items()]
    vis = {
        "legend": {"isVisible": legend, "position": "bottom"},
        "valueLabels": "hide",
        "fittingFunction": "None",
        "axisTitlesVisibilitySettings": {"x": False, "yLeft": False, "yRight": False},
        "tickLabelsVisibilitySettings": {"x": True, "yLeft": True, "yRight": True},
        "labelsOrientation": {"x": 0, "yLeft": 0, "yRight": 0},
        "gridlinesVisibilitySettings": {"x": False, "yLeft": True, "yRight": True},
        "preferredSeriesType": series,
        "layers": [layer],
    }
    layers = {"l1": (view, columns, ordered(columns))}
    for extra_id, (extra_view, extra_cols, extra_x, extra_ys, extra_colors) in (
        extra_layers or {}
    ).items():
        vis["layers"].append(
            {
                "layerId": extra_id,
                "accessors": extra_ys,
                "position": "top",
                "seriesType": series,
                "showGridlines": False,
                "layerType": "data",
                "xAccessor": extra_x,
                "yConfig": [
                    {"forAccessor": y, "color": c}
                    for y, c in (extra_colors or {}).items()
                ],
            }
        )
        layers[extra_id] = (extra_view, extra_cols, ordered(extra_cols))
    return lens(id_, title, description, "lnsXY", vis, layers, query=query)


def metric(id_, title, description, view, column, color=None, subtitle=None):
    cols = {"m": column}
    vis = {"layerId": "l1", "layerType": "data", "metricAccessor": "m"}
    if color:
        vis["color"] = color
    if subtitle:
        vis["subtitle"] = subtitle
    return lens(id_, title, description, "lnsMetric", vis, {"l1": (view, cols, ["m"])})


def table(
    id_,
    title,
    description,
    view,
    columns,
    order=None,
    sort=None,
    row_height="auto",
    flag_col=None,
    query=None,
):
    cols = [
        {"columnId": c, "isTransposed": False}
        for c in (order or ordered(columns))
        if c in columns
    ]
    vis = {
        "layerId": "l1",
        "layerType": "data",
        "columns": cols,
        "rowHeight": row_height,
        "headerRowHeight": "auto",
    }
    if sort:
        vis["sorting"] = {"columnId": sort[0], "direction": sort[1]}
    if flag_col:  # 0/1 column: a red cell for 1
        for c in vis["columns"]:
            if c["columnId"] == flag_col:
                c["colorMode"] = "cell"
                c["palette"] = {
                    "name": "custom",
                    "type": "palette",
                    "params": {
                        "name": "custom",
                        "reverse": False,
                        "rangeType": "number",
                        "rangeMin": 0,
                        "rangeMax": 1,
                        "progression": "fixed",
                        "stops": [
                            {"color": "#ffffff", "stop": 0.5},
                            {"color": RED, "stop": 1.5},
                        ],
                        "steps": 2,
                        "colorStops": [
                            {"color": "#ffffff", "stop": 0},
                            {"color": RED, "stop": 0.5},
                        ],
                        "continuity": "none",
                        "maxSteps": 5,
                    },
                }
    return lens(
        id_,
        title,
        description,
        "lnsDatatable",
        vis,
        {"l1": (view, columns, ordered(columns))},
        query=query,
    )


def donut(id_, title, description, view, columns, group, metric_col):
    vis = {
        "shape": "donut",
        "layers": [
            {
                "layerId": "l1",
                "primaryGroups": [group],
                "metrics": [metric_col],
                "numberDisplay": "percent",
                "categoryDisplay": "default",
                "legendDisplay": "show",
                "nestedLegend": False,
                "layerType": "data",
            }
        ],
    }
    return lens(
        id_,
        title,
        description,
        "lnsPie",
        vis,
        {"l1": (view, columns, ordered(columns))},
    )


def heatmap(id_, title, description, view, columns, x, y, value):
    vis = {
        "layerId": "l1",
        "layerType": "data",
        "shape": "heatmap",
        "xAccessor": x,
        "yAccessor": y,
        "valueAccessor": value,
        "gridConfig": {
            "type": "heatmap_grid",
            "isCellLabelVisible": True,
            "isYAxisLabelVisible": True,
            "isXAxisLabelVisible": True,
            "isYAxisTitleVisible": False,
            "isXAxisTitleVisible": False,
        },
        "legend": {"isVisible": True, "position": "right", "type": "heatmap_legend"},
    }
    return lens(
        id_,
        title,
        description,
        "lnsHeatmap",
        vis,
        {"l1": (view, columns, ordered(columns))},
    )


# --------------------------------------------------------------------------------------------------------- saved searches
def search(id_, title, description, view, columns, sort, query=""):
    ssj = {
        "highlightAll": True,
        "version": True,
        "query": kql(query),
        "filter": [],
        "indexRefName": "kibanaSavedObjectMeta.searchSourceJSON.index",
    }
    objects.append(
        {
            "type": "search",
            "id": id_,
            "attributes": {
                "title": title,
                "description": description,
                "columns": columns,
                "sort": sort,
                "grid": {},
                "hideChart": False,
                "isTextBasedQuery": False,
                "viewMode": "documents",
                "kibanaSavedObjectMeta": {"searchSourceJSON": json.dumps(ssj)},
            },
            "references": [
                {
                    "id": dv(view),
                    "name": "kibanaSavedObjectMeta.searchSourceJSON.index",
                    "type": "index-pattern",
                }
            ],
            "coreMigrationVersion": "8.8.0",
            "typeMigrationVersion": "10.5.0",
        }
    )
    return id_


# ---------------------------------------------------------------------------------------------------------------- dashboards
def dashboard(id_, title, description, panels, controls=None):
    """panels: [(object type, object id, x, y, w, h)]; controls: [(control id, view, field, title, single_select)]"""
    panel_json, refs = [], []
    for i, (otype, oid, x, y, w, h) in enumerate(panels, start=1):
        pid = f"p{i}"
        panel_json.append(
            {
                "version": KIBANA,
                "type": otype,
                "gridData": {"x": x, "y": y, "w": w, "h": h, "i": pid},
                "panelIndex": pid,
                "embeddableConfig": {
                    "enhancements": {"dynamicActions": {"events": []}}
                },
                "panelRefName": f"panel_{pid}",
            }
        )
        refs.append({"name": f"{pid}:panel_{pid}", "type": otype, "id": oid})
    attrs = {
        "title": title,
        "description": description,
        "hits": 0,
        "version": 1,
        "timeRestore": True,
        "timeFrom": "now-24h",
        "timeTo": "now",
        "refreshInterval": {"pause": False, "value": 60000},
        "optionsJSON": json.dumps(
            {
                "useMargins": True,
                "syncColors": True,
                "syncCursor": True,
                "syncTooltips": True,
                "hidePanelTitles": False,
            }
        ),
        "panelsJSON": json.dumps(panel_json),
        "kibanaSavedObjectMeta": {
            "searchSourceJSON": json.dumps({"query": kql(""), "filter": []})
        },
    }
    if controls:
        ctl = {}
        for order, (cid, view, field, ctitle, single) in enumerate(controls):
            ctl[cid] = {
                "type": "optionsListControl",
                "order": order,
                "grow": True,
                "width": "small",
                "explicitInput": {
                    "id": cid,
                    "fieldName": field,
                    "title": ctitle,
                    "searchTechnique": "prefix",
                    "selectedOptions": [],
                    "sort": {"by": "_count", "direction": "desc"},
                    "singleSelect": single,
                },
            }
            refs.append(
                {
                    "name": f"controlGroup_{cid}:optionsListControlDataView",
                    "type": "index-pattern",
                    "id": dv(view),
                }
            )
        attrs["controlGroupInput"] = {
            "chainingSystem": "HIERARCHICAL",
            "controlStyle": "oneLine",
            "ignoreParentSettingsJSON": json.dumps(
                {
                    "ignoreFilters": False,
                    "ignoreQuery": False,
                    "ignoreTimerange": False,
                    "ignoreValidations": False,
                }
            ),
            "panelsJSON": json.dumps(ctl),
            "showApplySelections": False,
        }
    objects.append(
        {
            "type": "dashboard",
            "id": id_,
            "attributes": attrs,
            "references": refs,
            "coreMigrationVersion": "8.8.0",
            "typeMigrationVersion": "10.2.0",
        }
    )


DEC_CONTROLS = [
    ("mn-ctl-province", "cc-decisions", S + "province", "Province", False),
    ("mn-ctl-product", "cc-decisions", P + "product", "Product", False),
    ("mn-ctl-outcome", "cc-decisions", P + "outcome", "Outcome", False),
    ("mn-ctl-source", "cc-decisions", "decisionSource", "Source (ODM / MANUAL)", False),
]
ODM = "decisionSource: ODM"
OUTCOME_Y = lambda: {
    "approve": col_count("Approved", ODM + " and " + P + "outcome: APPROVE"),
    "decline": col_count("Declined", ODM + " and " + P + "outcome: DECLINE"),
    "refer": col_count("Referred", ODM + " and " + P + "outcome: REFER"),
}
OUTCOME_YCOLORS = {"approve": GREEN, "decline": RED, "refer": AMBER}


def outcome_by(id_, title, description, field, label, size=15, numeric=False):
    cols = {
        "x": col_terms(field, label, order_col="total", size=size, numeric=numeric),
        "total": col_count("Decisions", ODM),
    }
    cols.update(OUTCOME_Y())
    order = ["x", "approve", "decline", "refer", "total"]
    layer = {
        "layerId": "l1",
        "accessors": ["approve", "decline", "refer"],
        "position": "top",
        "seriesType": "bar_stacked",
        "showGridlines": False,
        "layerType": "data",
        "xAccessor": "x",
        "yConfig": [{"forAccessor": k, "color": v} for k, v in OUTCOME_YCOLORS.items()],
    }
    vis = {
        "legend": {"isVisible": True, "position": "bottom"},
        "valueLabels": "hide",
        "fittingFunction": "None",
        "axisTitlesVisibilitySettings": {"x": False, "yLeft": False, "yRight": False},
        "tickLabelsVisibilitySettings": {"x": True, "yLeft": True, "yRight": True},
        "labelsOrientation": {"x": 0, "yLeft": 0, "yRight": 0},
        "gridlinesVisibilitySettings": {"x": False, "yLeft": True, "yRight": True},
        "preferredSeriesType": "bar_stacked",
        "layers": [layer],
    }
    return lens(
        id_, title, description, "lnsXY", vis, {"l1": ("cc-decisions", cols, order)}
    )


# =========================================================================================================== EXECUTIVE
metric(
    "mn-kpi-apps",
    "Applications today",
    "Automated (ODM) decisions since midnight; one per application that reached the rules.",
    "cc-decisions",
    col_count("Applications today", ODM + ' and @timestamp >= "now/d"', NUMBER0),
    color=BLUE,
)
metric(
    "mn-kpi-approval",
    "Approval rate",
    "Approved divided by all automated decisions in the selected period.",
    "cc-decisions",
    col_metric("average", "isApproved", "Approval %", ODM, PERCENT),
    color=GREEN,
)
metric(
    "mn-kpi-referral",
    "Referral rate",
    "Share of automated decisions that went to an underwriter (REFER).",
    "cc-decisions",
    col_metric("average", "isReferred", "Referral %", ODM, PERCENT),
    color=AMBER,
)
metric(
    "mn-kpi-decline",
    "Decline rate",
    "Share of automated decisions that were declined.",
    "cc-decisions",
    col_metric("average", "isDeclined", "Decline %", ODM, PERCENT),
    color=RED,
)
metric(
    "mn-kpi-limit",
    "Average credit limit",
    "Average limit of approved applications (CAD).",
    "cc-decisions",
    col_metric(
        "average",
        P + "creditLimit",
        "Avg limit",
        ODM + " and " + P + "outcome: APPROVE",
        CAD,
    ),
    color=BLUE,
)
metric(
    "mn-kpi-manual",
    "Manual decisions",
    "Decisions taken by an underwriter on a referral (decisionSource MANUAL).",
    "cc-decisions",
    col_count("Manual decisions", "decisionSource: MANUAL", NUMBER0),
    color=PURPLE,
)

cols = {"x": col_date("Time"), **OUTCOME_Y()}
xy(
    "mn-exec-outcomes-time",
    "Decisions over time",
    "Automated decisions per interval by outcome (approve green, decline red, refer amber).",
    "cc-decisions",
    cols,
    "x",
    ["approve", "decline", "refer"],
    series="bar_stacked",
    colors=OUTCOME_YCOLORS,
)
cols = {
    "x": col_date("Time"),
    "rate": col_metric("average", "isApproved", "Approval rate", ODM, PERCENT),
}
xy(
    "mn-exec-approval-time",
    "Approval rate over time",
    "Approved divided by all automated decisions, per interval.",
    "cc-decisions",
    cols,
    "x",
    ["rate"],
    series="line",
    colors={"rate": GREEN},
    legend=False,
)

outcome_by(
    "mn-exec-province",
    "Decisions by province",
    "Automated decisions per province by outcome.",
    S + "province",
    "Province",
    13,
)
cols = {
    "x": col_terms(P + "product", "Product", order_col="y", size=5),
    "y": col_count("Approved applications", ODM + " and " + P + "outcome: APPROVE"),
}
xy(
    "mn-exec-product",
    "Approved applications by product",
    "Products are assigned on approval only.",
    "cc-decisions",
    cols,
    "x",
    ["y"],
    series="bar",
    colors={"y": GREEN},
    legend=False,
)
outcome_by(
    "mn-exec-ageband",
    "Decisions by age band",
    "Automated decisions per applicant age band (derived by Logstash, never the date of birth).",
    S + "ageBand",
    "Age band",
    6,
)

cols = {
    "x": col_terms("limitBand", "Credit limit (CAD)", size=40),
    "y": col_count("Approved applications", ODM + " and " + P + "outcome: APPROVE"),
}
xy(
    "mn-exec-limit-dist",
    "Credit limit distribution",
    "Approved credit limits (CAD) in buckets.",
    "cc-decisions",
    cols,
    "x",
    ["y"],
    series="bar",
    colors={"y": GREEN},
    legend=False,
)
cols = {
    "x": col_terms(S + "scoreBand", "Bureau score band", size=10),
    **{k: v for k, v in OUTCOME_Y().items()},
}
xy(
    "mn-exec-score-hist",
    "Bureau score distribution",
    "Applications per bureau score bucket by outcome.",
    "cc-decisions",
    cols,
    "x",
    ["approve", "decline", "refer"],
    series="bar_stacked",
    colors=OUTCOME_YCOLORS,
)
cols = {
    "x": col_terms(S + "tdsBand", "Total debt service ratio band", size=10),
    "y": col_terms(P + "outcome", "Outcome", order_col="v", size=3),
    "v": col_count("Decisions", ODM),
}
heatmap(
    "mn-exec-tds-heatmap",
    "TDS ratio vs outcome",
    "How many decisions fall into each total debt service ratio bucket, per outcome.",
    "cc-decisions",
    cols,
    "x",
    "y",
    "v",
)

dashboard(
    "mn-dash-exec",
    "Credit Decisioning - Executive",
    "Volumes, approval, referral and decline rates, limits and score profile of the credit card decisions. Fictitious bank, synthetic data.",
    [
        ("lens", "mn-kpi-apps", 0, 0, 8, 7),
        ("lens", "mn-kpi-approval", 8, 0, 8, 7),
        ("lens", "mn-kpi-referral", 16, 0, 8, 7),
        ("lens", "mn-kpi-decline", 24, 0, 8, 7),
        ("lens", "mn-kpi-limit", 32, 0, 8, 7),
        ("lens", "mn-kpi-manual", 40, 0, 8, 7),
        ("lens", "mn-exec-outcomes-time", 0, 7, 24, 14),
        ("lens", "mn-exec-approval-time", 24, 7, 24, 14),
        ("lens", "mn-exec-province", 0, 21, 20, 15),
        ("lens", "mn-exec-product", 20, 21, 14, 15),
        ("lens", "mn-exec-ageband", 34, 21, 14, 15),
        ("lens", "mn-exec-limit-dist", 0, 36, 16, 15),
        ("lens", "mn-exec-score-hist", 16, 36, 16, 15),
        ("lens", "mn-exec-tds-heatmap", 32, 36, 16, 15),
    ],
    DEC_CONTROLS,
)

# ======================================================================================================= RISK & FAIRNESS
cols = {
    "x": col_terms(P + "reasonCodes", "Reason code", order_col="y", size=10),
    "y": col_count("Declines", ODM + " and " + P + "outcome: DECLINE"),
}
xy(
    "mn-risk-reasons",
    "Decline reasons (top 10)",
    "Reason codes of automated declines; one decline can carry several codes.",
    "cc-decisions",
    cols,
    "x",
    ["y"],
    series="bar_horizontal",
    colors={"y": RED},
    legend=False,
)
cols = {
    "x": col_date("Time"),
    "y": col_count("Declines", ODM + " and " + P + "outcome: DECLINE"),
    "s": col_terms(P + "reasonCodes", "Reason code", order_col="y", size=5),
}
xy(
    "mn-risk-reason-trend",
    "Decline reason trend",
    "The five most frequent decline reasons over time.",
    "cc-decisions",
    cols,
    "x",
    ["y"],
    series="area_stacked",
    split="s",
    query=kql(ODM + " and " + P + "outcome: DECLINE"),
)


def parity(id_, title, description, field, label, size):
    sum_a = col_metric("sum", "isApproved", "approved", ODM)
    cnt = col_count("n", ODM)
    leaves = {
        "a": sum_a,
        "b": cnt,
        "oa": {
            "label": "o",
            "dataType": "number",
            "operationType": "overall_sum",
            "isBucketed": False,
            "scale": "ratio",
            "references": ["a"],
        },
        "ob": {
            "label": "o",
            "dataType": "number",
            "operationType": "overall_sum",
            "isBucketed": False,
            "scale": "ratio",
            "references": ["b"],
        },
    }
    tree = fn("divide", fn("divide", "a", "b"), fn("divide", "oa", "ob"))
    cols = {"g": col_terms(field, label, order_col="n", size=size)}
    cols["n"] = col_count("Decisions (n)", ODM, NUMBER0)
    cols["rate"] = col_metric("average", "isApproved", "Approval rate", ODM, PERCENT)
    cols.update(
        formula_columns(
            "di",
            "Disparate impact ratio",
            "(sum(isApproved) / count()) / (overall_sum(sum(isApproved)) / overall_sum(count()))",
            tree,
            leaves,
            RATIO,
        )
    )
    flag_leaves = {k: dict(v) for k, v in leaves.items()}
    flag_tree = fn(
        "ifelse",
        fn("gte", "b", 30),
        fn("ifelse", fn("lt", tree, 0.8), 1, 0),
        0,
    )
    cols.update(
        formula_columns(
            "flag",
            "Review (1 = ratio below 0.8 with n of 30 or more)",
            "ifelse(count() >= 30, ifelse(((sum(isApproved) / count()) / (overall_sum(sum(isApproved)) / overall_sum(count()))) < 0.8, 1, 0), 0)",
            flag_tree,
            flag_leaves,
            NUMBER0,
        )
    )
    return table(
        id_,
        title,
        description,
        "cc-decisions",
        cols,
        order=["g", "n", "rate", "di", "flag"],
        sort=("di", "asc"),
        flag_col="flag",
    )


parity(
    "mn-risk-parity-province",
    "Fairness ratios by province",
    "Approval rate of each province, its sample size (n), the disparate impact ratio (group rate divided by the overall rate) and a review flag. "
    "Below 0.8 (four-fifths rule) with n of 30 or more is flagged; small n gives noisy ratios.",
    S + "province",
    "Province",
    13,
)
parity(
    "mn-risk-parity-age",
    "Fairness ratios by age band",
    "Same as by province, per applicant age band: ratio, n and the review flag.",
    S + "ageBand",
    "Age band",
    6,
)

cols = {
    "x": col_terms(P + "rulesetVersion", "Ruleset version", order_col="y", size=5),
    "y": col_count("Decisions", ODM),
}
donut(
    "mn-risk-ruleset",
    "Decisions by ruleset version",
    "Which version of the ODM ruleset produced the automated decisions.",
    "cc-decisions",
    cols,
    "x",
    "y",
)
cols = {
    "x": col_date("Time"),
    "odm": col_count("ODM (automated)", "decisionSource: ODM"),
    "manual": col_count("MANUAL (underwriter)", "decisionSource: MANUAL"),
}
xy(
    "mn-risk-source",
    "ODM vs MANUAL decisions",
    "Automated (ODM) decisions and underwriter (MANUAL) decisions over time.",
    "cc-decisions",
    cols,
    "x",
    ["odm", "manual"],
    series="bar_stacked",
    colors={"odm": BLUE, "manual": PURPLE},
)
cols = {
    "u": col_terms(P + "underwriterId", "Underwriter", order_col="n", size=15),
    "o": col_terms(P + "outcome", "Final outcome", order_col="n", size=3),
    "n": col_count("Manual decisions", "decisionSource: MANUAL", NUMBER0),
    "l": col_metric(
        "average", P + "creditLimit", "Avg limit granted", "decisionSource: MANUAL", CAD
    ),
}
table(
    "mn-risk-overrides",
    "Underwriter decisions on referrals",
    "Manual decisions per underwriter and final outcome (each answers a REFER from the rules).",
    "cc-decisions",
    cols,
    order=["u", "o", "n", "l"],
    sort=("n", "desc"),
)

dashboard(
    "mn-dash-risk",
    "Credit Decisioning - Risk & Fairness",
    "Why applications are declined, whether approval is even across provinces and age bands, which ruleset decided, and what underwriters changed.",
    [
        ("lens", "mn-risk-reasons", 0, 0, 24, 15),
        ("lens", "mn-risk-reason-trend", 24, 0, 24, 15),
        ("lens", "mn-risk-parity-province", 0, 15, 24, 17),
        ("lens", "mn-risk-parity-age", 24, 15, 24, 17),
        ("lens", "mn-risk-ruleset", 0, 32, 12, 15),
        ("lens", "mn-risk-source", 12, 32, 18, 15),
        ("lens", "mn-risk-overrides", 30, 32, 18, 15),
    ],
    DEC_CONTROLS,
)

# ============================================================================================================== OPERATIONS
metric(
    "mn-ops-kpi-dlq",
    "Dead letters",
    "Events that could not be processed and went to a dead letter topic (cc-dlq). Zero is good.",
    "cc-dlq",
    col_count("Dead letters", None, NUMBER0),
    color=RED,
)
metric(
    "mn-ops-kpi-notif",
    "Notifications sent",
    "Approval, adverse-action and referral notices produced (never actually sent anywhere).",
    "cc-notifications",
    col_count("Notifications", None, NUMBER0),
    color=BLUE,
)
metric(
    "mn-ops-kpi-errors",
    "Error log lines",
    "Log lines with level ERROR from all services (cc-logs).",
    "cc-logs",
    col_count("Errors", "level: ERROR", NUMBER0),
    color=RED,
)
metric(
    "mn-ops-kpi-alerts",
    "Alerts fired",
    "Alert rule notifications written to cc-alerts.",
    "cc-alerts",
    col_count("Alerts", None, NUMBER0),
    color=AMBER,
)

dec = {"t": col_date("Time", "1m"), "dec": col_count("Decisions per minute")}
nots = {"t": col_date("Time", "1m"), "not": col_count("Notifications per minute")}
aud = {"t": col_date("Time", "1m"), "aud": col_count("Audit events per minute")}
dl = {"t": col_date("Time", "1m"), "dlq": col_count("Dead letters per minute")}
xy(
    "mn-ops-throughput",
    "Throughput per minute by topic",
    "Events per minute on the decision, notification, audit and dead letter topics.",
    "cc-decisions",
    dec,
    "t",
    ["dec"],
    series="line",
    colors={"dec": GREEN},
    extra_layers={
        "l2": ("cc-notifications", nots, "t", ["not"], {"not": BLUE}),
        "l3": ("cc-audit", aud, "t", ["aud"], {"aud": GREY}),
        "l4": ("cc-dlq", dl, "t", ["dlq"], {"dlq": RED}),
    },
)
cols = {
    "t": col_date("Time"),
    "p50": col_metric(
        "percentile", P + "processingMillis", "p50", ODM, MS, percentile=50
    ),
    "p95": col_metric(
        "percentile", P + "processingMillis", "p95", ODM, MS, percentile=95
    ),
    "p99": col_metric(
        "percentile", P + "processingMillis", "p99", ODM, MS, percentile=99
    ),
}
xy(
    "mn-ops-latency",
    "Decision latency p50 / p95 / p99 (decisionLatencyMs)",
    "Time the rules call took, from the orchestrator (processingMillis).",
    "cc-decisions",
    cols,
    "t",
    ["p50", "p95", "p99"],
    series="line",
    colors={"p50": GREEN, "p95": AMBER, "p99": RED},
)
cols = {
    "topic": col_terms("originalTopic", "Original topic", order_col="n", size=10),
    "err": col_terms("errorClass", "Error class", order_col="n", size=5),
    "n": col_count("Dead letters", None, NUMBER0),
}
table(
    "mn-ops-dlq-table",
    "Dead letters by topic and error",
    "Which topic and which exception class the dead letters have (masked or redacted values only).",
    "cc-dlq",
    cols,
    order=["topic", "err", "n"],
    sort=("n", "desc"),
)
cols = {
    "t": col_date("Time"),
    "y": col_count("Errors", "level: ERROR"),
    "s": col_terms("service", "Service", order_col="y", size=8),
}
xy(
    "mn-ops-errors",
    "Error log lines by service",
    "Log lines with level ERROR per service over time.",
    "cc-logs",
    cols,
    "t",
    ["y"],
    series="bar_stacked",
    split="s",
)

cols = {
    "tool": col_terms(P + "resourceId", "Tool", order_col="n", size=20),
    "caller": col_terms(P + "actor", "Caller", order_col="n", size=10),
    "out": col_terms(P + "result", "Outcome", order_col="n", size=3),
    "n": col_count("Calls", None, NUMBER0),
}
table(
    "mn-ops-mcp-audit",
    "MCP audit trail",
    "Tool calls of the MCP servers (cc-audit, eventType cc.audit.mcp.v1) by tool, caller and outcome (SUCCESS, FAILURE or DENIED). Arguments are never stored, only their names and a hash.",
    "cc-audit",
    cols,
    order=["tool", "caller", "out", "n"],
    sort=("n", "desc"),
    query=kql('eventType: "cc.audit.mcp.v1"'),
)

search(
    "mn-search-recent-decisions",
    "Recent decisions",
    "The newest decisions with their outcome and applicant summary (masked).",
    "cc-decisions",
    [
        "decisionSource",
        P + "applicationId",
        P + "outcome",
        P + "creditLimit",
        P + "product",
        S + "province",
        S + "ageBand",
        S + "scoreBand",
    ],
    [["@timestamp", "desc"]],
)
search(
    "mn-search-alerts",
    "Alerts",
    "Notifications written by the alert rules.",
    "cc-alerts",
    ["rule", "status", "message"],
    [["@timestamp", "desc"]],
)

dashboard(
    "mn-dash-ops",
    "Credit Decisioning - Operations",
    "Throughput, latency, dead letters, notifications, errors and alerts of the decisioning platform.",
    [
        ("lens", "mn-ops-kpi-dlq", 0, 0, 12, 7),
        ("lens", "mn-ops-kpi-notif", 12, 0, 12, 7),
        ("lens", "mn-ops-kpi-errors", 24, 0, 12, 7),
        ("lens", "mn-ops-kpi-alerts", 36, 0, 12, 7),
        ("lens", "mn-ops-throughput", 0, 7, 24, 15),
        ("lens", "mn-ops-latency", 24, 7, 24, 15),
        ("lens", "mn-ops-dlq-table", 0, 22, 24, 14),
        ("lens", "mn-ops-errors", 24, 22, 24, 14),
        ("lens", "mn-ops-mcp-audit", 0, 36, 48, 14),
        ("search", "mn-search-recent-decisions", 0, 50, 48, 14),
        ("search", "mn-search-alerts", 0, 64, 48, 10),
    ],
    DEC_CONTROLS,
)

# ============================================================================================================= TRACE
search(
    "mn-search-trace-logs",
    "Logs of the correlation id",
    "Service log lines (cc-logs), oldest first: pick a correlation id above.",
    "cc-logs",
    ["service", "level", "message"],
    [["@timestamp", "asc"]],
)
search(
    "mn-search-trace-audit",
    "Audit events of the correlation id",
    "Audit trail (cc-audit): who did what to which application.",
    "cc-audit",
    [P + "actor", P + "action", P + "resourceId", P + "result", "producer"],
    [["@timestamp", "asc"]],
)
search(
    "mn-search-trace-decisions",
    "Decisions of the correlation id",
    "Automated and manual decisions (cc-decisions) with outcome and reasons.",
    "cc-decisions",
    [
        "decisionSource",
        P + "applicationId",
        P + "outcome",
        P + "reasonCodes",
        P + "firedRules",
        P + "underwriterId",
    ],
    [["@timestamp", "asc"]],
)
search(
    "mn-search-trace-notifications",
    "Notifications of the correlation id",
    "Notices produced for the application (cc-notifications).",
    "cc-notifications",
    ["producer", "eventType"],
    [["@timestamp", "asc"]],
)
search(
    "mn-search-trace-dlq",
    "Dead letters of the correlation id",
    "Anything that went to a dead letter topic (cc-dlq).",
    "cc-dlq",
    ["originalTopic", "errorClass", "errorMessage"],
    [["@timestamp", "asc"]],
)
dashboard(
    "mn-dash-trace",
    "Credit Decisioning - Trace an application",
    "Follow one application across the services: choose its correlation id (or application id) and read the decision, audit trail, notices, "
    "dead letters and log lines in order. Time range: widen it if nothing shows.",
    [
        ("search", "mn-search-trace-decisions", 0, 0, 48, 9),
        ("search", "mn-search-trace-audit", 0, 9, 24, 12),
        ("search", "mn-search-trace-notifications", 24, 9, 24, 12),
        ("search", "mn-search-trace-dlq", 0, 21, 48, 8),
        ("search", "mn-search-trace-logs", 0, 29, 48, 18),
    ],
    [
        ("mn-ctl-correlation", "cc-logs", "correlationId", "Correlation id", True),
        (
            "mn-ctl-application",
            "cc-decisions",
            P + "applicationId",
            "Application id",
            True,
        ),
    ],
)

with OUT.open("w") as f:
    for o in objects:
        f.write(json.dumps(o, separators=(",", ":")) + "\n")
print(f"{len(objects)} objects -> {OUT}")
