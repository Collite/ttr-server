# veles

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`infra/metadata/`), tag `kantheon-fork-point`, forked 2026-06-13 (originally named Ariadne; Veles is the survivor persona name).
> Maintained independently since the fork; do not assume parity with the ai-platform original.

The **model-graph service** for kantheon. Loads a TTR model from a git repository (`METADATA_GIT_*`, re-fetched for live reload — see [Model source](#model-source)) into an in-memory `ModelSnapshot`, then serves `ListObjects` / `GetObject` / `Search` / `ListQueries` / `GetModel` / `ResolveArea` / `GetStatus` / `Refresh` over gRPC (VelesService) and HTTP. The gRPC service proto is forked as `org.tatrman.meta.v1` / `VelesService` (Stage 1.2; the old `MetadataService` is gone).

## Runs on the `org.tatrman:ttr-metadata` library (Stage M4.1, ttr-metadata adoption)

Since the ttr-metadata swap (arc doc: [`docs/architecture/fork/ttr-metadata-adoption.md`](../../docs/architecture/fork/ttr-metadata-adoption.md)), Veles **owns no model/graph/search/resolution logic**. It is a thin gRPC facade over the third-party libraries `org.tatrman:ttr-metadata` (+ `:ttr-metadata-git` for `GitArchiveStorage`, MD3): typed model, sources, reconciler, resolver, graph, search, registry, and the `MetadataRefresher` mechanism all live there. What stays here:

- `grpc/MetadataServiceImpl` — proto ↔ library conversion + delegation to `MetadataQuery`/`WorldResolver` (MD2). The de-proto boundary is `grpc/ProtoConverters.kt` (proto `plan.v1.QualifiedName`/`SchemaCode`, `meta.v1.EdgeType`/`Direction` ↔ the library's proto-free types).
- `grpc/PageTokenCodec` — AIP-158 wire page tokens (wire-level, stays here).
- `refresh/RefreshScheduler` — scheduling policy over the library `MetadataRefresher`.
- `parse/` — the in-process query-parse worker (ttr-translator arc).
- `export/MetadataExportRoutes` — the Ktor `/model/export` + `/model/export/dot` routes (the export *pipeline* — `ModelToDefinitions`/`GraphDotExporter` — is library-owned).
- `Application.kt`, OTel/metrics/k8s wiring.

**Drift rule (contract, enforced by review + CI grep):** core model/graph/search/resolution fixes ship as a **new `org.tatrman:ttr-metadata` release**, never as a re-fork under `services/veles`. A PR adding such logic here is bounced to the library. The guard greps (all must be empty) live in the arc doc's execution checklist. The pinned version is `tatrman-ttr-metadata` in `gradle/libs.versions.toml` (currently `0.8.6`, published to GitHub Packages; the `0.0.1-LOCAL`/`mavenLocal()` local-iteration interim is retired).

**Areas (Golem P4 S4.2, 2026-06-25):** `ResolveArea(area) → packages + description + tags` resolves a subject area to its member packages, loaded from the model's `def area` blocks (`AreaDef`, modeler ≥ 0.7.0). Golem assembles a Shem by resolving its `areas` → packages → `GetModel`.

**Prompt-serving REMOVED (Golem P4 S4.2, 2026-06-25; reverses the 2026-06-13 addition):** the `GetPrompts` RPC, the `prompts/` package, and the `prompts/` Git subtree are gone — **the model is just the model.** Prompts belong to the **Shem** now (mounted with the Golem pod; each Golem's `PromptStore` loads its own `prompts/{locale}/…`). The git source carries the model only. See `docs/architecture/fork/contracts.md` §1.1.

## Package root

`org.tatrman.veles` (per Charon module-map idiom, `org/tatrman/<service>/`).

## Ports

- HTTP: **7260** (`VELES_HTTP_PORT`)
- gRPC: **7261** (`VELES_GRPC_PORT`)
- meta-mcp wrapper: **7262** (separate module)

## Run

```bash
just build-kt veles         # compile
just test-kt veles          # Kotest unit + component suite
just deploy-kt veles        # Jib image + k8s/overlays/local (local K3s)
```

## Model source

Veles serves the model of one git repository (the `github-model` source in `application.conf`),
cloned at boot and re-fetched every `METADATA_REFRESH_INTERVAL_SECONDS` (300 by default):

| Env | Meaning |
|---|---|
| `METADATA_GIT_REMOTE_URI` | The repository. Empty = no model source at all: Veles logs an error and stays not ready. |
| `METADATA_GIT_BRANCH` | The branch. Set it together with the URI. |
| `METADATA_GIT_SUBDIR` | The model's directory inside the repository. Set it together with the URI. |
| `METADATA_GIT_TOKEN` | A read token for a private repository (`METADATA_GIT_USERNAME` defaults to `x-access-token`). |
| `METADATA_GIT_CHECKOUT_DIR` | Where to clone (default `<tmp>/metadata-git/github-model`). |

**No fallback model (ttr-server#180).** Veles bundles no model. When the repository can't be loaded
(a bad token, a wrong branch or sub-path, no network), Veles serves no model — the built-in stock
roles only, so every catalog call answers empty — and:

- `GET /ready` answers **503** until every configured source has loaded at least once and its model
  is swapped in; the body says which source has not loaded and why. A source that loads but finds
  no model objects (an empty repository or a wrong sub-path) has not loaded. Once a source has
  loaded, a later failed refresh keeps the last good model and does not affect readiness.
- `GET /status` lists every source (`sources[]`: what it reads, `loaded`, `last_attempt_at`,
  `last_success_at`, `last_error`, `consecutive_failures`, `failures_total`, `serving_fallback`).
- `GET /metrics` has `veles_model_source_load_failures_total{source}` and
  `veles_model_source_loaded{source}`.
- A source that has never loaded is retried every 5 s, backing off to 60 s, independent of the
  refresh interval.

A fallback is opt-in, for demos that must come up offline: `METADATA_GIT_FALLBACK=local-model`
with `METADATA_TTR_ROOT=/path/to/model` serves that directory while the repository is unavailable
(never merged with it), and `/status` reports `serving_fallback: true`.
