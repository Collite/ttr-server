# query

> **forked-from:** the legacy predecessor platform (`services/query-runner/`), forked 2026-06-15 (Stage 3.5).
> Maintained independently since the fork; do not assume parity with the original.

**query-runner** (`services/query`) — the query orchestrator for kantheon, the
agent's single entry point for query execution. A thin orchestrator that chains
**translate** (translate) → **validate** (validate, RLS) → **dispatch**
(dispatch) → a worker (**worker-mssql** / **worker-polars**) and pipes
Arrow IPC results straight back. It owns the LRU compiled-RelNode plan cache so
repeated identical queries skip parse/translate.

## Surface

Proto `org.tatrman.query.v1` / `QueryService` (`com.tatrman.runner.v1` is gone):

- **Run** — submit a query in any source language; stream Arrow IPC results. SQL parameters are
  `{name}`, bound by name in `context.parameters`; one the plan reads with no value is refused
  `parameter_unbound` before dispatch (BI C-11 — it used to bind NULL and answer nothing). A
  translator refusal keeps translate's `line` / `column`. When the row cap cut the answer, the last
  batch carries `top_n_applied` as a WARNING and as a typed `Notice` (`requested`, `applied`; C-10).
- **Compile** — front of the pipeline only (translate + validate); returns the
  validated `PlanNode` + required parameters + predicted schema fingerprint (an unbound
  parameter is reported there, not refused).
- **Translate** — passthrough to translate's Translate (any-to-any conversion).
- **GetStatus** — readiness + cache stats.

Ports: HTTP 7305 (health/ready/status) · gRPC 7306 · Flight SQL 7308 (opt-in, see below). Downstreams via config
(`translate`/`validate`/`dispatch` host+port, env-overridable).

## Flight SQL edge (BI)

An Arrow Flight SQL server inside the same pod, so JDBC (DBeaver, IntelliJ), ADBC (pandas) and the
`tatrman://` Superset dialect read the model through the door — `org.tatrman.query.flight`. It is a
**client of the door**, not a second query path: every statement is this service's own `Run`, over a
loopback channel to the gRPC port, carrying the caller's identity.

- **Port 7308**, opt-in (`QUERY_FLIGHT_ENABLED=true`), bound to `QUERY_FLIGHT_BIND` (default
  `127.0.0.1`; a deployment behind the gateway sets `0.0.0.0`). The door's own gRPC 7306 has no
  authentication; the edge is the BI entry that verifies callers.
- **Catalog** from Veles (two `ListObjects` + one `GetModel`, cached by model version for
  `QUERY_FLIGHT_CATALOG_TTL_SECONDS`): catalog = the connection's world; schemas `er.entity`
  (entities), `obj.query` (parameterless saved logical queries, columns by a one-row probe as the
  caller) and `db.dbo` (bound tables and views). Primary keys from the model; foreign keys only from
  E-R relations that state join pairs.
- **Statements execute eagerly**: `GetFlightInfo` / `CreatePreparedStatement` start the run and read
  it to its first batch so the schema is real (the JDBC driver prepares even a plain `executeQuery`).
  The started run waits under its ticket for one `DoGet`, and is cancelled after
  `QUERY_FLIGHT_PENDING_TTL_SECONDS` (60) unread.
- **Results**: each worker batch re-framed (IPC → vectors, no value conversion). The last batch
  carries JSON app metadata — `correlation_id`, `connection_id`, `rows_total`, `top_n_applied`,
  `notices` (a typed notice's `requested` / `applied` with it, C-10). An error batch becomes a Flight
  error `"<code>: <one sentence>"`, the code also in the `x-ttr-code` error metadata and, when the
  refusal points into the statement, its position in `x-ttr-line` / `x-ttr-column` (C-11).
- **Read-only**: no DML, no transactions, no `DoPut`, no bound parameters.
- **Identity** (C-2): every call carries a bearer verified as query-mcp verifies one (mcp-identity's
  `JwksBearerVerifier` against `QUERY_FLIGHT_AUTH_ISSUER`, JWKS `QUERY_FLIGHT_AUTH_JWKS_URI`, optional
  `QUERY_FLIGHT_AUTH_AUDIENCE`), or a user name and password the edge turns into one with Keycloak's
  direct-access grant (`QUERY_FLIGHT_KC_TOKEN_URL`, client `QUERY_FLIGHT_KC_CLIENT`, default `jdbc`;
  empty = bearer only) and returns in the handshake. `user_id` / `auth_roles` go to `Run` from the
  verified token only. An enabled edge without an issuer does not start.
- **Worlds** (C-3, C-9): `QUERY_WORLDS` (`world=connection_id,…`) is the door's world table —
  `RunRequest.world` / `RunSavedQueryRequest.world` map through it to `DispatchRequest.connection_id`;
  an unknown world is refused `unknown_world`. The edge takes the connection's world from the `world`
  header (or the JDBC `catalog` session option, kept as a `ttr_world` cookie), sets it on every `Run`,
  refuses a statement naming another catalog (`world_mismatch`) and checks the receipt's connection.
- **Receipts** (C-6): one `bi_receipts` row per statement (schema `query`, Flyway-migrated on first
  use; `QUERY_RECEIPTS_DB_HOST/PORT/NAME/USER/PASSWORD`, no host = off), written after the stream on a
  bounded queue, best effort; `notices` keeps the closing metadata's warnings.
  `GET /receipts?correlation_id=…|user_id=…&since=…` for `query-platform-admin`.
- Telemetry: span `bi.statement` (`user_id`, `world`, `client`, `rows`, `status`); metrics
  `bi_statements_total{client,status}`, `bi_statement_seconds`. `/status` has a `flight` block
  (incl. `receipts`) and the door's `worlds`.
- JVM: Arrow needs `--add-opens=java.base/java.nio=ALL-UNNAMED` (set in `build.gradle.kts` for
  run, test and the Jib image).

Client recipes: [`docs/jdbc.md`](../../docs/jdbc.md).

## Plan cache

Caffeine LRU keyed by `(modelVersion, SHA-256(source), sourceLanguage,
paramSignature)` — `paramSignature` hashes parameter **names + types**, not
values, so one compiled plan serves many executions. A `model_version` change
invalidates the whole cache. `CompiledPlanCacheSpec` pins the cache, and
`QueryServiceImplSpec` proves a cache **replay serves the right compiled plan
for the right args** (captured at the dispatcher edge), not merely "dispatched
once" — the inherited call-counting trap.

## Identity

Roles travel as `PipelineContext.auth_roles`, set at the query-mcp edge from
the user's OBO bearer (`org.tatrman.query.mcp.identity`), and forwarded
unchanged to validate (bearer-roles, contracts §3). query-runner never re-parses the token.

## Run

```bash
just build-kt query         # compile
just test-kt query          # Kotest unit + component suite
just deploy-kt query        # Jib image + k8s/overlays/local (local K3s)
```

Ports: HTTP **7305** · gRPC **7306** (`QueryService`) · query-mcp wrapper
**7307** (the OBO/identity edge agents call). Downstream client endpoints
(translate / validate / dispatch) come from HOCON; `query.use-fixture` boots a
self-contained chain for local/test.

## Status (Stage 3.5)

Forked suite green (orchestration, retries, cache, fingerprint, e2e two-pass
chain) + the strengthened cache-replay test. Tag: `query/v0.1.0`.
