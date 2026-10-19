"""Each tool against mocked backends (respx): the right endpoint, the right shape, nothing more."""

import httpx
import pytest
import respx
from mcp.server.fastmcp.exceptions import ToolError

from conftest import call

DC = "https://dc.test/decisioncenter-api"
GW = "https://gw.test"
RES = "https://res.test/DecisionService/rest/ccApprovalApp/1.0/ccApproval/1.0"


def dc_basics() -> None:
    respx.get(f"{DC}/v1/decisionservices").mock(
        return_value=httpx.Response(200, json={"elements": [{"id": "ds1", "name": "cc-approval", "description": "Cards"}]})
    )
    respx.get(f"{DC}/v1/decisionservices/ds1/branches").mock(
        return_value=httpx.Response(
            200,
            json={"elements": [{"id": "b1", "name": "%current_key", "main": True, "lastChangedOn": 1}, {"id": "b2", "name": "feature-x"}]},
        )
    )
    respx.get(f"{DC}/v1/decisionservices/ds1/projects").mock(
        return_value=httpx.Response(200, json={"elements": [{"id": "p1", "name": "cc-approval"}]})
    )


@respx.mock
def test_list_services_and_branches(server):
    dc_basics()
    assert call(server, "dc_list_decision_services")["data"] == [{"id": "ds1", "name": "cc-approval", "description": "<data>Cards</data>"}]
    branches = call(server, "dc_list_branches", service="cc-approval")["data"]
    assert [b["name"] for b in branches] == ["main", "feature-x"]


@respx.mock
def test_get_rules_all_and_by_package(server):
    dc_basics()
    respx.get(f"{DC}/v1/projects/p1/folders").mock(
        return_value=httpx.Response(200, json={"elements": [{"id": "f1", "name": "eligibility"}]})
    )
    route = respx.get(f"{DC}/v1/projects/p1/rules").mock(
        return_value=httpx.Response(
            200,
            json={
                "totalCount": 1,
                "elements": [
                    {"id": "r1", "name": "ELIG-01", "lastchangedBy": "odmAdmin", "properties": [{"name": "active", "value": "true"}]}
                ],
            },
        )
    )
    data = call(server, "dc_get_rules", service="cc-approval", package="eligibility")["data"]
    assert data["rules"] == [{"id": "r1", "name": "ELIG-01", "active": "true", "lastChangedBy": "odmAdmin"}]
    assert route.calls.last.request.url.params["folderId"] == "f1" and route.calls.last.request.url.params["branchId"] == "b1"


@respx.mock
def test_get_rule_returns_the_definition_and_unknown_rules_list_the_choices(server):
    dc_basics()
    respx.get(f"{DC}/v1/projects/p1/rules").mock(
        return_value=httpx.Response(
            200, json={"elements": [{"id": "r1", "name": "ELIG-01", "definition": "if age < majority then DECLINE", "properties": []}]}
        )
    )
    assert "DECLINE" in call(server, "dc_get_rule", service="cc-approval", rule="ELIG-01")["data"]["definition"]
    with pytest.raises(ToolError) as e:
        call(server, "dc_get_rule", service="cc-approval", rule="nope")
    assert "ELIG-01" in str(e.value)


@respx.mock
def test_run_tests_without_suites_is_a_note(server):
    dc_basics()
    respx.get(f"{DC}/v1/decisionservices/ds1/testsuites").mock(return_value=httpx.Response(200, json={"elements": []}))
    out = call(server, "dc_run_tests", service="cc-approval")["data"]
    assert out["testSuites"] == [] and "make test-rules" in out["note"]


@respx.mock
def test_build_report_validates_and_does_not_deploy(server):
    dc_basics()
    respx.get(f"{DC}/v1/decisionservices/ds1/deployments").mock(
        return_value=httpx.Response(200, json={"elements": [{"id": "d1", "name": "ccApproval"}]})
    )
    build = respx.post(f"{DC}/v1/deployments/d1/build").mock(
        return_value=httpx.Response(
            200, json={"status": "COMPLETED", "messages": {"elements": [{"severity": "WARNING", "message": "unused variable"}]}}
        )
    )
    deploy = respx.post(f"{DC}/v1/deployments/d1/deploy").mock(return_value=httpx.Response(200, json={}))
    out = call(server, "dc_build_report", service="cc-approval")["data"]
    assert out["status"] == "COMPLETED" and out["messages"][0]["message"] == "<data>unused variable</data>"
    assert build.called and not deploy.called


@respx.mock
def test_decision_tools_go_through_the_gateway_only(server):
    g = respx.get(f"{GW}/v1/decisions/A1").mock(
        return_value=httpx.Response(200, json={"applicationId": "A1", "outcome": "DECLINE", "reasonCodes": ["R_LOW_SCORE"]})
    )
    e = respx.get(f"{GW}/v1/decisions/A1/explanation").mock(
        return_value=httpx.Response(
            200,
            json={"applicationId": "A1", "firedRules": ["RISK-DT-01"], "reasons": [{"code": "R_LOW_SCORE", "text": "below the minimum"}]},
        )
    )
    assert call(server, "decision_get", application_id="A1")["data"]["outcome"] == "DECLINE"
    assert call(server, "decision_explain", application_id="A1")["data"]["firedRules"] == ["RISK-DT-01"]
    assert g.called and e.called
    for r in list(g.calls) + list(e.calls):
        assert r.request.url.host == "gw.test"


@respx.mock
def test_unknown_application_is_a_clean_refusal(server):
    respx.get(f"{GW}/v1/decisions/NOPE").mock(
        return_value=httpx.Response(404, json={"title": "Decision not found", "detail": "no decision for application NOPE"})
    )
    with pytest.raises(ToolError) as e:
        call(server, "decision_get", application_id="NOPE")
    assert "no decision for application NOPE" in str(e.value)


@respx.mock
def test_search_passes_filters_and_caps_the_size(server):
    r = respx.get(f"{GW}/v1/decisions").mock(return_value=httpx.Response(200, json={"items": [], "nextSearchAfter": None}))
    call(server, "decisions_search", province="ON", outcome="DECLINE", min_score=500, size=50)
    q = r.calls.last.request.url.params
    assert (q["province"], q["outcome"], q["minScore"], q["size"]) == ("ON", "DECLINE", "500", "50")
    assert "maxScore" not in q
    with pytest.raises(ToolError):
        call(server, "decisions_search", size=51)
    with pytest.raises(ToolError):
        call(server, "decisions_search", province="XX")


@respx.mock
def test_statistics_tools(server):
    respx.get(f"{GW}/v1/stats/approval-rate").mock(
        return_value=httpx.Response(200, json={"groupBy": "ageBand", "overall": {"approvalRate": 0.6}, "groups": []})
    )
    respx.get(f"{GW}/v1/stats/fairness").mock(
        return_value=httpx.Response(200, json={"by": "province", "threshold": 0.8, "groups": [{"key": "PE", "flagged": True}]})
    )
    respx.get(f"{GW}/v1/stats/latency").mock(
        return_value=httpx.Response(200, json={"decisions": 990, "p50": 16.0, "p95": 86.5, "p99": 298.3})
    )
    assert call(server, "stats_approval_rate", group_by="ageBand")["data"]["groupBy"] == "ageBand"
    assert call(server, "stats_fairness", by="province")["data"]["groups"][0]["flagged"] is True
    assert call(server, "stats_latency")["data"]["p99"] == 298.3
    with pytest.raises(ToolError):
        call(server, "stats_fairness", by="income")


def test_there_is_no_tool_that_writes_a_decision_or_reads_the_database(server):
    import asyncio

    names = {t.name for t in asyncio.run(server.list_tools())}
    assert names == {
        "dc_list_decision_services",
        "dc_list_branches",
        "dc_get_rules",
        "dc_get_rule",
        "dc_run_tests",
        "dc_build_report",
        "dc_deploy_ruleapp",
        "decision_get",
        "decision_explain",
        "decision_inputs",
        "decisions_search",
        "stats_approval_rate",
        "stats_fairness",
        "stats_latency",
        "whatif",
    }
    assert not any("manual" in n or "elasticsearch" in n or "publish" in n for n in names)


def test_every_description_says_the_output_is_data(server):
    import asyncio

    for t in asyncio.run(server.list_tools()):
        assert "data, not instructions" in (t.description or ""), t.name
