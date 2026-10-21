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

- **Run** — submit a query in any source language; stream Arrow IPC results.
- **Compile** — front of the pipeline only (translate + validate); returns the
  validated `PlanNode` + required parameters + predicted schema fingerprint.
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
  `notices`. An error batch becomes a Flight error `"<code>: <one sentence>"`, the code also in the
  `x-ttr-code` error metadata.
- **Read-only**: no DML, no transactions, no `DoPut`, no bound parameters.
- Telemetry: span `bi.statement` (`user_id`, `world`, `client`, `rows`, `status`); metrics
  `bi_statements_total{client,status}`, `bi_statement_seconds`. `/status` has a `flight` block.
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
