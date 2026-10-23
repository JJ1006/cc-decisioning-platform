#!/usr/bin/env python3
"""A minimal MCP client for the REMOTE odm-dc server through the API gateway, with the mcp-client certificate (mutual TLS).

    uv run --project mcp/odm-dc-mcp-server python scripts/mcp/remote_client.py list
    uv run --project mcp/odm-dc-mcp-server python scripts/mcp/remote_client.py call decision_explain application_id=APP-00000986
    uv run --project mcp/odm-dc-mcp-server python scripts/mcp/remote_client.py call stats_fairness by=province

Arguments are key=value (numbers and JSON are parsed). The gateway URL, CA and certificate come from the same places as the other scripts.
"""

import asyncio
import json
import os
import pathlib
import ssl
import sys
from typing import Any

import httpx2
from mcp.client.streamable_http import streamable_http_client

from mcp import ClientSession

ROOT = pathlib.Path(__file__).resolve().parents[2]
PKI = pathlib.Path(os.environ.get("PKI_OUT", ROOT / "security/pki/out"))
URL = os.environ.get("MCP_URL", "https://localhost:8443/mcp/dc")
CLIENT = os.environ.get("MCP_CLIENT_CERT_NAME", "mcp-client")


def http_client() -> httpx2.AsyncClient:
    ctx = ssl.create_default_context(cafile=str(PKI / "ca/ca.crt"))
    ctx.load_cert_chain(
        str(PKI / CLIENT / f"{CLIENT}.crt"), str(PKI / CLIENT / f"{CLIENT}.key")
    )
    return httpx2.AsyncClient(timeout=httpx2.Timeout(60), verify=ctx)


def parse(pairs: list[str]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for p in pairs:
        k, _, v = p.partition("=")
        try:
            out[k] = json.loads(v)
        except ValueError:
            out[k] = v
    return out


async def main(argv: list[str]) -> int:
    async with (
        streamable_http_client(URL, http_client=http_client()) as (read, write),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        if argv[0] == "list":
            for t in (await session.list_tools()).tools:
                print(f"{t.name}: {(t.description or '')[:100]}")
            return 0
        result = await session.call_tool(argv[1], parse(argv[2:]))
        text = result.content[0].text  # type: ignore[union-attr]
        print(text)
        return 1 if result.is_error else 0


if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in ("list", "call"):
        sys.exit(__doc__)
    sys.exit(asyncio.run(main(sys.argv[1:])))
