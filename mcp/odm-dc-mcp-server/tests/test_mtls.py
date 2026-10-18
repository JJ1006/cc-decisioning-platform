"""Remote mode: the certificate decides who may connect and whose headers are believed."""

import asyncio
import datetime

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

from odm_dc_mcp.guard import CALLER, CORRELATION
from odm_dc_mcp.mtls import PEERS, ClientCertMiddleware


def der(cn: str) -> bytes:
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn)])
    now = datetime.datetime.now(datetime.UTC)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(1)
        .not_valid_before(now)
        .not_valid_after(now + datetime.timedelta(days=1))
        .sign(key, hashes.SHA256())
    )
    return cert.public_bytes(serialization.Encoding.DER)


def run(cn: str | None, headers: dict[str, str]) -> tuple[int, dict[str, str | None]]:
    seen: dict[str, str | None] = {}

    async def app(scope, receive, send):
        seen["caller"], seen["correlation"] = CALLER.get(), CORRELATION.get()
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"ok"})

    mw = ClientCertMiddleware(app, ("apisix", "mcp-agent"), "apisix")
    client = ("127.0.0.1", 40000 + len(PEERS))
    if cn:
        PEERS[client] = der(cn)  # what CertH11Protocol records for the connection
    scope = {"type": "http", "client": client, "headers": [(k.encode(), v.encode()) for k, v in headers.items()]}
    status: list[int] = []

    async def send(msg):
        if msg["type"] == "http.response.start":
            status.append(msg["status"])

    asyncio.run(mw(scope, None, send))
    return status[0], seen


def test_no_certificate_is_401_and_another_cn_is_403():
    assert run(None, {})[0] == 401
    assert run("demo-partner", {"x-consumer": "underwriter-portal", "x-role": "UNDERWRITER"})[0] == 403


def test_the_gateway_is_believed_and_its_consumer_becomes_the_caller():
    status, seen = run("apisix", {"x-consumer": "mcp-client", "x-role": "MCP_CLIENT", "x-correlation-id": "c-42"})
    assert status == 200 and seen == {"caller": "mcp-client:MCP_CLIENT", "correlation": "c-42"}


def test_the_gateway_without_identity_headers_is_refused():
    assert run("apisix", {})[0] == 403


def test_a_service_certificate_is_its_own_caller_and_headers_are_ignored():
    status, seen = run("mcp-agent", {"x-consumer": "somebody-else", "x-role": "OPS"})
    assert status == 200 and seen["caller"] == "mcp-agent"
