#!/usr/bin/env python3
"""Rule regression gate: runs every golden case against the deployed ruleset in RES and compares it with the label of the oracle.

Any difference in outcome, creditLimit, product, aprTier or the sorted reason codes is a failure (exit 1). Writes, into --out (default evals/rule-regression/out):
  report.md      human report: totals per tag, the diff of every failing case
  junit.xml      one testcase per golden case (CI test report)
  summary.json   counts, per tag counts, failing ids, ruleset path, timing

    python3 evals/rule-regression/run.py [--golden evals/golden/golden.json] [--version 1.0] [--out DIR]       (make eval-rules)

Connection settings are those of scripts/res-env.sh (RES_URL, RES_CA, RES_CERT, RES_CERT_KEY, RES_USER, RES_PASS, RULEAPP, RULEAPP_VERSION, RULESET).
Standard library only.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from xml.sax.saxutils import escape, quoteattr

ROOT = Path(__file__).resolve().parents[2]
FIELDS = ("outcome", "creditLimit", "product", "aprTier", "reasonCodes")


def res_settings() -> dict:
    pki = os.environ.get("PKI_OUT", str(ROOT / "security/pki/out"))
    return {
        "url": os.environ.get("RES_URL", "https://localhost:9443"),
        "ca": os.environ.get("RES_CA", f"{pki}/ca/ca.crt"),
        "cert": os.environ.get("RES_CERT", f"{pki}/admin/admin.crt"),
        "key": os.environ.get("RES_CERT_KEY", f"{pki}/admin/admin.key"),
        "user": os.environ.get("RES_USER", "resAdmin"),
        "password": os.environ.get("RES_PASS")
        or os.environ.get("RES_USER", "resAdmin"),
        "ruleapp": os.environ.get("RULEAPP", "ccApprovalApp"),
        "ruleapp_version": os.environ.get("RULEAPP_VERSION", "1.0"),
        "ruleset": os.environ.get("RULESET", "ccApproval"),
    }


def execute_url(s: dict, version: str) -> str:
    return f"{s['url']}/DecisionService/rest/{s['ruleapp']}/{s['ruleapp_version']}/{s['ruleset']}/{version}"


def make_caller(s: dict, version: str):
    ctx = ssl.create_default_context(cafile=s["ca"])
    ctx.load_cert_chain(s["cert"], s["key"])
    auth = "Basic " + base64.b64encode(f"{s['user']}:{s['password']}".encode()).decode()
    url = execute_url(s, version)

    def call(request: dict, retries: int = 2) -> dict:
        data = json.dumps(request).encode()
        for attempt in range(retries + 1):
            req = urllib.request.Request(
                url,
                data=data,
                headers={
                    "Content-Type": "application/json",
                    "Accept": "application/json",
                    "Authorization": auth,
                },
            )
            try:
                with urllib.request.urlopen(req, context=ctx, timeout=60) as r:
                    return json.loads(r.read())
            except urllib.error.HTTPError as e:
                body = e.read().decode(errors="replace")[:300]
                if attempt == retries:
                    return {"_error": f"HTTP {e.code}: {body}"}
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                if attempt == retries:
                    return {"_error": f"{type(e).__name__}: {e}"}
            time.sleep(1 + attempt)
        return {"_error": "unreachable"}

    return call


def project(decision: dict | None) -> dict | None:
    if not isinstance(decision, dict):
        return None
    return {
        "outcome": decision.get("outcome"),
        "creditLimit": float(decision.get("creditLimit") or 0),
        "product": decision.get("product"),
        "aprTier": decision.get("aprTier"),
        "reasonCodes": sorted(decision.get("reasonCodes") or []),
    }


def expected_of(case: dict) -> dict:
    e = case["expected"]
    return {
        **e,
        "creditLimit": float(e.get("creditLimit") or 0),
        "reasonCodes": sorted(e.get("reasonCodes") or []),
    }


def run(cases: list[dict], call, workers: int = 8) -> list[dict]:
    def one(case: dict) -> dict:
        t0 = time.time()
        resp = call(case["request"])
        got = project(resp.get("decision")) if "_error" not in resp else None
        want = expected_of(case)
        diff = {
            f: {"expected": want[f], "got": (got or {}).get(f)}
            for f in FIELDS
            if got is None or got[f] != want[f]
        }
        return {
            "id": case["id"],
            "name": case["name"],
            "tag": case.get("tag", ""),
            "ok": not diff,
            "diff": diff,
            "error": resp.get("_error"),
            "seconds": time.time() - t0,
        }

    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        return list(pool.map(one, cases))


def write_reports(results: list[dict], out: Path, meta: dict) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    failed = [r for r in results if not r["ok"]]
    by_tag: dict[str, dict] = {}
    for r in results:
        t = by_tag.setdefault(r["tag"] or "-", {"total": 0, "failed": 0})
        t["total"] += 1
        t["failed"] += 0 if r["ok"] else 1
    summary = {
        **meta,
        "total": len(results),
        "passed": len(results) - len(failed),
        "failed": len(failed),
        "failedIds": [r["id"] for r in failed],
        "byTag": by_tag,
    }
    (out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")

    md = [
        f"# Rule regression: {'PASS' if not failed else 'FAIL'} {summary['passed']}/{summary['total']}",
        "",
        f"Ruleset `{meta['rulesetPath']}`, golden dataset `{meta['golden']}` (labels from `evals/golden/oracle.py`, derived from `docs/rules-catalog.md`), {meta['seconds']:.1f} s.",
        "",
        "| Tag | Cases | Differences |",
        "|---|---|---|",
        *[f"| {t} | {v['total']} | {v['failed']} |" for t, v in sorted(by_tag.items())],
        "",
    ]
    if failed:
        md += [f"## Differences ({len(failed)})", ""]
        for r in failed[:60]:
            md.append(f"### {r['id']} {r['name']}")
            if r["error"]:
                md.append(f"error: `{r['error']}`")
            for f, d in r["diff"].items():
                md.append(
                    f"- `{f}`: expected `{json.dumps(d['expected'])}`, got `{json.dumps(d['got'])}`"
                )
            md.append("")
        if len(failed) > 60:
            md.append(f"... and {len(failed) - 60} more (see summary.json)")
    (out / "report.md").write_text("\n".join(md) + "\n")

    cases_xml = []
    for r in results:
        name = quoteattr(f"{r['id']} {r['name']}")
        cls = quoteattr(f"golden.{r['tag'] or 'case'}")
        if r["ok"]:
            cases_xml.append(
                f'  <testcase classname={cls} name={name} time="{r["seconds"]:.3f}"/>'
            )
        else:
            text = escape(r["error"] or json.dumps(r["diff"]))
            cases_xml.append(
                f'  <testcase classname={cls} name={name} time="{r["seconds"]:.3f}"><failure message="decision differs from the oracle">{text}</failure></testcase>'
            )
    (out / "junit.xml").write_text(
        f'<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="rule-regression" tests="{len(results)}" failures="{len(failed)}" time="{meta["seconds"]:.1f}">\n'
        + "\n".join(cases_xml)
        + "\n</testsuite>\n"
    )
    return summary


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--golden", default=str(ROOT / "evals/golden/golden.json"))
    ap.add_argument(
        "--version",
        default=os.environ.get("RULESET_VERSION", "1.0"),
        help="ruleset version to execute",
    )
    ap.add_argument("--out", default=str(ROOT / "evals/rule-regression/out"))
    ap.add_argument("--workers", type=int, default=8)
    args = ap.parse_args()

    s = res_settings()
    cases = json.loads(Path(args.golden).read_text())
    t0 = time.time()
    results = run(cases, make_caller(s, args.version), args.workers)
    meta = {
        "rulesetPath": f"/{s['ruleapp']}/{s['ruleapp_version']}/{s['ruleset']}/{args.version}",
        "golden": os.path.relpath(args.golden, ROOT),
        "seconds": time.time() - t0,
    }
    summary = write_reports(results, Path(args.out), meta)
    print(
        f"rule regression: {summary['passed']}/{summary['total']} cases match the oracle ({meta['rulesetPath']}, {meta['seconds']:.1f} s)"
    )
    for r in [x for x in results if not x["ok"]][:20]:
        print(f"FAIL  {r['id']} {r['name']}: {r['error'] or json.dumps(r['diff'])}")
    print(f"report: {os.path.relpath(Path(args.out) / 'report.md', ROOT)}")
    return 0 if summary["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
