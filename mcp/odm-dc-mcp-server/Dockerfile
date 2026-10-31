# syntax=docker/dockerfile:1
# Build context is the repository root:  docker build -f mcp/odm-dc-mcp-server/Dockerfile .
FROM python:3.15-slim-bookworm@sha256:4e03260adeeebafccd084f3584e6b84823aba5651cfb53ef8a078ed441ddf9cd AS build
RUN pip install --no-cache-dir uv==0.13.1
ENV UV_LINK_MODE=copy UV_COMPILE_BYTECODE=1 UV_PYTHON_PREFERENCE=only-system
WORKDIR /app
COPY mcp/odm-dc-mcp-server/pyproject.toml mcp/odm-dc-mcp-server/uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev --no-install-project
COPY mcp/odm-dc-mcp-server/src src
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev --no-editable

FROM python:3.15-slim-bookworm@sha256:4e03260adeeebafccd084f3584e6b84823aba5651cfb53ef8a078ed441ddf9cd
# the runtime needs no pip, setuptools or wheel (their bundled copies of urllib3, certifi and msgpack were what the image scan flagged)
RUN pip uninstall -y pip setuptools wheel && mkdir /app && chgrp 0 /app && chmod g=u /app
COPY --from=build --chown=10001:0 /app/.venv /app/.venv
# Non-root, and any UID works (OpenShift assigns a random one in group 0)
USER 10001
WORKDIR /app
ENV PATH=/app/.venv/bin:$PATH PYTHONUNBUFFERED=1 TRANSPORT=streamable-http PORT=3001
EXPOSE 3001
ENTRYPOINT ["odm-dc-mcp"]
