# SPDX-License-Identifier: Apache-2.0
# Build from the repository root, matching the Python release lane.
FROM docker.getcollate.io/openmetadata/ingestion-base:2.0.6
# 2.0.6 ingestion-base uses openmetadata (the full Airflow image uses airflow).
USER openmetadata
COPY --chown=openmetadata:0 connectors/openmetadata/pyproject.toml connectors/openmetadata/README.md /app/
COPY --chown=openmetadata:0 connectors/openmetadata/src /app/src
# The pinned base provides the SDK, requests, YAML and Pydantic.
RUN pip install --no-cache-dir "rdflib==7.6.0" \
    && pip install --no-cache-dir --no-deps /app \
    && python -c "from importlib.metadata import version; assert version('openmetadata-ingestion') == '2.0.6.0'"
ENTRYPOINT ["ttr-connect-openmetadata"]
