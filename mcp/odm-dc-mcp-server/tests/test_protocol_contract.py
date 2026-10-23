"""The wire contract of the server as a client sees it: stdio (a subprocess, like Claude Code starts it) and streamable HTTP (the SDK app, without the
certificate layer that test_mtls.py covers). Guards the SDK upgrade: same 15 tools, same schemas, refusals reach the client with their reason."""

import asyncio
import socket
import sys
import threading
import time

import uvicorn
from mcp.client import Client
from mcp.client.stdio import StdioServerParameters

from conftest import RecordingSink, make_settings
from odm_dc_mcp.guard import Guard
from odm_dc_mcp.tools import build_server

TOOLS = {
    "dc_build_report", "dc_deploy_ruleapp", "dc_get_rule", "dc_get_rules", "dc_list_branches", "dc_list_decision_services", "dc_run_tests",
    "decision_explain", "decision_get", "decision_inputs", "decisions_search", "stats_approval_rate", "stats_fairness", "stats_latency", "whatif",
}  # fmt: skip
STDIO_ENV = {
    "AUDIT_REQUIRED": "false",
    "KAFKA_BOOTSTRAP": "127.0.0.1:1",
    "GATEWAY_URL": "https://127.0.0.1:1",
    "DC_URL": "https://127.0.0.1:1",
}


async def check_contract(client: Client) -> None:
    tools = (await client.list_tools()).tools
    assert {t.name for t in tools} == TOOLS
    for t in tools:
        assert t.input_schema["type"] == "object"
    # an unknown tool is an error result the client can read, not a crash
    unknown = await client.call_tool("nope", {})
    assert unknown.is_error
    assert "Unknown tool" in unknown.content[0].text


def test_stdio_contract() -> None:
    params = StdioServerParameters(command=sys.executable, args=["-m", "odm_dc_mcp.server"], env=STDIO_ENV)

    async def run() -> None:
        async with Client(params) as client:
            await check_contract(client)

    asyncio.run(asyncio.wait_for(run(), 60))


def test_streamable_http_contract() -> None:
    s = make_settings()
    srv = build_server(s, Guard(s, RecordingSink()))
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(srv.streamable_http_app(host="127.0.0.1"), host="127.0.0.1", port=port, log_level="error"))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    try:
        for _ in range(100):
            if server.started:
                break
            time.sleep(0.05)
        assert server.started

        async def run() -> None:
            async with Client(f"http://127.0.0.1:{port}/mcp") as client:
                await check_contract(client)
                # a guardrail refusal keeps its reason: deploy is off unless both ALLOW_DEPLOY and the token are set (stdio skips this: its audit sink is real Kafka)
                refused = await client.call_tool("dc_deploy_ruleapp", {"service": "cc-approval", "approval_token": "x"})
                assert refused.is_error
                assert "disabled" in refused.content[0].text

        asyncio.run(asyncio.wait_for(run(), 60))
    finally:
        server.should_exit = True
        thread.join(10)
