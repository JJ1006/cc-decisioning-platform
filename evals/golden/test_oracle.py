"""Unit tests of the golden-dataset oracle. No ODM needed.  uv run --quiet --with pytest==9.1.1 python -m pytest evals/golden -q"""

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import oracle

ROOT = Path(__file__).resolve().parents[2]
SPEC = oracle.Spec()


def app(**o):
    return {
        "applicationId": "APP-T",
        "monthlyHousingCost": o.get("housing", 500),
        "applicant": {
            "ageYears": o.get("age", 35),
            "residencyStatus": o.get("residency", "CITIZEN"),
        },
        "address": {"province": o.get("province", "ON")},
        "employment": {"annualIncome": o.get("income", 120_000)},
        "bureau": {
            "score": o.get("score", 700),
            "monthlyDebtPayments": o.get("debt", 1500),
            "delinquencies": o.get("delinq", 0),
            "inquiriesLast6Months": o.get("inq", 1),
            "activeBankruptcy": o.get("bankrupt", False),
            "fileAgeMonths": o.get("file_age", 84),
        },
        "requestedLimit": o.get("limit", 50_000),
        **({"requestedProduct": o["product"]} if o.get("product") else {}),
    }


def test_spec_is_read_from_the_catalog():
    assert SPEC.age_19 == {"BC", "NB", "NL", "NS", "NT", "NU", "YT"}
    assert SPEC.age_18 == {"AB", "MB", "ON", "PE", "QC", "SK"}
    assert [(lo, hi) for lo, hi, _ in SPEC.score_tds] == [
        (0, 559),
        (560, 619),
        (620, 679),
        (680, 10**6),
    ]
    assert [str(u) for u in SPEC.tds_upper[:4]] == [
        "7/20",
        "21/50",
        "1/2",
        "3/5",
    ] and SPEC.tds_upper[4] is None
    assert SPEC.limit_scores == [(620, 679), (680, 739), (740, 10**6)]
    assert [row[1] for row in SPEC.limits] == [
        [1500, 3000, 5000],
        [3000, 6000, 10000],
        [6000, 12000, 20000],
        [10000, 20000, 35000],
    ]


def test_catalog_constants_match_the_catalog_and_the_guard_notices_a_change():
    assert oracle.check_catalog_constants() == []
    changed = (
        (ROOT / "docs/rules-catalog.md")
        .read_text()
        .replace("annual income < 15,000", "annual income < 20,000")
    )
    assert any("RISK-01" in p for p in oracle.check_catalog_constants(changed))


@pytest.mark.parametrize(
    "province,age,expect",
    [
        ("ON", 17, "DECLINE"),
        ("ON", 18, "APPROVE"),
        ("BC", 18, "DECLINE"),
        ("BC", 19, "APPROVE"),
        ("QC", 18, "APPROVE"),
        ("YT", 18, "DECLINE"),
    ],
)
def test_age_of_majority(province, age, expect):
    assert oracle.evaluate(app(province=province, age=age))["outcome"] == expect


@pytest.mark.parametrize(
    "score,debt,housing,outcome,reasons",
    [
        (550, 0, 0, "DECLINE", ["R_LOW_SCORE"]),
        (560, 3000, 500, "REFER", ["R_BORDERLINE_SCORE"]),  # TDS exactly 0.35
        (560, 3100, 500, "DECLINE", ["R_HIGH_TDS"]),  # 0.36
        (
            650,
            4200,
            0,
            "APPROVE",
            ["R_APPROVED_STANDARD"],
        ),  # 0.42 exactly: still approve
        (650, 4300, 0, "REFER", ["R_BORDERLINE_TDS"]),  # 0.43
        (650, 5000, 0, "REFER", ["R_BORDERLINE_TDS"]),  # 0.50 exactly
        (650, 5100, 0, "DECLINE", ["R_HIGH_TDS"]),
        (700, 5000, 0, "APPROVE", ["R_APPROVED_STANDARD"]),  # 0.50 exactly at 680+
        (700, 6000, 0, "REFER", ["R_BORDERLINE_TDS"]),  # 0.60 exactly
        (700, 6100, 0, "DECLINE", ["R_HIGH_TDS"]),
    ],
)
def test_score_tds_table_including_exact_boundaries(
    score, debt, housing, outcome, reasons
):
    got = oracle.evaluate(app(score=score, debt=debt, housing=housing))
    assert (got["outcome"], got["reasonCodes"]) == (outcome, reasons)


@pytest.mark.parametrize(
    "income,score,limit",
    [
        (29_999, 620, 1500),
        (30_000, 620, 3000),
        (59_999, 700, 6000),
        (60_000, 700, 12000),
        (99_999, 740, 20000),
        (100_000, 740, 35000),
        (100_000, 679, 10000),
    ],
)
def test_limit_table(income, score, limit):
    assert (
        oracle.evaluate(
            app(income=income, score=score, debt=int(income / 12 * 0.1), housing=0)
        )["creditLimit"]
        == limit
    )


def test_limit_never_exceeds_the_request():
    assert oracle.evaluate(app(score=760, limit=2_000))["creditLimit"] == 2_000


@pytest.mark.parametrize(
    "income,score,wanted,product",
    [
        (80_000, 720, "TRAVEL_INFINITE", "TRAVEL_INFINITE"),
        (80_000, 719, "TRAVEL_INFINITE", "CASHBACK"),
        (79_999, 760, "TRAVEL_INFINITE", "CASHBACK"),
        (39_999, 760, "TRAVEL_INFINITE", "NO_FEE"),
        (40_000, 660, "CASHBACK", "CASHBACK"),
        (40_000, 659, "CASHBACK", "NO_FEE"),
        (90_000, 800, None, "NO_FEE"),
    ],
)
def test_product_fallbacks(income, score, wanted, product):
    assert (
        oracle.evaluate(
            app(income=income, score=score, product=wanted, debt=100, housing=0)
        )["product"]
        == product
    )


@pytest.mark.parametrize(
    "score,tier",
    [
        (740, "TIER_1"),
        (739, "TIER_2"),
        (680, "TIER_2"),
        (679, "TIER_3"),
        (650, "TIER_3"),
        (649, "TIER_4"),
    ],
)
def test_apr_tiers(score, tier):
    assert oracle.evaluate(app(score=score, debt=100, housing=0))["aprTier"] == tier


def test_eligibility_decides_first_and_reports_every_reason():
    got = oracle.evaluate(
        app(age=17, residency="NON_RESIDENT", bankrupt=True, delinq=9, file_age=1)
    )
    assert got["outcome"] == "DECLINE" and got["reasonCodes"] == [
        "R_ACTIVE_BANKRUPTCY",
        "R_AGE_BELOW_MAJORITY",
        "R_NON_RESIDENT",
    ]


def test_decline_beats_refer_and_all_risk_reasons_are_listed():
    got = oracle.evaluate(
        app(
            income=12_000, delinq=4, inq=8, file_age=2, score=540, debt=900, housing=400
        )
    )
    assert got["outcome"] == "DECLINE"
    assert got["reasonCodes"] == sorted(
        [
            "R_INSUFFICIENT_INCOME",
            "R_DELINQUENCIES",
            "R_EXCESSIVE_INQUIRIES",
            "R_THIN_FILE",
            "R_LOW_SCORE",
        ]
    )


def test_oracle_agrees_with_the_30_hand_written_scenarios():
    """The scenarios were written by hand before the oracle: two independent sources of the same spec."""
    for c in json.loads((ROOT / "rules/tests/scenarios.json").read_text()):
        want = dict(c["expected"], reasonCodes=sorted(c["expected"]["reasonCodes"]))
        assert oracle.evaluate(c["request"]["application"]) == want, c["id"]


def test_golden_file_is_in_step_with_the_generator_and_covers_every_reason_code_3_times():
    cases = json.loads((Path(__file__).with_name("golden.json")).read_text())
    assert len(cases) == 300 and len({c["id"] for c in cases}) == 300
    for code in oracle.REASON_CODES:
        assert sum(code in c["expected"]["reasonCodes"] for c in cases) >= 3, code
    for c in cases:  # every label is what the oracle says today
        assert oracle.evaluate(c["request"]["application"]) == c["expected"], c["id"]
