# worker-postgres

**worker-postgres** — the PostgreSQL worker for kantheon, the third of the
workers the dispatcher dispatches to, alongside **worker-mssql** (MSSQL) and
**worker-polars** (Polars). A stateless per-engine query executor: it receives a validated
`PlanNode` from the dispatcher, asks **Translate** (the translator) to unparse it to PostgreSQL,
runs it over JDBC **read-only under per-tenant row-level security**, and streams the
result as Arrow IPC batches back through the dispatcher.

worker-postgres is **born in-repo** by mirroring worker-mssql — it forks the never-built predecessor-platform
`workers/postgres` brief, not predecessor-platform code, so it carries **no `forked-from`
provenance header** (AGENTS.md §12.1; same as Charon/Metis). worker-mssql is the structural
template; the deltas are the Postgres driver/URL, the `PostgresArrowTypeMapper`, and the
one genuinely new thing — the RLS session contract. See
[`docs/architecture/postgres/architecture.md`](../../docs/architecture/postgres/architecture.md).

worker-postgres implements the generic `org.tatrman.worker.v1.WorkerService` contract
(`Execute` / `GetCapabilities` / `GetStatus`) — no worker-specific proto. It advertises
`engine_name = "postgres"`, `supported_dialects = ["POSTGRESQL"]`,
`supports_stateful_sessions = false`, and the `connection_id`s it can serve.

## Connections (the named-connection idiom)

JDBC connections are declared per `connection_id` in the `connections {}` HOCON block
(charon/architecture §6 pattern). Credentials are **never** in the request and **never**
hardcoded in-repo — they arrive via env vars from K8s sealed secrets. An empty
`connections` block boots worker-postgres with zero connections (Ready only in fixture mode); an
overlay activates a connection by setting its env vars. The v1 connection is `pg-midas`
— the Midas operational Postgres, read through the non-owner `midas_app_readonly` role so
RLS policies apply.

## Row-level security (the one new thing)

A connection marked `requires-tenant-id = true` carries the RLS session contract: every
`Execute` runs inside one transaction that first issues
`SET LOCAL app.tenant_id = '<uuid>'` (the tenant arrives on `PipelineContext.tenant_id`,
parsed to a canonical UUID first → injection-safe, matching Midas-core's `TenantContext`)
so Midas's RLS policies (`USING (tenant_id = app_current_tenant())`) scope every row.
Missing/invalid tenant → `tenant_id_required` (**fails closed**, nothing runs); a `SET LOCAL`
failure → `rls_set_failed` + rollback. `SET LOCAL` is transaction-scoped, so no tenant bleeds
across pooled borrows. The role is a non-owner, so RLS is enforced (owners bypass RLS unless
`FORCE ROW LEVEL SECURITY`). See
[`docs/architecture/postgres/contracts.md`](../../docs/architecture/postgres/contracts.md) §2.1.

## Arrow IPC

The execute pipeline streams a JDBC `ResultSet` → Arrow `VectorSchemaRoot` → Arrow IPC
bytes (`ResultSetToArrow` + `ArrowIpcSerializer`, engine-agnostic copies from worker-mssql); the
first batch carries the schema + `schema_fingerprint` (SHA-256, the canonical cross-engine
string). `PostgresArrowTypeMapper` maps Postgres native types to Arrow per contracts §3
(`NUMERIC(20,4)→Decimal128(20,4)`, `timestamptz`→UTC, `uuid`/`json(b)`→VARCHAR+metadata,
ranges/arrays→opaque VARBINARY + `unsupported_type_as_binary` warning).

## Translator

worker-postgres calls **Translate** (`org.tatrman.translate.v1` / `TranslateService`,
`UnparseFromRelNode`, gRPC 7276) via `translate.{host,port}` with target dialect
`POSTGRESQL`. In fixture mode the translator client is a no-op stub.

## Tests

Unit specs (`src/test`) use mocked JDBC + a mocked translator (incl. `RlsEnvelopeSpec`,
which pins the `SET LOCAL` statement order, fail-closed, and rollback). The component tier
(`src/componentTest`, `@Tags("component")`) uses a real Postgres via Testcontainers — a
round-trip spec and an RLS-leakage spec proving tenant A's query returns zero of tenant B's
rows through the worker, plus the fail-closed path. Component specs run in CI
(`just test-component`).

## Status (Stage 1.3 — RLS contract, code-complete)

Ports: HTTP **7302** (health/ready/status) · gRPC **7303** (`Execute`/`GetCapabilities`/
`GetStatus`). `Execute` runs the full pipeline (unparse to PostgreSQL → `SET LOCAL`
tenant bind → JDBC → Arrow IPC) under the RLS session contract. The dispatcher registers worker-postgres as a
third worker slot via `DISPATCH_WORKER_POSTGRES_ENDPOINT` (`role-hint="postgres"`). The Helm chart
deploys image `postgres:dev`. **Remaining post-merge ship:** deploy to bp-dsk + the live
`pg-midas` path (needs the Midas-side `midas_app_readonly` role migration, contracts §6) +
tag `worker-postgres/v0.1.0`.
