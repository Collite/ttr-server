# SPDX-License-Identifier: Apache-2.0
"""The edge's catalog, read through Flight SQL's catalog calls and nothing else (BI C-15).

Catalog = the connection's world; schemas `er.entity` (entities), `obj.query` (saved logical queries, as views) and
`db.dbo` (bound tables); each table with its Arrow schema, typed by the edge from the model. Flight SQL's filter
patterns are LIKE patterns (`_` is any one character), so a lookup by name passes the name as the pattern and keeps
only the exact match.
"""

from __future__ import annotations

from dataclasses import dataclass

import pyarrow as pa

from . import _flightsql
from .dbapi import Connection


@dataclass(frozen=True)
class Table:
    schema: str
    name: str
    type: str  # TABLE | VIEW
    fields: pa.Schema | None = None


@dataclass(frozen=True)
class ForeignKey:
    name: str | None
    columns: list[str]
    referred_schema: str
    referred_table: str
    referred_columns: list[str]


def _catalog(conn: Connection) -> str | None:
    return conn.world or None


def schemas(conn: Connection) -> list[str]:
    table = conn.fetch_command(_flightsql.get_db_schemas(catalog=_catalog(conn)))
    return [s for s in table.column("db_schema_name").to_pylist() if s]


def tables(
    conn: Connection,
    schema: str,
    name: str | None = None,
    table_type: str | None = None,
    with_fields: bool = False,
) -> list[Table]:
    answer = conn.fetch_command(
        _flightsql.get_tables(
            catalog=_catalog(conn),
            schema_pattern=schema,
            table_pattern=name,
            table_types=[table_type] if table_type else (),
            include_schema=with_fields,
        )
    )
    out = []
    rows = answer.to_pylist()
    for row in rows:
        if row["db_schema_name"] != schema or (name is not None and row["table_name"] != name):
            continue
        if table_type and row["table_type"] != table_type:
            continue
        fields = pa.ipc.read_schema(pa.py_buffer(row["table_schema"])) if with_fields else None
        out.append(Table(row["db_schema_name"], row["table_name"], row["table_type"], fields))
    return out


def primary_key(conn: Connection, schema: str, name: str) -> list[str]:
    answer = conn.fetch_command(_flightsql.get_primary_keys(name, catalog=_catalog(conn), schema=schema))
    keys = [
        r
        for r in answer.to_pylist()
        if r["table_name"] == name and (r["db_schema_name"] in (None, schema)) and r["column_name"]
    ]
    return [r["column_name"] for r in sorted(keys, key=lambda r: r["key_sequence"] or 0)]


def foreign_keys(conn: Connection, schema: str, name: str) -> list[ForeignKey]:
    """The table's foreign keys, one per relation: grouped by the edge's `fk_key_name`. From an edge that names no key,
    a `key_sequence` back at 1 starts the next key, so two relations to one table stay two keys."""
    answer = conn.fetch_command(_flightsql.get_imported_keys(name, catalog=_catalog(conn), schema=schema))
    keys: list[list[dict]] = []
    named: dict[tuple, list[dict]] = {}
    unnamed: dict[tuple, list[dict]] = {}
    for r in answer.to_pylist():
        if r["fk_table_name"] != name or r["fk_db_schema_name"] not in (None, schema):
            continue
        target = (r["pk_db_schema_name"], r["pk_table_name"])
        if r["fk_key_name"]:
            rows = named.get((r["fk_key_name"], *target))
            if rows is None:
                rows = named[(r["fk_key_name"], *target)] = []
                keys.append(rows)
        else:
            rows = unnamed.get(target)
            if rows is None or (r["key_sequence"] or 1) == 1:
                rows = unnamed[target] = []
                keys.append(rows)
        rows.append(r)
    out = []
    for rows in keys:
        rows.sort(key=lambda r: r["key_sequence"] or 0)
        out.append(
            ForeignKey(
                name=rows[0]["fk_key_name"],
                columns=[r["fk_column_name"] for r in rows],
                referred_schema=rows[0]["pk_db_schema_name"] or schema,
                referred_table=rows[0]["pk_table_name"],
                referred_columns=[r["pk_column_name"] for r in rows],
            )
        )
    return out
