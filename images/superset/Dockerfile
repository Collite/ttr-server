# BI (contracts C-18) — the Superset image an operated deployment runs: the official image, plus what it does not
# ship. Published by release-image.yml as `ghcr.io/collite/superset:<superset version>-ttr<N>` on a
# `superset/v<superset version>-ttr<N>` tag (`superset/v6.1.0-ttr1` → `superset:6.1.0-ttr1`).
#
#   psycopg2-binary   the metadata database's driver (Postgres; the image ships none)
#   authlib           signing in with Keycloak (AUTH_OAUTH) and the per-database OAuth2 flow; 6.1.0 ships none
#   ttr-sqlalchemy    the `tatrman://` dialect and the Superset engine spec (entry point `superset.db_engine_specs`)
#
# Everything is pinned: an image tag is never rebuilt, so a new pin is a new `-ttr<N>`. Configuration
# (superset_config.py) is NOT baked in — the deployment supplies it (the Helm chart's config Secret); the
# compose convenience under compose/superset/ is the self-serve variant that does bake one.
#
# ⚑ The base is Docker Hub's, not a ghcr.io/collite mirror: mirror-base-images.yml copies Docker *official*
# images only, and apache/superset is not one. One pull per release tag; on `TOOMANYREQUESTS`, rerun the job.
FROM apache/superset:6.1.0

USER root
RUN uv pip install --python /app/.venv/bin/python --no-cache \
        "psycopg2-binary==2.9.13" "authlib==1.9.1" "ttr-sqlalchemy==0.11.0" \
    # The build fails here, not at the first sign-in, if the spec cannot load inside THIS Superset.
    && /app/.venv/bin/python -c "import psycopg2, authlib; \
from importlib.metadata import entry_points; \
spec = {e.name: e for e in entry_points(group='superset.db_engine_specs')}['tatrman'].load(); \
assert spec.engine == 'tatrman', spec.engine; print('tatrman engine spec:', spec.__name__)"
USER superset
