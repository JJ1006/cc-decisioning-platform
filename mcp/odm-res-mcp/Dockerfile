# syntax=docker/dockerfile:1
# The official IBM ODM Decision MCP server (Apache-2.0, github.com/DecisionsDev/ibm-odm-decision-mcp-server) at a pinned commit, remote mode
# (streamable HTTP, port 3000). Build context is the repository root:  docker build -f mcp/odm-res-mcp/Dockerfile .
ARG SERVER_SHA=c08180cea3013d9e57cafa3085b288ed596e668c
FROM python:3.15-slim-bookworm@sha256:4e03260adeeebafccd084f3584e6b84823aba5651cfb53ef8a078ed441ddf9cd AS build
ARG SERVER_SHA
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates \
 && rm -rf /var/lib/apt/lists/* && pip install --no-cache-dir uv==0.13.1
ENV UV_TOOL_DIR=/opt/tools UV_TOOL_BIN_DIR=/opt/bin UV_PYTHON_PREFERENCE=only-system
RUN uv tool install --from "git+https://github.com/DecisionsDev/ibm-odm-decision-mcp-server@${SERVER_SHA}" decision-mcp-server

FROM python:3.15-slim-bookworm@sha256:4e03260adeeebafccd084f3584e6b84823aba5651cfb53ef8a078ed441ddf9cd
COPY --from=build /opt/tools /opt/tools
COPY --from=build /opt/bin /opt/bin
COPY mcp/odm-res-mcp/entrypoint.sh /opt/entrypoint.sh
# Non-root, and any UID works (OpenShift assigns a random one in group 0)
# the runtime needs no pip, setuptools or wheel (their bundled copies of urllib3, certifi and msgpack were what the image scan flagged)
RUN chmod 755 /opt/entrypoint.sh && pip uninstall -y pip setuptools wheel
USER 10001
ENV PATH=/opt/bin:$PATH PYTHONUNBUFFERED=1
EXPOSE 3000
ENTRYPOINT ["/opt/entrypoint.sh"]
