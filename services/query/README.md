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

Ports: HTTP 7305 (health/ready/status) · gRPC 7306. Downstreams via config
(`translate`/`validate`/`dispatch` host+port, env-overridable).

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
