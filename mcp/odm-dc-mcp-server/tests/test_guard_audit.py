"""Audit events, the audit contract, fail-closed, rate limit, size cap, the data-not-instructions notice."""

import json

import httpx
import pytest
import respx
from jsonschema import Draft202012Validator
from mcp.server.mcpserver.exceptions import ToolError
from referencing import Registry, Resource

from conftest import REPO, RecordingSink, call, make_settings
from odm_dc_mcp.guard import NOTICE, Guard
from odm_dc_mcp.tools import build_server

SCHEMAS = {p.name: json.loads(p.read_text()) for p in (REPO / "contracts").glob("*.schema.json")}
REGISTRY = Registry().with_resources((s["$id"], Resource.from_contents(s)) for s in SCHEMAS.values())


def valid(event: dict) -> list[str]:
    return [e.message for e in Draft202012Validator(SCHEMAS["audit.schema.json"], registry=REGISTRY).iter_errors(event)]


def ds_ok() -> None:
    respx.get("https://dc.test/decisioncenter-api/v1/decisionservices").mock(
        return_value=httpx.Response(200, json={"elements": [{"id": "ds1", "name": "cc-approval", "description": "d"}]})
    )


@respx.mock
def test_every_call_is_one_audit_event_that_matches_the_contract(server, sink):
    ds_ok()
    out = call(server, "dc_list_decision_services")
    assert out["notice"] == NOTICE and out["data"][0]["name"] == "cc-approval"
    assert len(sink.events) == 1
    e = sink.events[0]
    assert valid(e) == []
    p = e["payload"]
    assert p["action"] == "MCP_TOOL_CALL" and p["resourceId"] == "dc_list_decision_services" and p["result"] == "SUCCESS"
    assert p["actor"] == "local-stdio" and p["details"]["tool"] == "dc_list_decision_services"
    assert e["producer"] == "odm-dc-mcp-server"


@respx.mock
def test_arguments_are_never_written_only_their_names_and_a_hash(server, sink):
    respx.get("https://gw.test/v1/decisions/APP-SECRET-123").mock(
        return_value=httpx.Response(200, json={"applicationId": "x", "outcome": "APPROVE"})
    )
    call(server, "decision_get", application_id="APP-SECRET-123")
    raw = json.dumps(sink.events[0])
    assert "APP-SECRET-123" not in raw
    d = sink.events[0]["payload"]["details"]
    assert d["argNames"] == ["application_id"] and len(d["argsSha256"]) == 16


@respx.mock
def test_a_failing_backend_is_audited_as_failure_and_does_not_leak_details(server, sink):
    respx.get("https://dc.test/decisioncenter-api/v1/decisionservices").mock(return_value=httpx.Response(500, text="stack trace here"))
    with pytest.raises(ToolError) as e:
        call(server, "dc_list_decision_services")
    assert "stack trace" not in str(e.value)
    assert sink.events[-1]["payload"]["result"] == "FAILURE" and valid(sink.events[-1]) == []


@respx.mock
def test_audit_down_means_no_data(settings):
    ds_ok()
    srv = build_server(settings, Guard(settings, RecordingSink(ok=False)))
    with pytest.raises(ToolError) as e:
        call(srv, "dc_list_decision_services")
    assert "audit trail unavailable" in str(e.value)


@respx.mock
def test_audit_can_be_made_optional_for_local_experiments():
    s = make_settings(audit_required=False)
    ds_ok()
    srv = build_server(s, Guard(s, RecordingSink(ok=False)))
    assert call(srv, "dc_list_decision_services")["data"]


@respx.mock
def test_rate_limit_refuses_and_audits_the_denial():
    s = make_settings(rate_limit_per_minute=2)
    sink = RecordingSink()
    srv = build_server(s, Guard(s, sink))
    ds_ok()
    call(srv, "dc_list_decision_services")
    call(srv, "dc_list_decision_services")
    with pytest.raises(ToolError) as e:
        call(srv, "dc_list_decision_services")
    assert "rate limit" in str(e.value)
    assert [ev["payload"]["result"] for ev in sink.events] == ["SUCCESS", "SUCCESS", "DENIED"]


@respx.mock
def test_big_results_are_cut(settings, sink):
    s = make_settings(max_result_bytes=300)
    respx.get("https://dc.test/decisioncenter-api/v1/decisionservices").mock(
        return_value=httpx.Response(
            200, json={"elements": [{"id": str(i), "name": f"service-{i}", "description": "x" * 40} for i in range(30)]}
        )
    )
    srv = build_server(s, Guard(s, sink))
    out = call(srv, "dc_list_decision_services")
    assert out["truncated"] is True and "narrow the request" in out["note"]


@respx.mock
def test_personal_data_in_a_backend_answer_is_masked_before_it_leaves(server):
    respx.get("https://gw.test/v1/decisions/A1").mock(
        return_value=httpx.Response(200, json={"applicationId": "A1", "note": "call 416-555-0199, SIN 046 454 286", "lastName": "Tremblay"})
    )
    out = json.dumps(call(server, "decision_get", application_id="A1"))
    assert "555-0199" not in out and "046" not in out and "Tremblay" not in out


class FlakyProducer:
    """A producer whose first delivery fails (a cold connection after a broker restart) and whose later ones work."""

    def __init__(self, failures: int) -> None:
        self.failures = failures
        self.sent: list[str] = []
        self._callbacks: list = []

    def produce(self, topic: str, key: str, value: str, on_delivery) -> None:
        self.sent.append(value)
        self._callbacks.append(on_delivery)

    def flush(self, _timeout: float) -> int:
        for cb in self._callbacks:
            cb(RuntimeError("broker not available") if self.failures > 0 else None, None)
            self.failures -= 1
        self._callbacks.clear()
        return 0

    def list_topics(self, _topic: str, timeout: float) -> None:
        raise RuntimeError("no broker")


def test_kafka_sink_retries_the_first_failed_send_once() -> None:
    from odm_dc_mcp.audit import KafkaSink

    sink = KafkaSink(make_settings())
    sink._producer = FlakyProducer(failures=1)
    assert sink.emit({"eventId": "e1"}, "k") is True
    assert len(sink._producer.sent) == 2 and sink._producer.sent[0] == sink._producer.sent[1]


def test_kafka_sink_fails_closed_after_the_retry() -> None:
    from odm_dc_mcp.audit import KafkaSink

    sink = KafkaSink(make_settings())
    sink._producer = FlakyProducer(failures=5)
    assert sink.emit({"eventId": "e1"}, "k") is False
    assert len(sink._producer.sent) == 2


def test_kafka_sink_warm_never_raises() -> None:
    from odm_dc_mcp.audit import KafkaSink

    sink = KafkaSink(make_settings())
    sink._producer = FlakyProducer(failures=0)
    sink.warm()  # the producer's list_topics raises; warm-up is best effort
