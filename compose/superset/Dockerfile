# The operated Superset image (images/superset: official Superset + psycopg2-binary, authlib and ttr-sqlalchemy, all
# pinned) plus this pairing's own configuration and the example databases. One install recipe for both: compose
# reads exactly what an operated deployment runs.
#
# SUPERSET_IMAGE picks another tag of it. To try an unreleased ttr-sqlalchemy, drop its wheel into ./wheels/: it
# replaces the image's pinned one (the rest of the image stays as released).
ARG SUPERSET_IMAGE=ghcr.io/collite/superset:6.1.0-ttr1
FROM ${SUPERSET_IMAGE}

USER root
COPY wheels/ /tmp/wheels/
RUN if ls /tmp/wheels/*.whl >/dev/null 2>&1; then \
        uv pip install --python /app/.venv/bin/python --no-cache --no-deps --reinstall /tmp/wheels/*.whl; \
    fi \
    && rm -rf /tmp/wheels
COPY superset_config.py /app/pythonpath/superset_config.py
COPY bundle/ /app/tatrman/bundle/
USER superset
