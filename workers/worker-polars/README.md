# worker-polars

> **forked-from:** the legacy predecessor platform (`workers/polars/`), forked 2026-06-15 (Stage 3.4).
> Maintained independently since the fork; do not assume parity with the original.

**worker-polars** — the Polars worker for kantheon, the second of the
workers the dispatcher dispatches to. Python + Polars + gRPC +
FastAPI for probes. **Stateful**: it holds an in-memory, session-scoped workspace
store so an agent can build up derived DataFrames step by step within a session
(`assign_to_workspace` → later `WorkspaceRef` reads).

worker-polars implements the generic `org.tatrman.worker.v1.WorkerService` contract
(`Execute` / `GetCapabilities` / `GetStatus`) — no worker-specific proto. It
advertises `engine_name = "polars"`, `supports_stateful_sessions = true`, and the
session caps below.

## Run

```bash
uv sync
uv run python -m workers_polars.main
```

Default ports: HTTP 7300 / gRPC 7301. Tests: `just test-py workers/worker-polars`.

## Layout

* `src/workers_polars/` — package source (`grpc_service`, `converter`,
  `workspace`, `config`, `probes`, `main`).
* `conf/application.conf` — HOCON config under the `workers-polars {}` namespace.
* `tests/` — pytest (`pytest-asyncio`, `asyncio_mode = auto`).

## Session workspace (the pattern Metis ports)

`workspace.py` is the session-scoped DataFrame store: keyed by
`(session_id, df_name)`, with an idle-TTL sweeper, per-df / per-session / total
byte caps, and a max-sessions cap. The same semantics Metis Phase 1 will port —
any deviation found during the fork is flagged here for Metis to inherit. **No
deviation from the predecessor source was found at the fork point.**

## Schema fingerprint (cross-engine contract)

The worker stamps `ResultBatch.schema_fingerprint` on the first batch. Kantheon's
canonical, **implementation-independent** fingerprint (Charon `Integrity.kt` +
the Python reference) is the cross-engine schema-identity check shared by worker-mssql
(Kotlin) and worker-polars (Python). The pinned reference fixtures live in
`shared/testdata/fingerprints/` (Stage 3.4 T2); worker-polars's pytest recomputes
against them so all implementations must agree. **List/map child-field naming**
matters: list value field → `item`, map → `entries` with `key`/`value` children.

## Throughput baseline (Fork Stage 4.1 T4)

Recorded read-out baseline — a representative Polars transform plus the worker's
own `_split_table` + `_serialize_record_batch` (Arrow IPC), the steady-state
read-out path. The **source fetch is excluded** (integration-suite territory).

| Dataset | batch | p50 | p95 | rows/s (p50) |
|---|---|---|---|---|
| 100 000 rows × 3 cols (id/region/amount) | 10 000 | 0.5 ms | 0.7 ms | ~188 M |

Indicative, single-host (Apple Silicon; Polars/pyarrow per `uv.lock`). Re-run on
demand (not in the CI gate): `uv run python bench/bench_throughput.py`. Takeaway:
read-out is **not** the bottleneck — end-to-end latency is dominated by the
source fetch + Polars compute on real workloads; `run_query` cost hints live on
`query.run:v1`, not on a worker manifest (workers carry no capability manifest).

## Notes

* gRPC stubs are not auto-generated; the worker registers handlers via
  `grpc.method_handlers_generic_handler`, so the build only needs the `*_pb2.py`
  message types from `org.tatrman.{worker,plan}.v1` (+ `org.tatrman.validate.v1` /
  `org.tatrman.common.v1` for status + Rule-6 messages).
* Workers stay dumb — display/value labels are decorated downstream, not written
  into Arrow schema field metadata here.
