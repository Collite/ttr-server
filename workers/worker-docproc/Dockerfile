# Repository-root context, matching worker-polars. All dependencies are lockfile-pinned.
FROM ghcr.io/collite/python:3.13-slim
ENV UV_PROJECT_ENVIRONMENT=/app/.venv
ENV PATH=/app/.venv/bin:$PATH
WORKDIR /app
RUN pip install --no-cache-dir uv==0.5.*
COPY workers/worker-docproc/pyproject.toml workers/worker-docproc/uv.lock workers/worker-docproc/README.md /app/workers/worker-docproc/
COPY shared/libs/python/otel-config /app/shared/libs/python/otel-config
WORKDIR /app/workers/worker-docproc
COPY workers/worker-docproc/src /app/workers/worker-docproc/src
RUN uv sync --frozen --no-dev
COPY workers/worker-docproc/conf /app/workers/worker-docproc/conf
ENV TIKTOKEN_CACHE_DIR=/app/workers/worker-docproc/src/docproc/_data/tiktoken
EXPOSE 7310
CMD ["uv", "run", "--frozen", "--no-dev", "python", "-m", "docproc.main"]
