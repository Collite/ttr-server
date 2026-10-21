# SPDX-License-Identifier: Apache-2.0
"""The Flight SQL commands this driver sends, encoded by hand.

A Flight SQL command is a protobuf message (`arrow.flight.protocol.sql.*`, `FlightSql.proto`) packed in a
`google.protobuf.Any` and sent as a Flight descriptor's command. The driver sends seven of them, all made of strings,
a bool and a repeated uint32, so it writes the protobuf wire format itself rather than ship generated stubs (BI C-13).
The answers are Arrow streams and need no decoding here. The recorded edge in the tests decodes every command with the
official `FlightSql.proto`, which is what keeps the field numbers below honest.
"""

from __future__ import annotations

from collections.abc import Iterable

_TYPE_PREFIX = "type.googleapis.com/arrow.flight.protocol.sql."

# SqlInfo ids (FlightSql.proto `enum SqlInfo`).
FLIGHT_SQL_SERVER_NAME = 0
FLIGHT_SQL_SERVER_VERSION = 1
FLIGHT_SQL_SERVER_READ_ONLY = 3


def _varint(n: int) -> bytes:
    out = bytearray()
    while True:
        byte = n & 0x7F
        n >>= 7
        if n:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def _length_delimited(field: int, payload: bytes) -> bytes:
    return _varint(field << 3 | 2) + _varint(len(payload)) + payload


def _string(field: int, value: str | None, *, explicit: bool = True) -> bytes:
    """An `optional string` is sent whenever it is set (even empty); a plain proto3 `string` only when non-empty."""
    if value is None or (not explicit and value == ""):
        return b""
    return _length_delimited(field, value.encode("utf-8"))


def _bool(field: int, value: bool) -> bytes:
    return _varint(field << 3 | 0) + _varint(1) if value else b""


def _uint32s(field: int, values: Iterable[int]) -> bytes:
    packed = b"".join(_varint(v) for v in values)
    return _length_delimited(field, packed) if packed else b""


def _command(name: str, body: bytes) -> bytes:
    return _length_delimited(1, (_TYPE_PREFIX + name).encode("ascii")) + _length_delimited(2, body)


def statement_query(query: str) -> bytes:
    """`CommandStatementQuery { string query = 1; }`."""
    return _command("CommandStatementQuery", _string(1, query, explicit=False))


def get_sql_info(info: Iterable[int]) -> bytes:
    """`CommandGetSqlInfo { repeated uint32 info = 1; }`."""
    return _command("CommandGetSqlInfo", _uint32s(1, info))


def get_catalogs() -> bytes:
    return _command("CommandGetCatalogs", b"")


def get_db_schemas(catalog: str | None = None, schema_pattern: str | None = None) -> bytes:
    """`CommandGetDbSchemas { optional string catalog = 1; optional string db_schema_filter_pattern = 2; }`."""
    return _command("CommandGetDbSchemas", _string(1, catalog) + _string(2, schema_pattern))


def get_tables(
    catalog: str | None = None,
    schema_pattern: str | None = None,
    table_pattern: str | None = None,
    table_types: Iterable[str] = (),
    include_schema: bool = False,
) -> bytes:
    """`CommandGetTables { optional catalog = 1; optional db_schema_filter_pattern = 2;
    optional table_name_filter_pattern = 3; repeated string table_types = 4; bool include_schema = 5; }`."""
    body = _string(1, catalog) + _string(2, schema_pattern) + _string(3, table_pattern)
    body += b"".join(_length_delimited(4, t.encode("utf-8")) for t in table_types)
    body += _bool(5, include_schema)
    return _command("CommandGetTables", body)


def get_primary_keys(table: str, catalog: str | None = None, schema: str | None = None) -> bytes:
    """`CommandGetPrimaryKeys { optional catalog = 1; optional db_schema = 2; string table = 3; }`."""
    return _command(
        "CommandGetPrimaryKeys", _string(1, catalog) + _string(2, schema) + _string(3, table, explicit=False)
    )


def get_imported_keys(table: str, catalog: str | None = None, schema: str | None = None) -> bytes:
    """`CommandGetImportedKeys`, the same three fields as `CommandGetPrimaryKeys`."""
    return _command(
        "CommandGetImportedKeys", _string(1, catalog) + _string(2, schema) + _string(3, table, explicit=False)
    )
