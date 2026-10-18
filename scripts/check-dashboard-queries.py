#!/usr/bin/env python3
"""Runs every Prometheus expression of the provisioned Grafana dashboards against the live Prometheus and lists the ones that return no
data (a wrong metric name, a label that does not exist, or a component that is not running). Quick feedback before the visual check.

    scripts/check-dashboard-queries.py [prometheus-url]       (default http://localhost:9090)
"""

import json
import pathlib
import sys
import urllib.parse
import urllib.request

PROM = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:9090"
ROOT = (
    pathlib.Path(__file__).resolve().parent.parent
    / "observability"
    / "grafana"
    / "dashboards"
)


def query(expr):
    url = f"{PROM}/api/v1/query?" + urllib.parse.urlencode({"query": expr})
    with urllib.request.urlopen(url, timeout=20) as r:
        body = json.load(r)
    return body["status"], body.get("data", {}).get("result", []), body.get("error")


empty = 0
total = 0
for f in sorted(ROOT.glob("*.json")):
    dash = json.loads(f.read_text())
    for p in dash["panels"]:
        for t in p.get("targets", []):
            expr = t.get("expr")
            if not expr:
                continue
            total += 1
            expr = expr.replace("$__rate_interval", "5m").replace("$__interval", "1m")
            try:
                status, result, error = query(expr)
            except Exception as e:  # noqa: BLE001
                status, result, error = "error", [], str(e)
            if status != "success" or not result:
                empty += 1
                why = error if status != "success" else "no data"
                print(f"{f.stem:14} {p['title'][:42]:42} {why}: {expr[:110]}")
print(f"\n{total} expressions, {empty} without data")
sys.exit(1 if empty else 0)
