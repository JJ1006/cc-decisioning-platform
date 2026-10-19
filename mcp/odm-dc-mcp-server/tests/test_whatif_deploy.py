"""What-if is a labelled simulation that publishes nothing; deployment is off unless explicitly enabled and approved."""

import httpx
import pytest
import respx
from mcp.server.fastmcp.exceptions import ToolError

from conftest import RecordingSink, call, make_settings
from odm_dc_mcp.guard import Guard
from odm_dc_mcp.tools import build_server

DC = "https://dc.test/decisioncenter-api"
GW = "https://gw.test"
RES = "https://res.test/DecisionService/rest/ccApprovalApp/1.0/ccApproval/1.0"

APP = {
    "applicationId": "APP-1",
    "requestedProduct": "CASHBACK",
    "requestedLimit": 8000,
    "monthlyHousingCost": 1800,
    "channel": "WEB",
    "applicant": {
        "applicantId": "C1",
        "firstName": "A",
        "lastName": "T",
        "ageYears": 36,
        "residencyStatus": "CITIZEN",
        "sin": "046454286",
        "dateOfBirth": "1990-05-14",
    },
    "address": {"line1": "1 Queen St", "city": "Toronto", "province": "ON", "postalCode": "M5V 2T6"},
    "employment": {"status": "EMPLOYED", "employer": "X", "annualIncome": 85000, "monthsAtEmployer": 48},
    "bureau": {
        "score": 742,
        "monthlyDebtPayments": 650,
        "existingCreditLimit": 12000,
        "delinquencies": 0,
        "inquiriesLast6Months": 1,
        "activeBankruptcy": False,
        "fileAgeMonths": 120,
    },
}


def res_by_income(request: httpx.Request) -> httpx.Response:
    import json

    app = json.loads(request.content)["application"]
    assert "sin" not in app["applicant"] and "dateOfBirth" not in app["applicant"], "identifiers must not reach the rules"
    if app["employment"]["annualIncome"] < 30000:
        return httpx.Response(
            200,
            json={
                "decision": {"outcome": "DECLINE", "creditLimit": 0, "reasonCodes": ["R_INSUFFICIENT_INCOME"], "firedRules": ["ELIG-05"]}
            },
        )
    return httpx.Response(
        200,
        json={
            "decision": {
                "outcome": "APPROVE",
                "creditLimit": 5000,
                "product": "CASHBACK",
                "aprTier": "TIER_2",
                "reasonCodes": ["R_APPROVED_STANDARD"],
                "firedRules": ["RSN-01"],
            }
        },
    )


@respx.mock
def test_whatif_with_a_full_application_shows_the_outcome_diff_and_says_simulation(server):
    res = respx.post(RES).mock(side_effect=res_by_income)
    out = call(server, "whatif", application=APP, overrides={"income": 20000})["data"]
    assert out["SIMULATION"].startswith("SIMULATION - not a decision")
    assert out["withoutOverrides"]["outcome"] == "APPROVE" and out["withOverrides"]["outcome"] == "DECLINE"
    assert out["outcomeChanged"] is True
    assert out["diff"]["outcome"] == {"from": "APPROVE", "to": "DECLINE"}
    assert res.call_count == 2


@respx.mock
def test_whatif_by_application_id_lists_its_assumptions(server):
    respx.get(f"{GW}/v1/decisions/A9").mock(
        return_value=httpx.Response(
            200,
            json={
                "applicationId": "A9",
                "outcome": "REFER",
                "decisionSource": "ODM",
                "creditLimit": 0,
                "province": "QC",
                "ageBand": "35-44",
                "bureauScore": 640,
            },
        )
    )
    respx.get(f"{GW}/v1/decisions/A9/inputs").mock(
        return_value=httpx.Response(404, json={"detail": "no decision inputs for application A9"})
    )
    respx.post(RES).mock(side_effect=res_by_income)
    out = call(server, "whatif", application_id="A9", overrides={"score": 700})["data"]
    assert out["actualDecision"]["outcome"] == "REFER"
    assert any("assumed" in a for a in out["assumptions"])  # a decision from before the inputs were kept: indicative


INPUTS = {
    "applicationId": "A10",
    "decisionId": "e1",
    "applicantToken": "00000000000000000000000000000001",
    "inputs": {
        "age": 36,
        "province": "ON",
        "residencyStatus": "CITIZEN",
        "employmentStatus": "EMPLOYED",
        "annualIncome": 85000,
        "monthsAtEmployer": 48,
        "monthlyHousingCost": 1800,
        "monthlyDebt": 650,
        "tds": 0.35,
        "creditScore": 742,
        "bankruptcyFlag": False,
        "delinquencies": 0,
        "inquiriesLast6Months": 1,
        "fileAgeMonths": 120,
        "existingCreditLimit": 12000,
        "requestedLimit": 8000,
        "requestedProduct": "CASHBACK",
        "applicantToken": "00000000000000000000000000000001",
    },
}


@respx.mock
def test_whatif_by_application_id_uses_the_exact_inputs_and_has_no_assumptions(server):
    respx.get(f"{GW}/v1/decisions/A10").mock(
        return_value=httpx.Response(200, json={"applicationId": "A10", "outcome": "APPROVE", "decisionSource": "ODM", "creditLimit": 8000})
    )
    respx.get(f"{GW}/v1/decisions/A10/inputs").mock(return_value=httpx.Response(200, json=INPUTS))
    sent = []

    def res(request: httpx.Request) -> httpx.Response:
        import json

        sent.append(json.loads(request.content)["application"])
        return res_by_income(request)

    respx.post(RES).mock(side_effect=res)
    out = call(server, "whatif", application_id="A10", overrides={"income": 20000})["data"]
    assert out["assumptions"] == []  # exact: nothing was assumed
    assert out["withoutOverrides"]["outcome"] == "APPROVE" and out["withOverrides"]["outcome"] == "DECLINE"
    base = sent[0]  # the run without overrides saw the stored values, not defaults
    assert base["employment"]["annualIncome"] == 85000 and base["bureau"]["score"] == 742 and base["bureau"]["monthlyDebtPayments"] == 650
    assert base["monthlyHousingCost"] == 1800 and base["applicant"]["ageYears"] == 36 and base["requestedLimit"] == 8000


@respx.mock
def test_whatif_never_talks_to_kafka_or_the_decision_publisher(server, sink):
    respx.post(RES).mock(side_effect=res_by_income)
    call(server, "whatif", application=APP, overrides={"debt": 100})
    hosts = {c.request.url.host for c in respx.calls}
    assert hosts == {"res.test"}
    assert len(sink.events) == 1  # only the audit event of the call itself


@respx.mock
def test_whatif_rejects_unknown_overrides_and_missing_input(server):
    with pytest.raises(ToolError) as e:
        call(server, "whatif", application=APP, overrides={"outcome": 1})
    assert "unknown override" in str(e.value)
    with pytest.raises(ToolError):
        call(server, "whatif")


def deploy_server(**kw):
    s = make_settings(**kw)
    sink = RecordingSink()
    return build_server(s, Guard(s, sink)), sink


def deploy_mocks():
    respx.get(f"{DC}/v1/decisionservices").mock(return_value=httpx.Response(200, json={"elements": [{"id": "ds1", "name": "cc-approval"}]}))
    respx.get(f"{DC}/v1/decisionservices/ds1/deployments").mock(
        return_value=httpx.Response(
            200, json={"elements": [{"id": "d1", "name": "ccApproval", "ruleAppName": "ccApprovalApp", "ruleAppVersion": "1.0"}]}
        )
    )
    respx.get(f"{DC}/v1/servers").mock(
        return_value=httpx.Response(200, json={"elements": [{"id": "s1", "name": "Decision Service Execution"}]})
    )


@respx.mock
def test_deploy_is_disabled_by_default_and_the_refusal_is_audited():
    srv, sink = deploy_server()
    with pytest.raises(ToolError) as e:
        call(srv, "dc_deploy_ruleapp", service="cc-approval", approval_token="anything")
    assert "disabled" in str(e.value)
    assert sink.events[-1]["payload"]["result"] == "DENIED"
    assert not respx.calls  # not even a request to Decision Center


@respx.mock
def test_deploy_needs_the_approval_token():
    srv, _ = deploy_server(allow_deploy=True, deploy_approval_token="s3cret-token")
    for token in ("", "wrong"):
        with pytest.raises(ToolError) as e:
            call(srv, "dc_deploy_ruleapp", service="cc-approval", approval_token=token)
        assert "approval_token" in str(e.value)
    assert not respx.calls


@respx.mock
def test_deploy_with_token_returns_a_plan_and_changes_nothing_without_execute():
    srv, _ = deploy_server(allow_deploy=True, deploy_approval_token="s3cret-token")
    deploy_mocks()
    post = respx.post(url__regex=rf"{DC}/v1/deployments/.*").mock(return_value=httpx.Response(200, json={"status": "COMPLETED"}))
    out = call(srv, "dc_deploy_ruleapp", service="cc-approval", approval_token="s3cret-token")["data"]
    assert out["executed"] is False and "plan only" in out["note"] and len(out["steps"]) == 2
    assert not post.called


@respx.mock
def test_deploy_executes_only_with_token_and_execute_true():
    srv, sink = deploy_server(allow_deploy=True, deploy_approval_token="s3cret-token")
    deploy_mocks()
    respx.post(f"{DC}/v1/deployments/d1/build").mock(return_value=httpx.Response(200, json={"status": "COMPLETED"}))
    deploy = respx.post(f"{DC}/v1/deployments/d1/deploy").mock(return_value=httpx.Response(200, json={}))
    out = call(srv, "dc_deploy_ruleapp", service="cc-approval", approval_token="s3cret-token", execute=True)["data"]
    assert out["executed"] is True and deploy.calls.last.request.url.params["serverId"] == "s1"
    assert "s3cret-token" not in str(sink.events)  # the token is an argument: never audited
