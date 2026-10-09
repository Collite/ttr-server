# RG-P1.S3.T2 — Stanza self-hosted backend (SELF_HOSTED_PINNED).
#
# Runs the uniform-JSON server (server.py) with Stanza cs+en models BAKED
# (pre-downloaded at build). CPU-only torch. The heavy model layer is isolated
# so app edits don't invalidate it (cached-base-layer pattern). Stanza is the cs
# DEP_PARSE hot path (feeds the resolver's span proposal) — cold-start budget
# per Q-10; the k8s readiness probe is set generously.
#
#   docker build -t nlp-stanza:dev services/nlp/backends/stanza

# Base images are ghcr.io/collite copies of the Docker official images (.github/workflows/mirror-base-images.yml).
FROM ghcr.io/collite/python:3.13-slim-bookworm AS base
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# ===== deps (CPU torch + stanza + server) =====
FROM base AS deps
RUN pip install --upgrade pip \
    && pip install --index-url https://download.pytorch.org/whl/cpu torch \
    && pip install "stanza>=1.10.0" "fastapi>=0.128.0" "uvicorn[standard]>=0.40.0"

# ===== models (baked; cache-busts only on dep change) =====
FROM deps AS models
ENV STANZA_RESOURCES_DIR=/opt/nlp-models/stanza
RUN python - <<'PY'
import stanza
for lang in ["cs", "en"]:
    stanza.download(lang, model_dir="/opt/nlp-models/stanza")
PY

# ===== runtime =====
FROM base AS runtime
RUN useradd -ms /bin/bash appuser
COPY --from=deps /usr/local /usr/local
COPY --from=models /opt/nlp-models /opt/nlp-models
RUN chown -R appuser:appuser /opt/nlp-models
USER appuser
WORKDIR /app
COPY --chown=appuser:appuser server.py ./
ENV STANZA_RESOURCES_DIR=/opt/nlp-models/stanza PORT=8090
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=5 \
  CMD curl -fsS "http://127.0.0.1:${PORT}/healthz" || exit 1
CMD ["python", "server.py"]
