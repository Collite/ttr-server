# dispatch

> **forked-from:** the legacy predecessor platform (`services/dispatcher/`), forked 2026-06-15 (Stage 3.3).
> Maintained independently since the fork; do not assume parity with the original.

**dispatch** — the worker dispatcher for kantheon. It routes a validated, physical
`PlanNode` (post-validate) to a capable worker and pipes the worker's
Arrow IPC result stream straight back upstream. The workers it dispatches to are
engine-named — **worker-mssql** (the MSSQL worker), and **worker-polars**
(the Polars worker, lands Stage 3.4).

- **ROUTING** — walks the plan's `TableScan` qnames against the World config to
  derive a `connection_id`; a caller may also supply one explicitly. Plans with
  no scans and tables with no matching exact/prefix pattern use the configured `default-connection`.
  Caller roles do not affect routing. Query's `QUERY_WORLDS` table handles the caller's explicit world;
  when `QUERY_REQUIRE_WORLD` is on (default off) and the table names more than one world, query refuses
  an omitted world before calling dispatch. Otherwise omission keeps derived/default routing. Do not enable
  that requirement on doors routing tables outside their worlds by `world.table-connections` patterns:
  a named world overrides patterns (`connection_id_overrides_derived`), and omission is refused
  ([#344](https://github.com/Collite/ttr-server/issues/344)).
- **CAPABILITY MATCH** — selects among workers that advertise the resolved
  connection (and, for stateful plans, `supports_stateful_sessions = true`).
- **STICKY ROUTING** — pins a `session_id` to a worker when the worker is
  stateful; consistent-hash fallback keeps the choice stable across cold starts.
- **FAIL CLOSED** — cross-database plans (`cross_database_not_supported`) and
  unroutable plans (`no_worker_for_connection`) terminate the stream with a typed
  Rule-6 message rather than hanging.

dispatch does **not** transform worker batches — it pipes them through unchanged,
adding only routing-decision warnings on the first batch.

## Proto

The service proto is `org.tatrman.dispatch.v1` / `DispatchService` (RPCs `Dispatch`
streaming `org.tatrman.worker.v1.ResultBatch`, `ListWorkers`, `GetStatus`;
`com.tatrman.dispatcher.v1` is gone). `OverallStatus` comes from
`org.tatrman.meta.v1`; Rule-6 `messages = 99` is the
`org.tatrman.common.v1.ResponseMessage` stand-in.

## Workers

Worker slots are declared in `dispatch.workers` (HOCON); each needs an env-backed
endpoint (empty endpoint → skipped at boot). At Stage 3.3 the active slot is
**worker-mssql** (`DISPATCH_WORKER_MSSQL_ENDPOINT`; default gRPC port 7296); the
worker-polars (Polars) slot is commented until Stage 3.4.

## Run

```bash
just build-kt dispatch        # compile
just test-kt dispatch         # Kotest unit + component suite
just deploy-kt dispatch       # Jib image + k8s/overlays/local (local K3s)
```

## Status (Stage 3.3 — dispatch dispatches)

Routes a validated fixture plan to worker-mssql and streams Arrow IPC back, verified
by mocked unit + component tests (`DispatchMssqlDispatchComponentSpec`); real
on-K3s dispatch is on the integration-test track. Ports: HTTP 7290
(health/ready/status) · gRPC 7291 (`Dispatch`/`ListWorkers`/`GetStatus`).
Tag: `dispatch/v0.1.0`.
