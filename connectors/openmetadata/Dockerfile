# SPDX-License-Identifier: Apache-2.0
# Build from the repository root, matching the Python release lane.
ARG OM_IMAGE_REGISTRY=docker.getcollate.io
# Vendor and Google cache have the identical multi-platform index; pin its bytes.
FROM ${OM_IMAGE_REGISTRY}/openmetadata/ingestion-base:2.0.6@sha256:b7228ccffde7e5d0eb89e48d2c5247476d7b0e87924e11de0c929150a28668b9
# 2.0.6 ingestion-base uses openmetadata (the full Airflow image uses airflow).
USER openmetadata
COPY --chown=openmetadata:0 connectors/openmetadata/pyproject.toml connectors/openmetadata/README.md /app/
COPY --chown=openmetadata:0 connectors/openmetadata/src /app/src
# The pinned base provides the SDK, requests, YAML and Pydantic.
RUN pip install --no-cache-dir "rdflib==7.6.0" \
    && pip install --no-cache-dir --no-deps /app \
    && python -c "from importlib.metadata import version; assert version('openmetadata-ingestion') == '2.0.6.0'"
ENTRYPOINT ["ttr-connect-openmetadata"]
