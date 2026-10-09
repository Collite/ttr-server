# RG-P1.S3.T2 — spaCy self-hosted backend (SELF_HOSTED_PINNED).
#
# Uniform-JSON server (server.py) with en_core_web_md BAKED. English tokenize +
# NER (the NER fallback). Lightweight (~40 MB model); no torch needed.
#
#   docker build -t nlp-spacy:dev services/nlp/backends/spacy

# Base images are ghcr.io/collite copies of the Docker official images (.github/workflows/mirror-base-images.yml).
FROM ghcr.io/collite/python:3.13-slim-bookworm AS base
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# ===== deps + baked model =====
FROM base AS deps
RUN pip install --upgrade pip \
    && pip install "spacy>=3.8.0" "fastapi>=0.128.0" "uvicorn[standard]>=0.40.0" \
    && python -m spacy download en_core_web_md

# ===== runtime =====
FROM deps AS runtime
RUN useradd -ms /bin/bash appuser
USER appuser
WORKDIR /app
COPY --chown=appuser:appuser server.py ./
ENV SPACY_MODEL=en_core_web_md PORT=8091
EXPOSE 8091
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=5 \
  CMD curl -fsS "http://127.0.0.1:${PORT}/healthz" || exit 1
CMD ["python", "server.py"]
