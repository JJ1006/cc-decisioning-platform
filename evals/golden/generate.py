#!/usr/bin/env python3
"""Generates evals/golden/golden.json: 300 labelled applications = hand-written edge cases + a seeded random fill.

Labels (expected outcome, limit, product, APR tier, reason codes) come from evals/golden/oracle.py, which reads docs/rules-catalog.md. ODM is never called.

    python3 evals/golden/generate.py            write golden.json
    python3 evals/golden/generate.py --check    exit 1 when golden.json is stale (CI, make verify)
"""

from __future__ import annotations

import json
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import oracle

OUT = Path(__file__).resolve().parent / "golden.json"
TOTAL = 300
SEED = 14
PROVINCES = [
    "AB",
    "BC",
    "MB",
    "NB",
    "NL",
    "NS",
    "NT",
    "NU",
    "ON",
    "PE",
    "QC",
    "SK",
    "YT",
]
MAJORITY = {p: 19 if p in oracle.Spec().age_19 else 18 for p in PROVINCES}

cases: list[dict] = []


def base(**o) -> dict:
    """A clean approval (score 700, TDS 0.20 on an income of 120000) with overrides: province, age, residency, income, score, debt, housing, ..."""
    income = o.get("income", 120_000)
    return {
        "applicationId": "",
        "requestedProduct": o.get("product"),
        "monthlyHousingCost": o.get("housing", 500),
        "channel": "WEB",
        "applicant": {
            "applicantId": "",
            "firstName": "Golden",
            "lastName": "Case",
            "ageYears": o.get("age", 35),
            "residencyStatus": o.get("residency", "CITIZEN"),
        },
        "address": {
            "line1": "1 Maple St",
            "city": "Testville",
            "province": o.get("province", "ON"),
            "postalCode": "A1A 1A1",
        },
        "employment": {
            "status": o.get("employment", "EMPLOYED"),
            "employer": "Acme Synthetic Ltd",
            "annualIncome": income,
            "monthsAtEmployer": 36,
        },
        "bureau": {
            "score": o.get("score", 700),
            "monthlyDebtPayments": o.get("debt", 1500),
            "existingCreditLimit": 2000,
            "delinquencies": o.get("delinq", 0),
            "inquiriesLast6Months": o.get("inq", 1),
            "activeBankruptcy": o.get("bankrupt", False),
            "fileAgeMonths": o.get("file_age", 84),
        },
        "requestedLimit": o.get("limit", 50_000),
    }


def add(name: str, tag: str, **o) -> None:
    app = base(**o)
    if app["requestedProduct"] is None:
        del app["requestedProduct"]
    n = len(cases) + 1
    app["applicationId"] = f"APP-G{n:03d}"
    app["applicant"]["applicantId"] = f"CUS-G{n:03d}"
    cases.append(
        {
            "id": f"G{n:03d}",
            "name": name,
            "tag": tag,
            "request": {"application": app},
            "expected": oracle.evaluate(app),
        }
    )


def tds_payments(ratio: str, income: int = 120_000) -> dict:
    """debt and housing that give exactly `ratio` (a decimal string) of the monthly income of 120000."""
    from fractions import Fraction

    total = Fraction(ratio) * Fraction(income) / 12
    assert total.denominator == 1, ratio
    total = int(total)
    housing = min(500, total)
    return {"housing": housing, "debt": total - housing, "income": income}


# ---------------------------------------------------------------------------------------------------------------- hand-written edge cases
for p in PROVINCES:  # age of majority, every province: one year below and exactly at
    add(
        f"age {MAJORITY[p] - 1} in {p}: below majority",
        "age-boundary",
        province=p,
        age=MAJORITY[p] - 1,
    )
    add(
        f"age {MAJORITY[p]} in {p}: at majority",
        "age-boundary",
        province=p,
        age=MAJORITY[p],
    )

for t in (
    "0.35",
    "0.42",
    "0.50",
    "0.60",
):  # TDS boundaries: -0.01, exactly, +0.01, in three score rows
    lo = f"{float(t) - 0.01:.2f}"
    hi = f"{float(t) + 0.01:.2f}"
    for score in (590, 650, 720):
        for label, ratio in (("-0.01", lo), ("exact", t), ("+0.01", hi)):
            add(
                f"TDS {t} {label} ({ratio}), score {score}",
                "tds-boundary",
                score=score,
                **tds_payments(ratio),
            )

for score in (
    299,
    300,
    559,
    560,
    561,
    619,
    620,
    621,
    649,
    650,
    651,
    659,
    660,
    661,
    679,
    680,
    681,
    719,
    720,
    721,
    739,
    740,
    741,
    900,
):
    add(f"score {score}, TDS 0.20", "score-edge", score=score)
for score in (560, 619):  # the REFER corner: low score, low TDS
    add(
        f"score {score}, TDS 0.35 (borderline score refer)",
        "score-edge",
        score=score,
        **tds_payments("0.35"),
    )

for income in (
    14_999,
    15_000,
    29_999,
    30_000,
    59_999,
    60_000,
    99_999,
    100_000,
):  # income edges of the limit table, every score column
    for score in (620, 680, 740):
        add(
            f"income {income}, score {score}",
            "income-edge",
            income=income,
            score=score,
            debt=int(income / 12 * 0.15),
            housing=0,
        )

for income in (39_999, 40_000, 79_999, 80_000):  # product eligibility edges
    for score in (659, 660, 719, 720):
        for product in ("CASHBACK", "TRAVEL_INFINITE"):
            add(
                f"{product} requested, income {income}, score {score}",
                "product",
                income=income,
                score=score,
                product=product,
                debt=int(income / 12 * 0.15),
                housing=0,
            )
for product in (None, "NO_FEE"):
    add(
        f"product {product or 'absent'} falls back to NO_FEE",
        "product",
        product=product,
    )

for limit in (500, 1_500, 20_000, 20_001, 35_000, 50_000):  # requested limit cap
    add(
        f"requested limit {limit} (income 120000, score 760: table 35000)",
        "limit-cap",
        score=760,
        limit=limit,
    )

for status in (
    "CITIZEN",
    "PERMANENT_RESIDENT",
    "WORK_PERMIT_VALID",
    "WORK_PERMIT_EXPIRED",
    "NON_RESIDENT",
):
    add(f"residency {status}", "residency", residency=status)
for emp in ("EMPLOYED", "SELF_EMPLOYED", "RETIRED", "STUDENT", "UNEMPLOYED"):
    add(f"employment {emp} (no rule uses it)", "employment", employment=emp)

add("active bankruptcy", "bankruptcy", bankrupt=True)
add(
    "active bankruptcy on a minor non-resident: three reasons",
    "bankruptcy",
    bankrupt=True,
    age=17,
    residency="NON_RESIDENT",
)
add(
    "bankruptcy hides risk reasons (eligibility decides first)",
    "bankruptcy",
    bankrupt=True,
    delinq=5,
    inq=9,
    file_age=1,
)
for d in (2, 3, 4):
    add(f"{d} delinquencies", "risk-boundary", delinq=d)
for n in (6, 7, 8):
    add(f"{n} inquiries", "risk-boundary", inq=n)
for m in (5, 6, 7):
    add(f"file age {m} months", "risk-boundary", file_age=m)
add(
    "stacked risk reasons",
    "risk-stack",
    income=12_000,
    delinq=4,
    inq=8,
    file_age=2,
    score=540,
    debt=900,
    housing=400,
)
add(
    "thin file plus borderline TDS refer",
    "risk-stack",
    file_age=3,
    **tds_payments("0.45"),
    score=650,
)
add(
    "thin file with a low score declines and refers: decline wins",
    "risk-stack",
    file_age=3,
    score=500,
)

# ---------------------------------------------------------------------------------------------------------------------- seeded random fill
rng = random.Random(SEED)


def random_case() -> None:
    prov = rng.choice(PROVINCES)
    income = int(rng.lognormvariate(11.0, 0.55))
    income = max(9_000, min(income, 400_000))
    monthly = income / 12
    ratio = rng.choice([0.10, 0.20, 0.30, 0.38, 0.45, 0.55, 0.65]) + rng.uniform(
        -0.04, 0.04
    )
    housing = int(monthly * ratio * 0.4)
    debt = int(monthly * ratio * 0.6)
    add(
        f"random {len(cases) + 1}",
        "random",
        province=prov,
        age=rng.randint(MAJORITY[prov] - 2, 78),
        residency=rng.choices(
            [
                "CITIZEN",
                "PERMANENT_RESIDENT",
                "WORK_PERMIT_VALID",
                "WORK_PERMIT_EXPIRED",
                "NON_RESIDENT",
            ],
            [60, 20, 10, 5, 5],
        )[0],
        employment=rng.choice(
            ["EMPLOYED", "SELF_EMPLOYED", "RETIRED", "STUDENT", "UNEMPLOYED"]
        ),
        income=income,
        score=rng.randint(480, 840),
        debt=debt,
        housing=housing,
        delinq=rng.choices([0, 1, 2, 3, 5], [70, 15, 8, 5, 2])[0],
        inq=rng.choices([0, 1, 3, 6, 8], [30, 30, 25, 10, 5])[0],
        bankrupt=rng.random() < 0.03,
        file_age=rng.choice([2, 5, 6, 18, 60, 120, 240]),
        limit=rng.choice([1_000, 2_500, 5_000, 8_000, 12_000, 20_000, 30_000, 40_000]),
        product=rng.choice([None, "NO_FEE", "CASHBACK", "TRAVEL_INFINITE"]),
    )


while len(cases) < TOTAL:
    random_case()
del cases[TOTAL:]

# --------------------------------------------------------------------------------------------------------------------------- self checks
counts = {
    code: sum(code in c["expected"]["reasonCodes"] for c in cases)
    for code in oracle.REASON_CODES
}
missing = {c: n for c, n in counts.items() if n < 3}
assert not missing, f"every reason code needs at least 3 cases: {missing}"
assert len({c["id"] for c in cases}) == len(cases)
assert not oracle.check_catalog_constants(), oracle.check_catalog_constants()
payload = json.dumps(cases, indent=1) + "\n"

if __name__ == "__main__":
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_text() != payload:
            print(
                "evals/golden/golden.json is stale: run python3 evals/golden/generate.py",
                file=sys.stderr,
            )
            sys.exit(1)
        print(f"golden.json up to date ({len(cases)} cases)")
    else:
        OUT.write_text(payload)
        by_tag: dict[str, int] = {}
        for c in cases:
            by_tag[c["tag"]] = by_tag.get(c["tag"], 0) + 1
        by_outcome: dict[str, int] = {}
        for c in cases:
            by_outcome[c["expected"]["outcome"]] = (
                by_outcome.get(c["expected"]["outcome"], 0) + 1
            )
        print(
            f"wrote {OUT.name}: {len(cases)} cases; by tag {by_tag}; by outcome {by_outcome}"
        )
        print("reason code coverage:", counts)
