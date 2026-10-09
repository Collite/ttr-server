# Base images are ghcr.io/collite copies of the Docker official images (.github/workflows/mirror-base-images.yml).
FROM ghcr.io/collite/python:3.13-slim

ENV UV_PROJECT_ENVIRONMENT=/app/.venv
ENV PATH=/app/.venv/bin:$PATH

WORKDIR /app

RUN pip install --no-cache-dir uv==0.5.*

COPY workers/worker-polars/pyproject.toml workers/worker-polars/uv.lock workers/worker-polars/README.md /app/workers/worker-polars/
COPY shared /app/shared

WORKDIR /app/workers/worker-polars

RUN uv sync --frozen --no-dev

COPY workers/worker-polars/src /app/workers/worker-polars/src
COPY workers/worker-polars/conf /app/workers/worker-polars/conf

EXPOSE 7300 7301

CMD ["uv", "run", "python", "-m", "workers_polars.main"]
