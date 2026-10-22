# JDBC, ADBC and other Flight SQL clients

The query service speaks [Arrow Flight SQL](https://arrow.apache.org/docs/format/FlightSql.html) on its
**Flight SQL edge** (port **7308**, opt-in). Any Flight SQL client reads the model through it — the stock
Arrow Flight SQL JDBC driver (DBeaver, IntelliJ, any JVM tool), ADBC (`adbc_driver_flightsql` for pandas /
Polars / DuckDB), and the `tatrman://` SQLAlchemy dialect for Superset and pandas
([`ttr-sqlalchemy`](../shared/libs/python/ttr-sqlalchemy/README.md)). Every statement goes through the same
translate → validate → dispatch path as any other caller's: your roles and row rules apply, nothing bypasses the
validator.

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
for you. Entity and table columns are typed from the model; a logical query's columns are read from its first row,
run as you, so a query your roles may not read lists no columns.

The edge reads the layer from those names: a statement over `er.entity` or `obj.query` reads entities, one over
`db.dbo` reads tables — so a name both hold (`warehouse` is an entity and a table) is never ambiguous. A bare name
is read in the same layer: `FROM db.dbo.inventory i JOIN warehouse w` reads the table `warehouse`. Only in a
statement with no qualified name is a bare name left to the door, which refuses it when both layers have it
(`detection_failed`). A statement over `obj.query` and `db.dbo` states no layer: a saved query can read either, and
the door uses the one its body reads.

Write each statement in one layer. A statement naming `er.entity` and `db.dbo` is refused with
`validator_mixed_layer_tree`, or with `detection_failed` when it names an entity that has no table and a table that
has no entity. There is one exception until the door reads qualified names itself. When every entity the statement
names is also a table, and a table it names has no entity, the door reads the entity names as their tables and runs
the statement. The answer then carries a `schema_corrected` warning.

The catalog lists each schema as one name with a dot in it, so a tool that writes SQL from the catalog quotes it:
`SELECT t.* FROM "er.entity".warehouse t` (DataGrip, IntelliJ or DBeaver opening a table). The edge reads a quoted
`"er.entity"`, `"db.dbo"` or `"obj.query"` at the head of a name as the schema's two parts (`er."entity"`), unless
the statement itself defines that quoted name, as an alias. Every other part keeps its quoting, and a refusal's
position still points into your text.

The edge is **read-only**: no DML, no transactions, no bound parameters (`?`), no uploads.

## Connecting

JDBC URL:

```
jdbc:arrow-flight-sql://<host>:<port>/?useEncryption=<true|false>&world=<world>
```

Behind the cluster gateway the edge is TLS-terminated at `flight.<cluster host>:443` (`useEncryption=true`, the
driver's default); a port-forward to the pod is plaintext on 7308 (`useEncryption=false`):

```
kubectl -n <namespace> port-forward deploy/query 7308:7308
jdbc:arrow-flight-sql://localhost:7308/?useEncryption=false&world=hartland_us
```

The driver is the stock [Arrow Flight SQL JDBC driver](https://arrow.apache.org/java/current/flight_sql_jdbc_driver.html)
(`org.apache.arrow:flight-sql-jdbc-driver`, tested with 18.3.0). On JDK 17+ the JVM that loads it needs
`--add-opens=java.base/java.nio=ALL-UNNAMED`. A URL property the driver does not know travels as a gRPC header;
that is how `world` reaches the edge.

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

A world selects the data; the caller's roles and row rules decide what of it they read. Worlds grant nothing:
any signed-in caller may pick any world the deployment serves, and the same row rules apply in each.

The world is the connection's only catalog (`getCatalogs()` returns it). A world the deployment does not serve
fails the connect with `unknown_world: World '<x>' is not served here; the worlds are …`. With no world, the
deployment's default connection answers and no catalog is listed.

A statement may qualify a table with the connection's own catalog (`hartland_us.er.entity.warehouse`,
`"hartland_us"."er.entity"."warehouse"`, or every part quoted, `"hartland_us"."er"."entity"."warehouse"`); the
qualifier is ignored (blanked out, so an error's line and column
still point into your text). Naming **another**
catalog is refused before anything runs: `world_mismatch: the statement reads catalog 'hartland_cz', but this
connection's world is 'hartland_us'.` The edge also checks the answer: if the door reports that it ran a statement
on a connection other than the world's, the read fails with `world_mismatch` instead of returning those rows as the
world's.

### DBeaver / IntelliJ

Both take the driver as a custom JDBC driver.

1. Download `flight-sql-jdbc-driver-18.3.0.jar` from Maven Central (`org.apache.arrow:flight-sql-jdbc-driver`).
2. **DBeaver:** *Database → Driver Manager → New*. Class name `org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver`,
   URL template `jdbc:arrow-flight-sql://{host}:{port}/`, default port `443`; add the jar under *Libraries*.
   **IntelliJ / DataGrip:** *Database → + → Driver*, the same class and jar.
3. The JVM flag: in DBeaver add `--add-opens=java.base/java.nio=ALL-UNNAMED` on its own line after `-vmargs` in
   `dbeaver.ini`; in IntelliJ add it to the data source's *Advanced → VM options*.
4. New connection with that driver: host `flight.<cluster host>`, port `443`; your realm user name and password
   in the login fields. Under *Driver properties* add `world` = `hartland_us` (or `hartland_cz`), and
   `useEncryption` = `false` for a port-forward.
5. *Test connection*. The navigator shows one catalog (the world) with the schemas `er.entity`, `obj.query` and
   `db.dbo`; open a table to read its rows.

One connection per world: to read the other world, create a second connection. A tool that browses with
catalog-qualified names (`hartland_us."er.entity".warehouse`) works, since the qualifier is the connection's own.

### pandas over ADBC

[`adbc_driver_flightsql`](https://arrow.apache.org/adbc/current/driver/flight_sql.html) (Python, `pip install
adbc-driver-flightsql pyarrow pandas`). The world is a call header; credentials are a bearer token or a user name
and password:

```python
import adbc_driver_flightsql.dbapi as flightsql
from adbc_driver_flightsql import DatabaseOptions

with flightsql.connect(
    "grpc+tls://flight.<cluster host>:443",          # grpc://localhost:7308 for a port-forward
    db_kwargs={
        "username": "marketa",
        "password": "…",                              # or: DatabaseOptions.AUTHORIZATION_HEADER.value: f"Bearer {token}"
        f"{DatabaseOptions.RPC_CALL_HEADER_PREFIX.value}world": "hartland_cz",
    },
) as conn, conn.cursor() as cur:
    cur.execute(
        "SELECT warehouse.warehouse_name AS dc, SUM(channel_sales.ext_sales_price) AS revenue "
        "FROM er.entity.channel_sales JOIN er.entity.warehouse GROUP BY warehouse.warehouse_name"
    )
    df = cur.fetch_df()
```

Receipts record the client as `adbc`, from the driver's user agent.

## Results

- Rows stream batch by batch as the workers produce them. An answer with no rows still has its columns. (The
  stock JDBC driver, since 15.0.0, lists an empty result's columns twice:
  [apache/arrow#44467](https://github.com/apache/arrow/issues/44467).)
- The **last batch carries app metadata** (JSON): `correlation_id` (the id to quote when asking an operator
  about a statement), `connection_id` (the connection that actually ran it), `rows_total`, `top_n_applied` and
  the door's `notices`. The stock JDBC driver does not surface app metadata; ADBC and `pyarrow.flight` can read it.
- **Row caps.** The validator caps every answer, and through the edge a statement gets up to its caller's
  **ceiling**: the deployment's `VALIDATE_MAX_TOP_N`, which a role can raise (`VALIDATE_MAX_TOP_N_BY_ROLE`, e.g.
  `kantheon-bi-reader:10000`). The edge asks for as many rows as the ceiling allows, so a statement without a `LIMIT`
  gets the ceiling, not the deployment's default (`VALIDATE_DEFAULT_TOP_N`, which applies to callers that state no
  row window). A statement's own `LIMIT` lowers that. A caller with no such role gets `VALIDATE_MAX_TOP_N`, which
  is the default unless an operator raised it. An answer the ceiling cut says so on its last batch:
  `top_n_applied: true`, and its entry in `notices` carries `requested: 0` (the edge states no number) and `applied`
  (the ceiling). A full page with that notice means "the estate's ceiling", not "the end of the data". The stock
  JDBC driver does not surface it; ADBC and `pyarrow.flight` read it from the closing metadata, and the receipt
  records it.
- **Errors** arrive as Flight errors whose description is `"<code>: <one sentence>"` (e.g.
  `translator_rejected: Object 'warehouse' not found within 'er' — did you mean: warehouse, warehouse_id?` for
  `FROM er.warehouse`,
  `column_denied: …`); the code is also in the `x-ttr-code` error metadata. A refusal that points into the
  statement carries its position in `x-ttr-line` and `x-ttr-column` (1-based, in the text as sent). A refusal
  before any row fails the `execute`; a failure mid-stream fails the read. Refusals raised while the call is being
  authenticated (`missing_credentials`, `invalid_token`, `unknown_world`) lead with the code but carry no
  metadata: Flight drops it at that stage.

## Receipts

Every statement through the edge leaves one receipt, written after its stream ends: `correlation_id`, time,
`user_id`, roles, world, `connection_id`, client (`jdbc` / `adbc` / `superset` / `other`), the statement as sent,
the SQL the door executed, the row rules it applied (`security_applied`), rows, `top_n_applied`, the notices
(the same list as the closing metadata's), how it ended (`ok` / `refused` / `failed` / `cancelled` / `expired`) and
the error code. Refused statements leave receipts too.

Operators read them on the query service's HTTP port, with a bearer carrying `query-platform-admin`:

```
GET /receipts?correlation_id=<id>
GET /receipts?user_id=<user>&since=2026-10-21T00:00:00Z&limit=100
```

Receipts are best effort: if their database is down, statements still run and the missing receipts are logged.
