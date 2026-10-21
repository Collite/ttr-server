# SPDX-License-Identifier: Apache-2.0
"""BI (contracts C-14, C-15) — the `tatrman://` dialect: URL, introspection from the Flight catalog, quoting."""

from __future__ import annotations

import pytest
import sqlalchemy as sa
from sqlalchemy import types as sqltypes
from sqlalchemy.engine import make_url

from ttr_sqlalchemy.dialect import TatrmanDialect


@pytest.fixture
def engine(edge):
    engine = sa.create_engine(f"tatrman://{edge.url}/hartland_cz?tls=false", connect_args={"token": "tok-petr"})
    yield engine
    engine.dispose()


@pytest.fixture
def inspector(engine):
    return sa.inspect(engine)


def _args(url: str) -> dict:
    return TatrmanDialect().create_connect_args(make_url(url))[1]


def test_the_url_names_host_port_and_world():
    assert _args("tatrman://flight.example.com/hartland_us") == {
        "host": "flight.example.com",
        "port": None,
        "world": "hartland_us",
        "tls": True,
    }
    assert _args("tatrman://localhost:7308/hartland_cz?tls=false") == {
        "host": "localhost",
        "port": 7308,
        "world": "hartland_cz",
        "tls": False,
    }


def test_a_user_name_and_password_in_the_url_log_in():
    assert _args("tatrman://marketa:secret@flight.example.com/hartland_cz") == {
        "host": "flight.example.com",
        "port": None,
        "world": "hartland_cz",
        "tls": True,
        "user": "marketa",
        "password": "secret",
    }


def test_url_options():
    assert _args("tatrman://h/w?tls_skip_verify=true&timeout=30&client=superset") == {
        "host": "h",
        "port": None,
        "world": "w",
        "tls": True,
        "tls_skip_verify": True,
        "timeout": 30.0,
        "client": "superset",
    }
    with pytest.raises(sa.exc.ArgumentError, match="flavour"):
        _args("tatrman://h/w?flavour=x")
    with pytest.raises(sa.exc.ArgumentError, match="tls"):
        _args("tatrman://h/w?tls=maybe")


def test_no_world_is_the_deployments_default():
    assert _args("tatrman://h")["world"] == ""


def test_the_schemas_are_the_three_the_edge_serves(inspector):
    assert inspector.get_schema_names() == ["er.entity", "obj.query", "db.dbo"]
    assert inspector.default_schema_name == "er.entity"


def test_tables_and_views_per_schema(inspector):
    assert inspector.get_table_names("er.entity") == ["channel_sales", "warehouse"]
    assert inspector.get_table_names() == ["channel_sales", "warehouse"]
    assert inspector.get_view_names("er.entity") == []
    assert inspector.get_table_names("obj.query") == []
    assert inspector.get_view_names("obj.query") == ["revenue_per_customer"]
    assert inspector.get_table_names("db.dbo") == ["inventory"]


def test_the_catalog_calls_name_the_world(engine, calls):
    sa.inspect(engine).get_table_names("er.entity")
    tables = [c for c in calls if c.command == "CommandGetTables"]
    assert tables and all(c.headers["world"] == "hartland_cz" for c in tables)
    assert all(c.headers["authorization"] == "Bearer tok-petr" for c in tables)


def test_columns_are_typed_from_arrow(inspector):
    columns = {c["name"]: c for c in inspector.get_columns("channel_sales", schema="er.entity")}
    assert list(columns) == [
        "sale_id",
        "warehouse_id",
        "ext_sales_price",
        "sold_date",
        "sold_at",
        "is_return",
        "quantity",
        "discount",
    ]
    expected = {
        "sale_id": sqltypes.BigInteger,
        "warehouse_id": sqltypes.BigInteger,
        "ext_sales_price": sqltypes.Numeric,
        "sold_date": sqltypes.Date,
        "sold_at": sqltypes.DateTime,
        "is_return": sqltypes.Boolean,
        "quantity": sqltypes.Integer,
        "discount": sqltypes.Float,
    }
    for name, kind in expected.items():
        assert type(columns[name]["type"]) is kind, name
    assert (columns["ext_sales_price"]["type"].precision, columns["ext_sales_price"]["type"].scale) == (19, 2)
    assert columns["sale_id"]["nullable"] is False
    assert columns["warehouse_id"]["nullable"] is True
    strings = inspector.get_columns("warehouse", schema="er.entity")
    assert type(strings[1]["type"]) is sqltypes.String


def test_a_saved_querys_columns_come_from_the_catalog_too(inspector):
    columns = inspector.get_columns("revenue_per_customer", schema="obj.query")
    assert [(c["name"], type(c["type"])) for c in columns] == [
        ("customer_name", sqltypes.String),
        ("revenue", sqltypes.Numeric),
    ]


def test_an_unknown_table_has_no_columns(inspector):
    with pytest.raises(sa.exc.NoSuchTableError):
        inspector.get_columns("nope", schema="er.entity")


def test_has_table_is_exact(inspector):
    assert inspector.has_table("warehouse", schema="er.entity")
    assert not inspector.has_table("warehouse", schema="db.dbo")
    # Flight SQL's table pattern is a LIKE pattern; `_` must not match any one character here.
    assert not inspector.has_table("warehous_", schema="er.entity")


def test_primary_keys_from_the_catalog(inspector):
    assert inspector.get_pk_constraint("warehouse", schema="er.entity") == {
        "constrained_columns": ["warehouse_id"],
        "name": None,
    }
    assert inspector.get_pk_constraint("revenue_per_customer", schema="obj.query")["constrained_columns"] == []


def test_foreign_keys_from_relations_with_join_pairs(inspector):
    assert inspector.get_foreign_keys("channel_sales", schema="er.entity") == [
        {
            "name": None,
            "constrained_columns": ["warehouse_id"],
            "referred_schema": "er.entity",
            "referred_table": "warehouse",
            "referred_columns": ["warehouse_id"],
            "options": {},
        }
    ]
    assert inspector.get_foreign_keys("warehouse", schema="er.entity") == []


def test_nothing_else_is_reflected(inspector):
    assert inspector.get_indexes("warehouse", schema="er.entity") == []
    assert inspector.get_unique_constraints("warehouse", schema="er.entity") == []
    assert inspector.get_check_constraints("warehouse", schema="er.entity") == []


def test_ping_is_get_sql_info(engine, calls):
    with engine.connect() as conn:
        raw = conn.connection.dbapi_connection if hasattr(conn.connection, "dbapi_connection") else conn.connection
        calls.clear()
        assert engine.dialect.do_ping(raw) is True
    assert [c.command for c in calls] == ["CommandGetSqlInfo", "CommandGetSqlInfo"]


def test_a_pre_pinged_pool_works(edge):
    engine = sa.create_engine(
        f"tatrman://{edge.url}/hartland_cz?tls=false", connect_args={"token": "tok-petr"}, pool_pre_ping=True
    )
    try:
        for _ in range(2):
            with engine.connect() as conn:
                assert conn.execute(sa.text("SELECT 1")).scalar() == 1
    finally:
        engine.dispose()


def _warehouse() -> sa.Table:
    return sa.Table(
        "warehouse",
        sa.MetaData(),
        sa.Column("warehouse_id", sa.BigInteger),
        sa.Column("warehouse_name", sa.String),
        sa.Column("city", sa.String),
        schema="er.entity",
    )


def test_the_preparer_quotes_each_part_of_a_dotted_schema(engine):
    t = _warehouse()
    sql = str(sa.select(t.c.warehouse_name.label("dc")).where(t.c.city == "Brno").compile(engine))
    assert sql == (
        'SELECT "er"."entity"."warehouse"."warehouse_name" AS "dc" \n'
        'FROM "er"."entity"."warehouse" \n'
        'WHERE "er"."entity"."warehouse"."city" = :city_1'
    )


def test_every_identifier_is_quoted(engine):
    preparer = engine.dialect.identifier_preparer
    assert preparer.quote("count") == '"count"'
    assert preparer.quote("revenue") == '"revenue"'
    assert preparer.quote("SUM(revenue)") == '"SUM(revenue)"'
    assert preparer.quote('a"b') == '"a""b"'
    assert preparer.format_schema("er.entity") == '"er"."entity"'


def test_limit_and_offset_render_for_the_door(engine):
    t = _warehouse()
    assert str(sa.select(t.c.city).limit(5).compile(engine)).endswith("\n LIMIT :param_1")
    assert str(sa.select(t.c.city).offset(5).compile(engine)).endswith("\n OFFSET :param_1")
    assert str(sa.select(t.c.city).limit(5).offset(10).compile(engine)).endswith("\n LIMIT :param_1\n OFFSET :param_2")


def test_a_core_select_runs_with_its_bound_values_as_literals(engine, edge):
    t = _warehouse()
    with engine.connect() as conn:
        rows = conn.execute(sa.select(t).where(t.c.city == "Ostrava").limit(10)).fetchall()
    assert [tuple(r) for r in rows] == [(2, "Ostrava DC", "Ostrava")]
    assert edge.statements()[-1] == (
        'SELECT "er"."entity"."warehouse"."warehouse_id", "er"."entity"."warehouse"."warehouse_name", '
        '"er"."entity"."warehouse"."city" \n'
        'FROM "er"."entity"."warehouse" \n'
        'WHERE "er"."entity"."warehouse"."city" = \'Ostrava\'\n'
        " LIMIT 10"
    )


def test_a_text_statement_runs(engine):
    with engine.connect() as conn:
        result = conn.execute(sa.text("SELECT * FROM er.entity.warehouse WHERE city = :c"), {"c": "Brno"})
        assert list(result.keys()) == ["warehouse_id", "warehouse_name", "city"]
        assert [tuple(r) for r in result] == [(1, "Brno DC", "Brno")]


def test_pandas_style_read_through_the_engine(engine):
    with engine.connect() as conn:
        result = conn.exec_driver_sql("SELECT * FROM er.entity.channel_sales LIMIT 10000")
        assert result.cursor.top_n_applied is True
        assert len(result.fetchall()) == 1


def test_a_refusal_surfaces_as_a_sqlalchemy_dbapi_error(engine):
    with engine.connect() as conn, pytest.raises(sa.exc.ProgrammingError) as e:
        conn.execute(sa.text("SELECT * FROM er.warehouse"))
    assert e.value.orig.code == "translator_rejected"


def test_the_dialect_is_registered_by_its_entry_point():
    from sqlalchemy.dialects import registry

    assert registry.load("tatrman") is TatrmanDialect


def test_an_expired_or_refused_bearer_makes_the_pool_reconnect(edge):
    """A pooled connection outlives its access token: pre-ping says "gone" (False) and the pool logs in again."""
    from ttr_sqlalchemy import dbapi

    host, port = edge.url.split(":")
    dialect = TatrmanDialect()
    stale = dbapi.connect(host=host, port=int(port), tls=False, token="expired", world="hartland_cz")
    try:
        assert dialect.do_ping(stale) is False
    finally:
        stale.close()
    assert dialect.do_ping(stale) is False  # closed: gone too
    assert dialect.is_disconnect(dbapi.OperationalError("invalid_token", "expired"), None, None)
    assert dialect.is_disconnect(dbapi.OperationalError("unavailable", "failed to connect"), None, None)
    assert not dialect.is_disconnect(dbapi.ProgrammingError("translator_rejected", "no"), None, None)
    assert not dialect.is_disconnect(dbapi.DatabaseError("column_denied", "no"), None, None)


def test_a_ping_refused_for_another_reason_still_raises(edge):
    from ttr_sqlalchemy import dbapi

    host, port = edge.url.split(":")
    conn = dbapi.connect(host=host, port=int(port), tls=False, token="tok-petr", world="hartland_xx")
    try:
        with pytest.raises(dbapi.OperationalError) as e:
            TatrmanDialect().do_ping(conn)
        assert e.value.code == "unknown_world"
    finally:
        conn.close()


def test_an_in_list_expands_to_literals(engine, edge):
    t = _warehouse()
    with engine.connect() as conn:
        conn.execute(sa.select(t).where(t.c.city.in_(["Brno", "O'Hara"]))).fetchall()
    assert edge.statements()[-1].endswith('WHERE "er"."entity"."warehouse"."city" IN (\'Brno\', \'O\'\'Hara\')')
