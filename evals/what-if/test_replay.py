"""The what-if analysis without ODM or Elasticsearch: synthetic replay rows."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import replay


def row(i, province, age, champion, challenger, limit=5000):
    def d(outcome, codes):
        return {
            "outcome": outcome,
            "creditLimit": limit if outcome == "APPROVE" else 0,
            "product": None,
            "aprTier": None,
            "reasonCodes": codes,
        }

    return {
        "applicationId": f"A{i}",
        "province": province,
        "age": age,
        "score": 700,
        "tds": 0.45,
        "champion": d(*champion),
        "challenger": d(*challenger),
    }


ROWS = (
    [
        row(
            i,
            "ON",
            30,
            ("APPROVE", ["R_APPROVED_STANDARD"]),
            ("APPROVE", ["R_APPROVED_STANDARD"]),
        )
        for i in range(6)
    ]
    + [
        row(
            10 + i,
            "ON",
            30,
            ("REFER", ["R_BORDERLINE_TDS"]),
            ("APPROVE", ["R_APPROVED_STANDARD"]),
        )
        for i in range(2)
    ]
    + [
        row(20 + i, "QC", 50, ("DECLINE", ["R_HIGH_TDS"]), ("DECLINE", ["R_HIGH_TDS"]))
        for i in range(2)
    ]
)


def test_rates_exposure_migration_and_reasons():
    a = replay.analyse(ROWS, min_group=5)
    assert a["n"] == 10
    assert a["champion"]["approve"] == 0.6 and a["challenger"]["approve"] == 0.8
    assert a["challenger"]["exposure"] - a["champion"]["exposure"] == 10_000
    assert (
        a["migration"]["REFER"]["APPROVE"] == 2
        and a["migration"]["APPROVE"]["APPROVE"] == 6
    )
    assert a["reasons"]["R_BORDERLINE_TDS"] == {
        "champion": 2,
        "challenger": 0,
        "delta": -2,
    }
    assert [f["applicationId"] for f in a["flips"]] == ["A10", "A11"]


def test_fairness_small_groups_are_marked_and_di_is_group_over_overall():
    a = replay.analyse(ROWS, min_group=5)
    by = {r["group"]: r for r in a["fairness"]["province"]}
    assert by["ON"]["smallN"] is False and by["QC"]["smallN"] is True
    assert round(by["ON"]["championDI"], 3) == round((6 / 8) / 0.6, 3)
    assert by["QC"]["challengerDI"] == 0


def test_age_bands():
    assert [replay.age_band(a) for a in (18, 24, 25, 44, 54, 64, 65, 80)] == [
        "18-24",
        "18-24",
        "25-34",
        "35-44",
        "45-54",
        "55-64",
        "65+",
        "65+",
    ]


def test_report_has_every_section():
    md = replay.render(
        replay.analyse(ROWS, 5),
        {"champion": "1.0", "challenger": "1.1", "minGroupSize": 5, "seconds": 1.0},
    )
    for part in (
        "Approval and exposure",
        "Outcome migration",
        "Reason code mix",
        "disparate impact ratio",
        "By province",
        "By ageBand",
        "Flipped applications",
    ):
        assert part in md
