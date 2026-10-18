"""Remote mode: who is on the other end of the TLS connection.

uvicorn terminates TLS with client certificates REQUIRED (our CA) and hands the peer certificate to the ASGI app. This middleware lets only the listed
certificate CNs through (the gateway, CN=apisix) and takes the caller's identity from X-Consumer only on such a connection, the same rule as the Java services
(docs/security-mtls.md): nobody else can present that certificate, so nobody else can assert an identity.
"""

import logging
import uuid
from typing import Any

from cryptography import x509
from cryptography.x509.oid import NameOID

from .guard import CALLER, CORRELATION

LOG = logging.getLogger("odm_dc_mcp.mtls")


def peer_cn(scope: dict[str, Any]) -> str | None:
    chain = (scope.get("extensions") or {}).get("tls", {}).get("client_cert_chain") or []
    if not chain:
        return None
    cert = x509.load_pem_x509_certificate(chain[0].encode() if isinstance(chain[0], str) else chain[0])
    cns = cert.subject.get_attributes_for_oid(NameOID.COMMON_NAME)
    return str(cns[0].value) if cns else None


class ClientCertMiddleware:
    def __init__(self, app: Any, allowed_cns: tuple[str, ...], gateway_cn: str) -> None:
        self.app = app
        self.allowed = set(allowed_cns)
        self.gateway_cn = gateway_cn

    async def __call__(self, scope: dict[str, Any], receive: Any, send: Any) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        cn = peer_cn(scope)
        if cn is None or cn not in self.allowed:
            LOG.warning("client certificate CN %s is not allowed", cn)
            await _problem(send, 403 if cn else 401, "Caller not allowed" if cn else "Client certificate required")
            return
        headers = {k.decode().lower(): v.decode() for k, v in scope.get("headers", [])}
        if cn == self.gateway_cn:
            consumer, role = headers.get("x-consumer"), headers.get("x-role")
            if not consumer or not role:
                await _problem(send, 403, "Identity missing")
                return
            caller = f"{consumer}:{role}"
        else:
            caller = cn
        t1 = CALLER.set(caller)
        t2 = CORRELATION.set(headers.get("x-correlation-id") or str(uuid.uuid4()))
        try:
            await self.app(scope, receive, send)
        finally:
            CALLER.reset(t1)
            CORRELATION.reset(t2)


async def _problem(send: Any, status: int, title: str) -> None:
    body = f'{{"type":"about:blank","title":"{title}","status":{status}}}'.encode()
    await send(
        {
            "type": "http.response.start",
            "status": status,
            "headers": [(b"content-type", b"application/problem+json"), (b"content-length", str(len(body)).encode())],
        }
    )
    await send({"type": "http.response.body", "body": body})
