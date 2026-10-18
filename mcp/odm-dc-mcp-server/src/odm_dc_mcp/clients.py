"""HTTP clients of the three backends. Each has its own client certificate; all verify our CA."""

from __future__ import annotations

from typing import Any

import httpx

from .config import Settings


def _verify(settings: Settings) -> str | bool:
    return settings.ca_file or True


def _cert(crt: str, key: str) -> tuple[str, str] | None:
    return (crt, key) if crt and key else None


def dc_client(s: Settings) -> httpx.Client:
    """Decision Center REST API: mutual TLS (any certificate our CA signed) plus the ODM demo user."""
    return httpx.Client(
        base_url=s.dc_url,
        verify=_verify(s),
        cert=_cert(s.res_cert, s.res_cert_key),
        auth=(s.dc_username, s.dc_password),
        timeout=30,
        headers={"Accept": "application/json"},
    )


def res_client(s: Settings) -> httpx.Client:
    """Rule Execution Server (what-if only): the mcp-res certificate plus the ODM demo user."""
    return httpx.Client(
        base_url=s.res_url,
        verify=_verify(s),
        cert=_cert(s.res_cert, s.res_cert_key),
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
        verify=_verify(s),
        cert=_cert(s.agent_cert, s.agent_cert_key),
        timeout=30,
        headers=headers,
    )


def get_json(client: httpx.Client, path: str, params: dict[str, Any] | None = None) -> Any:
    r = client.get(path, params={k: v for k, v in (params or {}).items() if v is not None})
    r.raise_for_status()
    return r.json()
