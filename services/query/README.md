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
  validated `PlanNode` + referenced parameters + predicted schema fingerprint. Requirements come from
  the returned plan, once per named reference in first-reference order; unused declarations are omitted.
  Types come from this caller, then this translation, else are empty. Values come only from this caller,
  including on cache hits. An unbound plan reference is reported, not refused. Successful ER compilation
  also returns `er_reads` when its
  validated ER tree can be fully inspected; `plan` remains the physical DB plan. This evidence includes
  expression subqueries, expanded logical-query bodies and their stamped entity boundaries. Presence with
  an empty list certifies a constant query; absence provides no read-scope evidence.

- **RunSavedQuery** — read a stored canonical plan from Metadata and run it through the same execution
  pipeline. Named parameters referenced by that plan must have values in this caller's context, including
  when no declaration/binding is supplied at all; otherwise `parameter_unbound` is returned before dispatch.
  Physical (DB) saved queries may declare parameters. Logical saved queries cannot declare them (LQ-5).

- **Translate** — passthrough to translate's Translate (any-to-any conversion).
- **GetStatus** — readiness + cache stats.

`RunRequest.allowed_er_reads` optionally constrains both Run and Compile to declared ER qnames. Query checks
this call's validated ER tree on every request, including cache hits, before dispatch. Undeclared reads or a
DB/workspace/write/uninspectable plan return `out-of-scope-read`. An empty, present allow-list permits no
model reads. Context audit entries and physical DB names cannot authorize ER reads. AU uses Compile preflight
and sends the same allow-list on Run, so a changed policy or logical body cannot bypass the execution check.
Caller-dependent validation/evidence is never cached.

### Parameter refusals

The `parameter_unbound` check introduced with #264 applies to **named references in the executable plan**,
including nested expressions, repeated references and cached plans. Each execution checks its own context;
a previous caller's bound values do not satisfy a later call. A missing binding, absent `value`, or empty
`Value` is unbound. Explicit NULL (`is_null = true`) and scalar zero/false are values. An unused declaration
is not a required reference. Binding names match exactly: bind `id`, not `{id}`. Compile accepts typed
valueless declarations and derives `required_parameters` from its returned plan, using only the current
caller's values. Cached plans contain no parameter bindings. Anonymous `?N` references are skipped.
This is a Query execution check; Validate does not emit this code.

For SQL text, provide parameter **names and types** to let translate parse `{name}`. This is separate from
providing their values. With other typed bindings present, an undeclared placeholder is refused as
`parameter_unbound` on Run; Compile preserves translate's `parameter_unknown`. With no bindings, translate
still parses SQL verbatim: raw `{name}` text can produce `translator_rejected` until
[ttr-core#334](https://github.com/Collite/ttr-core/issues/334) and a translator update. Other translation
failures, including `parameter_in_string_literal`, remain `translator_rejected` on Run, with their original
position. A REL_NODE or saved plan already carries its named references, so an entirely missing binding
is `parameter_unbound`.

An omitted optional parameter must be sent as explicit NULL. Client migrations are tracked in
[ttr-platform#92](https://github.com/Collite/ttr-platform/issues/92) and
[kantheon#285](https://github.com/Collite/kantheon/issues/285); matching core documentation corrections are
[ttr-core#335](https://github.com/Collite/ttr-core/issues/335).

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
- **Catalog** from Metadata (two `ListObjects` + one `GetModel`, cached by model version for
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
  an unknown world is refused `unknown_world` regardless of the flag. When `QUERY_REQUIRE_WORLD` is on
  (default **off**) and the table names more than one world, both execution
  RPCs refuse an empty world with `world_unspecified`, before compilation/cache lookup or saved-query
  metadata lookup, regardless of roles. With the flag off or zero/one world, omission preserves dispatch's
  derived/default routing; an explicit known world still selects its mapped connection. Compile ignores
  world selection. Configure every served world here: query does not discover dispatch's connections.
  Enable only after every caller names a world. Do not enable on a door routing tables outside its worlds
  through `world.table-connections` patterns: an explicit world overrides those patterns and omission is
  refused ([#344](https://github.com/Collite/ttr-server/issues/344)).
  The edge takes the connection's world from the `world`
  header (or the JDBC `catalog` session option, kept as a `ttr_world` cookie), sets it on every `Run`,
  refuses a statement naming another catalog (`world_mismatch`) and checks the receipt's connection.
- **Receipts** (C-6): one `bi_receipts` row per statement (schema `query`, Flyway-migrated on first
  use; `QUERY_RECEIPTS_DB_HOST/PORT/NAME/USER/PASSWORD`, no host = off), written after the stream on a
  bounded queue, best effort; `notices` keeps the closing metadata's warnings.
  `GET /receipts?correlation_id=…|user_id=…&since=…` for `query-platform-admin`.
- Telemetry: span `bi.statement` (`user_id`, `world`, `client`, `rows`, `status`); metrics
  `bi_statements_total{client,status}`, `bi_statement_seconds`. `/status` has a `flight` block
  (incl. `receipts`), the door's `worlds` and configured `require_world` flag.
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
