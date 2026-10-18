"""HTTP clients of the three backends. Each has its own client certificate; all verify our CA."""

from __future__ import annotations

import ssl
from typing import Any

import httpx

from .config import Settings


def _ssl(ca_file: str, crt: str, key: str) -> ssl.SSLContext | bool:
    """TLS context that verifies our CA and presents the client certificate (httpx ignores `cert=` when `verify=` is a path)."""
    if not ca_file and not crt:
        return True
    ctx = ssl.create_default_context(cafile=ca_file or None)
    if crt and key:
        ctx.load_cert_chain(crt, key)
    return ctx


def dc_client(s: Settings) -> httpx.Client:
    """Decision Center REST API: mutual TLS (any certificate our CA signed) plus the ODM demo user."""
    return httpx.Client(
        base_url=s.dc_url,
        verify=_ssl(s.ca_file, s.res_cert, s.res_cert_key),
        auth=(s.dc_username, s.dc_password),
        timeout=30,
        headers={"Accept": "application/json"},
    )


def res_client(s: Settings) -> httpx.Client:
    """Rule Execution Server (what-if only): the mcp-res certificate plus the ODM demo user."""
    return httpx.Client(
        base_url=s.res_url,
        verify=_ssl(s.ca_file, s.res_cert, s.res_cert_key),
        auth=(s.res_username, s.res_password),
        timeout=30,
        headers={"Accept": "application/json", "Content-Type": "application/json"},
    )


def gateway_client(s: Settings, correlation_id: str | None = None) -> httpx.Client:
    """The API gateway as the mcp-agent consumer: it may read decisions, explanations and statistics, nothing else."""
    headers = {"Accept": "application/json"}
    if correlation_id:
        headers["X-Correlation-Id"] = correlation_id
    return httpx.Client(
        base_url=s.gateway_url,
        verify=_ssl(s.ca_file, s.agent_cert, s.agent_cert_key),
        timeout=30,
        headers=headers,
    )


def get_json(client: httpx.Client, path: str, params: dict[str, Any] | None = None) -> Any:
    r = client.get(path, params={k: v for k, v in (params or {}).items() if v is not None})
    r.raise_for_status()
    return r.json()
