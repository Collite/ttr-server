# veles

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`infra/metadata/`), tag `kantheon-fork-point`, forked 2026-06-13 (originally named Ariadne; Veles is the survivor persona name).
> Maintained independently since the fork; do not assume parity with the ai-platform original.

The **model-graph service** for kantheon. Loads the `ai-models` model (YAML/JSON via ClasspathStorage, Git source via `METADATA_GIT_*` for live reload) into an in-memory `ModelSnapshot`, then serves `ListObjects` / `GetObject` / `Search` / `ListQueries` / `GetModel` / `ResolveArea` / `GetStatus` / `Refresh` over gRPC (VelesService) and HTTP. The gRPC service proto is forked as `org.tatrman.meta.v1` / `VelesService` (Stage 1.2; the old `MetadataService` is gone).

## Runs on the `org.tatrman:ttr-metadata` library (Stage M4.1, ttr-metadata adoption)

Since the ttr-metadata swap (arc doc: [`docs/architecture/fork/ttr-metadata-adoption.md`](../../docs/architecture/fork/ttr-metadata-adoption.md)), Veles **owns no model/graph/search/resolution logic**. It is a thin gRPC facade over the third-party libraries `org.tatrman:ttr-metadata` (+ `:ttr-metadata-git` for `GitArchiveStorage`, MD3): typed model, sources, reconciler, resolver, graph, search, registry, and the `MetadataRefresher` mechanism all live there. What stays here:

- `grpc/MetadataServiceImpl` — proto ↔ library conversion + delegation to `MetadataQuery`/`WorldResolver` (MD2). The de-proto boundary is `grpc/ProtoConverters.kt` (proto `plan.v1.QualifiedName`/`SchemaCode`, `meta.v1.EdgeType`/`Direction` ↔ the library's proto-free types).
- `grpc/PageTokenCodec` — AIP-158 wire page tokens (wire-level, stays here).
- `refresh/RefreshScheduler` — scheduling policy over the library `MetadataRefresher`.
- `parse/` — the in-process query-parse worker (ttr-translator arc).
- `export/MetadataExportRoutes` — the Ktor `/model/export` + `/model/export/dot` routes (the export *pipeline* — `ModelToDefinitions`/`GraphDotExporter` — is library-owned).
- `Application.kt`, OTel/metrics/k8s wiring.

**Drift rule (contract, enforced by review + CI grep):** core model/graph/search/resolution fixes ship as a **new `org.tatrman:ttr-metadata` release**, never as a re-fork under `services/veles`. A PR adding such logic here is bounced to the library. The guard greps (all must be empty) live in the arc doc's execution checklist. The pinned version is `tatrman-ttr-metadata` in `gradle/libs.versions.toml` (currently `0.8.6`, published to GitHub Packages; the `0.0.1-LOCAL`/`mavenLocal()` local-iteration interim is retired).

**Areas (Golem P4 S4.2, 2026-06-25):** `ResolveArea(area) → packages + description + tags` resolves a subject area to its member packages, loaded from `model-ttr/areas/*.ttrm` (`AreaDef`, modeler ≥ 0.7.0). Golem assembles a Shem by resolving its `areas` → packages → `GetModel`.

**Prompt-serving REMOVED (Golem P4 S4.2, 2026-06-25; reverses the 2026-06-13 addition):** the `GetPrompts` RPC, the `prompts/` package, and the `prompts/` Git subtree are gone — **the model is just the model.** Prompts belong to the **Shem** now (mounted with the Golem pod; each Golem's `PromptStore` loads its own `prompts/{locale}/…`). The Git source is back to `model-ttr/` only. See `docs/architecture/fork/contracts.md` §1.1.

## Package root

`org.tatrman.veles` (per Charon module-map idiom, `org/tatrman/<service>/`).

## Ports

- HTTP: **7260** (`VELES_HTTP_PORT`)
- gRPC: **7261** (`VELES_GRPC_PORT`)
- meta-mcp wrapper: **7262** (separate module)

## JSON read API (the catalog viewer's)

`GET /model/index`, `/model/graph?schema=&package=`, `/model/object?qname=`, `/model/search?query=&limit=`
(`read/VelesReadRoutes.kt`) are a read-only JSON projection of the loaded model for a browser catalog,
the Studio Viewer (Collite/ttr-core). A node (`/model/graph` `nodes[]`, `/model/object` `object`) is
`{ qname, kind, label, schema, pkg }`, where `label` is the leaf identifier, plus these optional fields,
each omitted when empty (ttr-server#185):

| Field | On | |
|---|---|---|
| `displayLabel` | entity, attribute | `{ "en": "Product", "cs": "Produkt" }`, every authored language |
| `labelPlural` | entity | `"Products"` |
| `description` / `descriptionLocalized` | any object | the plain form / the `{ lang: text }` form |
| `from`, `to`, `cardinality` | relation | entity qnames; `{ "from": "0..*", "to": "1" }` |
| `fromColumns`, `toColumns` | foreign key | column qnames |

Veles picks no locale here. The reader chooses the language and applies the D7 chain to descriptions:
requested locale → plain form → `en` → first by language code.

## Run

```bash
just build-kt veles         # compile
just test-kt veles          # Kotest unit + component suite
just deploy-kt veles        # Jib image + k8s/overlays/local (local K3s)
```

ClasspathStorage (the in-repo `model-ttr/` seed, incl. `model-ttr/areas/`) is the
default source; set `METADATA_GIT_*` to load the `ai-models` repo with live reload.
