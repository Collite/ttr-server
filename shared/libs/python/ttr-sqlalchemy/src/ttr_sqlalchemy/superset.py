# SPDX-License-Identifier: Apache-2.0
"""The Superset engine spec for `tatrman://` (BI contract C-16), registered as `superset.db_engine_specs: tatrman`.

Written against Superset 6.1.0. Only Superset loads it, through the entry point, and nothing else in the package imports
it, so the driver and the dialect never need Superset installed.

**Impersonation is mandatory.** A Tatrman database reads as its viewer, so the door applies the viewer's own roles and
row rules. Superset's per-database OAuth2 (the client in `DATABASE_OAUTH2_CLIENTS["tatrman"]`, Keycloak's
authorization-code grant) stores and refreshes each viewer's token, and `impersonate_user` sends it as the bearer. A
viewer with no token is sent to sign in. Two cases refuse to connect, because a shared login would show every viewer
the same rows: a database with "Impersonate logged in user" off, and a database with no OAuth2 client to get tokens
from. `BI_REQUIRE_IMPERSONATION = False` lifts both refusals, for a local trial with a fixed `token` or a user in the
URL.

**Answers the door cut are refused.** When the door's row cap cut an answer (`top_n_applied`), the rows are not shown:
a chart over part of the rows would mislead, and Superset 6.1.0 has no channel for a notice beside the rows. The
refusal is a `RESULT_TOO_LARGE_ERROR` warning that names the cap and what was asked for.

Time grains use the string route: day, month, quarter and year, built from `EXTRACT`, `||` and `CAST … AS DATE`,
which the door runs today. There is no week grain until the translator lowers `DATE_TRUNC` (demand T-1).
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

import pyarrow as pa
from flask import current_app, g
from requests.exceptions import HTTPError
from sqlalchemy import types as sqltypes
from superset.constants import TimeGrain
from superset.db_engine_specs.base import BaseEngineSpec
from superset.db_engine_specs.exceptions import (
    SupersetDBAPIConnectionError,
    SupersetDBAPIDatabaseError,
    SupersetDBAPIDataError,
    SupersetDBAPIOperationalError,
    SupersetDBAPIProgrammingError,
)
from superset.errors import ErrorLevel, SupersetError, SupersetErrorType
from superset.exceptions import SupersetErrorException
from superset.utils.oauth2 import get_oauth2_redirect_uri

from . import dbapi
from .dialect import TatrmanDialect, sqla_type

#: The `DATABASE_OAUTH2_CLIENTS` key (C-16). Superset's own lookup is by `engine_name`; this spec keys by engine.
OAUTH2_CLIENT = "tatrman"

#: Refusals that mean "this viewer's token is missing or no longer accepted": sign in again.
_SIGN_IN_CODES = frozenset({"missing_credentials", "invalid_token", "unauthenticated"})

_DIALECT = TatrmanDialect()

_YEAR = "CAST(EXTRACT(YEAR FROM {col}) AS VARCHAR(4))"


class OAuth2TokenRequired(SupersetDBAPIConnectionError):
    """The viewer has no token for this database, or no longer a good one. Superset catches it and sends the viewer to
    sign in. Outside a signed-in request (`superset import-directory`, say) it is a connection error, which Superset's
    importers tolerate."""


class ImpersonationRequired(SupersetErrorException):
    """The database would read with a shared login instead of the viewer's own."""

    def __init__(self, message: str):
        super().__init__(
            SupersetError(
                message=message,
                error_type=SupersetErrorType.CONNECTION_MISSING_PARAMETERS_ERROR,
                level=ErrorLevel.ERROR,
                extra={"engine_name": TatrmanEngineSpec.engine_name},
            ),
            status=400,
        )


class TruncatedAnswer(SupersetErrorException):
    """The door's row cap cut this answer."""

    def __init__(self, cursor: Any):
        notice = next((n for n in getattr(cursor, "notices", []) if n.get("code") == "top_n_applied"), {})
        applied = notice.get("applied") or 0
        requested = notice.get("requested") or 0
        correlation_id = getattr(cursor, "correlation_id", "")
        at = f" at {applied} rows" if applied else ""
        asked = f" ({requested} were asked for)" if requested else ""
        message = (
            f"Tatrman's row cap cut this answer{at}{asked}, so it is not shown: part of the rows would mislead. "
            "Narrow it with a filter, a shorter time range, fewer groups or a row limit within the cap, or ask an "
            "operator to raise the cap for your role."
        )
        if correlation_id:
            message += f" Statement {correlation_id}."
        super().__init__(
            SupersetError(
                message=message,
                error_type=SupersetErrorType.RESULT_TOO_LARGE_ERROR,
                level=ErrorLevel.WARNING,
                extra={
                    "engine_name": TatrmanEngineSpec.engine_name,
                    "requested": requested,
                    "applied": applied,
                    "correlation_id": correlation_id,
                },
            ),
            status=422,
        )


class TatrmanEngineSpec(BaseEngineSpec):
    engine = "tatrman"
    engine_name = "Tatrman"
    default_driver = "flightsql"
    drivers = {"flightsql": "Arrow Flight SQL"}
    sqlalchemy_uri_placeholder = "tatrman://flight.example.com/hartland_us"

    supports_catalog = False  # one database = one world (D-2)
    supports_dynamic_schema = False
    allows_joins = True
    allows_subqueries = True  # derived tables pass translate; Superset's virtual datasets need them
    allows_alias_in_select = True
    allows_alias_in_orderby = True
    allows_sql_comments = False  # Superset strips them; the BI-0 ledger ran the door without
    # Superset's default labels (`count`, `sum`, …) are reserved words to the door's parser: quote every alias.
    force_column_alias_quotes = True
    max_column_name_length = 63
    disable_ssh_tunneling = True

    supports_oauth2 = True
    oauth2_scope = "openid"
    oauth2_exception = OAuth2TokenRequired

    _time_grain_expressions = {
        None: "{col}",
        TimeGrain.DAY: "CAST({col} AS DATE)",
        TimeGrain.MONTH: "CAST(" + _YEAR + " || '-' || CAST(EXTRACT(MONTH FROM {col}) AS VARCHAR(2)) || '-01' AS DATE)",
        TimeGrain.QUARTER: (
            "CAST(" + _YEAR + " || '-' || CAST(EXTRACT(QUARTER FROM {col}) * 3 - 2 AS VARCHAR(2)) || '-01' AS DATE)"
        ),
        TimeGrain.YEAR: "CAST(" + _YEAR + " || '-01-01' AS DATE)",
    }

    # Types and literals ---------------------------------------------------------------------------------------------

    @classmethod
    def convert_dttm(cls, target_type: str, dttm: datetime, db_extra: dict[str, Any] | None = None) -> str | None:
        column_type = cls.get_sqla_column_type(target_type)
        if isinstance(column_type, sqltypes.DateTime):  # TIMESTAMP, and DATETIME (a reflected `DateTime`)
            return f"TIMESTAMP '{dttm.isoformat(sep=' ', timespec='seconds')}'"
        if isinstance(column_type, sqltypes.Date):
            return f"DATE '{dttm.date().isoformat()}'"
        return None

    @classmethod
    def get_datatype(cls, type_code: Any) -> str | None:
        """The cursor's type codes are Arrow types; they read as the SQL names reflection gives the same column."""
        if isinstance(type_code, pa.DataType):
            if pa.types.is_null(type_code):
                return None
            return cls.column_datatype_to_string(sqla_type(type_code), _DIALECT)
        return super().get_datatype(type_code)

    # Connections ----------------------------------------------------------------------------------------------------

    @classmethod
    def adjust_engine_params(cls, uri, connect_args, catalog=None, schema=None):
        # The edge's receipts record the client; a `?client=` in the URL still wins.
        if "client" not in uri.query:
            connect_args.setdefault("client", "superset")
        return uri, connect_args

    @classmethod
    def impersonate_user(cls, database, username, user_token, url, engine_kwargs):
        # Not the base hook: it puts the username into the URL, and the dialect would log in with it and no password.
        connect_args = engine_kwargs.setdefault("connect_args", {})
        if user_token:
            connect_args["token"] = user_token
            connect_args.pop("user", None)
            connect_args.pop("password", None)
            return url._replace(username=None, password=None), engine_kwargs
        if database.is_oauth2_enabled():
            raise OAuth2TokenRequired(
                f"{username or 'This viewer'} has no Tatrman token for this database yet: open it in Superset to "
                "sign in."
            )
        if _impersonation_required():
            raise ImpersonationRequired(
                "This Tatrman database has no viewer's token to send: configure the Keycloak client Superset signs "
                'viewers in with as DATABASE_OAUTH2_CLIENTS["tatrman"]. A local trial with a fixed credential can set '
                "BI_REQUIRE_IMPERSONATION = False instead."
            )
        return url, engine_kwargs

    @classmethod
    def update_params_from_encrypted_extra(cls, database, params) -> None:
        # Superset 6.1.0 calls this for every engine it builds, right after impersonation (`Database._get_sqla_engine`).
        # It is the one hook that sees the database whether or not impersonation is on.
        if not database.impersonate_user and _impersonation_required():
            raise ImpersonationRequired(
                'This Tatrman database must read as its viewer: turn on "Impersonate logged in user" (Advanced → '
                "Security). Tatrman applies each viewer's own roles and row rules; a shared login would show every "
                "viewer the same rows."
            )
        super().update_params_from_encrypted_extra(database, params)

    # OAuth2 ---------------------------------------------------------------------------------------------------------

    @classmethod
    def is_oauth2_enabled(cls) -> bool:
        return cls.supports_oauth2 and OAUTH2_CLIENT in _oauth2_clients()

    @classmethod
    def get_oauth2_config(cls):
        """`DATABASE_OAUTH2_CLIENTS["tatrman"]`: `id`, `secret`, and `issuer` (the realm URL, e.g.
        `https://keycloak.example.com/realms/hartland`) or both `authorization_request_uri` and `token_request_uri`;
        `scope` defaults to `openid`."""
        client = _oauth2_clients().get(OAUTH2_CLIENT)
        if client is None:
            return None
        issuer = str(client.get("issuer", "")).rstrip("/")
        authorize = client.get("authorization_request_uri") or (issuer and f"{issuer}/protocol/openid-connect/auth")
        token = client.get("token_request_uri") or (issuer and f"{issuer}/protocol/openid-connect/token")
        if not (authorize and token):
            raise ValueError(
                'DATABASE_OAUTH2_CLIENTS["tatrman"] needs `issuer` (the Keycloak realm URL) or both '
                "`authorization_request_uri` and `token_request_uri`."
            )
        return {
            "id": client["id"],
            "secret": client["secret"],
            "scope": client.get("scope") or cls.oauth2_scope,
            "redirect_uri": get_oauth2_redirect_uri(),
            "authorization_request_uri": authorize,
            "token_request_uri": token,
            "request_content_type": client.get("request_content_type", cls.oauth2_token_request_type),
        }

    @classmethod
    def get_oauth2_fresh_token(cls, config, refresh_token):
        try:
            return super().get_oauth2_fresh_token(config, refresh_token)
        except HTTPError as e:
            # Keycloak answers a refresh token whose session has ended with 400 `invalid_grant`: sign in again.
            if e.response is not None and e.response.status_code == 400 and _oauth_error(e.response) == "invalid_grant":
                raise OAuth2TokenRequired("The Tatrman sign-in has ended: sign in again.") from e
            raise

    @classmethod
    def needs_oauth2(cls, ex: Exception) -> bool:
        if super().needs_oauth2(ex):
            return True
        error = getattr(ex, "orig", ex)  # SQLAlchemy wraps the driver's error
        return bool(g and hasattr(g, "user") and getattr(error, "code", "") in _SIGN_IN_CODES)

    # Answers and errors ---------------------------------------------------------------------------------------------

    @classmethod
    def fetch_data(cls, cursor: Any, limit: int | None = None) -> list[tuple[Any, ...]]:
        rows = super().fetch_data(cursor, limit)
        # The door marks an answer only when it reached the cap, so a marked answer has rows it did not send.
        if getattr(cursor, "top_n_applied", False):
            raise TruncatedAnswer(cursor)
        return rows

    @classmethod
    def get_dbapi_exception_mapping(cls) -> dict[type[Exception], type[Exception]]:
        # Superset looks up the exact class, so every concrete driver error is listed.
        return {
            dbapi.ProgrammingError: SupersetDBAPIProgrammingError,
            dbapi.NotSupportedError: SupersetDBAPIProgrammingError,
            dbapi.DatabaseError: SupersetDBAPIDatabaseError,
            dbapi.InternalError: SupersetDBAPIDatabaseError,
            dbapi.IntegrityError: SupersetDBAPIDatabaseError,
            dbapi.DataError: SupersetDBAPIDataError,
            dbapi.OperationalError: SupersetDBAPIOperationalError,
            dbapi.InterfaceError: SupersetDBAPIConnectionError,
        }


def _impersonation_required() -> bool:
    return bool(current_app.config.get("BI_REQUIRE_IMPERSONATION", True))


def _oauth2_clients() -> dict:
    return current_app.config.get("DATABASE_OAUTH2_CLIENTS") or {}


def _oauth_error(response) -> str:
    try:
        return str(response.json().get("error", ""))
    except ValueError:
        return ""
