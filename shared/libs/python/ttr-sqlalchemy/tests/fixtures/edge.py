# SPDX-License-Identifier: Apache-2.0
"""A recorded Flight SQL edge: a small in-process server that answers the way a Tatrman Server's edge does.

It replays the edge's observable behaviour — the Basic handshake that returns a bearer, the bearer and `world` headers
on every call, the three schemas and their tables, one-line `"<code>: <sentence>"` refusals, the JSON closing metadata
on the last batch, and `world_mismatch` failing a stream after rows were sent — without a door behind it. Commands are
decoded with the official `FlightSql.proto` (compiled at test time by `conftest.py`), so the driver's hand-written
encoding is checked against the real schema, not against itself.
"""

from __future__ import annotations

import base64
import datetime as dt
import decimal
import json
import re
import threading
from dataclasses import dataclass, field

import pyarrow as pa
import pyarrow.flight as fl

from .generated import FlightSql_pb2 as sql_pb2

TYPE_PREFIX = "type.googleapis.com/arrow.flight.protocol.sql."

TOKENS = {"tok-petr": "petr", "tok-marketa": "marketa"}
PASSWORDS = {"marketa": "marketa-pw"}

CONNECTIONS = {"hartland_cz": "pg-hartland-cz", "hartland_us": "pg-hartland-us"}


@dataclass
class Table:
    schema: str
    name: str
    type: str
    fields: pa.Schema
    primary_key: list[str] = field(default_factory=list)


CATALOG = [
    Table(
        "er.entity",
        "warehouse",
        "TABLE",
        pa.schema(
            [
                pa.field("warehouse_id", pa.int64(), nullable=False),
                pa.field("warehouse_name", pa.utf8()),
                pa.field("city", pa.utf8()),
            ]
        ),
        primary_key=["warehouse_id"],
    ),
    Table(
        "er.entity",
        "channel_sales",
        "TABLE",
        pa.schema(
            [
                pa.field("sale_id", pa.int64(), nullable=False),
                pa.field("warehouse_id", pa.int64()),
                pa.field("ext_sales_price", pa.decimal128(19, 2)),
                pa.field("sold_date", pa.date32()),
                pa.field("sold_at", pa.timestamp("us")),
                pa.field("is_return", pa.bool_()),
                pa.field("quantity", pa.int32()),
                pa.field("discount", pa.float64()),
            ]
        ),
        primary_key=["sale_id"],
    ),
    Table(
        "obj.query",
        "revenue_per_customer",
        "VIEW",
        pa.schema([pa.field("customer_name", pa.utf8()), pa.field("revenue", pa.decimal128(19, 2))]),
    ),
    Table(
        "db.dbo",
        "inventory",
        "TABLE",
        pa.schema([pa.field("inv_item_sk", pa.int64(), nullable=False), pa.field("inv_quantity_on_hand", pa.int32())]),
        primary_key=["inv_item_sk"],
    ),
]

# channel_sales.warehouse_id → warehouse.warehouse_id: the one E-R relation with join pairs. As the edge sends a key:
# (pk schema, pk table, pk column, fk schema, fk table, fk column, key_sequence, fk_key_name — one per relation).
FOREIGN_KEYS = [
    (
        "er.entity",
        "warehouse",
        "warehouse_id",
        "er.entity",
        "channel_sales",
        "warehouse_id",
        1,
        "channel_sales_warehouse_id_fkey",
    )
]

WAREHOUSES = {
    "hartland_cz": [(1, "Brno DC", "Brno"), (2, "Ostrava DC", "Ostrava"), (3, "Plzeň DC", "Plzeň")],
    "hartland_us": [(10, "Dallas DC", "Dallas")],
}


@dataclass
class Call:
    method: str
    headers: dict[str, str]
    command: str = ""
    query: str = ""


class _Middleware(fl.ServerMiddleware):
    def __init__(self, token: str | None, user: str, headers: dict[str, str]):
        self.token = token
        self.user = user
        self.headers = headers

    def sending_headers(self):
        return {"authorization": self.token} if self.token else {}


class _MiddlewareFactory(fl.ServerMiddlewareFactory):
    """C-2: a bearer on every call; Basic credentials on the handshake are exchanged for one."""

    def __init__(self, edge: RecordedEdge):
        self.edge = edge

    def start_call(self, info, headers):
        flat = {k: v[0] if isinstance(v[0], str) else v[0].decode() for k, v in headers.items()}
        auth = flat.get("authorization", "")
        if info.method == fl.FlightMethod.HANDSHAKE:
            self.edge.record(Call("handshake", flat))
            if not auth.startswith("Basic "):
                raise fl.FlightUnauthenticatedError("missing_credentials: no bearer token and no user name.")
            user, _, password = base64.b64decode(auth[6:]).decode().partition(":")
            if PASSWORDS.get(user) != password:
                raise fl.FlightUnauthenticatedError(
                    "password_refused: Keycloak refused the password grant (401 invalid_grant)."
                )
            return _Middleware(f"Bearer tok-{user}", user, flat)
        if not auth.startswith("Bearer "):
            raise fl.FlightUnauthenticatedError("missing_credentials: no bearer token and no user name.")
        user = TOKENS.get(auth[7:])
        if user is None:
            raise fl.FlightUnauthenticatedError("invalid_token: The bearer token was not accepted (signature).")
        world = flat.get("world", "")
        if world and world not in CONNECTIONS:
            raise fl.FlightUnauthenticatedError(
                f"unknown_world: World '{world}' is not served here; the worlds are hartland_cz, hartland_us."
            )
        return _Middleware(None, user, flat)


class _NoHandshakeBody(fl.ServerAuthHandler):
    """The handshake is header-only (Flight's auth2 shape); the middleware does the work."""

    def authenticate(self, outgoing, incoming):
        pass

    def is_valid(self, token):
        return ""


class RecordedEdge(fl.FlightServerBase):
    def __init__(self):
        super().__init__(
            "grpc://127.0.0.1:0", auth_handler=_NoHandshakeBody(), middleware={"id": _MiddlewareFactory(self)}
        )
        self.calls: list[Call] = []
        self._lock = threading.Lock()
        self._statements: dict[bytes, str] = {}

    @property
    def url(self) -> str:
        return f"127.0.0.1:{self.port}"

    def record(self, call: Call) -> None:
        with self._lock:
            self.calls.append(call)

    def statements(self) -> list[str]:
        return [c.query for c in self.calls if c.command == "CommandStatementQuery"]

    # --- Flight --------------------------------------------------------------------------------------------------

    def get_flight_info(self, context, descriptor):
        any_ = _unpack(descriptor.command)
        name = any_["name"]
        headers = _headers(context)
        if name == "CommandStatementQuery":
            command = sql_pb2.CommandStatementQuery.FromString(any_["value"])
            self.record(Call("get_flight_info", headers, name, command.query))
            schema = self._statement_schema(command.query, headers)
            handle = f"stmt-{len(self.calls)}".encode()
            with self._lock:
                self._statements[handle] = command.query
            ticket = _pack("TicketStatementQuery", sql_pb2.TicketStatementQuery(statement_handle=handle))
            return fl.FlightInfo(schema, descriptor, [fl.FlightEndpoint(ticket, [])], -1, -1)
        self.record(Call("get_flight_info", headers, name))
        schema = self._catalog_schema(name, any_["value"])
        return fl.FlightInfo(schema, descriptor, [fl.FlightEndpoint(descriptor.command, [])], -1, -1)

    def do_get(self, context, ticket):
        any_ = _unpack(ticket.ticket)
        headers = _headers(context)
        world = headers.get("world", "")
        if any_["name"] == "TicketStatementQuery":
            handle = sql_pb2.TicketStatementQuery.FromString(any_["value"]).statement_handle
            with self._lock:
                query = self._statements.pop(handle)
            self.record(Call("do_get", headers, "TicketStatementQuery", query))
            return self._statement_stream(query, world)
        self.record(Call("do_get", headers, any_["name"]))
        return self._catalog_stream(any_["name"], any_["value"], world)

    # --- statements: the replayed answers ------------------------------------------------------------------------

    def _statement_schema(self, query: str, headers: dict[str, str]) -> pa.Schema:
        q = " ".join(query.split())
        if "FROM er.warehouse" in q:
            raise pa.ArrowInvalid(
                "translator_rejected: Object 'warehouse' not found within 'er' — did you mean: warehouse, warehouse_id?"
            )
        if "INSERT" in q.upper():
            raise pa.ArrowInvalid("translator_rejected: The edge is read-only; INSERT is not a query.")
        if "world_required" in q and not headers.get("world"):
            raise pa.ArrowInvalid(
                "world_unspecified: A world is required here; the worlds are hartland_cz, hartland_us."
            )
        if "column_denied" in q:
            raise fl.FlightUnauthorizedError("column_denied: Column 'margin' of 'channel_sales' is not readable.")
        if "dispatch_down" in q:
            raise fl.FlightUnavailableError("dispatch_unavailable: dispatch did not answer.")
        return self._answer(q, headers.get("world", ""))[0]

    def _statement_stream(self, query: str, world: str):
        q = " ".join(query.split())
        schema, batches, closing = self._answer(q, world)

        def gen():
            for i, batch in enumerate(batches):
                if i == len(batches) - 1 and closing is not None:
                    yield batch, json.dumps(closing).encode()
                else:
                    yield batch
            if "wrong_connection" in q:
                raise fl.FlightUnauthorizedError(
                    f"world_mismatch: the door ran this on connection 'pg-hartland-us', not on world '{world}' "
                    f"('{CONNECTIONS.get(world, '')}')."
                )

        return fl.GeneratorStream(schema, gen())

    def _answer(self, q: str, world: str):
        """(schema, batches, closing metadata) for a statement — the shapes the real edge streams."""
        connection = CONNECTIONS.get(world, "pg-hartland-cz")
        rows_world = world or "hartland_cz"
        if "warehouse" in q and "wrong_connection" not in q:
            rows = [r for r in WAREHOUSES[rows_world] if _matches(q, r)]
            schema = CATALOG[0].fields
            # Two batches, as the workers batch: every row reaches the client however the stream is framed.
            batches = [_batch(schema, rows[:2]), _batch(schema, rows[2:])] if len(rows) > 2 else [_batch(schema, rows)]
            return schema, batches, _closing(connection, len(rows))
        if "wrong_connection" in q:
            schema = CATALOG[0].fields
            return schema, [_batch(schema, WAREHOUSES["hartland_us"])], None
        if "channel_sales" in q:
            schema = CATALOG[1].fields
            row = (
                7,
                1,
                decimal.Decimal("12.50"),
                dt.date(2026, 10, 1),
                dt.datetime(2026, 10, 1, 12, 30),
                False,
                3,
                0.5,
            )
            capped = "LIMIT 10000" in q
            closing = _closing(connection, 1)
            if capped:
                closing["top_n_applied"] = True
                closing["notices"] = [
                    {
                        "code": "top_n_applied",
                        "severity": "WARNING",
                        # As the real edge sends it (C-10): the edge asks for as many rows as the caller's
                        # ceiling allows, so `requested` is 0 whatever the LIMIT, and the cap is the ceiling.
                        "message": (
                            "Answer limited to 200 rows by the row cap; the caller asked for as many rows as its "
                            "ceiling allows."
                        ),
                        "requested": 0,
                        "applied": 200,
                    }
                ]
            return schema, [_batch(schema, [row])], closing
        # `SELECT 1`-shaped probes.
        schema = pa.schema([pa.field("EXPR$0", pa.int32(), nullable=False)])
        return schema, [_batch(schema, [(1,)])], _closing(connection, 1)

    # --- catalog -------------------------------------------------------------------------------------------------

    def _catalog_schema(self, name: str, value: bytes) -> pa.Schema:
        return self._catalog_table(name, value, "").schema

    def _catalog_stream(self, name: str, value: bytes, world: str):
        return fl.RecordBatchStream(self._catalog_table(name, value, world))

    def _catalog_table(self, name: str, value: bytes, world: str) -> pa.Table:
        catalog = world or None
        if name == "CommandGetSqlInfo":
            command = sql_pb2.CommandGetSqlInfo.FromString(value)
            return _sql_info(list(command.info))
        if name == "CommandGetCatalogs":
            return pa.table({"catalog_name": pa.array([world] if world else [], pa.utf8())})
        if name == "CommandGetDbSchemas":
            command = sql_pb2.CommandGetDbSchemas.FromString(value)
            pattern = command.db_schema_filter_pattern if command.HasField("db_schema_filter_pattern") else None
            names = [s for s in ("er.entity", "obj.query", "db.dbo") if _like(pattern, s)]
            return pa.table(
                {
                    "catalog_name": pa.array([catalog] * len(names), pa.utf8()),
                    "db_schema_name": pa.array(names, pa.utf8()),
                }
            )
        if name == "CommandGetTableTypes":
            return pa.table({"table_type": pa.array(["TABLE", "VIEW"], pa.utf8())})
        if name == "CommandGetTables":
            command = sql_pb2.CommandGetTables.FromString(value)
            schema_pattern = command.db_schema_filter_pattern if command.HasField("db_schema_filter_pattern") else None
            table_pattern = command.table_name_filter_pattern if command.HasField("table_name_filter_pattern") else None
            tables = [
                t
                for t in CATALOG
                if _like(schema_pattern, t.schema)
                and _like(table_pattern, t.name)
                and (not command.table_types or t.type in command.table_types)
            ]
            columns = {
                "catalog_name": pa.array([catalog] * len(tables), pa.utf8()),
                "db_schema_name": pa.array([t.schema for t in tables], pa.utf8()),
                "table_name": pa.array([t.name for t in tables], pa.utf8()),
                "table_type": pa.array([t.type for t in tables], pa.utf8()),
            }
            if command.include_schema:
                columns["table_schema"] = pa.array([t.fields.serialize().to_pybytes() for t in tables], pa.binary())
            return pa.table(columns)
        if name == "CommandGetPrimaryKeys":
            command = sql_pb2.CommandGetPrimaryKeys.FromString(value)
            keys = [
                (t, c, i + 1)
                for t in CATALOG
                if t.name == command.table and (not command.HasField("db_schema") or t.schema == command.db_schema)
                for i, c in enumerate(t.primary_key)
            ]
            return pa.table(
                {
                    "catalog_name": pa.array([catalog] * len(keys), pa.utf8()),
                    "db_schema_name": pa.array([t.schema for t, _, _ in keys], pa.utf8()),
                    "table_name": pa.array([t.name for t, _, _ in keys], pa.utf8()),
                    "column_name": pa.array([c for _, c, _ in keys], pa.utf8()),
                    "key_name": pa.array([None] * len(keys), pa.utf8()),
                    "key_sequence": pa.array([s for _, _, s in keys], pa.int32()),
                }
            )
        if name == "CommandGetImportedKeys":
            command = sql_pb2.CommandGetImportedKeys.FromString(value)
            keys = [
                k
                for k in FOREIGN_KEYS
                if k[4] == command.table and (not command.HasField("db_schema") or k[3] == command.db_schema)
            ]
            n = len(keys)
            return pa.table(
                {
                    "pk_catalog_name": pa.array([catalog] * n, pa.utf8()),
                    "pk_db_schema_name": pa.array([k[0] for k in keys], pa.utf8()),
                    "pk_table_name": pa.array([k[1] for k in keys], pa.utf8()),
                    "pk_column_name": pa.array([k[2] for k in keys], pa.utf8()),
                    "fk_catalog_name": pa.array([catalog] * n, pa.utf8()),
                    "fk_db_schema_name": pa.array([k[3] for k in keys], pa.utf8()),
                    "fk_table_name": pa.array([k[4] for k in keys], pa.utf8()),
                    "fk_column_name": pa.array([k[5] for k in keys], pa.utf8()),
                    "key_sequence": pa.array([k[6] for k in keys], pa.int32()),
                    "fk_key_name": pa.array([k[7] for k in keys], pa.utf8()),
                    "pk_key_name": pa.array([None] * n, pa.utf8()),
                    "update_rule": pa.array([3] * n, pa.uint8()),
                    "delete_rule": pa.array([3] * n, pa.uint8()),
                }
            )
        raise fl.FlightServerError(f"unsupported: {name}")


def _headers(context) -> dict[str, str]:
    """The call's headers, as the middleware saw them (pyarrow exposes them only there)."""
    return dict(context.get_middleware("id").headers)


def _unpack(raw: bytes) -> dict:
    from google.protobuf import any_pb2

    any_ = any_pb2.Any.FromString(raw)
    if not any_.type_url.startswith(TYPE_PREFIX):
        raise fl.FlightServerError(f"not a Flight SQL command: {any_.type_url}")
    return {"name": any_.type_url[len(TYPE_PREFIX) :], "value": any_.value}


def _pack(name: str, message) -> bytes:
    from google.protobuf import any_pb2

    return any_pb2.Any(type_url=TYPE_PREFIX + name, value=message.SerializeToString()).SerializeToString()


def _batch(schema: pa.Schema, rows: list[tuple]) -> pa.RecordBatch:
    columns = list(zip(*rows, strict=True)) if rows else [[] for _ in schema]
    return pa.record_batch([pa.array(list(c), f.type) for c, f in zip(columns, schema, strict=True)], schema=schema)


def _closing(connection: str, rows: int) -> dict:
    return {
        "correlation_id": "c0ffee00-0000-4000-8000-000000000001",
        "connection_id": connection,
        "rows_total": rows,
        "top_n_applied": False,
        "notices": [],
    }


def _matches(q: str, row: tuple) -> bool:
    """`WHERE city = '<x>'` (or `"city" = '<x>'`) filters the replayed warehouses; anything else returns them all."""
    m = re.search(r"city\"? = '([^']*)'", q)
    return m is None or row[2] == m.group(1)


def _like(pattern: str | None, value: str) -> bool:
    if pattern is None:
        return True

    regex = "".join(".*" if c == "%" else "." if c == "_" else re.escape(c) for c in pattern)
    return re.fullmatch(regex, value) is not None


def _sql_info(ids: list[int]) -> pa.Table:
    """GetSqlInfo's dense-union answer, for the ids the edge knows (name, version, read-only)."""
    known = {0: ("string", "Tatrman Flight SQL edge"), 1: ("string", "0.0.0-test"), 3: ("bool", True)}
    wanted = [i for i in (ids or list(known)) if i in known]
    union_type = pa.dense_union(
        [
            pa.field("string_value", pa.utf8()),
            pa.field("bool_value", pa.bool_()),
            pa.field("bigint_value", pa.int64()),
            pa.field("int32_bitmask", pa.int32()),
            pa.field("string_list", pa.list_(pa.utf8())),
        ],
        type_codes=[0, 1, 2, 3, 4],
    )
    strings = [known[i][1] for i in wanted if known[i][0] == "string"]
    bools = [known[i][1] for i in wanted if known[i][0] == "bool"]
    types, offsets, s, b = [], [], 0, 0
    for i in wanted:
        if known[i][0] == "string":
            types.append(0)
            offsets.append(s)
            s += 1
        else:
            types.append(1)
            offsets.append(b)
            b += 1
    value = pa.UnionArray.from_dense(
        pa.array(types, pa.int8()),
        pa.array(offsets, pa.int32()),
        [
            pa.array(strings, pa.utf8()),
            pa.array(bools, pa.bool_()),
            pa.array([], pa.int64()),
            pa.array([], pa.int32()),
            pa.array([], pa.list_(pa.utf8())),
        ],
        ["string_value", "bool_value", "bigint_value", "int32_bitmask", "string_list"],
        [0, 1, 2, 3, 4],
    )
    assert value.type == union_type
    return pa.table({"info_name": pa.array(wanted, pa.uint32()), "value": value})
