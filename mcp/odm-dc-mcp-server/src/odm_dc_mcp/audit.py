"""Audit trail: every tool call is one event on cc.audit.v1 (contracts/audit.schema.json), published over mutual TLS.

What is recorded: who called (the gateway consumer, or the local stdio caller), which tool, the outcome, the size of the result and the time it took,
and the NAMES of the arguments plus a short hash of their values. The values themselves are never written (they may contain an application id or
free text typed by a person): the audit says that a call was made and by whom, the hash lets an investigator match a known call.
"""

from __future__ import annotations

import hashlib
import json
import logging
import uuid
from datetime import UTC, datetime
from typing import Any, Protocol

from .config import Settings

LOG = logging.getLogger("odm_dc_mcp.audit")
PRODUCER = "odm-dc-mcp-server"
ACTION = "MCP_TOOL_CALL"


class Sink(Protocol):
    def emit(self, event: dict[str, Any], key: str) -> bool: ...


def args_digest(args: dict[str, Any]) -> str:
    raw = json.dumps(args, sort_keys=True, default=str).encode()
    return hashlib.sha256(raw).hexdigest()[:16]


def build_event(
    tool: str,
    caller: str,
    outcome: str,
    args: dict[str, Any],
    correlation_id: str,
    result_bytes: int,
    millis: int,
    error: str | None = None,
) -> dict[str, Any]:
    """An audit envelope. `outcome` is SUCCESS, FAILURE or DENIED."""
    details: dict[str, Any] = {
        "tool": tool,
        "caller": caller,
        "outcome": outcome,
        "argNames": sorted(args),
        "argsSha256": args_digest(args),
        "resultBytes": result_bytes,
        "durationMs": millis,
    }
    if error:
        details["error"] = error[:200]
    return {
        "eventId": str(uuid.uuid4()),
        "correlationId": correlation_id,
        "eventType": "cc.audit.v1",
        "schemaVersion": 1,
        "occurredAt": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z",
        "producer": PRODUCER,
        "payload": {
            "actor": caller,
            "action": ACTION,
            "resourceType": "mcp-tool",
            "resourceId": tool,
            "result": outcome,
            "details": details,
        },
    }


class KafkaSink:
    """Idempotent producer with mutual TLS (the mcp-agent certificate). Created on first use."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._producer: Any = None

    def _client(self) -> Any:
        if self._producer is None:
            from confluent_kafka import Producer  # imported late: needs librdkafka only when auditing

            s = self._settings
            self._producer = Producer(
                {
                    "bootstrap.servers": s.kafka_bootstrap,
                    "security.protocol": "SSL",
                    "ssl.ca.location": s.ca_file,
                    "ssl.certificate.location": s.agent_cert,
                    "ssl.key.location": s.agent_cert_key,
                    "ssl.endpoint.identification.algorithm": "none",
                    "enable.idempotence": True,
                    "acks": "all",
                    "client.id": PRODUCER,
                    "socket.timeout.ms": 10000,
                    "message.timeout.ms": 12000,
                }
            )
        return self._producer

    def emit(self, event: dict[str, Any], key: str) -> bool:
        delivered: list[bool] = []

        def done(err: Any, _msg: Any) -> None:
            delivered.append(err is None)

        try:
            producer = self._client()
            producer.produce(self._settings.audit_topic, key=key, value=json.dumps(event), on_delivery=done)
            remaining = producer.flush(15)
            if remaining:
                LOG.error("audit event not delivered in time (%d message(s) pending)", remaining)
        except Exception:  # noqa: BLE001
            LOG.exception("audit event could not be produced")
            return False
        return bool(delivered) and all(delivered)
