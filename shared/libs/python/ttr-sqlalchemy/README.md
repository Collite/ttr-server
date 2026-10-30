<!-- SPDX-License-Identifier: Apache-2.0 -->
# ttr-sqlalchemy

A Python DB-API driver and the **`tatrman://`** SQLAlchemy dialect for a Tatrman Server's
[Arrow Flight SQL edge](../../../../docs/jdbc.md). Apache-2.0, published to PyPI as **`ttr-sqlalchemy`**, imported as
**`ttr_sqlalchemy`**.

It is what Superset (and pandas, and anything else that takes a SQLAlchemy URL) uses to read a model through the
query door: every statement goes through the same translate → validate → dispatch path as any other caller's, with
your roles and row rules applied. It speaks Flight SQL through `pyarrow.flight` — no gRPC stubs, no native driver
beyond pyarrow.

## Install

```bash
pip install ttr-sqlalchemy
```

Python ≥ 3.10, SQLAlchemy 1.4 or 2.x (Superset 6.1.0 ships 1.4.54), pyarrow ≥ 16.

## Connect

```python
import sqlalchemy as sa

engine = sa.create_engine(
    "tatrman://flight.<cluster host>/hartland_cz",   # one URL = one world
    connect_args={"token": access_token},             # or user= / password=
)
```

| URL part | Meaning |
|---|---|
| host, port | the edge: `flight.<cluster host>` (port 443, TLS) behind the gateway; `localhost:7308` with `?tls=false` through a port-forward (`kubectl -n <namespace> port-forward deploy/query 7308:7308`) |
| database | the **world** (`hartland_cz`, `hartland_us`): sent on every call; the connection reads that world only. None preserves derived/default routing unless `QUERY_REQUIRE_WORLD` is on (default off) with multiple worlds; then execution fails `world_unspecified` |
| user, password | optional; the edge exchanges them for a bearer at connect (Keycloak's password grant) and the password is not sent again |
| `?tls=` | `true` (default) or `false` |
| `?tls_skip_verify=` | `true` to skip certificate verification (a test cluster with its own CA) |
| `?timeout=` | seconds, for every call; none by default |
| `?client=` | `superset`, `jdbc`, `adbc` or `other` (default): what the edge's receipts record as the client |

`connect_args` take the same names (`token`, `user`, `password`, `tls`, `timeout`, `client`, plus `tls_root_certs` as
PEM bytes). A `token` is an access token from the realm, sent as `Authorization: Bearer`; Superset's user
impersonation puts the viewer's there. Access tokens are short-lived and a pooled connection outlives them: with
`pool_pre_ping=True` a connection whose bearer is no longer accepted is replaced on checkout — with a user and
password the replacement logs in again; a fixed `token` fails the same way until you pass a fresh one.

**Worlds are tenants — switch, never mix.** To read two worlds, make two engines.

## What you can read

```python
inspector = sa.inspect(engine)
inspector.get_schema_names()                 # ['er.entity', 'obj.query', 'db.dbo']
inspector.get_table_names("er.entity")       # the model's entities
inspector.get_view_names("obj.query")        # saved logical queries without parameters
inspector.get_table_names("db.dbo")          # bound physical tables and views
inspector.get_columns("warehouse", schema="er.entity")
```

Columns are typed from the Arrow schema the edge publishes (`int64` → `BigInteger`, `decimal128(p, s)` →
`Numeric(p, s)`, `date32` → `Date`, `timestamp` → `DateTime`, `utf8` → `String`, `bool` → `Boolean`). Primary keys come
from the model; foreign keys only from E-R relations that state join pairs, one key per relation (named
`<table>_<columns>_fkey`), so two relations to one table are two keys. The edge is **read-only**: no DML, no
transactions (`commit` and `rollback` are no-ops so pools keep working).

Every identifier is quoted, and a dotted schema is two quoted names: `SELECT "er"."entity"."warehouse"."city" FROM
"er"."entity"."warehouse"`. Hand-written SQL may stay unquoted (`SELECT city FROM er.entity.warehouse`) except for
reserved words (`"count"`, `"value"`, `"year"`).

## Parameters

`:name`, from a mapping:

```python
conn.execute(sa.text("SELECT * FROM er.entity.warehouse WHERE city = :city"), {"city": "Brno"})
```

The edge takes no bound parameters, so the driver writes each value into the statement as a literal: strings quoted
(`'O''Brien'`), `DATE '…'`, `TIMESTAMP '…'`, `TIME '…'`, `None` as `NULL`, booleans as `TRUE` / `FALSE`. A `:name`
inside a string literal, a quoted identifier or a comment is left alone. A name with no value is refused before
anything is sent (`parameter_unbound`); a value with no safe literal (bytes, a datetime with a time zone, NaN) is
refused (`parameter_type`). Positional parameters are refused (`parameter_style`).

## Results

A result is read **whole** before its first row is returned. The edge holds an answer's last batch until the door's
receipt names the connection it ran on, and fails the stream with `world_mismatch` if that is another world's; reading
to the end first means such an answer yields no rows, only the error. The door caps every answer at the caller's
**ceiling** (the deployment's `VALIDATE_MAX_TOP_N`, which a role can raise with `VALIDATE_MAX_TOP_N_BY_ROLE`): the edge
asks for as many rows as the ceiling allows, so a statement without a `LIMIT` gets the ceiling, and its own `LIMIT`
lowers it. The ceiling is also what bounds the memory a whole answer takes.

The DB-API cursor carries what the edge says about the answer (its last batch's app metadata):

```python
with engine.connect() as conn:
    result = conn.exec_driver_sql("SELECT * FROM er.entity.channel_sales LIMIT 10000")
    rows = result.fetchall()
    cursor = result.cursor
    cursor.correlation_id   # quote it to an operator: it finds the statement's receipt
    cursor.connection_id    # the connection the door ran it on
    cursor.top_n_applied    # True: the cap cut the answer — a full page is the ceiling, not the end of the data
    cursor.notices          # [{'code': 'top_n_applied', 'requested': 0, 'applied': 200, …}]
```

`cursor.fetch_arrow_table()` returns the answer as the Arrow table it arrived as.

## Errors

Refusals keep the edge's code and its one sentence: `err.code`, `err.message`, and `str(err)` =
`"<code>: <sentence>"` (`translator_rejected: Object 'warehouse' not found within 'er' — did you mean: warehouse,
warehouse_id?`). Through SQLAlchemy they arrive wrapped (`sqlalchemy.exc.DBAPIError`, the driver's error as `.orig`).

| PEP 249 class | Codes |
|---|---|
| `ProgrammingError` | the statement's fault: `translator_rejected`, `parameter_unbound`, … (the default) |
| `DatabaseError` | refused on access: `access_denied`, `column_denied`, `permission_denied`, `world_mismatch` |
| `OperationalError` | no answer to give: `missing_credentials`, `invalid_token`, `password_refused`, `unknown_world`, `world_unspecified`, any `*_unavailable` |
| `InternalError` | the door's own failure: `query_failed`, `translator_failed`, … |
| `NotSupportedError` | `executemany`, a value with no literal |

## Superset

The package also carries Superset's engine spec for `tatrman://` (`ttr_sqlalchemy.superset`), registered through the
`superset.db_engine_specs` entry point, so installing the package into Superset's environment is all it takes for
Superset to find it. Tested on Superset 6.1.0. A Tatrman database always reads as its viewer: it needs Superset's
per-database OAuth2 (`DATABASE_OAUTH2_CLIENTS["tatrman"]`) and "Impersonate logged in user" on, and refuses to
connect without them. [docs/superset.md](../../../../docs/superset.md) has the configuration, and
[compose/superset/](../../../../compose/superset/README.md) runs it locally.

## The DB-API without SQLAlchemy

```python
from ttr_sqlalchemy import dbapi

with dbapi.connect(host="localhost", port=7308, world="hartland_us", tls=False, user="marketa", password="…") as conn:
    cur = conn.cursor()
    cur.execute("SELECT * FROM er.entity.warehouse")
    print(cur.fetchall())
```

## Development

```bash
uv run ruff check .
uv run pytest -q                                   # against the recorded edge (tests/fixtures/edge.py)
TTR_FLIGHT_URL=localhost:7308 TTR_FLIGHT_USER=petr TTR_FLIGHT_PASSWORD=… uv run pytest -m live -v

# the engine spec, inside Superset 6.1.0 (PyPI's apache-superset 6.1.0 leaves cachetools out of its dependencies)
uv run --isolated --python 3.10 --with apache-superset==6.1.0 --with cachetools pytest -q
```

The recorded edge is an in-process Flight SQL server that answers the way the real one does. It decodes the driver's
commands with Apache Arrow's `FlightSql.proto` (vendored under `tests/fixtures/vendor/`, compiled at test time), so the
driver's hand-written encoding is checked against the real schema. CI also runs the suite on Superset's floor
(Python 3.10, SQLAlchemy 1.4.54, pyarrow 16), and once more inside Superset 6.1.0, where `tests/test_superset_spec.py`
drives the engine spec through Superset's own database model, OAuth2 token store and fetch path (elsewhere it skips). Releases: a `python-sqlalchemy/v<x.y.z>-RELEASE` tag
(`just publish alchemy release set <x.y.z>`, short for `just publish wheel ttr-sqlalchemy release set <x.y.z>`).
