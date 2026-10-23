"""Entry point: stdio for a local client (Claude Code, Claude Desktop), or streamable HTTP with mutual TLS behind the API gateway."""

import argparse
import logging
import os
import ssl
import sys

import uvicorn

from . import config
from .audit import KafkaSink
from .guard import Guard
from .mtls import CertH11Protocol, ClientCertMiddleware
from .tools import build_server


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="odm-dc-mcp")
    parser.add_argument("--transport", choices=["stdio", "streamable-http"], default=os.environ.get("TRANSPORT", "stdio"))
    parser.add_argument("--host", default=os.environ.get("HOST", "0.0.0.0"))  # noqa: S104 - inside the container, behind mutual TLS
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", "3001")))
    parser.add_argument("--tls-cert", default=os.environ.get("TLS_CERT", ""))
    parser.add_argument("--tls-key", default=os.environ.get("TLS_KEY", ""))
    parser.add_argument("--tls-ca", default=os.environ.get("TLS_CA", ""))
    args = parser.parse_args(argv)

    # stdout belongs to the MCP protocol in stdio mode: logs go to stderr
    logging.basicConfig(
        level=logging.INFO, stream=sys.stderr, format='{"level":"%(levelname)s","logger":"%(name)s","message":"%(message)s"}'
    )
    settings = config.load()
    sink = KafkaSink(settings)
    sink.warm()
    guard = Guard(settings, sink)

    if args.transport == "stdio":
        build_server(settings, guard).run(transport="stdio")
        return

    if not (args.tls_cert and args.tls_key and args.tls_ca):
        parser.error("streamable-http needs --tls-cert, --tls-key and --tls-ca: the remote server only speaks mutual TLS")
    mcp = build_server(settings, guard)
    app = ClientCertMiddleware(mcp.streamable_http_app(host=args.host), settings.allowed_client_cns, settings.gateway_cn)
    uvicorn.run(
        app,
        host=args.host,
        port=args.port,
        ssl_certfile=args.tls_cert,
        ssl_keyfile=args.tls_key,
        ssl_ca_certs=args.tls_ca,
        ssl_cert_reqs=ssl.CERT_REQUIRED,
        http=CertH11Protocol,
        log_level="warning",
    )


if __name__ == "__main__":
    main()
