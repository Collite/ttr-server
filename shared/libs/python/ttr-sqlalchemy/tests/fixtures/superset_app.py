# SPDX-License-Identifier: Apache-2.0
"""The Superset configuration `tests/test_superset_spec.py` builds its app from: Superset's defaults, metadata in a
throwaway SQLite file, and a placeholder per-database OAuth2 client (no Keycloak is called; token requests are faked).
"""

import os

from superset.config import *  # noqa: F403

SECRET_KEY = "test-only-not-a-secret-32-bytes-long"
SQLALCHEMY_DATABASE_URI = os.environ["TTR_SUPERSET_TEST_METADATA"]
DATABASE_OAUTH2_REDIRECT_URI = "https://superset.example.com/api/v1/database/oauth2/"
DATABASE_OAUTH2_CLIENTS = {
    "tatrman": {
        "id": "superset-db",
        "secret": "test-only-not-a-secret",
        "issuer": "https://keycloak.example.com/realms/hartland",
    }
}
