#!/usr/bin/env bash
# Every alert has a runbook that exists: Prometheus rules (runbook_url) and Kibana rules ("runbook: <path>" in the message), and docs/runbooks/README.md lists every runbook.
# Part of make verify. No Docker needed.
exec python3 - "$(cd "$(dirname "$0")/.." && pwd)" <<'PY'
import json, pathlib, re, sys
root = pathlib.Path(sys.argv[1])
bad = []
prom = (root / "observability/prometheus/rules/alerts.yml").read_text()
alerts = re.findall(r"- alert: (\w+)", prom)
urls = re.findall(r"runbook_url: (\S+)", prom)
if len(alerts) != len(urls):
    bad.append(f"prometheus: {len(alerts)} alerts, {len(urls)} runbook_url")
bad += [f"prometheus: missing {u}" for u in urls if not (root / u).exists()]
rules = sorted((root / "observability/kibana/alerting/rules").glob("*.json"))
for f in rules:
    d = json.loads(f.read_text())
    paths = {m for a in d["actions"] for m in re.findall(r"runbook: (\S+\.md)", a.get("params", {}).get("message", ""))}
    if not paths:
        bad.append(f"kibana: {f.name} has no runbook in its message")
    bad += [f"kibana: {f.name} -> missing {p}" for p in paths if not (root / p).exists()]
index = (root / "docs/runbooks/README.md").read_text()
for p in sorted((root / "docs/runbooks").rglob("*.md")):
    rel = p.relative_to(root / "docs/runbooks").as_posix()
    if rel != "README.md" and rel not in index:
        bad.append(f"docs/runbooks/README.md does not list {rel}")
if bad:
    print("\n".join("RUNBOOK  " + b for b in bad)); sys.exit(1)
print(f"OK  {len(alerts)} Prometheus + {len(rules)} Kibana alerts have a runbook; index complete")
PY
