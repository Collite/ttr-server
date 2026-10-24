# veles

> **forked-from:** the legacy predecessor platform (`infra/metadata/`), forked 2026-06-13 (originally named Ariadne; Veles is the survivor persona name).
> Maintained independently since the fork; do not assume parity with the original.

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

## The cn candidate store (`cn/`, off by default)

Producers propose concepts, terms and relations as `cn-candidate.v1` documents (ttr-concept's
`org.tatrman.concept.candidate`). A steward rejects or promotes them, and a promotion is a pull request against
the model text: canon stays text, and the steward merges. The routes live under `/v1/cn/candidates` and
`/v1/vocabularies` on the HTTP port.

| Env | Meaning |
|---|---|
| `METADATA_CN_DB_HOST` · `_PORT` · `_NAME` · `_USER` · `_PASSWORD` | The store's Postgres. Tables go in schema `metadata`, migrated on first use. No host: the store is off. |
| `METADATA_CN_AUTH_ISSUER` · `METADATA_CN_AUTH_JWKS_URI` · `METADATA_CN_AUTH_AUDIENCE` | Bearer verification. Every call needs a token; without an issuer the store is off. |
| `METADATA_CN_STEWARD_ROLE` · `METADATA_CN_IMPORTER_ROLE` | The realm roles that reject and promote (`cn-steward`) and that register vocabulary versions (`cn-importer`). |
| `METADATA_CN_PRODUCERS` | Who submits as which producer: `producer=principal[\|principal…]`, comma-separated. |
| `METADATA_CN_PROMOTION_SOURCE` | The `metadata.sources` block that a promotion patches (default `github-model`). Its loads decide merges. |
| `METADATA_CN_PR_TOKEN` · `METADATA_CN_PR_REPOSITORY` · `METADATA_CN_PR_API_URL` | Optional: open GitHub pull requests. The token needs contents and pull-request write access; the repository defaults to the source's `github.com` remote. |
| `METADATA_CN_INBOX_DIR` | Optional: write each promotion's `<id>.diff` and `<id>.md` there. Every diff the store hands out (this file and the API's `promotion.diff`) has the repository's paths, the source's sub-path included: `git apply <id>.diff` at the repository root. |
| `METADATA_CN_NOTIFY_URL` | Optional: every candidate event is POSTed here. |

A promotion is checked by a gate before it goes anywhere. The gate loads the model before and after the patch,
through the strict load report and the cn definition diagnostics. A new error refuses the promotion; other new
findings and a phase's er rendering go into the pull request. The candidate then waits in `pending_merge`. It
becomes `promoted` when a model load carries the def, with `canon_ref` = that load's commit `:` the def's qname.
Contract: cn contracts §6 in `project/tatrman/features/conceptual/plan/contracts.md`.

## Run

```bash
just build-kt veles         # compile
just test-kt veles          # Kotest unit + component suite
just deploy-kt veles        # Jib image + k8s/overlays/local (local K3s)
```

## Model source

Veles serves the model of one git repository (the `github-model` source in `application.conf`),
cloned at boot and re-fetched every `METADATA_REFRESH_INTERVAL_SECONDS` (300 by default).

**The default is the public Hartland demo model** (Collite/ttr-demo, branch `master`, sub-path `model`):
it needs no token, so `docker run` or a fresh chart install has a model to explore. To serve your own
model, set the URI, the branch and the sub-path together — the branch and sub-path defaults are
Hartland's, so a URI alone would look for `master` and `model/` in your repository.

| Env | Meaning |
|---|---|
| `METADATA_GIT_REMOTE_URI` | The repository. Default `https://github.com/Collite/ttr-demo.git`. Empty = no model source at all: Veles logs an error and stays not ready. |
| `METADATA_GIT_BRANCH` | The branch. Default `master`. Set it together with the URI. |
| `METADATA_GIT_SUBDIR` | The model's directory inside the repository. Default `model`. Set it together with the URI. |
| `METADATA_GIT_TOKEN` | A read token for a private repository (`METADATA_GIT_USERNAME` defaults to `x-access-token`). |
| `METADATA_GIT_CHECKOUT_DIR` | Where to clone (default `<tmp>/metadata-git/github-model`). |

**A second repository.** A model whose packages live in two repositories (a public one and a private
one, say) can be served by one Veles: set `METADATA_GIT2_REMOTE_URI` and Veles adds a second git
source, `second-model`, beside the first. It is off when the URI is unset. The two are separate
sources: each is cloned, refreshed and reported on `/status` on its own, and the reconciler merges
them by package as it merges any two sources. Veles is ready only when both have loaded.

| Env | Meaning |
|---|---|
| `METADATA_GIT2_REMOTE_URI` | The second repository. Unset or empty = no second source. |
| `METADATA_GIT2_BRANCH` | Its branch. Default `master`. |
| `METADATA_GIT2_SUBDIR` | Its model's directory. Default `model`. |
| `METADATA_GIT2_TOKEN` | Its read token. Default: `METADATA_GIT_TOKEN`, so one token that reads both repositories is enough. |
| `METADATA_GIT2_USERNAME` / `METADATA_GIT2_CHECKOUT_DIR` | As for the first source (checkout default `<tmp>/metadata-git/second-model`). |

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
