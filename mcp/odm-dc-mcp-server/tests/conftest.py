"""Shared fixtures: settings that point at fake hosts (respx answers them), an audit sink that records, a server built on both."""

import asyncio
import json
import pathlib
from typing import Any

import pytest
from mcp.server.mcpserver import MCPServer

from odm_dc_mcp.config import Settings
from odm_dc_mcp.guard import Guard
from odm_dc_mcp.tools import build_server

REPO = pathlib.Path(__file__).resolve().parents[3]


class RecordingSink:
    def __init__(self, ok: bool = True) -> None:
        self.events: list[dict[str, Any]] = []
        self.ok = ok

    def emit(self, event: dict[str, Any], key: str) -> bool:
        if self.ok:
            self.events.append(event)
        return self.ok


def make_settings(**kw: Any) -> Settings:
    base: dict[str, Any] = {
        "dc_url": "https://dc.test/decisioncenter-api",
        "dc_password": "pw",
        "res_url": "https://res.test",
        "res_password": "pw",
        "gateway_url": "https://gw.test",
        "rate_limit_per_minute": 1000,
    }
    base.update(kw)
    return Settings(**base)


@pytest.fixture
def sink() -> RecordingSink:
    return RecordingSink()


@pytest.fixture
def settings() -> Settings:
    return make_settings()


@pytest.fixture
def server(settings: Settings, sink: RecordingSink) -> MCPServer:
    return build_server(settings, Guard(settings, sink))


def call(server: MCPServer, tool: str, **args: Any) -> dict[str, Any]:
    """Call a tool the way a client does; returns the parsed result envelope."""
    result = asyncio.run(server.call_tool(tool, args))
    text = result.content[0].text
    return json.loads(text)  # type: ignore[no-any-return]
