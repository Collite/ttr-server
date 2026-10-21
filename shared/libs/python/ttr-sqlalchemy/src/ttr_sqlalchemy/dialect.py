# SPDX-License-Identifier: Apache-2.0
"""The `tatrman://` SQLAlchemy dialect over the edge's Flight SQL (BI contracts C-14, C-15).

    tatrman://<flight host>[:<port>]/<world>[?tls=true|false]

One URL is one world (D-2): the URL's database is the world, sent on every call. Credentials go in `connect_args` —
`token` (a bearer; Superset's impersonation puts the viewer's there) or `user` / `password` — or as the URL's user
and password. Options in the query: `tls` (default true), `tls_skip_verify`, `timeout` (seconds), `client`
(`superset`, `jdbc`, `adbc` or `other`, for the edge's receipts).

Introspection is the Flight catalog: `get_schema_names` = `er.entity`, `obj.query`, `db.dbo`; entities and bound
tables are tables, saved queries are views; columns typed from the Arrow schema the edge publishes. Written for the
SQLAlchemy 1.4 API (Superset 6.1.0) and tested on 2.x.
"""

from __future__ import annotations

import pyarrow as pa
from sqlalchemy import exc
from sqlalchemy import types as sqltypes
from sqlalchemy.engine import default, reflection
from sqlalchemy.sql import compiler

from . import catalog
from .preparer import TatrmanIdentifierPreparer

#: The schema an unqualified name means — the model's entities.
DEFAULT_SCHEMA = "er.entity"

#: A connection that fails with one of these is gone, not wrong: the pool replaces it (and logs in again).
_RECONNECT_CODES = frozenset({"invalid_token", "unauthenticated", "unavailable", "connection_closed"})

_URL_FLAGS = ("tls", "tls_skip_verify")
_URL_OPTIONS = (*_URL_FLAGS, "timeout", "client")


class TatrmanCompiler(compiler.SQLCompiler):
    def limit_clause(self, select, **kw):
        # The default writes `LIMIT -1 OFFSET n` for an offset alone; the door reads `OFFSET n` on its own.
        text = ""
        if select._limit_clause is not None:
            text += "\n LIMIT " + self.process(select._limit_clause, **kw)
        if select._offset_clause is not None:
            text += "\n OFFSET " + self.process(select._offset_clause, **kw)
        return text


def sqla_type(t: pa.DataType) -> sqltypes.TypeEngine:
    """The SQLAlchemy type for an Arrow type in the edge's catalog."""
    if pa.types.is_int64(t) or pa.types.is_uint64(t) or pa.types.is_uint32(t):
        return sqltypes.BigInteger()
    if pa.types.is_int32(t) or pa.types.is_uint16(t):
        return sqltypes.Integer()
    if pa.types.is_integer(t):
        return sqltypes.SmallInteger()
    if pa.types.is_decimal(t):
        return sqltypes.Numeric(t.precision, t.scale)
    if pa.types.is_floating(t):
        return sqltypes.Float()
    if pa.types.is_boolean(t):
        return sqltypes.Boolean()
    if pa.types.is_date(t):
        return sqltypes.Date()
    if pa.types.is_timestamp(t):
        return sqltypes.DateTime(timezone=t.tz is not None)
    if pa.types.is_time(t):
        return sqltypes.Time()
    if pa.types.is_binary(t) or pa.types.is_large_binary(t) or pa.types.is_fixed_size_binary(t):
        return sqltypes.LargeBinary()
    if pa.types.is_null(t):
        return sqltypes.NullType()
    return sqltypes.String()


class TatrmanDialect(default.DefaultDialect):
    name = "tatrman"
    driver = "flightsql"

    supports_statement_cache = True
    supports_alter = False
    supports_sequences = False
    supports_native_boolean = True
    supports_native_decimal = True
    supports_default_values = False
    supports_empty_insert = False
    supports_multivalues_insert = False
    supports_server_side_cursors = False
    postfetch_lastrowid = False
    # SQLAlchemy 1.4 otherwise probes string handling with test statements at first connect.
    supports_unicode_statements = True
    supports_unicode_binds = True
    returns_unicode_strings = getattr(sqltypes.String, "RETURNS_UNICODE", True)
    description_encoding = None

    preparer = TatrmanIdentifierPreparer
    statement_compiler = TatrmanCompiler
    default_paramstyle = "named"
    default_schema_name = DEFAULT_SCHEMA

    @classmethod
    def import_dbapi(cls):
        from . import dbapi

        return dbapi

    @classmethod
    def dbapi(cls):  # SQLAlchemy 1.4's spelling of import_dbapi
        return cls.import_dbapi()

    def create_connect_args(self, url):
        query = dict(url.query)
        unknown = sorted(set(query) - set(_URL_OPTIONS))
        if unknown:
            raise exc.ArgumentError(
                f"tatrman:// takes no option {', '.join(unknown)}; it takes {', '.join(_URL_OPTIONS)}"
            )
        args: dict = {"host": url.host or "localhost", "port": url.port, "world": url.database or ""}
        args["tls"] = _flag(query, "tls", default=True)
        if "tls_skip_verify" in query:
            args["tls_skip_verify"] = _flag(query, "tls_skip_verify", default=False)
        if "timeout" in query:
            args["timeout"] = float(query["timeout"])
        if "client" in query:
            args["client"] = query["client"]
        if url.username:
            args["user"] = url.username
            args["password"] = url.password or ""
        return [], args

    # Connection lifecycle -------------------------------------------------------------------------------------------

    def do_ping(self, dbapi_connection) -> bool:
        """`GetSqlInfo` (C-14). False — "replace this connection" — when its bearer stopped being accepted (access
        tokens are short-lived; a pooled connection outlives them) or the edge is gone; any other refusal raises."""
        from .dbapi import Error

        try:
            return dbapi_connection.ping()
        except Error as e:
            if self.is_disconnect(e, dbapi_connection, None):
                return False
            raise

    def is_disconnect(self, e, connection, cursor) -> bool:
        return getattr(e, "code", "") in _RECONNECT_CODES

    def do_rollback(self, dbapi_connection) -> None:
        pass

    def do_commit(self, dbapi_connection) -> None:
        pass

    def _get_server_version_info(self, connection):
        return None

    def _get_default_schema_name(self, connection) -> str:
        return DEFAULT_SCHEMA

    def get_isolation_level(self, dbapi_connection):
        raise NotImplementedError("the edge has no transactions")

    def get_default_isolation_level(self, dbapi_conn):
        raise NotImplementedError("the edge has no transactions")

    def _check_max_identifier_length(self, connection):
        return None

    # Introspection (C-15) ---------------------------------------------------------------------------------------

    @reflection.cache
    def get_schema_names(self, connection, **kw):
        return catalog.schemas(_raw(connection))

    def has_table(self, connection, table_name, schema=None, **kw) -> bool:
        return bool(catalog.tables(_raw(connection), schema or DEFAULT_SCHEMA, name=table_name))

    @reflection.cache
    def get_table_names(self, connection, schema=None, **kw):
        found = catalog.tables(_raw(connection), schema or DEFAULT_SCHEMA, table_type="TABLE")
        return sorted(t.name for t in found)

    @reflection.cache
    def get_view_names(self, connection, schema=None, **kw):
        found = catalog.tables(_raw(connection), schema or DEFAULT_SCHEMA, table_type="VIEW")
        return sorted(t.name for t in found)

    @reflection.cache
    def get_columns(self, connection, table_name, schema=None, **kw):
        found = catalog.tables(_raw(connection), schema or DEFAULT_SCHEMA, name=table_name, with_fields=True)
        if not found:
            raise exc.NoSuchTableError(f"{schema or DEFAULT_SCHEMA}.{table_name}")
        fields = found[0].fields or pa.schema([])
        return [
            {"name": f.name, "type": sqla_type(f.type), "nullable": f.nullable, "default": None, "autoincrement": False}
            for f in fields
        ]

    @reflection.cache
    def get_pk_constraint(self, connection, table_name, schema=None, **kw):
        columns = catalog.primary_key(_raw(connection), schema or DEFAULT_SCHEMA, table_name)
        return {"constrained_columns": columns, "name": None}

    @reflection.cache
    def get_foreign_keys(self, connection, table_name, schema=None, **kw):
        return [
            {
                "name": fk.name,
                "constrained_columns": fk.columns,
                "referred_schema": fk.referred_schema,
                "referred_table": fk.referred_table,
                "referred_columns": fk.referred_columns,
                "options": {},
            }
            for fk in catalog.foreign_keys(_raw(connection), schema or DEFAULT_SCHEMA, table_name)
        ]

    def get_indexes(self, connection, table_name, schema=None, **kw):
        return []

    def get_unique_constraints(self, connection, table_name, schema=None, **kw):
        return []

    def get_check_constraints(self, connection, table_name, schema=None, **kw):
        return []

    def get_table_comment(self, connection, table_name, schema=None, **kw):
        return {"text": None}

    def get_view_definition(self, connection, view_name, schema=None, **kw):
        return None


def _flag(query: dict, name: str, default: bool) -> bool:
    raw = query.get(name)
    if raw is None:
        return default
    value = str(raw).strip().lower()
    if value in ("true", "1", "yes", "on"):
        return True
    if value in ("false", "0", "no", "off"):
        return False
    raise exc.ArgumentError(f"tatrman:// option {name}={raw!r} is not true or false")


def _raw(connection):
    """The DB-API connection under a SQLAlchemy one (1.4 and 2.x name it differently)."""
    fairy = connection.connection
    return getattr(fairy, "dbapi_connection", None) or fairy.connection


dialect = TatrmanDialect
