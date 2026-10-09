# morph-studio — the LM editorial service (⚑LMP-D5).
#
# One instance per world (LM-5): `MORPH_WORLD` is required and the container
# refuses to start without it.
#
# Build from the repo root — the uv path dependencies (`ttr-morph`, `ttr-nlp`)
# resolve there and nowhere else:
#   docker build -t morph-studio:dev -f services/morph-studio/Dockerfile .
#
# ⚑ `ttr-morph` is NOT on PyPI (⚑LMP-D4) and neither is this image's ability to
# build outside the monorepo. That is deliberate: the studio proposes what the
# compiler will accept, which means running the compiler's own code.

# Base images are ghcr.io/collite copies of the Docker official images (.github/workflows/mirror-base-images.yml).
FROM ghcr.io/collite/python:3.13-slim-bookworm AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# ===== dependencies layer =====
FROM base AS deps

WORKDIR /build/services/morph-studio
# The two workspace libraries this service is built on. `psycopg[binary]` means
# no libpq build stage — the wheel carries it.
COPY shared/libs/python/ttr-nlp /build/shared/libs/python/ttr-nlp
COPY shared/libs/python/ttr-morph /build/shared/libs/python/ttr-morph
COPY services/morph-studio/pyproject.toml services/morph-studio/uv.lock ./
# `--no-dev`: a runtime image has no business carrying pytest, ruff and a
# protobuf compiler. The one build tool this image needs is installed in the
# stage that needs it, below — the same shape `services/nlp` uses, and the
# reason its image had stopped building at all.
RUN pip install --upgrade pip && pip install uv && uv sync --frozen --no-dev

# ===== proto generation layer (org.tatrman.{nlp,common}.v1 stubs) =====
#
# ⛑⛑ Without this the Q-7 RELOAD LEG DOES NOT WORK — the one place this service
# touches the estate. `ttr-nlp[grpc]` is supposed to carry the stubs, and for a
# PyPI wheel it does: `hatch_build.py` bundles them into `ttrnlp/_proto` at wheel
# BUILD time. This image installs `ttr-nlp` from a path, so that hook never runs
# and `NlpClient()` raises "the org.tatrman.nlp.v1 proto stubs are not available"
# — lazily, inside `reload_front`, which catches everything and reports it as a
# reload failure. So the overlay was written, the front was never told, and the
# enrichment loop silently took as long as the front's own reload schedule.
#
# Nothing before the arc-gate-7 tier could see it: every unit test leaves
# `MORPH_STUDIO_FRONT_TARGET` empty, which is a supported deployment and returns
# before the client is ever constructed.
FROM deps AS protogen
ENV PATH="/build/services/morph-studio/.venv/bin:${PATH}"
# Pinned to the version in `services/nlp`'s lock, so both images' stubs come
# from the same compiler.
RUN uv pip install "grpcio-tools==1.81.1"
COPY shared/proto/src/main/proto /build/shared/proto/src/main/proto
RUN python /build/shared/libs/python/ttr-nlp/scripts/gen_proto.py

# ===== the FI-7 frontend (NLS-P9.3) =====
#
# Built here and served by the backend — one image, one process, one port. An
# nginx sidecar would buy a chart, a Service, an ingress rule and a CORS policy
# in order to serve files the API process can already read.
#
# `npm ci` needs the lockfile and nothing else from the repo, so this layer is
# cached across every backend change.
FROM ghcr.io/collite/node:24-slim AS frontend

WORKDIR /fe
COPY services/morph-studio/frontend/package.json services/morph-studio/frontend/package-lock.json ./
RUN npm ci
# ⚑ Named, not `COPY frontend/ ./`. The build context is the repo root and has
# no .dockerignore, so a wildcard would drag a developer's `node_modules` and
# `dist` into the image layer — hundreds of megabytes, and a `dist` from
# whenever somebody last ran the build locally.
COPY services/morph-studio/frontend/index.html services/morph-studio/frontend/env.d.ts ./
COPY services/morph-studio/frontend/vite.config.ts services/morph-studio/frontend/tsconfig*.json ./
COPY services/morph-studio/frontend/src ./src
RUN npm run build-only

# ===== runtime =====
FROM base AS runtime

RUN useradd -ms /bin/bash appuser

# ⛑ The two writable directories, created and owned BEFORE the volumes are
# mounted over them (NLS-P9.3 T7). Docker initialises an empty named volume from
# the image's contents at that path — ownership included — so a path that does
# not exist in the image produces a root-owned volume that the unprivileged
# process cannot write. Found on the gate stack, where it cost the Q-7 overlay
# ("Permission denied: /var/lib/morph-studio/overlay/.emit-…") and would have
# cost `POST /export?write=true` the same way.
RUN mkdir -p /var/lib/morph-studio/overlay /var/lib/morph-studio/export \
    && chown -R appuser:appuser /var/lib/morph-studio

WORKDIR /app

COPY --from=deps /usr/local /usr/local
COPY --from=deps /build/services/morph-studio/.venv /build/services/morph-studio/.venv
COPY --from=deps /build/shared /build/shared
COPY --from=protogen /build/shared/libs/python/ttr-nlp/generated ./generated

USER appuser
COPY --chown=appuser:appuser services/morph-studio/pyproject.toml \
                             services/morph-studio/README.md \
                             services/morph-studio/alembic.ini ./
COPY --chown=appuser:appuser services/morph-studio/src ./src
COPY --chown=appuser:appuser services/morph-studio/migrations ./migrations
COPY --from=frontend --chown=appuser:appuser /fe/dist ./frontend/dist

ENV PATH="/build/services/morph-studio/.venv/bin:${PATH}"
ENV PYTHONPATH=/app/src:/app/generated
ENV MORPH_STUDIO_HOST=0.0.0.0 \
    MORPH_STUDIO_PORT=7290 \
    MORPH_STUDIO_EXPORT_DIR=/var/lib/morph-studio/export \
    MORPH_STUDIO_STATIC_DIR=/app/frontend/dist

EXPOSE 7290

# ⚑ The schema is alembic's, not the service's: `alembic upgrade head` runs as a
# migration job (or an init container), and the app never creates tables. A
# service that quietly created them on boot would let a missing migration go
# unnoticed until the first environment that had an older database.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://127.0.0.1:${MORPH_STUDIO_PORT:-7290}/healthz || exit 1

CMD ["python", "src/main.py"]
