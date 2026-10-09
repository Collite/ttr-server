# SPDX-License-Identifier: Apache-2.0
# golem-py — the Python OS Golem (RV-P4). Multi-stage, cloned from services/nlp's
# shape: uv + a proto-generation layer + a thin app layer, non-root at runtime.
#
# ⚑ No server port is exposed, and that is the design (see src/main.py): the OS Golem
# is the resolution LOOP, driven by the CLI here and by the conformance runner. The
# conversation door belongs to the platform shell. When a serving surface is specified,
# it gets its own port and its own health contract — inventing one now would ship a
# door nobody has agreed on.
#
# Build from the repo root (the shared .proto tree resolves there):
#   docker build -t golem-py:dev -f services/golem-py/Dockerfile .

# Base images are ghcr.io/collite copies of the Docker official images (.github/workflows/mirror-base-images.yml).
FROM ghcr.io/collite/python:3.13-slim-bookworm AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# ===== dependencies layer =====
FROM base AS deps

WORKDIR /build/services/golem-py
COPY services/golem-py/pyproject.toml services/golem-py/uv.lock ./
RUN pip install --upgrade pip && pip install uv && uv sync --frozen

# ===== proto generation layer (org.tatrman.{resolver,nlp,common}.v1 stubs) =====
FROM deps AS protogen
ENV PATH="/build/services/golem-py/.venv/bin:${PATH}"
COPY shared/proto/src/main/proto /build/shared/proto/src/main/proto
COPY services/golem-py/scripts ./scripts
RUN python scripts/gen_proto.py   # → /build/services/golem-py/generated/org/...

# ===== runtime layer =====
FROM base AS runtime

RUN useradd -ms /bin/bash appuser
WORKDIR /app

COPY --from=deps /usr/local /usr/local
COPY --from=deps /build/services/golem-py/.venv /build/services/golem-py/.venv
COPY --from=protogen /build/services/golem-py/generated ./generated

USER appuser
COPY --chown=appuser:appuser services/golem-py/pyproject.toml services/golem-py/README.md ./
COPY --chown=appuser:appuser services/golem-py/src ./src
# The SHIPPED zero-rung ladder (RV-27). An estate overrides it by mounting its own file
# and pointing GOLEM_LADDER_CONFIG at it — the same posture the lexicon archive takes.
COPY --chown=appuser:appuser services/golem-py/config ./config

ENV PATH="/build/services/golem-py/.venv/bin:${PATH}"
ENV PYTHONPATH=/app/src:/app/generated
ENV GOLEM_LADDER_CONFIG=/app/config/golem-ladder.yaml

ENTRYPOINT ["python", "src/main.py"]
CMD ["--help"]
