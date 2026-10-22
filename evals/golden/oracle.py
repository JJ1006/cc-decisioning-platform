#!/usr/bin/env python3
"""Independent oracle of the ccApproval ruleset: labels the golden dataset WITHOUT calling ODM.

The two decision tables and the age of majority are read from the markdown of docs/rules-catalog.md at run time, so the oracle follows the written
spec, not the .brl or CSV sources (that difference is what the regression gate detects). The remaining thresholds are the numbers of the rule
table of the catalog; `check_catalog_constants()` fails when the catalog text no longer says what is hard-coded here.

It does not import rules/tests/check_scenarios.py: that file restates the spec for the 30 hand-written scenarios, this one is the labelling source
of the 300 golden cases. Exact arithmetic (fractions) is used for the debt service ratio, so a boundary such as 0.35 is exact.

    python3 evals/golden/oracle.py '<application json>'      prints the expected decision
"""

from __future__ import annotations

import json
import re
import sys
from fractions import Fraction
from pathlib import Path

CATALOG = Path(__file__).resolve().parents[2] / "docs" / "rules-catalog.md"
SEVERITY = {None: -1, "APPROVE": 0, "REFER": 1, "DECLINE": 2}

# constants of the rule table (docs/rules-catalog.md, columns Condition) that are prose, not a table; check_catalog_constants() guards them
MIN_INCOME = 15_000  # RISK-01: annual income < 15,000
DELINQUENCIES = 3  # RISK-02: delinquencies (24 months) >= 3
MAX_INQUIRIES = 6  # RISK-03: hard inquiries (6 months) > 6
MIN_FILE_AGE_MONTHS = 6  # RISK-04: file age < 6 months
CASHBACK_MIN_INCOME, CASHBACK_MIN_SCORE = 40_000, 660  # PROD-02/03
TRAVEL_MIN_INCOME, TRAVEL_MIN_SCORE = 80_000, 720  # PROD-04
APR_TIERS = [
    (740, "TIER_1"),
    (680, "TIER_2"),
    (650, "TIER_3"),
    (0, "TIER_4"),
]  # APR-01..04
NON_ELIGIBLE_RESIDENCY = {"NON_RESIDENT", "WORK_PERMIT_EXPIRED"}  # ELIG-02

REASON_CODES = [
    "R_AGE_BELOW_MAJORITY",
    "R_NON_RESIDENT",
    "R_ACTIVE_BANKRUPTCY",
    "R_INSUFFICIENT_INCOME",
    "R_DELINQUENCIES",
    "R_EXCESSIVE_INQUIRIES",
    "R_THIN_FILE",
    "R_LOW_SCORE",
    "R_HIGH_TDS",
    "R_BORDERLINE_SCORE",
    "R_BORDERLINE_TDS",
    "R_APPROVED_STANDARD",
]


def _section_rows(text: str, heading: str) -> list[list[str]]:
    """The cells of the data rows of the first table under `## heading`."""
    body = text.split(heading, 1)[1].split("\n## ", 1)[0]
    rows = [
        [c.strip() for c in ln.strip().strip("|").split("|")]
        for ln in body.splitlines()
        if ln.startswith("|")
    ]
    return rows[2:]  # header and separator


def _number(s: str) -> int:
    return int(s.replace(",", ""))


def _parse_score_band(label: str) -> tuple[int, int]:
    """'< 560' -> (0, 559); '560-619' -> (560, 619); '>= 680' -> (680, 10**6)."""
    label = label.strip()
    if label.startswith("<"):
        return 0, _number(label[1:].strip()) - 1
    if label.startswith(">="):
        return _number(label[2:].strip()), 10**6
    lo, hi = label.split("-")
    return _number(lo), _number(hi)


def _parse_income_band(label: str) -> tuple[int, int]:
    return _parse_score_band(label)


class Spec:
    """Everything read from the catalog."""

    def __init__(self, text: str | None = None) -> None:
        text = text if text is not None else CATALOG.read_text()
        self.age_19 = set(
            re.search(r"19 in ([A-Z, ]+?);", text).group(1).replace(" ", "").split(",")
        )
        self.age_18 = set(
            re.search(r"18 in ([A-Z, ]+?)\.", text).group(1).replace(" ", "").split(",")
        )
        # ScoreTdsOutcome: list of (score lo, score hi, [cell per TDS band]); TDS upper bounds from the header
        head = text.split("## ScoreTdsOutcome", 1)[1].split("\n## ", 1)[0]
        header = [
            c.strip()
            for c in next(ln for ln in head.splitlines() if ln.startswith("|"))
            .strip()
            .strip("|")
            .split("|")
        ]
        self.tds_upper: list[Fraction | None] = []
        for h in header[1:]:
            m = re.search(r"([0-9.]+)\]?$", h)
            self.tds_upper.append(None if h.startswith(">") else Fraction(m.group(1)))
        self.score_tds = []
        for row in _section_rows(text, "## ScoreTdsOutcome"):
            lo, hi = _parse_score_band(row[0])
            self.score_tds.append((lo, hi, [self._cell(c) for c in row[1:]]))
        # IncomeScoreLimit
        head = text.split("## IncomeScoreLimit", 1)[1].split("\n## ", 1)[0]
        header = [
            c.strip()
            for c in next(ln for ln in head.splitlines() if ln.startswith("|"))
            .strip()
            .strip("|")
            .split("|")
        ]
        self.limit_scores = [_parse_score_band(h) for h in header[1:]]
        self.limits = []
        for row in _section_rows(text, "## IncomeScoreLimit"):
            self.limits.append(
                (_parse_income_band(row[0]), [_number(c) for c in row[1:]])
            )

    @staticmethod
    def _cell(cell: str) -> tuple[str | None, str | None]:
        cell = cell.strip()
        if cell.lower() == "approve":
            return None, None
        outcome, reason = cell.split()
        return outcome, reason


def tds(app: dict) -> Fraction:
    monthly_income = Fraction(app["employment"]["annualIncome"]) / 12
    return (
        Fraction(app["bureau"]["monthlyDebtPayments"])
        + Fraction(app.get("monthlyHousingCost", 0))
    ) / monthly_income


def evaluate(app: dict, spec: Spec | None = None) -> dict:
    """The expected decision of an application: outcome, creditLimit, product, aprTier, reasonCodes (sorted)."""
    spec = spec or _SPEC
    outcome: str | None = None
    reasons: set[str] = set()

    def escalate(out: str, reason: str) -> None:
        nonlocal outcome
        if SEVERITY[out] > SEVERITY[outcome]:
            outcome = out
        reasons.add(reason)

    applicant, bureau, employment = app["applicant"], app["bureau"], app["employment"]
    province = app["address"]["province"]
    income, score = employment["annualIncome"], bureau["score"]

    # eligibility: every rule is evaluated (all reasons are reported)
    majority = 19 if province in spec.age_19 else 18
    if province not in spec.age_19 | spec.age_18:
        raise ValueError(f"province {province} is not in the catalog")
    if applicant["ageYears"] < majority:
        escalate("DECLINE", "R_AGE_BELOW_MAJORITY")
    if applicant["residencyStatus"] in NON_ELIGIBLE_RESIDENCY:
        escalate("DECLINE", "R_NON_RESIDENT")
    if bureau["activeBankruptcy"]:
        escalate("DECLINE", "R_ACTIVE_BANKRUPTCY")

    # risk: only when eligibility left no outcome
    if outcome is None:
        if income < MIN_INCOME:
            escalate("DECLINE", "R_INSUFFICIENT_INCOME")
        if bureau["delinquencies"] >= DELINQUENCIES:
            escalate("DECLINE", "R_DELINQUENCIES")
        if bureau["inquiriesLast6Months"] > MAX_INQUIRIES:
            escalate("DECLINE", "R_EXCESSIVE_INQUIRIES")
        if bureau["fileAgeMonths"] < MIN_FILE_AGE_MONTHS:
            escalate("REFER", "R_THIN_FILE")
        ratio = tds(app)
        for lo, hi, cells in spec.score_tds:
            if lo <= score <= hi:
                for upper, (out, reason) in zip(spec.tds_upper, cells):
                    if upper is None or ratio <= upper:
                        if out:
                            escalate(out, reason)
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

    # limit by income and score, capped at the requested limit (LIM-02)
    base = None
    for (ilo, ihi), cells in spec.limits:
        if ilo <= income <= ihi:
            for (slo, shi), value in zip(spec.limit_scores, cells):
                if slo <= score <= shi:
                    base = value
    if base is None:
        raise ValueError(f"no limit cell for income {income} score {score}")
    limit = min(base, app["requestedLimit"])

    wanted = app.get("requestedProduct") or "NO_FEE"
    cashback = income >= CASHBACK_MIN_INCOME and score >= CASHBACK_MIN_SCORE
    travel = income >= TRAVEL_MIN_INCOME and score >= TRAVEL_MIN_SCORE
    if wanted == "TRAVEL_INFINITE":
        product = "TRAVEL_INFINITE" if travel else "CASHBACK" if cashback else "NO_FEE"
    elif wanted == "CASHBACK":
        product = "CASHBACK" if cashback else "NO_FEE"
    else:
        product = "NO_FEE"
    apr = next(tier for floor, tier in APR_TIERS if score >= floor)
    return {
        "outcome": "APPROVE",
        "creditLimit": limit,
        "product": product,
        "aprTier": apr,
        "reasonCodes": ["R_APPROVED_STANDARD"],
    }


def check_catalog_constants(text: str | None = None) -> list[str]:
    """Problems when the catalog no longer states the constants hard-coded above."""
    text = text if text is not None else CATALOG.read_text()
    expect = {
        "RISK-01": "annual income < 15,000",
        "RISK-02": "delinquencies (24 months) >= 3",
        "RISK-03": "hard inquiries (6 months) > 6",
        "RISK-04": "file age < 6 months",
        "PROD-02": "requested CASHBACK, income >= 40,000, score >= 660",
        "PROD-04": "requested TRAVEL_INFINITE, income >= 80,000, score >= 720",
        "APR-01": "score >= 740",
        "APR-02": "680 <= score <= 739",
        "APR-03": "650 <= score <= 679",
        "APR-04": "score <= 649",
        "ELIG-02": "NON_RESIDENT or WORK_PERMIT_EXPIRED",
    }
    problems = []
    for rule, phrase in expect.items():
        line = next((ln for ln in text.splitlines() if ln.startswith(f"| {rule} ")), "")
        if phrase not in line:
            problems.append(f"{rule}: catalog does not say '{phrase}'")
    return problems


_SPEC = Spec()

if __name__ == "__main__":
    print(json.dumps(evaluate(json.loads(sys.argv[1])), indent=2))
