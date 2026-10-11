# worker-mssql

> **forked-from:** the legacy predecessor platform (`workers/mssql/`), forked 2026-06-15 (Stage 3.3).
> Maintained independently since the fork; do not assume parity with the original.

**worker-mssql** — the MSSQL worker for kantheon, the first of the
workers the dispatcher dispatches to. A stateless per-engine query
executor: it receives a validated `PlanNode` from the dispatcher, asks **Translate** (the
translator) to unparse it to MSSQL, runs it over JDBC, and streams the result as
Arrow IPC batches back through the dispatcher.

worker-mssql implements the generic `org.tatrman.worker.v1.WorkerService` contract
(`Execute` / `GetCapabilities` / `GetStatus`) — no worker-specific proto. It
advertises `engine_name = "mssql"`, `supports_stateful_sessions = false`, and the
`connection_id`s it can serve.

## Connections (the named-connection idiom)

JDBC connections are declared per `connection_id` in the `connections {}` HOCON
block (charon/architecture §6 pattern). Credentials are **never** in the request
and **never** hardcoded in-repo — they arrive via env vars from K8s sealed
secrets. An empty `connections` block boots worker-mssql with zero connections (Ready
only in fixture mode); an overlay activates a connection by setting its env vars.
The local backing store is `deployment/local/mssql`.

## Arrow IPC

The execute pipeline streams a JDBC `ResultSet` → Arrow `VectorSchemaRoot` →
Arrow IPC bytes (`ResultSetToArrow` + `ArrowIpcSerializer`); the first batch
carries the schema + `schema_fingerprint` (SHA-256). The worker ⇄ formatter
contract — that `shared/libs/kotlin/data-formatter` round-trips exactly what
worker-mssql emits — is pinned by `ArrowIpcFormatterContractSpec`.

## Translator

worker-mssql calls **Translate** (`org.tatrman.translate.v1` / `TranslateService`,
`UnparseFromRelNode`, gRPC 7276) via `translate.{host,port}`. In fixture mode the
translator client is a no-op stub.

## Tests

Unit/component only — mocked JDBC (`mockk`) and mocked translator; the source
never used Testcontainers, so the suite already runs at the mocked-driver level
the testing policy wants. Real-MSSQL verification (against `deployment/local/mssql`)
lives in the separate integration-test suite.

## Throughput baseline (Fork Stage 4.1 T4)

Recorded read-out baseline — the CPU-bound `ArrowIpcSerializer.serializeBatch`
step that turns a fetched batch into the Arrow IPC bytes the dispatcher streams back.
The **MSSQL fetch is excluded** (no DB in unit scope — that is the integration
suite's territory); this isolates the serialize step shared by every query.

| Dataset | IPC size | p50 | p95 | rows/s (p50) |
|---|---|---|---|---|
| 100 000 rows × 3 cols (id/region/amount) | 2.24 MB | 1.2 ms | 2.9 ms | ~84 M |

Indicative, single-host (Apple Silicon, JDK 21). Re-run on demand (not in the CI
gate): `./gradlew :workers:worker-mssql:benchThroughput` (harness:
`src/bench/kotlin/.../bench/ThroughputBench.kt`). Takeaway: read-out is **not**
the bottleneck — end-to-end query latency is dominated by the MSSQL fetch, so
`run_query` cost hints live on `query.run:v1`, not on a worker manifest
(workers carry no capability manifest).

## Status (Stage 3.3 — the first worker)

Ports: HTTP 7295 (health/ready/status) · gRPC 7296 (`Execute`/`GetCapabilities`/
`GetStatus`). Readiness gates on a configured JDBC connection (or fixture mode).
Tag: `worker-mssql/v0.1.0`.
