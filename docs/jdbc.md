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

### Authentication

Every call is authenticated; there is no anonymous mode. Two ways, both ending in the same identity — the realm's
`preferred_username` and its realm roles, exactly as the MCP door reads them:

- **User name and password** (`user` / `password` properties; DBeaver's login fields). The edge exchanges them for
  an access token with Keycloak's direct-access grant on the `jdbc` client, returns the token in the handshake, and
  the driver sends it from then on. The edge keeps neither the password nor the token. A wrong password fails the
  connect with `password_refused: Keycloak refused the password grant (401 invalid_grant).`
- **A bearer token** (`token` property): an access token from the realm, sent as `Authorization: Bearer`. Access
  tokens are short-lived (minutes), so a `token=` URL goes stale; password login is the practical desktop path.

A token the realm did not sign, or one that has expired, fails with `invalid_token: The bearer token was not
accepted (<reason>).` No credential at all fails with `missing_credentials: …`. When the token expires during a
long session, reconnect. The driver logs in once per connection; it does not refresh the token.

### Choosing the world

A connection reads **one world**. Name it with either property:

| Property | How it travels | Notes |
|---|---|---|
| `world=<world>` | a gRPC header on every call (the driver sends unknown URL properties as headers) | **Recommended.** |
| `catalog=<world>` | the driver's `SetSessionOptions(catalog)` at connect; the edge answers with a `ttr_world` cookie the driver returns on every call | With the stock driver 18.3.0, closing such a connection reports `Channel shutdown invoked`: the driver closes its session twice ([apache/arrow-java#863](https://github.com/apache/arrow-java/issues/863)). The statements are unaffected. |

```
jdbc:arrow-flight-sql://<host>:<port>/?useEncryption=true&world=hartland_us
```

The world is the connection's only catalog (`getCatalogs()` returns it). A world the deployment does not serve
fails the connect with `unknown_world: World '<x>' is not served here; the worlds are …`. With no world, the
deployment's default connection answers and no catalog is listed.

A statement may qualify a table with the connection's own catalog (`hartland_us.er.entity.warehouse`, or
`"hartland_us"."er.entity"."warehouse"`); the qualifier is dropped before the statement runs. Naming **another**
catalog is refused before anything runs: `world_mismatch: the statement reads catalog 'hartland_cz', but this
connection's world is 'hartland_us'.` The edge also checks the answer: if the door reports that it ran a statement
on a connection other than the world's, the read fails with `world_mismatch` instead of returning those rows as the
world's.

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

## Receipts

Every statement through the edge leaves one receipt, written after its stream ends: `correlation_id`, time,
`user_id`, roles, world, `connection_id`, client (`jdbc` / `adbc` / `superset` / `other`), the statement as sent,
the SQL the door executed, the row rules it applied (`security_applied`), rows, `top_n_applied`, how it ended
(`ok` / `refused` / `failed` / `cancelled` / `expired`) and the error code. Refused statements leave receipts too.

Operators read them on the query service's HTTP port, with a bearer carrying `query-platform-admin`:

```
GET /receipts?correlation_id=<id>
GET /receipts?user_id=<user>&since=2026-10-21T00:00:00Z&limit=100
```

Receipts are best effort: if their database is down, statements still run and the missing receipts are logged.
