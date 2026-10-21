# JDBC, ADBC and other Flight SQL clients

The query service speaks [Arrow Flight SQL](https://arrow.apache.org/docs/format/FlightSql.html) on its
**Flight SQL edge** (port **7308**, opt-in). Any Flight SQL client reads the model through it — the stock
Arrow Flight SQL JDBC driver (DBeaver, IntelliJ, any JVM tool), ADBC (`adbc_driver_flightsql` for pandas /
Polars / DuckDB), and the `tatrman://` SQLAlchemy dialect for Superset. Every statement goes through the same
translate → validate → dispatch path as any other caller's: your roles and row rules apply, nothing bypasses the
validator.

> This page is being completed alongside the edge. Sections marked *(pending)* describe behaviour that is not
> available yet.

## What you see

| Flight SQL / JDBC | Tatrman |
|---|---|
| catalog | the connection's **world** (e.g. `hartland_us`) — one connection reads one world |
| schema `er.entity` | the model's entities |
| schema `obj.query` | saved logical queries without parameters (read like views) |
| schema `db.dbo` | bound physical tables and views |

Write statements with the full three-part names: `SELECT * FROM er.entity.warehouse`, `SELECT * FROM
obj.query.revenue_per_customer`, `SELECT * FROM db.dbo.inventory`. Quote an identifier that is a reserved word
(`"count"`, `"value"`, `"year"`); the edge publishes the reserved-word list as `SQL_KEYWORDS` so clients quote
for you. Entity and table columns are typed from the model; a logical query's columns are read from its first row.

The edge is **read-only**: no DML, no transactions, no bound parameters (`?`), no uploads.

## Connecting

JDBC URL:

```
jdbc:arrow-flight-sql://<host>:<port>/?useEncryption=<true|false>
```

Behind the cluster gateway the edge is TLS-terminated at `flight.<cluster host>:443` (`useEncryption=true`); a
port-forward to the pod is plaintext on 7308 (`useEncryption=false`).

### Authentication *(pending)*

### Choosing the world *(pending)*

### DBeaver / IntelliJ *(pending)*

### pandas over ADBC *(pending)*

## Results

- Rows stream batch by batch as the workers produce them.
- The **last batch carries app metadata** (JSON): `correlation_id` (the id to quote when asking an operator
  about a statement), `connection_id` (the connection that actually ran it), `rows_total`, `top_n_applied` and
  the door's `notices`. The stock JDBC driver does not surface app metadata; ADBC and `pyarrow.flight` can read it.
- **Row caps.** The validator caps answers (`top_n_applied`). A capped answer says so in the closing metadata —
  a full page with `top_n_applied: true` means "the estate's ceiling", not "the end of the data".
- **Errors** arrive as Flight errors whose description is `"<code>: <one sentence>"` (e.g.
  `translator_rejected: …`, `column_denied: …`); the code is also in the `x-ttr-code` error metadata. A refusal
  before any row fails the `execute`; a failure mid-stream fails the read.

## Receipts *(pending)*
