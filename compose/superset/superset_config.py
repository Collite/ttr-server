# SPDX-License-Identifier: Apache-2.0
"""Superset for the compose convenience: an evaluation setup next to a Tatrman Server, not a production topology.

Everything deployment-specific comes from the environment (`.env`; see `.env.example`). Two Keycloak flows meet here:

- **Signing in to Superset** is FAB's `keycloak` provider. It maps `preferred_username` to the Superset username,
  which is the name the edge reads from a token, and it maps the `groups` claim to Superset roles.
- **Reading a Tatrman database** is Superset's per-database OAuth2 with the engine spec's client
  (`DATABASE_OAUTH2_CLIENTS["tatrman"]`). The first time a viewer opens a database, Keycloak asks them to consent.
  After that, Superset keeps their token, refreshes it, and the engine spec sends it to the edge as the bearer.
"""

import os

from flask_appbuilder.security.manager import AUTH_OAUTH


def _env(name: str, default: str | None = None) -> str:
    value = os.environ.get(name) or default
    if not value:
        raise RuntimeError(f"compose/superset: set {name} in .env (see .env.example)")
    return value


SECRET_KEY = _env("SUPERSET_SECRET_KEY")
SQLALCHEMY_DATABASE_URI = "postgresql+psycopg2://superset:superset@metadata:5432/superset"

KEYCLOAK_ISSUER = _env("KEYCLOAK_ISSUER").rstrip("/")  # https://<keycloak host>/realms/<realm>

AUTH_TYPE = AUTH_OAUTH
OAUTH_PROVIDERS = [
    {
        "name": "keycloak",
        "icon": "fa-key",
        "token_key": "access_token",
        "remote_app": {
            "client_id": _env("KEYCLOAK_CLIENT_ID"),
            "client_secret": _env("KEYCLOAK_CLIENT_SECRET"),
            "server_metadata_url": f"{KEYCLOAK_ISSUER}/.well-known/openid-configuration",
            "api_base_url": f"{KEYCLOAK_ISSUER}/protocol/",
            "client_kwargs": {"scope": "openid email profile"},
        },
    }
]
AUTH_USER_REGISTRATION = True
AUTH_USER_REGISTRATION_ROLE = "Gamma"
AUTH_ROLES_MAPPING = {_env("SUPERSET_ADMIN_GROUP", "superset-admins"): ["Admin"]}
AUTH_ROLES_SYNC_AT_LOGIN = True

# The engine spec's client. It may be the sign-in client, given both redirect URIs (see .env.example).
DATABASE_OAUTH2_CLIENTS = {
    "tatrman": {
        "id": _env("TATRMAN_CLIENT_ID", os.environ.get("KEYCLOAK_CLIENT_ID")),
        "secret": _env("TATRMAN_CLIENT_SECRET", os.environ.get("KEYCLOAK_CLIENT_SECRET")),
        "issuer": KEYCLOAK_ISSUER,
    }
}
# The default, stated so it is seen: a Tatrman database without the viewer's own token refuses to connect.
BI_REQUIRE_IMPERSONATION = True

# No workers, no cache, no alerts: one container (the Server's governed path is the product here, not Superset's).
FEATURE_FLAGS = {"ALERT_REPORTS": False}
TALISMAN_ENABLED = False  # served on http://localhost
