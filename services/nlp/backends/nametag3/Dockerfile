# RG-P1.S2.T3 — NameTag 3 self-hosted backend (SELF_HOSTED_PINNED tier).
#
# Runs UFAL `nametag3_server.py` (ufal/nametag3 v3.2.0) with the PINNED model
# `nametag3-czech-cnec2.0-240830` (LINDAT hdl:11234/1-5677) + the RobeCzech base
# PLM (`ufal/robeczech-base`) BAKED, launched with an EXPLICIT model id (S-1).
# CPU-only torch. Runs fully OFFLINE at runtime (HF_HUB_OFFLINE=1) — no HF/Lindat
# egress — so the RG-P1 determinism/offline claim holds.
#
# Sizing (Q-10 §3/§5): ~1.1 GB RSS, ~72 ms p50, ~12 rps/replica (torch is
# thread-bound → scale by replicas, not concurrency); cold start ~5 s. The
# training-only deps (keras/tensorboard/datasets/peft) are dropped vs the raw
# 5.49 GB spike image.
#
# ARCH: build for the x86 cluster — `docker buildx build --platform linux/amd64`.
# Model redistribution (FI-4): CC BY-NC-SA 4.0 — publishing the image is gated.

# Base images are ghcr.io/collite copies of the Docker official images (.github/workflows/mirror-base-images.yml).
FROM ghcr.io/collite/python:3.11-slim-bookworm AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    git curl ca-certificates unzip \
    && rm -rf /var/lib/apt/lists/*

# ===== deps: nametag3 + CPU torch (inference-only) =====
FROM base AS deps

ARG NAMETAG3_VERSION=v3.2.0
WORKDIR /opt
RUN git clone --depth 1 --branch ${NAMETAG3_VERSION} https://github.com/ufal/nametag3.git

# CPU-only torch from the PyTorch CPU index; transformers + the runtime deps
# nametag3_server imports at STARTUP. The startup import chain
# (nametag3_server → nametag3_dataset / nametag3_dataset_collection /
# nametag3_model → …) loads keras, peft and ufal.udpipe unconditionally at module
# level, so they are runtime deps, NOT training extras — omitting them aborts the
# server on boot (`ModuleNotFoundError: keras` / `peft` / `ufal`). keras 3 runs on
# its torch backend here (nametag3_server sets KERAS_BACKEND=torch before import;
# we also pin it via ENV below), so NO tensorflow is pulled in. The genuinely
# training-only deps stay out: datasets, tensorboard, langcodes, language_data.
# The server speaks raw http.server/socketserver — flask/gunicorn are never
# imported, so they are not installed. Pins track nametag3 v3.2.0's requirements.
RUN pip install --upgrade pip \
    && pip install --index-url https://download.pytorch.org/whl/cpu torch \
    && pip install "transformers>=4.40" "tokenizers" "numpy" "seqeval" \
       "keras~=3.13.1" "peft" "ufal.udpipe~=1.3.1.1"

# ===== models: bake the fine-tuned model + the RobeCzech PLM =====
FROM deps AS models

ARG MODEL_ID=nametag3-czech-cnec2.0-240830
# LINDAT hdl:11234/1-5677. As with MorphoDiTa, LINDAT is a DSpace-7 SPA — pass the
# resolved DSpace REST bitstream-content URL as a build-arg (working URL recorded
# in the ai-platform Q-10 harness). Pin MODEL_SHA256 after the first download.
ARG MODEL_URL=REPLACE_WITH_DSPACE_BITSTREAM_URL
ARG MODEL_SHA256=REPLACE_WITH_VERIFIED_DIGEST

RUN apt-get update && apt-get install -y --no-install-recommends file && rm -rf /var/lib/apt/lists/*
WORKDIR /models
RUN test "${MODEL_URL}" != "REPLACE_WITH_DSPACE_BITSTREAM_URL" \
      || { echo "ERROR: pass --build-arg MODEL_URL=<DSpace bitstream-content URL> (see header)"; exit 1; } \
    && curl -fsSL "${MODEL_URL}" -o model.zip \
    && if ! file model.zip | grep -qi zip; then \
         echo "ERROR: downloaded file is not a zip ($(file -b model.zip)) — wrong MODEL_URL (DSpace SPA?)"; exit 1; fi \
    && if [ "${MODEL_SHA256}" != "REPLACE_WITH_VERIFIED_DIGEST" ]; then \
         echo "${MODEL_SHA256}  model.zip" | sha256sum -c -; \
       else echo "WARN: MODEL_SHA256 not pinned — set it after the first verified build"; fi \
    && unzip -q model.zip && rm model.zip

# Bake the RobeCzech base PLM into the HF cache so runtime is fully offline.
ENV HF_HOME=/opt/hf-cache
RUN python - <<'PY'
from huggingface_hub import snapshot_download
snapshot_download("ufal/robeczech-base")
print("RobeCzech base PLM baked")
PY

# ===== runtime =====
FROM base AS runtime

RUN useradd -ms /bin/bash appuser
ARG MODEL_ID=nametag3-czech-cnec2.0-240830
ENV MODEL_ID=${MODEL_ID} \
    PORT=8001 \
    HF_HOME=/opt/hf-cache \
    HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1 \
    KERAS_BACKEND=torch

COPY --from=deps /usr/local /usr/local
COPY --from=deps /opt/nametag3 /opt/nametag3
COPY --from=models /models /models
COPY --from=models /opt/hf-cache /opt/hf-cache
RUN chown -R appuser:appuser /models /opt/hf-cache

USER appuser
WORKDIR /opt/nametag3
EXPOSE 8001

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=5 \
  CMD curl -fsS -X POST "http://127.0.0.1:${PORT}/recognize" \
      --data-urlencode "data=Praha" --data "output=conll" --data "input=untokenized" \
      >/dev/null || exit 1

# S-1: explicit model id on launch (server-model-name model-spec model-dir).
# Model spec `<id>:czech:cs` binds the served model to cs (nametag3_server args).
CMD ["sh", "-c", "exec python3 ./nametag3_server.py ${PORT} ${MODEL_ID} \
  ${MODEL_ID}:czech:cs /models/${MODEL_ID}/ '' 'nlp self-hosted NameTag 3 (CC BY-NC-SA)'"]
