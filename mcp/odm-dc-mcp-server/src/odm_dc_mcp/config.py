"""Configuration from the environment. No secrets in code, none in .mcp.json: scripts/mcp/odm-dc.sh exports them from the local env file."""

from __future__ import annotations

import os
from dataclasses import dataclass, field


def _bool(name: str, default: bool = False) -> bool:
    return os.environ.get(name, str(default)).strip().lower() in {"1", "true", "yes"}


def _int(name: str, default: int) -> int:
    return int(os.environ.get(name, default))


@dataclass(frozen=True)
class Settings:
    # Decision Center REST API (mutual TLS to ODM + the ODM demo user)
    dc_url: str = field(default_factory=lambda: os.environ.get("DC_URL", "https://localhost:9443/decisioncenter-api"))
    dc_username: str = field(default_factory=lambda: os.environ.get("DC_USERNAME", "odmAdmin"))
    dc_password: str = field(default_factory=lambda: os.environ.get("DC_PASSWORD", ""))
    # Rule Execution Server (what-if runs): mutual TLS with the mcp-res certificate + the ODM demo user
    res_url: str = field(default_factory=lambda: os.environ.get("RES_URL", "https://localhost:9443"))
    res_username: str = field(default_factory=lambda: os.environ.get("RES_USERNAME", "resAdmin"))
    res_password: str = field(default_factory=lambda: os.environ.get("RES_PASSWORD", ""))
    res_path: str = field(default_factory=lambda: os.environ.get("RES_PATH", "/DecisionService/rest/ccApprovalApp/1.0/ccApproval/1.0"))
    # the API gateway (decisions and statistics, never the database): mutual TLS with the mcp-agent certificate
    gateway_url: str = field(default_factory=lambda: os.environ.get("GATEWAY_URL", "https://localhost:8443"))
    ca_file: str = field(default_factory=lambda: os.environ.get("CA_FILE", ""))
    res_cert: str = field(default_factory=lambda: os.environ.get("MCP_RES_CERT", ""))
    res_cert_key: str = field(default_factory=lambda: os.environ.get("MCP_RES_CERT_KEY", ""))
    agent_cert: str = field(default_factory=lambda: os.environ.get("MCP_AGENT_CERT", ""))
    agent_cert_key: str = field(default_factory=lambda: os.environ.get("MCP_AGENT_CERT_KEY", ""))
    # audit trail: every tool call becomes an event on cc.audit.v1 (mutual TLS to Kafka, mcp-agent certificate)
    kafka_bootstrap: str = field(default_factory=lambda: os.environ.get("KAFKA_BOOTSTRAP", "localhost:9093"))
    audit_topic: str = field(default_factory=lambda: os.environ.get("AUDIT_TOPIC", "cc.audit.v1"))
    audit_required: bool = field(default_factory=lambda: _bool("AUDIT_REQUIRED", True))
    # guardrails
    max_result_bytes: int = field(default_factory=lambda: _int("MAX_RESULT_BYTES", 20_000))
    rate_limit_per_minute: int = field(default_factory=lambda: _int("RATE_LIMIT_PER_MINUTE", 60))
    # deployment: off unless both are set
    allow_deploy: bool = field(default_factory=lambda: _bool("ALLOW_DEPLOY", False))
    deploy_approval_token: str = field(default_factory=lambda: os.environ.get("DEPLOY_APPROVAL_TOKEN", ""))
    # who is calling when there is no gateway in front (stdio)
    default_caller: str = field(default_factory=lambda: os.environ.get("MCP_CALLER", "local-stdio"))
    # remote mode: the connection must present one of these certificate CNs; only the gateway's headers are believed
    allowed_client_cns: tuple[str, ...] = field(
        default_factory=lambda: tuple(c for c in os.environ.get("ALLOWED_CLIENT_CNS", "apisix").split(",") if c)
    )
    gateway_cn: str = field(default_factory=lambda: os.environ.get("GATEWAY_CN", "apisix"))


def load() -> Settings:
    return Settings()
