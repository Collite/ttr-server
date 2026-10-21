# The official Superset image, plus what it does not ship: the Postgres driver its metadata database needs, Authlib
# for signing in with Keycloak, and ttr-sqlalchemy (the tatrman:// dialect and the Superset engine spec).
#
# TTR_SQLALCHEMY is a pip requirement: `ttr-sqlalchemy` (the newest release, the default) or a pin such as
# `ttr-sqlalchemy==0.1.0`. A wheel dropped into ./wheels/ is a candidate too; that is how to try an unreleased build.
FROM apache/superset:6.1.0

USER root
ARG TTR_SQLALCHEMY=ttr-sqlalchemy
COPY wheels/ /tmp/wheels/
RUN uv pip install --python /app/.venv/bin/python --no-cache --find-links /tmp/wheels \
        psycopg2-binary "authlib>=1.3,<2" "${TTR_SQLALCHEMY}" \
    && rm -rf /tmp/wheels
COPY superset_config.py /app/pythonpath/superset_config.py
COPY bundle/ /app/tatrman/bundle/
USER superset
