# SPDX-License-Identifier: Apache-2.0
"""BI (contract C-16) — the Superset engine spec, run inside Superset 6.1.0 itself.

The spec's hooks are exercised through Superset's own code: its `Database` model builds the engines, its OAuth2 token
store hands out the viewer's token, and its error and fetch paths carry the edge's answers, against the recorded edge.
Superset pins SQLAlchemy 1.4, so this module runs in its own environment and is skipped where Superset is not
installed. CI sets `TTR_SUPERSET_TESTS=required`, so a broken install fails instead of skipping:

    uv run --isolated --python 3.10 --with apache-superset==6.1.0 --with cachetools \
        pytest tests/test_superset_spec.py
"""

from __future__ import annotations

import os
import pathlib
import uuid
from datetime import datetime, timedelta
from urllib.parse import parse_qs, urlsplit

import pyarrow as pa
import pytest

if os.environ.get("TTR_SUPERSET_TESTS") == "required":
    import superset  # noqa: F401
else:
    pytest.importorskip("superset")

import requests  # noqa: E402
import sqlalchemy as sa  # noqa: E402
from flask import g  # noqa: E402
from sqlalchemy.engine import make_url  # noqa: E402

from tests.fixtures.edge import WAREHOUSES  # noqa: E402
from ttr_sqlalchemy import dbapi  # noqa: E402
from ttr_sqlalchemy.dialect import TatrmanDialect, sqla_type  # noqa: E402

ISSUER = "https://keycloak.example.com/realms/hartland"
COMPOSE = pathlib.Path(__file__).resolve().parents[5] / "compose" / "superset"  # the repo's compose convenience

# Superset's models may be imported only once its app exists, so every test builds on the app.
pytestmark = pytest.mark.usefixtures("app")


@pytest.fixture(scope="session")
def app(tmp_path_factory):
    os.environ["TTR_SUPERSET_TEST_METADATA"] = f"sqlite:///{tmp_path_factory.mktemp('superset') / 'metadata.db'}"
    from superset.app import create_app

    app = create_app(superset_config_module="tests.fixtures.superset_app")
    with app.app_context():
        import superset.key_value.models  # noqa: F401 — the PKCE verifiers of the sign-in flow
        import superset.models  # noqa: F401 — databases and their per-viewer OAuth2 tokens
        from flask_appbuilder import Model
        from superset import db, security_manager

        Model.metadata.create_all(db.engine)  # Superset's and FAB's tables share FAB's declarative base
        gamma = security_manager.add_role("Gamma")
        security_manager.add_user("petr", "Petr", "Dvořák", "petr@example.com", gamma)
    yield app
    os.environ.pop("TTR_SUPERSET_TEST_METADATA", None)


@pytest.fixture
def ctx(app):
    with app.app_context():
        yield app


@pytest.fixture
def petr(app):
    """A request signed in as petr — what every chart, dataset and SQL Lab call runs under."""
    from superset import security_manager

    with app.test_request_context():
        g.user = security_manager.find_user("petr")
        yield g.user


@pytest.fixture
def spec():
    from ttr_sqlalchemy.superset import TatrmanEngineSpec

    return TatrmanEngineSpec


def _database(edge, *, impersonate=True, world="hartland_cz"):
    from superset import db
    from superset.models.core import Database

    database = Database(
        database_name=f"{world}-{uuid.uuid4().hex[:8]}",
        sqlalchemy_uri=f"tatrman://{edge.url}/{world}?tls=false",
        impersonate_user=impersonate,
    )
    db.session.add(database)
    db.session.commit()
    return database


def _token(database, user, access_token, *, expires_in=300, refresh_token="refresh-1"):
    from superset import db
    from superset.models.core import DatabaseUserOAuth2Tokens

    db.session.add(
        DatabaseUserOAuth2Tokens(
            user_id=user.id,
            database_id=database.id,
            access_token=access_token,
            access_token_expiration=datetime.now() + timedelta(seconds=expires_in),
            refresh_token=refresh_token,
        )
    )
    db.session.commit()


class _Stub:
    """A database as the spec's hooks see it, for the hooks Superset calls with one."""

    def __init__(self, *, impersonate_user=True, oauth2=False):
        self.impersonate_user = impersonate_user
        self._oauth2 = oauth2

    def is_oauth2_enabled(self):
        return self._oauth2


# The spec ------------------------------------------------------------------------------------------------------------


def test_superset_finds_the_spec_by_its_entry_point(spec):
    from importlib.metadata import entry_points

    from superset.db_engine_specs import get_engine_spec, load_engine_specs

    (ep,) = [ep for ep in entry_points(group="superset.db_engine_specs") if ep.name == "tatrman"]
    assert ep.value == "ttr_sqlalchemy.superset:TatrmanEngineSpec"
    assert spec in load_engine_specs()
    assert get_engine_spec("tatrman", "flightsql") is spec
    assert spec.supports_url(make_url("tatrman://flight.example.com/hartland_us"))


def test_the_contract_attributes(spec):
    assert spec.engine == "tatrman"
    assert spec.supports_catalog is False  # one database = one world
    assert spec.allows_subqueries is True
    assert spec.force_column_alias_quotes is True
    assert spec.max_column_name_length == 63
    assert spec.supports_oauth2 is True


def test_time_grains_are_the_string_route(ctx, spec):
    grains = spec.get_time_grain_expressions()
    # No week until the translator lowers DATE_TRUNC (demand T-1): no spelling of a week start runs on the door today.
    # When T-1 lands this flips: P1W joins, as DATE_TRUNC('week', …).
    assert "P1W" not in grains
    assert set(grains) == {None, "P1D", "P1M", "P3M", "P1Y"}
    assert not any("DATE_TRUNC" in expr for expr in grains.values())

    def rendered(grain):
        return str(spec.get_timestamp_expr(sa.column("sold_date"), None, grain).compile(dialect=TatrmanDialect()))

    year = 'CAST(EXTRACT(YEAR FROM "sold_date") AS VARCHAR(4))'
    assert rendered("P1D") == 'CAST("sold_date" AS DATE)'
    assert rendered("P1M") == (
        f"CAST({year} || '-' || CAST(EXTRACT(MONTH FROM \"sold_date\") AS VARCHAR(2)) || '-01' AS DATE)"
    )
    assert rendered("P3M") == (
        f"CAST({year} || '-' || CAST(EXTRACT(QUARTER FROM \"sold_date\") * 3 - 2 AS VARCHAR(2)) || '-01' AS DATE)"
    )
    assert rendered("P1Y") == f"CAST({year} || '-01-01' AS DATE)"


def test_every_alias_is_quoted(spec):
    # Superset's default labels (`count`, `sum`, …) are reserved words to the door's parser.
    label = spec.make_label_compatible("count")
    sql = str(sa.select(sa.literal_column("1").label(label)).compile(dialect=TatrmanDialect()))
    assert sql == 'SELECT 1 AS "count"'


def test_datetimes_become_typed_literals(ctx, spec):
    at = datetime(2026, 10, 1, 13, 45, 7)
    assert spec.convert_dttm("DATE", at) == "DATE '2026-10-01'"
    assert spec.convert_dttm("TIMESTAMP", at) == "TIMESTAMP '2026-10-01 13:45:07'"
    # A reflected `DateTime` column reads as DATETIME (SQLAlchemy's generic name) — still a timestamp.
    assert spec.convert_dttm("DATETIME", at) == "TIMESTAMP '2026-10-01 13:45:07'"
    assert spec.convert_dttm("VARCHAR", at) is None


def test_cursor_types_read_as_the_same_sql_type_names_as_reflection(ctx, edge, spec):
    with dbapi.connect(host="127.0.0.1", port=edge.port, world="hartland_cz", tls=False, token="tok-petr") as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM er.entity.channel_sales")
        types = [spec.get_datatype(d[1]) for d in cur.description]
    assert types == ["BIGINT", "BIGINT", "NUMERIC(19, 2)", "DATE", "DATETIME", "BOOLEAN", "INTEGER", "FLOAT"]
    # A virtual dataset (its types from the cursor) and a physical one (from reflection) read the same column alike.
    for t in (pa.int64(), pa.decimal128(19, 9), pa.date32(), pa.timestamp("us"), pa.utf8(), pa.bool_()):
        assert spec.get_datatype(t) == spec.column_datatype_to_string(sqla_type(t), TatrmanDialect())
    assert spec.get_column_spec(spec.get_datatype(pa.timestamp("us"))).is_dttm
    assert spec.get_column_spec(spec.get_datatype(pa.date32())).is_dttm
    assert spec.get_datatype(pa.null()) is None
    assert spec.get_datatype("varchar") == "VARCHAR"


# Identity: impersonation is mandatory ------------------------------------------------------------------------------


def test_the_viewer_token_is_the_bearer_and_no_shared_login_rides_along(spec):
    url, kwargs = spec.impersonate_user(
        _Stub(), "petr", "tok-petr", make_url("tatrman://service:secret@flight.example.com/hartland_cz"), {}
    )
    assert kwargs["connect_args"]["token"] == "tok-petr"
    assert (url.username, url.password, url.host, url.database) == (None, None, "flight.example.com", "hartland_cz")


def test_without_a_token_or_a_client_to_get_one_the_database_refuses(ctx, spec, monkeypatch):
    from ttr_sqlalchemy.superset import ImpersonationRequired

    url = make_url("tatrman://flight.example.com/hartland_cz")
    with pytest.raises(ImpersonationRequired) as refused:
        spec.impersonate_user(_Stub(oauth2=False), "petr", None, url, {})
    assert 'DATABASE_OAUTH2_CLIENTS["tatrman"]' in str(refused.value)

    monkeypatch.setitem(ctx.config, "BI_REQUIRE_IMPERSONATION", False)  # a local trial with a fixed credential
    assert spec.impersonate_user(_Stub(oauth2=False), "petr", None, url, {}) == (url, {"connect_args": {}})


def test_a_database_without_impersonation_refuses_to_connect(ctx, edge, spec, monkeypatch):
    from ttr_sqlalchemy.superset import ImpersonationRequired

    database = _database(edge, impersonate=False)
    with pytest.raises(ImpersonationRequired) as refused:
        with database.get_sqla_engine():
            pass
    assert "Impersonate logged in user" in str(refused.value)

    monkeypatch.setitem(ctx.config, "BI_REQUIRE_IMPERSONATION", False)
    with database.get_sqla_engine() as engine:
        assert engine.dialect.name == "tatrman"


def test_the_compose_databases_import_with_no_one_signed_in(ctx):
    """`superset import-directory`, as the compose convenience's `init` runs it: Superset lists each database's
    schemas while it imports, and with no viewer there is no token. That is a connection error the import passes over,
    not a failure."""
    from superset import db
    from superset.examples.utils import load_configs_from_directory
    from superset.models.core import Database

    load_configs_from_directory(COMPOSE / "bundle", overwrite=True)
    imported = {
        d.database_name: d for d in db.session.query(Database).filter(Database.database_name.like("hartland_%"))
    }
    assert set(imported) >= {"hartland_us", "hartland_cz"}
    for world in ("hartland_us", "hartland_cz"):
        database = imported[world]
        assert database.impersonate_user and not database.allow_dml
        assert database.db_engine_spec.engine == "tatrman"
        assert make_url(database.sqlalchemy_uri).database == world


def test_the_oauth2_client_is_database_oauth2_clients_tatrman(ctx, spec, monkeypatch):
    config = spec.get_oauth2_config()
    assert config == {
        "id": "superset-db",
        "secret": "test-only-not-a-secret",
        "scope": "openid",
        "redirect_uri": "https://superset.example.com/api/v1/database/oauth2/",
        "authorization_request_uri": f"{ISSUER}/protocol/openid-connect/auth",
        "token_request_uri": f"{ISSUER}/protocol/openid-connect/token",
        "request_content_type": "data",
    }
    assert spec.is_oauth2_enabled()

    explicit = {
        "id": "c",
        "secret": "s",
        "authorization_request_uri": "https://a/auth",
        "token_request_uri": "https://a/t",
    }
    monkeypatch.setitem(ctx.config, "DATABASE_OAUTH2_CLIENTS", {"tatrman": explicit})
    config = spec.get_oauth2_config()
    assert (config["authorization_request_uri"], config["token_request_uri"]) == ("https://a/auth", "https://a/t")

    monkeypatch.setitem(ctx.config, "DATABASE_OAUTH2_CLIENTS", {"tatrman": {"id": "c", "secret": "s"}})
    with pytest.raises(ValueError, match="issuer"):
        spec.get_oauth2_config()

    monkeypatch.setitem(ctx.config, "DATABASE_OAUTH2_CLIENTS", {})
    assert spec.get_oauth2_config() is None
    assert not spec.is_oauth2_enabled()


def test_a_viewer_reads_with_their_own_token(petr, edge, calls):
    database = _database(edge)
    _token(database, petr, "tok-petr")
    df = database.get_df("SELECT * FROM er.entity.warehouse")
    assert len(df) == len(WAREHOUSES["hartland_cz"])
    statement = [c for c in calls if c.command == "CommandStatementQuery"]
    assert statement and {c.headers["authorization"] for c in calls} == {"Bearer tok-petr"}
    assert {c.headers["x-ttr-client"] for c in calls} == {"superset"}
    assert {c.headers["world"] for c in calls} == {"hartland_cz"}


def _sign_in_url(redirect) -> dict:
    target = urlsplit(redirect.value.error.extra["url"])
    return {"endpoint": f"{target.scheme}://{target.netloc}{target.path}", **parse_qs(target.query)}


def test_a_viewer_without_a_token_is_sent_to_sign_in(petr, edge, calls):
    from superset.exceptions import OAuth2RedirectError

    database = _database(edge)
    with pytest.raises(OAuth2RedirectError) as redirect:
        database.get_df("SELECT * FROM er.entity.warehouse")
    sign_in = _sign_in_url(redirect)
    assert sign_in["endpoint"] == f"{ISSUER}/protocol/openid-connect/auth"
    assert (sign_in["client_id"], sign_in["scope"], sign_in["code_challenge_method"]) == (
        ["superset-db"],
        ["openid"],
        ["S256"],
    )
    assert calls == []  # nothing reached the edge


def test_a_token_the_edge_refuses_sends_the_viewer_to_sign_in_again(petr, edge):
    from superset.exceptions import OAuth2RedirectError

    database = _database(edge)
    _token(database, petr, "tok-revoked")
    with pytest.raises(OAuth2RedirectError):
        database.get_df("SELECT * FROM er.entity.warehouse")


class _Response:
    def __init__(self, status: int, body: dict):
        self.status_code = status
        self._body = body

    def json(self):
        return self._body

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f"{self.status_code}", response=self)


def test_an_expired_token_is_refreshed(petr, edge, calls, monkeypatch):
    posted = []

    def post(uri, data=None, json=None, timeout=None):
        posted.append((uri, data))
        return _Response(200, {"access_token": "tok-petr", "expires_in": 300, "refresh_token": "refresh-2"})

    monkeypatch.setattr("superset.db_engine_specs.base.requests.post", post)
    database = _database(edge)
    _token(database, petr, "tok-stale", expires_in=-1)
    database.get_df("SELECT * FROM er.entity.warehouse")
    ((uri, body),) = posted
    assert uri == f"{ISSUER}/protocol/openid-connect/token"
    assert (body["grant_type"], body["refresh_token"], body["client_id"]) == (
        "refresh_token",
        "refresh-1",
        "superset-db",
    )
    assert {c.headers["authorization"] for c in calls} == {"Bearer tok-petr"}


def test_a_refresh_token_past_its_session_sends_the_viewer_to_sign_in(petr, edge, monkeypatch):
    from superset import db
    from superset.exceptions import OAuth2RedirectError
    from superset.models.core import DatabaseUserOAuth2Tokens

    # Keycloak's answer once the SSO session behind a refresh token has ended.
    refused = _Response(400, {"error": "invalid_grant", "error_description": "Session not active"})
    monkeypatch.setattr("superset.db_engine_specs.base.requests.post", lambda *a, **k: refused)
    database = _database(edge)
    _token(database, petr, "tok-stale", expires_in=-1)
    with pytest.raises(OAuth2RedirectError):
        database.get_df("SELECT * FROM er.entity.warehouse")
    assert db.session.query(DatabaseUserOAuth2Tokens).filter_by(database_id=database.id).count() == 0


def test_a_token_endpoint_failure_is_not_a_sign_in(ctx, spec, monkeypatch):
    monkeypatch.setattr("superset.db_engine_specs.base.requests.post", lambda *a, **k: _Response(503, {}))
    with pytest.raises(requests.HTTPError):
        spec.get_oauth2_fresh_token(spec.get_oauth2_config(), "refresh-1")


# Answers --------------------------------------------------------------------------------------------------------------


def test_a_cut_answer_is_refused_with_the_numbers(petr, edge):
    from superset.errors import ErrorLevel, SupersetErrorType

    from ttr_sqlalchemy.superset import TruncatedAnswer

    database = _database(edge)
    _token(database, petr, "tok-petr")
    with pytest.raises(TruncatedAnswer) as cut:
        database.get_df("SELECT * FROM er.entity.channel_sales LIMIT 10000")
    error = cut.value.error
    assert (error.error_type, error.level) == (SupersetErrorType.RESULT_TOO_LARGE_ERROR, ErrorLevel.WARNING)
    assert "200 rows" in error.message and "10000" in error.message
    assert error.extra["requested"] == 10000 and error.extra["applied"] == 200
    assert error.extra["correlation_id"]

    assert len(database.get_df("SELECT * FROM er.entity.channel_sales LIMIT 10")) == 1  # an uncut answer reads


@pytest.mark.parametrize(
    ("driver_error", "superset_error"),
    [
        ("ProgrammingError", "SupersetDBAPIProgrammingError"),
        ("NotSupportedError", "SupersetDBAPIProgrammingError"),
        ("DatabaseError", "SupersetDBAPIDatabaseError"),
        ("InternalError", "SupersetDBAPIDatabaseError"),
        ("IntegrityError", "SupersetDBAPIDatabaseError"),
        ("DataError", "SupersetDBAPIDataError"),
        ("OperationalError", "SupersetDBAPIOperationalError"),
        ("InterfaceError", "SupersetDBAPIConnectionError"),
    ],
)
def test_driver_errors_map_to_superset_dbapi_errors(spec, driver_error, superset_error):
    from superset.db_engine_specs import exceptions

    mapped = spec.get_dbapi_mapped_exception(getattr(dbapi, driver_error)("some_code", "One sentence."))
    assert type(mapped) is getattr(exceptions, superset_error)
    assert str(mapped) == "some_code: One sentence."


def test_a_refusal_reaches_superset_with_the_edges_code_and_sentence(petr, edge):
    from superset.db_engine_specs.exceptions import SupersetDBAPIProgrammingError

    database = _database(edge)
    _token(database, petr, "tok-petr")
    with pytest.raises(SupersetDBAPIProgrammingError) as refused:
        database.get_df("SELECT * FROM er.warehouse")
    assert str(refused.value).startswith("translator_rejected: Object 'warehouse' not found within 'er'")
