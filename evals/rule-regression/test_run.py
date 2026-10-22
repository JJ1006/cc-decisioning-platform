"""The regression runner without ODM: a fake ruleset call proves that a difference fails and shows up in the reports."""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run

CASES = [
    {
        "id": "G1",
        "name": "ok",
        "tag": "t",
        "request": {"x": 1},
        "expected": {
            "outcome": "APPROVE",
            "creditLimit": 1000,
            "product": "NO_FEE",
            "aprTier": "TIER_2",
            "reasonCodes": ["R_APPROVED_STANDARD"],
        },
    },
    {
        "id": "G2",
        "name": "limit differs",
        "tag": "t",
        "request": {"x": 2},
        "expected": {
            "outcome": "APPROVE",
            "creditLimit": 2000,
            "product": "NO_FEE",
            "aprTier": "TIER_2",
            "reasonCodes": ["R_APPROVED_STANDARD"],
        },
    },
    {
        "id": "G3",
        "name": "engine error",
        "tag": "u",
        "request": {"x": 3},
        "expected": {
            "outcome": "DECLINE",
            "creditLimit": 0,
            "product": None,
            "aprTier": None,
            "reasonCodes": ["R_LOW_SCORE"],
        },
    },
]


def fake(request):
    if request["x"] == 3:
        return {"_error": "HTTP 400: boom"}
    return {
        "decision": {
            "outcome": "APPROVE",
            "creditLimit": 1000,
            "product": "NO_FEE",
            "aprTier": "TIER_2",
            "reasonCodes": ["R_APPROVED_STANDARD"],
            "firedRules": ["RSN-01"],
        }
    }


def test_a_difference_and_an_engine_error_fail_and_are_reported(tmp_path):
    results = run.run(CASES, fake, workers=2)
    assert [r["ok"] for r in results] == [True, False, False]
    assert results[1]["diff"] == {"creditLimit": {"expected": 2000.0, "got": 1000.0}}
    summary = run.write_reports(
        results,
        tmp_path,
        {"rulesetPath": "/a/1.0/b/1.0", "golden": "g.json", "seconds": 1.0},
    )
    assert (summary["passed"], summary["failed"], summary["failedIds"]) == (
        1,
        2,
        ["G2", "G3"],
    )
    assert "FAIL 1/3" in (tmp_path / "report.md").read_text()
    xml = (tmp_path / "junit.xml").read_text()
    assert 'tests="3" failures="2"' in xml and "<failure" in xml
    assert json.loads((tmp_path / "summary.json").read_text())["byTag"] == {
        "t": {"total": 2, "failed": 1},
        "u": {"total": 1, "failed": 1},
    }


def test_reason_code_order_does_not_matter_but_content_does():
    a = {
        "outcome": "DECLINE",
        "creditLimit": 0,
        "product": None,
        "aprTier": None,
        "reasonCodes": ["R_B", "R_A"],
    }
    assert run.project(a)["reasonCodes"] == ["R_A", "R_B"]
