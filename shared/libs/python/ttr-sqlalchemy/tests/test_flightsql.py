# SPDX-License-Identifier: Apache-2.0
"""The hand-written Flight SQL commands, decoded with Apache Arrow's own FlightSql.proto."""

from __future__ import annotations

from google.protobuf import any_pb2

from tests.fixtures.generated import FlightSql_pb2 as sql_pb2
from ttr_sqlalchemy import _flightsql

PREFIX = "type.googleapis.com/arrow.flight.protocol.sql."


def _decode(raw: bytes, message):
    any_ = any_pb2.Any.FromString(raw)
    assert any_.type_url == PREFIX + message.DESCRIPTOR.name
    return message.FromString(any_.value)


def test_statement_query_carries_the_sql_verbatim():
    sql = "SELECT * FROM er.entity.warehouse WHERE city = 'Plzeň' -- " + "x" * 300
    assert _decode(_flightsql.statement_query(sql), sql_pb2.CommandStatementQuery).query == sql


def test_sql_info_ids_pack_including_multi_byte_varints():
    command = _decode(_flightsql.get_sql_info([0, 1, 3, 508]), sql_pb2.CommandGetSqlInfo)
    assert list(command.info) == [0, 1, 3, 508]
    assert list(_decode(_flightsql.get_sql_info([]), sql_pb2.CommandGetSqlInfo).info) == []


def test_optional_strings_are_present_when_set_even_empty():
    command = _decode(_flightsql.get_db_schemas(catalog="", schema_pattern=None), sql_pb2.CommandGetDbSchemas)
    assert command.HasField("catalog") and command.catalog == ""
    assert not command.HasField("db_schema_filter_pattern")
    _decode(_flightsql.get_catalogs(), sql_pb2.CommandGetCatalogs)


def test_get_tables_every_field():
    command = _decode(
        _flightsql.get_tables(
            catalog="hartland_cz",
            schema_pattern="er.entity",
            table_pattern="warehouse",
            table_types=["TABLE", "VIEW"],
            include_schema=True,
        ),
        sql_pb2.CommandGetTables,
    )
    assert (command.catalog, command.db_schema_filter_pattern, command.table_name_filter_pattern) == (
        "hartland_cz",
        "er.entity",
        "warehouse",
    )
    assert list(command.table_types) == ["TABLE", "VIEW"]
    assert command.include_schema is True
    bare = _decode(_flightsql.get_tables(), sql_pb2.CommandGetTables)
    assert not bare.HasField("catalog") and not bare.include_schema and not bare.table_types


def test_key_lookups():
    for build, message in (
        (_flightsql.get_primary_keys, sql_pb2.CommandGetPrimaryKeys),
        (_flightsql.get_imported_keys, sql_pb2.CommandGetImportedKeys),
    ):
        command = _decode(build("channel_sales", catalog="hartland_us", schema="er.entity"), message)
        assert (command.catalog, command.db_schema, command.table) == ("hartland_us", "er.entity", "channel_sales")
