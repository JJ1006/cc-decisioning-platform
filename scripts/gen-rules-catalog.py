#!/usr/bin/env python3
"""Generate the machine-readable rules catalog of the explanation endpoint from docs/rules-catalog.md - no hand copy.

docs/rules-catalog.md  ->  services/decision-query-api/src/main/resources/rules-catalog.json
gen-rules-catalog.py          write the file
gen-rules-catalog.py --check  exit 1 when the committed file is stale (make verify, CI)
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOC = ROOT / "docs" / "rules-catalog.md"
OUT = (
    ROOT
    / "services"
    / "decision-query-api"
    / "src"
    / "main"
    / "resources"
    / "rules-catalog.json"
)


def build() -> dict:
    text = DOC.read_text()
    rules = []
    for line in text.splitlines():
        if not re.match(r"\| (ELIG|RISK|LIM|PROD|APR|RSN)-", line):
            continue
        cols = [c.strip() for c in line.strip().strip("|").split("|")]
        rid, package, intent, condition, action, reasons = (cols + [""] * 6)[:6]
        codes = re.findall(r"R_[A-Z_]+", reasons)
        severity = (
            3
            if "DECLINE" in action
            else 2
            if "REFER" in action
            else 1
            if "APPROVE" in action
            else 0
        )
        rules.append(
            {
                "id": rid,
                "package": package,
                "intent": intent or condition,
                "condition": condition,
                "action": action,
                "reasonCodes": codes,
                "severity": severity,
            }
        )
    if not rules:
        raise SystemExit("no rules found in docs/rules-catalog.md")
    return {"source": "docs/rules-catalog.md", "rules": rules}


def render() -> str:
    return json.dumps(build(), indent=2) + "\n"


if __name__ == "__main__":
    new = render()
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_text() != new:
            print(
                f"{OUT.relative_to(ROOT)} is stale: run scripts/gen-rules-catalog.py",
                file=sys.stderr,
            )
            sys.exit(1)
        print("rules catalog resource is up to date")
    else:
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(new)
        print(f"wrote {OUT.relative_to(ROOT)} ({len(json.loads(new)['rules'])} rules)")
