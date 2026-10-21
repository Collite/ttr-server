# SPDX-License-Identifier: Apache-2.0
"""BI (contracts C-14, C-17) — the DB-API over the edge's Flight SQL, against the recorded edge."""

from __future__ import annotations

import datetime as dt
import decimal

import pytest

from ttr_sqlalchemy import dbapi


def _connect(edge, **kw):
    host, port = edge.url.split(":")
    kw.setdefault("token", "tok-petr")
    return dbapi.connect(host=host, port=int(port), tls=False, **kw)


def test_module_is_pep_249():
    assert dbapi.apilevel == "2.0"
    assert dbapi.threadsafety == 1
    assert dbapi.paramstyle == "named"
    assert issubclass(dbapi.Warning, Exception) and not issubclass(dbapi.Warning, dbapi.Error)
    for name in (
        "InterfaceError",
        "DataError",
        "OperationalError",
        "IntegrityError",
        "InternalError",
        "ProgrammingError",
        "NotSupportedError",
    ):
        assert issubclass(getattr(dbapi, name), dbapi.Error)
    assert issubclass(dbapi.ProgrammingError, dbapi.DatabaseError)


def test_the_bearer_and_the_world_travel_on_every_call(edge, calls):
    with _connect(edge, world="hartland_cz") as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM er.entity.warehouse")
        assert cur.fetchall() == [(1, "Brno DC", "Brno"), (2, "Ostrava DC", "Ostrava"), (3, "Plzeň DC", "Plzeň")]
    statement_calls = [c for c in calls if c.command in ("CommandStatementQuery", "TicketStatementQuery")]
    assert [c.method for c in statement_calls] == ["get_flight_info", "do_get"]
    for c in statement_calls:
        assert c.headers["authorization"] == "Bearer tok-petr"
        assert c.headers["world"] == "hartland_cz"
        assert c.headers["x-ttr-client"] == "other"


def test_one_connection_reads_one_world(edge, calls):
    with _connect(edge, world="hartland_us") as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM er.entity.warehouse")
        assert cur.fetchall() == [(10, "Dallas DC", "Dallas")]
        assert cur.connection_id == "pg-hartland-us"


def test_a_user_name_and_password_are_exchanged_for_a_bearer_at_connect(edge, calls):
    with _connect(edge, token=None, user="marketa", password="marketa-pw", world="hartland_cz") as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM er.entity.warehouse")
        cur.fetchall()
    assert calls[0].method == "handshake"
    assert calls[0].headers["authorization"].startswith("Basic ")
    # The password went once, on the handshake; every later call carries the bearer the edge handed back.
    assert {c.headers["authorization"] for c in calls[1:]} == {"Bearer tok-marketa"}


def test_a_refused_password_fails_the_connect(edge):
    with pytest.raises(dbapi.OperationalError) as e:
        _connect(edge, token=None, user="marketa", password="wrong")
    assert e.value.code == "password_refused"
    assert e.value.message == "Keycloak refused the password grant (401 invalid_grant)."


def test_a_bad_token_fails_the_first_call_readably(edge):
    with _connect(edge, token="nope") as conn, pytest.raises(dbapi.OperationalError) as e:
        conn.cursor().execute("SELECT 1")
    assert e.value.code == "invalid_token"
    assert str(e.value) == "invalid_token: The bearer token was not accepted (signature)."


def test_rows_stream_batch_by_batch_and_every_row_arrives(edge):
    # The edge sends the three Czech warehouses in two batches (2 + 1).
    with _connect(edge, world="hartland_cz") as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM er.entity.warehouse")
        assert cur.rowcount == 3
        assert cur.fetchone() == (1, "Brno DC", "Brno")
        assert cur.fetchmany(1) == [(2, "Ostrava DC", "Ostrava")]
        assert cur.fetchall() == [(3, "Plzeň DC", "Plzeň")]
        assert cur.fetchone() is None
        assert [d[0] for d in cur.description] == ["warehouse_id", "warehouse_name", "city"]
        assert cur.description[0][1] == dbapi.NUMBER
        assert cur.description[1][1] == dbapi.STRING
        assert cur.description[0][6] is False and cur.description[1][6] is True
        assert cur.fetch_arrow_table().num_rows == 3


def test_values_arrive_as_python_values(edge):
    with _connect(edge, world="hartland_cz") as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM er.entity.channel_sales")
        assert cur.fetchall() == [
            (7, 1, decimal.Decimal("12.50"), dt.date(2026, 10, 1), dt.datetime(2026, 10, 1, 12, 30), False, 3, 0.5)
        ]
        assert cur.description[3][1] == dbapi.DATETIME


def test_the_closing_metadata_is_read(edge):
    with _connect(edge, world="hartland_cz") as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM er.entity.warehouse")
        assert cur.correlation_id == "c0ffee00-0000-4000-8000-000000000001"
        assert cur.connection_id == "pg-hartland-cz"
        assert cur.top_n_applied is False
        assert cur.notices == []


def test_a_capped_answer_says_so(edge):
    with _connect(edge, world="hartland_cz") as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM er.entity.channel_sales LIMIT 10000")
        assert cur.top_n_applied is True
        (notice,) = cur.notices
        assert notice["code"] == "top_n_applied"
        assert (notice["requested"], notice["applied"]) == (0, 200)


@pytest.mark.parametrize(
    ("value", "literal"),
    [
        ("Brno", "'Brno'"),
        ("O'Brien", "'O''Brien'"),
        (None, "NULL"),
        (True, "TRUE"),
        (False, "FALSE"),
        (42, "42"),
        (-7, "-7"),
        (2.5, "2.5"),
        (decimal.Decimal("12.50"), "12.50"),
        (dt.date(2026, 10, 1), "DATE '2026-10-01'"),
        (dt.datetime(2026, 10, 1, 12, 30), "TIMESTAMP '2026-10-01 12:30:00'"),
        (dt.datetime(2026, 10, 1, 12, 30, 0, 250000), "TIMESTAMP '2026-10-01 12:30:00.250000'"),
        (dt.time(8, 15), "TIME '08:15:00'"),
    ],
)
def test_named_parameters_bind_as_literals(edge, calls, value, literal):
    with _connect(edge, world="hartland_cz") as conn:
        conn.cursor().execute("SELECT * FROM er.entity.warehouse WHERE city = :city", {"city": value})
    assert edge.statements()[-1] == f"SELECT * FROM er.entity.warehouse WHERE city = {literal}"


def test_a_bound_value_filters_the_answer(edge):
    with _connect(edge, world="hartland_cz") as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM er.entity.warehouse WHERE city = :city", {"city": "Ostrava"})
        assert cur.fetchall() == [(2, "Ostrava DC", "Ostrava")]


def test_a_colon_inside_a_literal_a_quoted_name_or_a_comment_is_not_a_parameter(edge, calls):
    sql = "SELECT \"a:b\", ':x' AS t, '12:30' AS u -- :y\nFROM er.entity.warehouse /* :z */ WHERE city = :city"
    with _connect(edge, world="hartland_cz") as conn:
        conn.cursor().execute(sql, {"city": "Brno"})
    assert edge.statements()[-1] == (
        "SELECT \"a:b\", ':x' AS t, '12:30' AS u -- :y\nFROM er.entity.warehouse /* :z */ WHERE city = 'Brno'"
    )


def test_a_statement_without_parameters_is_sent_as_written(edge, calls):
    with _connect(edge, world="hartland_cz") as conn:
        conn.cursor().execute("SELECT * FROM er.entity.warehouse WHERE city = ':city'")
    assert edge.statements()[-1] == "SELECT * FROM er.entity.warehouse WHERE city = ':city'"


def test_a_parameter_without_a_value_is_refused_before_anything_is_sent(edge, calls):
    with _connect(edge, world="hartland_cz") as conn, pytest.raises(dbapi.ProgrammingError) as e:
        conn.cursor().execute("SELECT * FROM er.entity.warehouse WHERE city = :city", {"town": "Brno"})
    assert e.value.code == "parameter_unbound"
    assert e.value.message == "Parameter 'city' has no value; bind it, or send an explicit NULL (None)."
    assert edge.statements() == []


def test_positional_parameters_are_refused(edge):
    with _connect(edge, world="hartland_cz") as conn, pytest.raises(dbapi.ProgrammingError) as e:
        conn.cursor().execute("SELECT * FROM er.entity.warehouse WHERE city = :city", ["Brno"])
    assert e.value.code == "parameter_style"


def test_a_value_with_no_sql_literal_is_refused(edge):
    with _connect(edge, world="hartland_cz") as conn, pytest.raises(dbapi.NotSupportedError) as e:
        conn.cursor().execute("SELECT * FROM er.entity.warehouse WHERE city = :city", {"city": b"\x00"})
    assert e.value.code == "parameter_type"


def test_a_refusal_is_a_database_error_with_its_code_and_sentence(edge):
    with _connect(edge, world="hartland_cz") as conn, pytest.raises(dbapi.ProgrammingError) as e:
        conn.cursor().execute("SELECT * FROM er.warehouse")
    assert e.value.code == "translator_rejected"
    assert e.value.message == "Object 'warehouse' not found within 'er' — did you mean: warehouse, warehouse_id?"
    assert isinstance(e.value, dbapi.DatabaseError)


@pytest.mark.parametrize(
    ("sql", "error", "code"),
    [
        ("SELECT column_denied FROM er.entity.channel_sales", dbapi.DatabaseError, "column_denied"),
        ("SELECT dispatch_down FROM er.entity.channel_sales", dbapi.OperationalError, "dispatch_unavailable"),
    ],
)
def test_refusals_map_to_pep_249_classes(edge, sql, error, code):
    with _connect(edge, world="hartland_cz") as conn, pytest.raises(error) as e:
        conn.cursor().execute(sql)
    assert e.value.code == code


def test_an_unknown_world_fails_readably(edge):
    with _connect(edge, world="hartland_xx") as conn, pytest.raises(dbapi.OperationalError) as e:
        conn.cursor().execute("SELECT 1")
    assert e.value.code == "unknown_world"


def test_a_stream_the_edge_fails_on_its_receipt_yields_no_rows(edge):
    """C-17 — the edge sent rows, then found the door ran on another world's connection: none of them surface."""
    with _connect(edge, world="hartland_cz") as conn:
        cur = conn.cursor()
        with pytest.raises(dbapi.DatabaseError) as e:
            cur.execute("SELECT * FROM er.entity.warehouse WHERE wrong_connection = 1")
        assert e.value.code == "world_mismatch"
        assert "pg-hartland-us" in e.value.message
        assert cur.rowcount == -1
        assert cur.description is None
        with pytest.raises(dbapi.ProgrammingError):
            cur.fetchall()


def test_the_edge_is_read_only(edge):
    with _connect(edge, world="hartland_cz") as conn:
        with pytest.raises(dbapi.NotSupportedError):
            conn.cursor().executemany("INSERT INTO er.entity.warehouse VALUES (:a)", [{"a": 1}])
        conn.commit()  # nothing to commit: a no-op, so pools that commit or roll back on return keep working
        conn.rollback()


def test_fetching_before_executing_is_an_error(edge):
    with _connect(edge) as conn, pytest.raises(dbapi.ProgrammingError):
        conn.cursor().fetchone()


def test_close_closes_the_flight_client(edge):
    conn = _connect(edge, world="hartland_cz")
    cur = conn.cursor()
    conn.close()
    assert conn.closed
    with pytest.raises(dbapi.InterfaceError):
        conn.cursor()
    with pytest.raises(dbapi.InterfaceError):
        cur.execute("SELECT 1")
    conn.close()  # twice is fine


def test_the_exit_sweep_closes_what_is_still_open(edge):
    a = _connect(edge, world="hartland_cz")
    b = _connect(edge, world="hartland_us")
    a.close()
    dbapi._sweep()
    assert a.closed and b.closed


def test_a_cursor_iterates(edge):
    with _connect(edge, world="hartland_cz") as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM er.entity.warehouse")
        assert [r[0] for r in cur] == [1, 2, 3]


def test_the_client_kind_can_be_named(edge, calls):
    with _connect(edge, world="hartland_cz", client="superset") as conn:
        conn.cursor().execute("SELECT 1")
    assert {c.headers["x-ttr-client"] for c in calls} == {"superset"}


def test_ping_asks_for_sql_info(edge, calls):
    with _connect(edge, world="hartland_cz") as conn:
        assert conn.ping() is True
    assert [c.command for c in calls] == ["CommandGetSqlInfo", "CommandGetSqlInfo"]


def test_error_text_from_a_java_edge_parses_too():
    """The real edge is grpc-java: pyarrow wraps its status without Arrow's own detail suffix."""
    text = (
        'Flight returned invalid argument error, with message: translator_rejected: Syntax error near "= {".. '
        "gRPC client debug context: UNKNOWN:Error received from peer ipv4:10.0.0.1:443 {grpc_status:3, "
        'grpc_message:"translator_rejected: Syntax error near \\"= {\\"."}. Client context: OK'
    )
    assert dbapi._code_and_sentence(text) == ("translator_rejected", 'Syntax error near "= {".')
    assert dbapi._code_and_sentence("Flight returned unavailable error, with message: failed to connect") == (
        "",
        "failed to connect",
    )
