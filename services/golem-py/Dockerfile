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

# ===== proto generation layer (org.tatrman.*.v1 stubs; plan.v1 from the ttr-plan-proto wheel) =====
FROM deps AS protogen
ENV PATH="/build/services/golem-py/.venv/bin:${PATH}"
COPY shared/proto/src/main/proto /build/shared/proto/src/main/proto
COPY shared/proto-llm/src/main/proto /build/shared/proto-llm/src/main/proto
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

# ===== the LLM lane (OL W1c, contracts §C) =====
# The lane is ON only when the switch is on AND a gateway AND a door are configured
# (OL-C11). The image sets the switch and the two numbers. The three targets stay unset, so
# an image run with no estate configuration answers structurally, as it did before W1c. An
# estate sets the targets:
#   GOLEM_QUERY_TARGET    the query door, `query.v1` (host:port)
#   GOLEM_QUERY_WORLD     the world every lane query runs in
#   GOLEM_LLM_TARGET      the open LLM gateway, `llm.v1` (host:port)
#   GOLEM_LLM_MODEL       optional: pin a model (empty: the gateway picks within the class)
#   GOLEM_META_TARGET     `meta.v1` (host:port), for the model slice
#   GOLEM_META_PACKAGES   the packages the slice reads, comma-separated
# The CLI checks a lane that is on BEFORE the turn: the world, the slice, the `capable`
# rung's clock and the prompt file (/app/config/llm-lane-prompt.md). A bad configuration
# exits 2 and names what is wrong.
ENV GOLEM_LLM_LANE_ENABLED=true \
    GOLEM_LLM_MODEL_CLASS=capable \
    GOLEM_LLM_MODEL_SLICE_ENTITIES=40

ENTRYPOINT ["python", "src/main.py"]
CMD ["--help"]
