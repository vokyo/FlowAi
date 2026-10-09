# syntax=docker/dockerfile:1.7

FROM python:3.14-slim AS build

COPY --from=ghcr.io/astral-sh/uv:0.12.10 /uv /bin/uv

# Compile bytecode at build time, copy files out of uv's cache rather than linking
# them, and use this image's Python instead of downloading another.
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never

WORKDIR /app

# Dependencies first, so a code change does not reinstall them.
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project

# The project itself goes into the environment as a package, not as a link to
# src/, so the runtime image needs nothing but the environment.
COPY README.md ./
COPY src/ src/
RUN uv sync --locked --no-dev --no-editable


FROM python:3.14-slim AS runtime

RUN groupadd --system flowai \
    && useradd --system --gid flowai --no-create-home flowai

WORKDIR /app

COPY --from=build --chown=flowai:flowai /app/.venv /app/.venv

# An empty HOST listens on every IPv4 and every IPv6 address. Railway's private
# network may reach a service over either, and uvicorn given "::" takes IPv6 only.
# Only the backend should reach the agent, which does not check the tokens it is
# given, so the agent must never get a public address.
ENV PATH="/app/.venv/bin:$PATH" \
    HOST="" \
    PORT=8000

USER flowai

EXPOSE 8000

# FastAPI serves its OpenAPI document as soon as the app has started.
HEALTHCHECK --interval=15s \
    --timeout=3s \
    --start-period=20s \
    --retries=5 \
    CMD python -c "import os, urllib.request; urllib.request.urlopen('http://localhost:' + os.environ['PORT'] + '/openapi.json', timeout=2)" || exit 1

CMD ["sh", "-c", "exec uvicorn flowai_agent.main:app --host \"$HOST\" --port \"$PORT\""]
