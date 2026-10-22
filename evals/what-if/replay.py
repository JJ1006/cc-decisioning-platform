#!/usr/bin/env python3
"""Champion / challenger what-if: replays the last N automated decisions through two versions of the ruleset and reports the impact of the change.

The inputs are the EXACT inputs the rules saw, read from the restricted Elasticsearch index cc-decision-inputs with the credential of the query API
(the only reader besides privacy operations): no summary, no assumptions. Each application goes through ccApproval <champion> and ccApproval <challenger>
in RES (read-only: nothing is published, no event is produced). The report has:

  * approval rate and decline / refer rates of both versions and the delta
  * credit exposure: total and average approved limit, delta
  * the outcome migration matrix (champion rows, challenger columns)
  * reason code mix (count of decisions carrying each code) and its delta
  * fairness: disparate impact ratio (group approval rate / overall approval rate) per province and per age band for both versions and the delta;
    groups below --min-group-size are marked "small n" and left out of the verdict, like GET /v1/stats/fairness
  * the 20 applications that flipped outcome (ids only, no personal data exists in the inputs)

    python3 evals/what-if/replay.py --challenger 1.1 [--champion 1.0] [-n 1000] [--min-group-size 30] [--out DIR]       (make what-if CHALLENGER=1.1 N=1000)

Output (default evals/what-if/out): report.md, report.json, flips.ndjson (one line per flipped application, Kibana-friendly: import as a data view over a file or
index it with scripts/es-curl.sh). Standard library only.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import ssl
import sys
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "evals" / "rule-regression"))
import run as regression

OUTCOMES = ["APPROVE", "REFER", "DECLINE"]


# ------------------------------------------------------------------------------------------------------------------------------- inputs
def env_value(name: str) -> str:
    """A value of the root env file (never printed), unless the process environment has it."""
    if os.environ.get(name):
        return os.environ[name]
    f = ROOT / ".env"
    if f.exists():
        for line in f.read_text().splitlines():
            if line.startswith(name + "="):
                return line.split("=", 1)[1]
    return ""


def fetch_inputs(n: int) -> list[dict]:
    """The newest decision inputs, one per application, from cc-decision-inputs as the query API's Elasticsearch user."""
    pki = os.environ.get("PKI_OUT", str(ROOT / "security/pki/out"))
    ctx = ssl.create_default_context(cafile=os.environ.get("ES_CA", f"{pki}/ca/ca.crt"))
    user = os.environ.get("ES_USER", "decision-query-api")
    password = os.environ.get("ES_PASSWORD") or env_value("QUERY_API_PASSWORD")
    if not password:
        raise SystemExit(
            "what-if: no password for the Elasticsearch user (QUERY_API_PASSWORD in the root env file, or ES_PASSWORD)"
        )
    url = (
        os.environ.get("ES_URL", "https://localhost:9200")
        + "/cc-decision-inputs/_search"
    )
    body = {
        "size": min(n * 2, 10_000),
        "sort": [{"occurredAt": "desc"}, {"eventId": "desc"}],
        "_source": ["eventId", "occurredAt", "payload.applicationId", "payload.inputs"],
    }
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode(),
        headers={
            "Content-Type": "application/json",
            "Authorization": "Basic "
            + base64.b64encode(f"{user}:{password}".encode()).decode(),
        },
    )
    with urllib.request.urlopen(req, context=ctx, timeout=60) as r:
        hits = json.loads(r.read())["hits"]["hits"]
    seen: dict[str, dict] = {}
    for h in hits:
        p = h["_source"]["payload"]
        seen.setdefault(
            p["applicationId"],
            {"applicationId": p["applicationId"], "inputs": p["inputs"]},
        )
        if len(seen) >= n:
            break
    return list(seen.values())


def application_of(inputs: dict, application_id: str) -> dict:
    """The application the rules saw (the same mapping as the what-if tool of the odm-dc MCP server)."""
    i = inputs
    app = {
        "applicationId": f"WHATIF-{application_id}"[:60],
        "requestedLimit": i["requestedLimit"],
        "monthlyHousingCost": i["monthlyHousingCost"],
        "channel": "WEB",
        "applicant": {
            "applicantId": "WHATIF",
            "firstName": "What",
            "lastName": "If",
            "ageYears": i["age"],
            "residencyStatus": i["residencyStatus"],
        },
        "address": {
            "line1": "1 Simulation St",
            "city": "Testville",
            "province": i["province"],
            "postalCode": "A1A 1A1",
        },
        "employment": {
            "status": i["employmentStatus"],
            "employer": "Simulated",
            "annualIncome": i["annualIncome"],
            "monthsAtEmployer": i["monthsAtEmployer"],
        },
        "bureau": {
            "score": i["creditScore"],
            "monthlyDebtPayments": i["monthlyDebt"],
            "existingCreditLimit": i["existingCreditLimit"],
            "delinquencies": i["delinquencies"],
            "inquiriesLast6Months": i["inquiriesLast6Months"],
            "activeBankruptcy": i["bankruptcyFlag"],
            "fileAgeMonths": i["fileAgeMonths"],
        },
    }
    if i.get("requestedProduct"):
        app["requestedProduct"] = i["requestedProduct"]
    return app


def age_band(age: int) -> str:
    for hi, label in (
        (24, "18-24"),
        (34, "25-34"),
        (44, "35-44"),
        (54, "45-54"),
        (64, "55-64"),
    ):
        if age <= hi:
            return label
    return "65+"


# ---------------------------------------------------------------------------------------------------------------------------- analysis
def pct(x: float) -> str:
    return f"{100 * x:.1f}%"


def rate(rows: list[dict], key: str, outcome: str = "APPROVE") -> float:
    return sum(r[key]["outcome"] == outcome for r in rows) / len(rows) if rows else 0.0


def analyse(rows: list[dict], min_group: int) -> dict:
    n = len(rows)
    out: dict = {"n": n}
    for side in ("champion", "challenger"):
        out[side] = {
            "approve": rate(rows, side),
            "refer": rate(rows, side, "REFER"),
            "decline": rate(rows, side, "DECLINE"),
            "exposure": sum(
                r[side]["creditLimit"] for r in rows if r[side]["outcome"] == "APPROVE"
            ),
        }
        approved = sum(r[side]["outcome"] == "APPROVE" for r in rows)
        out[side]["avgLimit"] = out[side]["exposure"] / approved if approved else 0.0
    out["migration"] = {
        a: {
            b: sum(
                r["champion"]["outcome"] == a and r["challenger"]["outcome"] == b
                for r in rows
            )
            for b in OUTCOMES
        }
        for a in OUTCOMES
    }
    reasons = {
        side: Counter(c for r in rows for c in r[side]["reasonCodes"])
        for side in ("champion", "challenger")
    }
    out["reasons"] = {
        c: {
            "champion": reasons["champion"][c],
            "challenger": reasons["challenger"][c],
            "delta": reasons["challenger"][c] - reasons["champion"][c],
        }
        for c in sorted(set(reasons["champion"]) | set(reasons["challenger"]))
    }
    fairness: dict = {}
    for dim, key in (
        ("province", lambda r: r["province"]),
        ("ageBand", lambda r: age_band(r["age"])),
    ):
        groups: dict[str, list[dict]] = defaultdict(list)
        for r in rows:
            groups[key(r)].append(r)
        table = []
        for g, members in sorted(groups.items()):
            row = {"group": g, "n": len(members), "smallN": len(members) < min_group}
            for side in ("champion", "challenger"):
                overall = out[side]["approve"]
                row[f"{side}Rate"] = rate(members, side)
                row[f"{side}DI"] = row[f"{side}Rate"] / overall if overall else None
            row["deltaDI"] = (
                None
                if row["championDI"] is None or row["challengerDI"] is None
                else row["challengerDI"] - row["championDI"]
            )
            table.append(row)
        fairness[dim] = table
    out["fairness"] = fairness
    out["flips"] = [
        r for r in rows if r["champion"]["outcome"] != r["challenger"]["outcome"]
    ]
    return out


def render(a: dict, meta: dict) -> str:
    ch, cl = a["champion"], a["challenger"]
    md = [
        f"# What-if: ccApproval {meta['champion']} (champion) vs {meta['challenger']} (challenger)",
        "",
        f"{a['n']} automated decisions replayed with their exact stored inputs (`cc-decision-inputs`, newest first), {meta['seconds']:.1f} s. A simulation: nothing was published or stored.",
        "",
        "## Approval and exposure",
        "",
        "| | Champion | Challenger | Delta |",
        "|---|---|---|---|",
        *[
            f"| {lbl} rate | {pct(ch[k])} | {pct(cl[k])} | {100 * (cl[k] - ch[k]):+.1f} pp |"
            for lbl, k in (
                ("Approve", "approve"),
                ("Refer", "refer"),
                ("Decline", "decline"),
            )
        ],
        f"| Approved exposure (CAD) | {ch['exposure']:,.0f} | {cl['exposure']:,.0f} | {cl['exposure'] - ch['exposure']:+,.0f} |",
        f"| Average approved limit (CAD) | {ch['avgLimit']:,.0f} | {cl['avgLimit']:,.0f} | {cl['avgLimit'] - ch['avgLimit']:+,.0f} |",
        "",
        "## Outcome migration (rows: champion, columns: challenger)",
        "",
        "| | " + " | ".join(OUTCOMES) + " |",
        "|---|" + "---|" * len(OUTCOMES),
        *[
            f"| **{x}** | "
            + " | ".join(str(a["migration"][x][y]) for y in OUTCOMES)
            + " |"
            for x in OUTCOMES
        ],
        "",
        "## Reason code mix (decisions carrying the code)",
        "",
        "| Reason code | Champion | Challenger | Delta |",
        "|---|---|---|---|",
        *[
            f"| {c} | {v['champion']} | {v['challenger']} | {v['delta']:+d} |"
            for c, v in a["reasons"].items()
        ],
        "",
        f"## Fairness: disparate impact ratio (group approval rate / overall approval rate; groups under {meta['minGroupSize']} decisions are small n)",
        "",
    ]
    for dim, table in a["fairness"].items():
        md += [
            f"### By {dim}",
            "",
            "| Group | n | Champion DI | Challenger DI | Delta | |",
            "|---|---|---|---|---|---|",
        ]
        for r in table:
            di = lambda v: "-" if v is None else f"{v:.2f}"
            flag = (
                "small n"
                if r["smallN"]
                else ("**below 0.8**" if (r["challengerDI"] or 1) < 0.8 else "")
            )
            md.append(
                f"| {r['group']} | {r['n']} | {di(r['championDI'])} | {di(r['challengerDI'])} | {'-' if r['deltaDI'] is None else f'{r['deltaDI']:+.2f}'} | {flag} |"
            )
        md.append("")
    flips = a["flips"]
    md += [
        f"## Flipped applications ({len(flips)}; top 20)",
        "",
        "| Application | Champion | Challenger | Limit | Score | TDS |",
        "|---|---|---|---|---|---|",
    ]
    for r in flips[:20]:
        md.append(
            f"| {r['applicationId']} | {r['champion']['outcome']} {','.join(r['champion']['reasonCodes'])} | {r['challenger']['outcome']} {','.join(r['challenger']['reasonCodes'])} | {r['challenger']['creditLimit']:,.0f} | {r['score']} | {r['tds']} |"
        )
    md.append("")
    return "\n".join(md)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--champion", default="1.0")
    ap.add_argument("--challenger", required=True)
    ap.add_argument("-n", type=int, default=1000)
    ap.add_argument("--min-group-size", type=int, default=30)
    ap.add_argument("--out", default=str(ROOT / "evals/what-if/out"))
    args = ap.parse_args()

    import time

    t0 = time.time()
    docs = fetch_inputs(args.n)
    if not docs:
        raise SystemExit("what-if: cc-decision-inputs has no documents (run make demo)")
    s = regression.res_settings()
    calls = {
        "champion": regression.make_caller(s, args.champion),
        "challenger": regression.make_caller(s, args.challenger),
    }

    import concurrent.futures

    def one(d: dict) -> dict:
        app = application_of(d["inputs"], d["applicationId"])
        row = {
            "applicationId": d["applicationId"],
            "province": d["inputs"]["province"],
            "age": d["inputs"]["age"],
            "score": d["inputs"]["creditScore"],
            "tds": d["inputs"].get("tds"),
        }
        for side, call in calls.items():
            resp = call({"application": app})
            if "_error" in resp:
                raise SystemExit(
                    f"what-if: ruleset {args.champion if side == 'champion' else args.challenger} failed for {d['applicationId']}: {resp['_error'][:300]}"
                )
            row[side] = regression.project(resp["decision"])
        return row

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        rows = list(pool.map(one, docs))
    meta = {
        "champion": args.champion,
        "challenger": args.challenger,
        "minGroupSize": args.min_group_size,
        "seconds": time.time() - t0,
    }
    a = analyse(rows, args.min_group_size)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    md = render(a, meta)
    (out / "report.md").write_text(md)
    (out / "report.json").write_text(
        json.dumps(
            {
                **{k: v for k, v in a.items() if k != "flips"},
                "meta": meta,
                "flips": [r["applicationId"] for r in a["flips"]],
            },
            indent=2,
        )
        + "\n"
    )
    with open(out / "flips.ndjson", "w") as f:
        f.writelines(
            json.dumps(
                {
                    "applicationId": r["applicationId"],
                    "champion": r["champion"]["outcome"],
                    "challenger": r["challenger"]["outcome"],
                    "championReasons": r["champion"]["reasonCodes"],
                    "challengerReasons": r["challenger"]["reasonCodes"],
                    "creditLimit": r["challenger"]["creditLimit"],
                    "province": r["province"],
                    "score": r["score"],
                    "tds": r["tds"],
                }
            )
            + "\n"
            for r in a["flips"]
        )
    print(md)
    print(
        f"report: {os.path.relpath(out / 'report.md', ROOT)} (+ report.json, flips.ndjson)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
