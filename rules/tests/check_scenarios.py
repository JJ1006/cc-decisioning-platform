#!/usr/bin/env python3
"""Executable spec of the ccApproval rules, used to check rules/tests/scenarios.json.

This is NOT the rules engine. It restates docs/rules-catalog.md in Python so the hand-written
expected values in scenarios.json are proven consistent with the written spec before they are
used against ODM RES (scripts/test-ruleset.sh). If you change a threshold, change it in the
catalog, in the Rule Designer project and here.

    python3 rules/tests/check_scenarios.py
"""

import json
import pathlib
import sys

AGE_19 = {"BC", "NB", "NL", "NS", "NT", "NU", "YT"}
SEVERITY = {None: -1, "APPROVE": 0, "REFER": 1, "DECLINE": 2}

# ScoreTdsOutcome: (min score, [(tds upper bound inclusive, outcome, reason)])
SCORE_TDS = [
    (
        680,
        [
            (0.50, None, None),
            (0.60, "REFER", "R_BORDERLINE_TDS"),
            (9.99, "DECLINE", "R_HIGH_TDS"),
        ],
    ),
    (
        620,
        [
            (0.42, None, None),
            (0.50, "REFER", "R_BORDERLINE_TDS"),
            (9.99, "DECLINE", "R_HIGH_TDS"),
        ],
    ),
    (560, [(0.35, "REFER", "R_BORDERLINE_SCORE"), (9.99, "DECLINE", "R_HIGH_TDS")]),
    (0, [(9.99, "DECLINE", "R_LOW_SCORE")]),
]
# IncomeScoreLimit: (min income, [(min score, limit)]) for approved applicants (score >= 620)
LIMITS = [
    (100000, [(740, 35000), (680, 20000), (620, 10000)]),
    (60000, [(740, 20000), (680, 12000), (620, 6000)]),
    (30000, [(740, 10000), (680, 6000), (620, 3000)]),
    (0, [(740, 5000), (680, 3000), (620, 1500)]),
]
APR = [(740, "TIER_1"), (680, "TIER_2"), (650, "TIER_3"), (0, "TIER_4")]


def tds(app):
    income = app["employment"]["annualIncome"] / 12
    b = app["bureau"]
    return (b["monthlyDebtPayments"] + app.get("monthlyHousingCost", 0)) / income


def evaluate(app):
    outcome, reasons = None, set()

    def hit(out, reason):
        nonlocal outcome
        if SEVERITY[out] > SEVERITY[outcome]:
            outcome = out
        reasons.add(reason)

    a, b, e = app["applicant"], app["bureau"], app["employment"]
    prov = app["address"]["province"]
    if a["ageYears"] < (19 if prov in AGE_19 else 18):
        hit("DECLINE", "R_AGE_BELOW_MAJORITY")
    if a["residencyStatus"] in ("NON_RESIDENT", "WORK_PERMIT_EXPIRED"):
        hit("DECLINE", "R_NON_RESIDENT")
    if b["activeBankruptcy"]:
        hit("DECLINE", "R_ACTIVE_BANKRUPTCY")
    if outcome is None:
        if e["annualIncome"] < 15000:
            hit("DECLINE", "R_INSUFFICIENT_INCOME")
        if b["delinquencies"] >= 3:
            hit("DECLINE", "R_DELINQUENCIES")
        if b["inquiriesLast6Months"] > 6:
            hit("DECLINE", "R_EXCESSIVE_INQUIRIES")
        if b["fileAgeMonths"] < 6:
            hit("REFER", "R_THIN_FILE")
        t = tds(app)
        for min_score, rows in SCORE_TDS:
            if b["score"] >= min_score:
                for upper, out, reason in rows:
                    if t <= upper:
                        if out:
                            hit(out, reason)
                        break
                break
    if outcome is not None:
        return {
            "outcome": outcome,
            "creditLimit": 0,
            "product": None,
            "aprTier": None,
            "reasonCodes": sorted(reasons),
        }

    income, score = e["annualIncome"], b["score"]
    base = next(
        lim for lo, rows in LIMITS if income >= lo for s, lim in rows if score >= s
    )
    requested = app.get("requestedLimit")
    limit = min(base, requested) if requested is not None else base
    req = app.get("requestedProduct") or "NO_FEE"
    cashback_ok = income >= 40000 and score >= 660
    travel_ok = income >= 80000 and score >= 720
    if req == "TRAVEL_INFINITE" and travel_ok:
        product = "TRAVEL_INFINITE"
    elif req in ("TRAVEL_INFINITE", "CASHBACK") and cashback_ok:
        product = "CASHBACK"
    else:
        product = "NO_FEE"
    apr = next(t for s, t in APR if score >= s)
    return {
        "outcome": "APPROVE",
        "creditLimit": limit,
        "product": product,
        "aprTier": apr,
        "reasonCodes": ["R_APPROVED_STANDARD"],
    }


def main():
    path = pathlib.Path(__file__).with_name("scenarios.json")
    cases = json.loads(path.read_text())
    bad = 0
    for c in cases:
        want = dict(c["expected"], reasonCodes=sorted(c["expected"]["reasonCodes"]))
        got = evaluate(c["request"]["application"])
        if got != want:
            bad += 1
            print(
                f"MISMATCH {c['id']} {c['name']}\n  spec     {got}\n  expected {want}"
            )
    ids = [c["id"] for c in cases]
    if len(set(ids)) != len(ids):
        bad += 1
        print("duplicate scenario ids")
    print(f"{len(cases) - bad}/{len(cases)} scenarios consistent with the written spec")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
