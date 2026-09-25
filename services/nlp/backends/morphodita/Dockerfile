# RG-P1.S2.T2 — MorphoDiTa self-hosted backend (SELF_HOSTED_PINNED tier).
#
# Builds UFAL MorphoDiTa's `rest_server` (`morphodita_server`) from source and
# bakes the PINNED model `czech-morfflex2.0-pdtc1.0-220710` (= czech-morfflex2.0+pdtc,
# LINDAT hdl:11234/1-4794). The server is launched with an EXPLICIT model id
# (S-1 — no empty/default model). CPU-only, featherweight (~250 MB RSS; Q-10 §3).
#
# ARCH: build for the x86 cluster — `docker buildx build --platform linux/amd64`.
# MorphoDiTa v1.11.3's build hard-codes x86 SSE flags; targeting amd64 (native or
# QEMU) avoids the native-aarch64 patch the Q-10 spike needed for arm64 builds.
#
# Model redistribution (FI-4): the model is CC BY-NC-SA 4.0 — baking/running is
# fine; PUBLISHING this image to a public registry is the gated legal item.
#
#   docker buildx build --platform linux/amd64 \
#     -t nlp-morphodita:dev services/nlp/backends/morphodita

# ===== build stage: compile morphodita_server =====
FROM debian:bookworm-slim AS build

ARG MORPHODITA_VERSION=v1.11.3
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
RUN git clone --depth 1 --branch ${MORPHODITA_VERSION} https://github.com/ufal/morphodita .
# `make server` builds src/rest_server/morphodita_server (the same REST server
# that backs the LINDAT service — so the wire protocol matches the front's
# MorphoDiTa adapter and the Q-10 parity fixtures).
RUN cd src && make -j"$(nproc)" server

# ===== model stage: fetch + verify the pinned tagger =====
FROM debian:bookworm-slim AS model

ARG MODEL_ID=czech-morfflex2.0-pdtc1.0-220710
# LINDAT hdl:11234/1-4794. NOTE: LINDAT is now a DSpace-7 SPA — the legacy
# `/xmlui/bitstream/handle/...zip` URL returns an HTML page, NOT the file. Pass
# the resolved DSpace REST bitstream-content URL as a build-arg (the working URL
# is recorded in the ai-platform Q-10 harness, infra/nlp/spikes/q10):
#   --build-arg MODEL_URL='https://lindat.mff.cuni.cz/repository/server/api/core/bitstreams/<uuid>/content'
# Fill MODEL_SHA256 from the first verified download to pin it (digest-pin).
ARG MODEL_URL=REPLACE_WITH_DSPACE_BITSTREAM_URL
ARG MODEL_SHA256=REPLACE_WITH_VERIFIED_DIGEST

RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl unzip file \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /models
RUN test "${MODEL_URL}" != "REPLACE_WITH_DSPACE_BITSTREAM_URL" \
      || { echo "ERROR: pass --build-arg MODEL_URL=<DSpace bitstream-content URL> (see header)"; exit 1; } \
    && curl -fsSL "${MODEL_URL}" -o model.zip \
    # Fail LOUDLY if LINDAT handed back the SPA instead of the archive.
    && if ! file model.zip | grep -qi zip; then \
         echo "ERROR: downloaded file is not a zip ($(file -b model.zip)) — wrong MODEL_URL (DSpace SPA?)"; exit 1; fi \
    && if [ "${MODEL_SHA256}" != "REPLACE_WITH_VERIFIED_DIGEST" ]; then \
         echo "${MODEL_SHA256}  model.zip" | sha256sum -c -; \
       else echo "WARN: MODEL_SHA256 not pinned — set it after the first verified build"; fi \
    && unzip -q model.zip && rm model.zip \
    # Pick the tagger BY EXACT NAME, never "the first one".
    #
    # ⛔ This archive holds FOUR taggers — the plain one plus `-no_dia`,
    # `-pos_only` and `-no_dia-pos_only`. `find -name '*.tagger' | head -1` took
    # whichever the filesystem happened to list first and the `cp` below then gave
    # it the PINNED name, so /models, the S-1 provenance echo and every model-id
    # check reported the right model while the wrong one was loaded. It shipped
    # `-no_dia` — a diacritics-RESTORATION model, keyed on diacritic-stripped
    # forms — so correctly-spelled Czech was out-of-dictionary and fell to the
    # guesser: `trhů` -> `trhů`, `žena` -> `žit`, while the misspelt `zena`
    # cheerfully returned `žena`. Renaming the file is what made it invisible.
    && TAGGER="$(find . -name "${MODEL_ID}.tagger" -type f)" \
    && if [ -z "${TAGGER}" ] || [ "$(echo "${TAGGER}" | wc -l)" != "1" ]; then \
         echo "ERROR: expected exactly one ${MODEL_ID}.tagger in the archive, found:"; \
         echo "${TAGGER:-(none)}"; \
         echo "archive contains:"; find . -name '*.tagger' -type f | sed 's/^/  /'; \
         exit 1; fi \
    && mkdir -p /opt/model && cp "${TAGGER}" "/opt/model/${MODEL_ID}.tagger" \
    && echo "baked $(basename "${TAGGER}") ($(stat -c%s "/opt/model/${MODEL_ID}.tagger") bytes)"

# ===== runtime =====
FROM debian:bookworm-slim AS runtime

RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -ms /bin/bash appuser

ARG MODEL_ID=czech-morfflex2.0-pdtc1.0-220710
ENV MODEL_ID=${MODEL_ID}
ENV PORT=8080
# --max_request_size raised to 65536 KB so a full bulk-lemmatize call (Q-11 ≤100k
# strings) fits ONE /tag pass (Q-10 §4); default 1024 KB would 413.
ENV MAX_REQUEST_SIZE=65536
ENV CONNECTION_TIMEOUT=120

COPY --from=build /src/src/rest_server/morphodita_server /usr/local/bin/morphodita_server
COPY --from=model /opt/model /opt/model

# ⛔ Build-time proof that the baked model is the right VARIANT, not merely the
# right FILENAME — the check whose absence let `-no_dia` ship for two months under
# the pinned id. Every identity signal (the file name, /models, the S-1 provenance
# echo) is derived from MODEL_ID, so none of them can catch a swapped file. The only
# thing that can is asking the model a question the variants answer differently.
#
# `trhů` is that question. The real tagger lemmatises it to `trh`; the diacritics-
# restoration variant has no diacritic forms in its dictionary, so it falls to the
# guesser and echoes the surface form back. Runs as root, BEFORE `USER appuser`, so
# --log_file=/dev/stderr is writable either way.
RUN <<'SMOKE'
set -eu
morphodita_server --connection_timeout=10 --log_file=/dev/stderr \
  "${PORT}" "${MODEL_ID}" "/opt/model/${MODEL_ID}.tagger" 'build-check' &
server=$!
trap 'kill "${server}" 2>/dev/null || true' EXIT

ready=""
for _ in $(seq 1 60); do
  if curl -fsS -X POST "http://127.0.0.1:${PORT}/tag" \
       --data-urlencode 'data=pes' --data "model=${MODEL_ID}" \
       --data output=vertical --data input=untokenized >/dev/null 2>&1; then
    ready=1; break
  fi
  sleep 1
done
[ -n "${ready}" ] || { echo "ERROR: morphodita_server did not come up for the model smoke test"; exit 1; }

out="$(curl -fsS -X POST "http://127.0.0.1:${PORT}/tag" \
  --data-urlencode 'data=trhů' --data "model=${MODEL_ID}" \
  --data output=vertical --data input=untokenized)"
printf 'smoke: %s\n' "${out}"

# The separators on the wire are JSON escapes — a literal backslash followed by t,
# NOT tab characters (verified with od -c against a live server). Hence `grep -F`
# on a backslash-t needle. And `printf %s`, never `echo`: /bin/sh here is dash,
# whose builtin echo EXPANDS those backslash escapes into real tabs, which would
# make this test fail against a perfectly good model.
if printf '%s' "${out}" | grep -qF 'trhů\ttrh\t'; then
  echo "OK: diacritic input lemmatises (trhů -> trh) — this is the full tagger"
else
  echo "ERROR: 'trhů' did not lemmatise to 'trh'."
  echo "       The baked tagger is NOT czech-morfflex2.0-pdtc1.0-220710 — almost"
  echo "       certainly the -no_dia (diacritics-restoration) variant, which is keyed"
  echo "       on diacritic-stripped forms and guesses on correctly-spelled Czech."
  exit 1
fi
SMOKE

USER appuser
EXPOSE 8080

# tcpSocket-style container health (the raw server exposes no GET path; k8s uses
# a tcpSocket readiness probe — see the chart).
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD curl -fsS -X POST "http://127.0.0.1:${PORT}/tag" --data-urlencode "data=Ahoj" \
      --data "output=vertical" --data "input=untokenized" >/dev/null || exit 1

# S-1: register the tagger under the PINNED model id as its rest_id (NOT a generic
# `czech`). The front addresses MorphoDiTa with `model=${MODEL_ID}` (S-1, no blank
# default); morphodita_server does an exact-match lookup on that rest_id and returns
# 400 "Requested model '…' does not exist" on any mismatch — so the rest_id MUST equal
# the id the front sends. The `<ack>` acknowledgements arg is required by the server.
#
# --log_file: microrestd's rest_server writes a connection log to a file named
# after the binary (default `morphodita_server.log`) in the CWD. The runtime CWD
# is `/` and we run as the non-root `appuser`, so the default path is unwritable
# and the server aborts at startup ("Cannot open log file '...' for writing!").
# Route the log to /dev/stderr — no filesystem write, and the server's log flows
# to `kubectl logs` / `docker logs` like the other backends.
CMD ["sh", "-c", "exec morphodita_server \
  --connection_timeout=${CONNECTION_TIMEOUT} \
  --max_request_size=${MAX_REQUEST_SIZE} \
  --log_file=/dev/stderr \
  ${PORT} ${MODEL_ID} /opt/model/${MODEL_ID}.tagger 'nlp self-hosted MorphoDiTa (CC BY-NC-SA)'"]
