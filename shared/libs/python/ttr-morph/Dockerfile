# `nlp-morph-tools` — the `ttr-morph` CLI, containerized (LM contracts §10).
#
# This image exists because `ttr-morph` deliberately stays off PyPI (⚑LMP-D4):
# the published artifact of the LM effort is the *snapshot*, not an editorial
# toolchain nobody asked us to promise compatibility for. World repos still need
# to validate their own `*.morph.yaml` layer files before opening a PR (NLB-P5
# T3), and a container is how they get the exact validator the publish lane runs
# without installing anything.
#
# Build from the repo root — the wheel is a path dependency:
#   docker build -t nlp-morph-tools:dev -f shared/libs/python/ttr-morph/Dockerfile .
#
# Use:
#   docker run --rm -v "$PWD:/work" nlp-morph-tools:dev validate /work/*.morph.yaml
#   docker run --rm -v "$PWD:/work" nlp-morph-tools:dev compile /work/*.morph.yaml \
#       -o /work/dist/cs.morph.snap --snapshot-version 0.1.0

# Base images are ghcr.io/collite copies of the Docker official images (.github/workflows/mirror-base-images.yml).
FROM ghcr.io/collite/python:3.13-slim-bookworm AS build

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /build/shared/libs/python/ttr-morph

# The wheel first: `ttr-morph` depends on it for `fold` and the snapshot format,
# and the sibling path source in pyproject.toml resolves relative to here.
COPY shared/libs/python/ttr-nlp /build/shared/libs/python/ttr-nlp
COPY shared/libs/python/ttr-morph /build/shared/libs/python/ttr-morph

RUN pip install --upgrade pip && pip install uv && uv sync --no-dev

FROM ghcr.io/collite/python:3.13-slim-bookworm AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN useradd -ms /bin/bash analyst
COPY --from=build /build /build

USER analyst
WORKDIR /work

ENV PATH="/build/shared/libs/python/ttr-morph/.venv/bin:${PATH}"

# No default arguments: every subcommand of this CLI reads or writes files the
# caller mounted, so a default would be a guess about somebody else's layout.
ENTRYPOINT ["ttr-morph"]
CMD ["--help"]
