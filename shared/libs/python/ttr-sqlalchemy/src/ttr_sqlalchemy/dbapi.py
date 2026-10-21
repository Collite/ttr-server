# SPDX-License-Identifier: Apache-2.0
"""A PEP 249 driver for a Tatrman Server's Flight SQL edge (BI contracts C-14, C-17).

    conn = connect(host="flight.example.com", port=443, world="hartland_cz", token="<bearer>")
    cur = conn.cursor()
    cur.execute("SELECT * FROM er.entity.warehouse WHERE city = :city", {"city": "Brno"})
    cur.fetchall()

It speaks Arrow Flight SQL through `pyarrow.flight`. Every call carries the bearer (a `token`, or the one the edge hands
back for a `user` and `password` at connect), the connection's `world` and the client kind (`x-ttr-client`). A
statement is one `GetFlightInfo` (the edge runs it there) and one `DoGet` per endpoint.

**A result is read whole before its first row is returned.** The edge holds an answer's last batch until the door's
receipt has named the connection it ran on; a receipt from another world's connection fails the stream with
`world_mismatch` after earlier batches were sent. Reading to the end first means such a stream yields no rows at all,
only the error. The door caps every answer (the validator's row ceiling), which bounds what is held.

The last batch's app metadata (JSON: `correlation_id`, `connection_id`, `rows_total`, `top_n_applied`, `notices`) is
on the cursor after `execute`. The edge takes no bound parameters; `:name` values are written into the statement as
literals ([params]).
"""

from __future__ import annotations

import atexit
import datetime as dt
import json
import re
import threading
import time
import weakref
from typing import Any

import pyarrow as pa
import pyarrow.flight as flight

from . import _flightsql, params
from .errors import (
    DatabaseError,
    DataError,
    Error,
    IntegrityError,
    InterfaceError,
    InternalError,
    NotSupportedError,
    OperationalError,
    ProgrammingError,
    Warning,
    error_class,
)

__all__ = [
    "apilevel",
    "threadsafety",
    "paramstyle",
    "connect",
    "Connection",
    "Cursor",
    "Error",
    "Warning",
    "InterfaceError",
    "DatabaseError",
    "DataError",
    "OperationalError",
    "IntegrityError",
    "InternalError",
    "ProgrammingError",
    "NotSupportedError",
    "STRING",
    "BINARY",
    "NUMBER",
    "DATETIME",
    "ROWID",
    "Date",
    "Time",
    "Timestamp",
    "DateFromTicks",
    "TimeFromTicks",
    "TimestampFromTicks",
    "Binary",
]

apilevel = "2.0"
threadsafety = 1
paramstyle = "named"

#: The client kinds the edge records on its receipts (anything else is recorded as `other`).
CLIENTS = ("superset", "jdbc", "adbc", "other")


# --- type objects and constructors (PEP 249) ---------------------------------------------------------------------


class _TypeObject:
    """Compares equal to every Arrow type its predicates accept — `description[i][1] == NUMBER`."""

    def __init__(self, name: str, *predicates):
        self._name = name
        self._predicates = predicates

    def __eq__(self, other: object) -> bool:
        if isinstance(other, pa.DataType):
            return any(p(other) for p in self._predicates)
        return NotImplemented

    def __hash__(self) -> int:
        return hash(self._name)

    def __repr__(self) -> str:
        return self._name


def _is_string(t: pa.DataType) -> bool:
    return pa.types.is_string(t) or pa.types.is_large_string(t) or getattr(pa.types, "is_string_view", bool)(t)


STRING = _TypeObject("STRING", _is_string)
BINARY = _TypeObject("BINARY", pa.types.is_binary, pa.types.is_large_binary, pa.types.is_fixed_size_binary)
NUMBER = _TypeObject("NUMBER", pa.types.is_integer, pa.types.is_floating, pa.types.is_decimal)
DATETIME = _TypeObject("DATETIME", pa.types.is_date, pa.types.is_timestamp, pa.types.is_time)
ROWID = _TypeObject("ROWID")

Date = dt.date
Time = dt.time
Timestamp = dt.datetime
Binary = bytes


def DateFromTicks(ticks: float) -> dt.date:  # PEP 249's names, hence the case
    return dt.date(*time.localtime(ticks)[:3])


def TimeFromTicks(ticks: float) -> dt.time:
    return dt.time(*time.localtime(ticks)[3:6])


def TimestampFromTicks(ticks: float) -> dt.datetime:
    return dt.datetime(*time.localtime(ticks)[:6])


# --- errors ------------------------------------------------------------------------------------------------------

_FLIGHT_PREFIX = re.compile(r"^Flight returned [a-z ]+ error, with message: ")
_TRAILERS = (". Detail: ", ". gRPC client debug context:")
_CODED = re.compile(r"^([a-z][a-z0-9_]*): (.*)$", re.DOTALL)


def _code_and_sentence(text: str) -> tuple[str, str]:
    """The edge's `"<code>: <one sentence>"` out of pyarrow's wrapping of a Flight status."""
    text = _FLIGHT_PREFIX.sub("", text.strip())
    cuts = [i for i in (text.find(t) for t in _TRAILERS) if i >= 0]
    if cuts:
        text = text[: min(cuts)]
    text = " ".join(text.split())
    m = _CODED.match(text)
    return (m.group(1), m.group(2)) if m else ("", text)


def _translate(exc: BaseException) -> Error:
    """A pyarrow Flight error as this driver's PEP 249 error, keeping the edge's code and sentence."""
    if isinstance(exc, Error):
        return exc
    code, sentence = _code_and_sentence(str(exc))
    if code:
        return error_class(code)(code, sentence)
    if isinstance(exc, flight.FlightUnauthenticatedError):
        return OperationalError("unauthenticated", sentence)
    if isinstance(exc, (flight.FlightUnavailableError, flight.FlightTimedOutError, flight.FlightCancelledError)):
        return OperationalError("unavailable", sentence)
    if isinstance(exc, flight.FlightInternalError):
        return InternalError("internal", sentence)
    if isinstance(exc, pa.ArrowNotImplementedError):
        return NotSupportedError("unsupported", sentence)
    return DatabaseError("flight_error", sentence)


# --- connections -------------------------------------------------------------------------------------------------

_OPEN: weakref.WeakSet[Connection] = weakref.WeakSet()
_OPEN_LOCK = threading.Lock()


def _sweep() -> None:
    """Close every connection still open — a gRPC channel left open can hold the interpreter at exit."""
    with _OPEN_LOCK:
        open_now = list(_OPEN)
    for conn in open_now:
        conn.close()


atexit.register(_sweep)


def connect(
    host: str = "localhost",
    port: int | None = None,
    world: str = "",
    *,
    tls: bool = True,
    token: str | None = None,
    user: str | None = None,
    password: str | None = None,
    client: str = "other",
    tls_skip_verify: bool = False,
    tls_root_certs: bytes | None = None,
    timeout: float | None = None,
) -> Connection:
    """A connection to the edge at [host]:[port] reading [world].

    Authenticate with a bearer `token`, or a `user` and `password` the edge exchanges for one at connect (the password
    goes once, on the handshake). [port] defaults to 443 with TLS, 7308 without. [timeout] (seconds) bounds every
    call; none by default.
    """
    return Connection(
        host=host,
        port=port if port else (443 if tls else 7308),
        world=world,
        tls=tls,
        token=token,
        user=user,
        password=password,
        client=client,
        tls_skip_verify=tls_skip_verify,
        tls_root_certs=tls_root_certs,
        timeout=timeout,
    )


class Connection:
    def __init__(
        self,
        *,
        host: str,
        port: int,
        world: str,
        tls: bool,
        token: str | None,
        user: str | None,
        password: str | None,
        client: str,
        tls_skip_verify: bool,
        tls_root_certs: bytes | None,
        timeout: float | None,
    ):
        self.world = world
        self.closed = False
        self._timeout = timeout
        scheme = "grpc+tls" if tls else "grpc+tcp"
        kwargs: dict[str, Any] = {}
        if tls and tls_skip_verify:
            kwargs["disable_server_verification"] = True
        if tls and tls_root_certs:
            kwargs["tls_root_certs"] = tls_root_certs
        self._client = flight.FlightClient(f"{scheme}://{host}:{port}", **kwargs)
        headers: list[tuple[bytes, bytes]] = []
        if world:
            headers.append((b"world", world.encode()))
        headers.append((b"x-ttr-client", (client if client in CLIENTS else "other").encode()))
        try:
            if token:
                bearer = token if token.lower().startswith("bearer ") else f"Bearer {token}"
                headers.append((b"authorization", bearer.encode()))
            elif user:
                key, value = self._client.authenticate_basic_token(
                    user, password or "", flight.FlightCallOptions(headers=list(headers), timeout=timeout)
                )
                headers.append((key.lower(), value))
        except Exception as e:
            self._client.close()
            raise _translate(e) from e
        self._headers = headers
        with _OPEN_LOCK:
            _OPEN.add(self)

    # PEP 249 -------------------------------------------------------------------------------------------------------

    def cursor(self) -> Cursor:
        self._check_open()
        return Cursor(self)

    def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        with _OPEN_LOCK:
            _OPEN.discard(self)
        try:
            self._client.close()
        except Exception:  # closing is best effort; the channel goes either way
            pass

    def commit(self) -> None:
        """No transactions: nothing to commit (a no-op, so a pool that commits on return keeps working)."""

    def rollback(self) -> None:
        """No transactions: nothing to roll back (a no-op, so a pool that rolls back on return keeps working)."""

    def __enter__(self) -> Connection:
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    # Flight SQL ----------------------------------------------------------------------------------------------------

    def ping(self) -> bool:
        """`GetSqlInfo` for the server's name: the edge answered, and accepted this connection's credentials."""
        self.fetch_command(_flightsql.get_sql_info([_flightsql.FLIGHT_SQL_SERVER_NAME]))
        return True

    def fetch_command(self, command: bytes) -> pa.Table:
        """One Flight SQL command's whole answer (a catalog call, `GetSqlInfo`)."""
        return self._read(command)[0]

    def _run(self, sql: str) -> tuple[pa.Table, dict]:
        """A statement's whole answer and its closing metadata."""
        return self._read(_flightsql.statement_query(sql))

    def _read(self, command: bytes) -> tuple[pa.Table, dict]:
        self._check_open()
        options = flight.FlightCallOptions(headers=list(self._headers), timeout=self._timeout)
        try:
            info = self._client.get_flight_info(flight.FlightDescriptor.for_command(command), options)
            schema = info.schema
            batches: list[pa.RecordBatch] = []
            closing = b""
            for endpoint in info.endpoints:
                reader = self._client.do_get(endpoint.ticket, options)
                schema = reader.schema
                while True:
                    try:
                        chunk = reader.read_chunk()
                    except StopIteration:
                        break
                    if chunk.data is not None and chunk.data.num_rows:
                        batches.append(chunk.data)
                    if chunk.app_metadata is not None:
                        closing = chunk.app_metadata.to_pybytes()
        except Exception as e:
            raise _translate(e) from e
        return pa.Table.from_batches(batches, schema=schema), _closing(closing)

    def _check_open(self) -> None:
        if self.closed:
            raise InterfaceError("connection_closed", "The connection is closed.")


def _closing(raw: bytes) -> dict:
    if not raw:
        return {}
    try:
        value = json.loads(raw)
    except ValueError:
        return {}
    return value if isinstance(value, dict) else {}


# --- cursors -----------------------------------------------------------------------------------------------------


class Cursor:
    arraysize = 1

    def __init__(self, connection: Connection):
        self.connection = connection
        self.closed = False
        self._reset()

    def _reset(self) -> None:
        self.description: list[tuple] | None = None
        self.rowcount = -1
        self._table: pa.Table | None = None
        self._rows: list[tuple] = []
        self._next = 0
        self.closing: dict = {}

    # The closing metadata (C-5) -----------------------------------------------------------------------------------

    @property
    def correlation_id(self) -> str:
        """The id an operator looks the statement up by (its receipt)."""
        return str(self.closing.get("correlation_id", ""))

    @property
    def connection_id(self) -> str:
        """The connection the door actually ran the statement on."""
        return str(self.closing.get("connection_id", ""))

    @property
    def top_n_applied(self) -> bool:
        """The door's row cap cut this answer: a full page means the ceiling, not the end of the data."""
        return bool(self.closing.get("top_n_applied", False))

    @property
    def notices(self) -> list[dict]:
        """The door's notices on the answer; `top_n_applied` carries `requested` (0: no number stated) and `applied`."""
        notices = self.closing.get("notices", [])
        return [n for n in notices if isinstance(n, dict)] if isinstance(notices, list) else []

    # PEP 249 -------------------------------------------------------------------------------------------------------

    def execute(self, operation: str, parameters: Any = None) -> Cursor:
        self._check_open()
        self._reset()
        sql = params.bind(operation, parameters)
        table, closing = self.connection._run(sql)
        self._table = table
        columns = [column.to_pylist() for column in table.columns]
        self._rows = list(zip(*columns, strict=True)) if columns else []
        self.rowcount = table.num_rows
        self.description = [_describe(f) for f in table.schema]
        self.closing = closing
        return self

    def executemany(self, operation: str, seq_of_parameters: Any) -> None:
        raise NotSupportedError("unsupported", "The edge is read-only: there is nothing to execute many times.")

    def fetchone(self) -> tuple | None:
        self._check_result()
        if self._next >= len(self._rows):
            return None
        row = self._rows[self._next]
        self._next += 1
        return row

    def fetchmany(self, size: int | None = None) -> list[tuple]:
        self._check_result()
        size = self.arraysize if size is None else size
        rows = self._rows[self._next : self._next + size]
        self._next += len(rows)
        return rows

    def fetchall(self) -> list[tuple]:
        self._check_result()
        rows = self._rows[self._next :]
        self._next = len(self._rows)
        return rows

    def fetch_arrow_table(self) -> pa.Table:
        """The whole answer as the Arrow table it arrived as."""
        self._check_result()
        assert self._table is not None
        return self._table

    def close(self) -> None:
        self.closed = True
        self._reset()

    def setinputsizes(self, sizes: Any) -> None:
        pass

    def setoutputsize(self, size: Any, column: Any = None) -> None:
        pass

    def __iter__(self):
        while (row := self.fetchone()) is not None:
            yield row

    def __enter__(self) -> Cursor:
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def _check_open(self) -> None:
        if self.closed:
            raise InterfaceError("cursor_closed", "The cursor is closed.")
        self.connection._check_open()

    def _check_result(self) -> None:
        self._check_open()
        if self._table is None:
            raise ProgrammingError("no_result", "No statement has an answer on this cursor; execute one first.")


def _describe(f: pa.Field) -> tuple:
    precision = getattr(f.type, "precision", None)
    scale = getattr(f.type, "scale", None)
    return (f.name, f.type, None, None, precision, scale, f.nullable)
