# metadata

> **forked-from:** `metadata` since 2026-10 (MR); formerly `veles` (2026-06-13 → 2026-10), originally `ai-platform/infra/metadata` (Ariadne).
> Maintained independently since the fork; do not assume parity with the original.

The **model-graph service** for kantheon. Loads a TTR model from a git repository (`METADATA_GIT_*`, re-fetched for live reload — see [Model source](#model-source)) into an in-memory `ModelSnapshot`, then serves `ListObjects` / `GetObject` / `Search` / `ListQueries` / `GetModel` / `ResolveArea` / `GetStatus` / `Refresh` over gRPC (MetadataService) and HTTP. The gRPC service proto is `org.tatrman.meta.v1` / `MetadataService`; all consumers roll with the server (MR).

## Runs on the `org.tatrman:ttr-metadata` library (Stage M4.1, ttr-metadata adoption)

Since the ttr-metadata swap (arc doc: [`docs/architecture/fork/ttr-metadata-adoption.md`](../../docs/architecture/fork/ttr-metadata-adoption.md)), Metadata **owns no model/graph/search/resolution logic**. It is a thin gRPC facade over the third-party libraries `org.tatrman:ttr-metadata` (+ `:ttr-metadata-git` for `GitArchiveStorage`, MD3): typed model, sources, reconciler, resolver, graph, search, registry, and the legacy `MetadataRefresher` mechanism all live there. What stays here:

- `grpc/MetadataServiceImpl` — proto ↔ library conversion + delegation to `MetadataQuery`/`WorldResolver` (MD2). The de-proto boundary is `grpc/ProtoConverters.kt` (proto `plan.v1.QualifiedName`/`SchemaCode`, `meta.v1.EdgeType`/`Direction` ↔ the library's proto-free types).
- `grpc/PageTokenCodec` — AIP-158 wire page tokens (wire-level, stays here).
- `refresh/GuardedRefresher` — service publication policy (#258), delegating parsing, reconciliation, graph and layouts to the library. `RefreshScheduler` schedules this same guarded path.
- `parse/` — the in-process query-parse worker (ttr-translator arc).
- `export/MetadataExportRoutes` — the Ktor `/model/export` + `/model/export/dot` routes (the export *pipeline* — `ModelToDefinitions`/`GraphDotExporter` — is library-owned).
- `Application.kt`, OTel/metrics/k8s wiring.

**Drift rule (contract, enforced by review + CI grep):** core model/graph/search/resolution fixes ship as a **new `org.tatrman:ttr-metadata` release**, never as a re-fork under `services/metadata`. A PR adding such logic here is bounced to the library. The guard greps (all must be empty) live in the arc doc's execution checklist. The pinned version is `tatrman-ttr-metadata` in `gradle/libs.versions.toml` (currently `0.18.12`, published to GitHub Packages; the `0.0.1-LOCAL`/`mavenLocal()` local-iteration interim is retired).

**Areas (Golem P4 S4.2, 2026-06-25):** `ResolveArea(area) → packages + description + tags` resolves a subject area to its member packages, loaded from the model's `def area` blocks (`AreaDef`, modeler ≥ 0.7.0). Golem assembles a Shem by resolving its `areas` → packages → `GetModel`.

**Prompt-serving REMOVED (Golem P4 S4.2, 2026-06-25; reverses the 2026-06-13 addition):** the `GetPrompts` RPC, the `prompts/` package, and the `prompts/` Git subtree are gone — **the model is just the model.** Prompts belong to the **Shem** now (mounted with the Golem pod; each Golem's `PromptStore` loads its own `prompts/{locale}/…`). The git source carries the model only. See `docs/architecture/fork/contracts.md` §1.1.

## Strict model publication (#258)

`METADATA_STRICT_SWAP=true` (the default; HOCON `metadata.strict-swap`) refuses loads when the
library's strict load report has an ERROR or drops a file, a source throws, or a configured source
contains no model content. The verdict is `MetadataLoader.load(LoadOptions(strict = true)).report`,
the same library check used by the model-repository `ttr-metadata load --strict` gate. The service uses
its default `PackageLayout.Flexible`: package-declaration mismatches remain warnings. Known gap:
Metadata fetches only the configured model subtree and cannot read the repository's `modeler.toml`
layout policy.

Changed sources owning blocking issues retain their previous accepted contribution. A second pass
reconciles the remaining candidates with those last-good contributions; a clean pass publishes the
independent updates. An unattributable issue or a blocking second pass refuses the whole transaction,
charging only changed configured sources. Unchanged and internal sources are not blamed for another
source's error. Parsing, validation and reconciliation remain library-owned; this switch controls
publication of their result.

Startup, scheduled ticks, `POST /refresh`, gRPC `Refresh` and CN-triggered refreshes share the same
serialized path. A targeted retry also loads unpublished internal contributions (stock roles and registered
vocabulary versions) needed to recover from an initial reconciliation failure. A rejected candidate never updates the accepted source cache, served packages,
model objects, layouts or template/lexicon sidecars. Rejected versions are retried on later refreshes,
even at the same SHA. A successful repair publishes as one model swap. Same-version repairs to optional
sidecars of an already accepted source are cached but retain the existing served catalog until the next model swap,
as described below. Layout, report/form and lexicon content issues remain non-blocking.

With configured sources but no accepted contribution, strict mode publishes nothing: gRPC
`model_loaded` stays false and `/ready` answers 503. With no configured content sources, startup
exits non-zero with `no_model_source_configured`, before telemetry, CN database initialization or serving listeners.
Configure `METADATA_GIT_REMOTE_URI`, `METADATA_GIT_BRANCH` and `METADATA_GIT_SUBDIR`, or explicitly enable
`metadata.sources.local-model` with `METADATA_TTR_ROOT` naming a model directory. This configuration check also
applies with `METADATA_STRICT_SWAP=false`; built-in stock roles and registered CN vocabulary versions do not satisfy it.
A configured source with unavailable or empty content still starts unready and retries with the existing backoff.
Once every configured source has contributed a
published load, a later failure keeps readiness UP while the old content is served. Built-in stock
roles and registered CN vocabulary versions are not configured sources and do not satisfy this gate.
Md-only sources, including directive-less files, and cn concepts count as model content even without
typed model carriers. Lexicon entries and project headers alone do not count.

`GET /status` advertises `strict_swap: true|false`. Rejections log their full details at ERROR once per source/version/issues; repeated refusals log a
short ERROR referring to `/status`. Acceptance resets this suppression. Refusals appear in
`sources[].last_error`, `last_attempt_at`, `consecutive_failures`, `failures_total`, the existing source
failure metric, and `issues[]` with source/file/location details. Source `version` and `last_success_at`
continue to describe the accepted contribution; issues clear on a successful retry. Served model
warnings and optional-content issues are also listed. gRPC `GetStatus.sources` uses the same source
board, including before a model is loaded: never-loaded sources are DOWN, last-good sources with an
error (including an explicit fallback) are DEGRADED, healthy sources are OK. `version`, `last_success`,
`last_error` and `last_error_at` are populated; overall status degrades when any configured source does.
Credentials in URI diagnostics are redacted.

`METADATA_STRICT_SWAP=false` restores legacy partial/empty publication for a temporary demo opt-out.
Load exceptions still retain the previous contribution. A source with no objects is still reported as a
health failure; permissive publication does not imply a healthy source. The Studio editor should require
strict mode outside a deployment that explicitly accepts partial models. No deployment is changed here.

## Package root

`org.tatrman.metadata` (per Charon module-map idiom, `org/tatrman/<service>/`).

## Ports

- HTTP: **7260** (`METADATA_HTTP_PORT`)
- gRPC: **7261** (`METADATA_GRPC_PORT`)
- meta-mcp wrapper: **7262** (separate module)

## JSON read API (the catalog viewer's)

`GET /model/index`, `/model/graph?schema=&package=`, `/model/object?qname=`, `/model/search?query=&limit=`
(`read/MetadataReadRoutes.kt`) are a read-only JSON projection of the loaded model for a browser catalog,
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
| `levels` | hierarchy | ordered level attribute qnames |

Metadata picks no locale here. The reader chooses the language and applies the D7 chain to descriptions:
requested locale → plain form → `en` → first by language code.

### Multidimensional structural reads (#274)

The index, graph and object routes also serve the loaded `md` definitions: domains, dimensions and
nested attributes, maps, hierarchies, measures and cubelets. They use the same five node fields above,
with `schema: "md"`, library canonical qnames (for example `md.dimension.Buyer.id` and
`md.cubelet.purchases`), the file's declared or inferred package, optional authored descriptions,
attribute `displayLabel` and ordered hierarchy `levels`.
The index includes `md` when present; its object count covers the same objects as the unfiltered graph.
Object detail returns the same node, its source file and its outgoing references.

Edges retain `{ from, to, type }` and the existing four type literals:

- A nested md attribute points to its dimension with `DEFINES`.
- Cubelet grain points to dimension attributes, cubelet measures to measures, and hierarchy levels
  to dimension attributes with `REFERENCES`.
- `er2db` and `md2db` bindings add **logical object → physical object** `MAPS_TO` edges. This includes
  explicit and inline er2db mappings, and md2db cubelet, domain and map bindings. Existing mapping
  vertices and their outgoing edges remain available.

For each binding, the typed query-path endpoints win when both are catalog objects; otherwise the
library's definition graph supplies the fallback. Er2db copies are compared by their resolved endpoints:
identical endpoint pairs serve once, including copies that differ only in description, tags, imports or
`whereFilter` (the read edge has no filter). Conflicting copies are omitted and diagnosed until ttr-core#321.
Md2db and md copies instead compare rendered definition, imports, package, parent and compatible effective
model/namespace provenance. Directive-less db/er objects use the loader's kind defaults; unknown md/binding
provenance matches a specified value. Different imports or packages keep md2db copies differing until
per-declaration resolution is available in ttr-core#321.
Ambiguous export identities are omitted and diagnosed. Er2db, `md2db_domain` and `md2db_map` logical objects
with multiple or contested physical targets are omitted; omitted binding copies still contest that object.
Each source table of a multi-source cubelet is served. A conflicting copy of one cubelet binding is omitted
without suppressing the cubelet's other binding ids.
Diagnostics are logged once per publication of a version with diagnostics
(consecutive publications of one version log once).
Md ids carry no package or cubelet scope: differing declarations with one id (including same-named inline
measures in two cubelets) are served as one node selected by the library's definition order
(root-relative path, then source priority on ties). Identical md precedence copies produce no collision warning.
A warning once per publication of a version with diagnostics (consecutive publications of one version log once)
names the kept and dropped declarations, including file:line and parent qname.
The shared binding resolver and scoped md identities are tracked in [ttr-core#321](https://github.com/Collite/ttr-core/issues/321)
and [ttr-core#322](https://github.com/Collite/ttr-core/issues/322).

The library's captured definition graph supplies md objects and resolves references; no files are read
or parsed on HTTP requests. The whole projection and diagnostics are cached by snapshot identity
in a registry listener after package-index publication, with initial-load priming under the registry swap monitor.
Old/in-flight requests can build uncached without evicting the current entry. Failed builds are never cached.
Disk edits become visible only through a registry swap. Unresolved export stubs are omitted,
and every graph scope keeps edges only when both endpoints survive its filters. Thus cross-schema
bindings are visible on the unfiltered graph; `?schema=md` serves only md-to-md edges. Package filters
retain their existing exact-match behavior.

`md2er` bindings and concept groundings are not projected.

This is the structural catalog surface. Rich md slot data and Viewer rendering/capability changes stay
in ttr-core. `/model/search` retains its existing library search path. No new gRPC contract, gateway
route, write API or deployment configuration is introduced.

### Report and form templates (#272)

The same unauthenticated, cluster-internal HTTP port serves read-only authored content from each configured
Git source. The source's configured `subdirectory` is the model root (normally `model`), normalized
once for tree lookup (`./model`, `model/.`, repeated separators). A blank root serves only repository-root
`reports/**` and `forms/**`; it never merges an additional `model/` tree. Absolute roots and roots that escape the repository
with `..` after normalization contribute no templates. They emit one configuration warning at source construction
and reuse one empty catalog with a source-specific issue; no template Git read is attempted for those roots.

| Route | Response |
|---|---|
| `GET /model/reports` | `{ reports: [{ id, title, program, params, roles, refresh, source, version }] }` |
| `GET /model/report?id=sales` | `{ id, yaml, template, sha256 }` |
| `GET /model/forms` | `{ forms: [{ target, source, ...authoredFields }] }` |

Reports live at `reports/<id>/report.yaml` with a sibling `template.xlsx`; forms at `forms/<target>.yaml`
under that model root. Public `source` paths use `model/reports/...` and `model/forms/...`. Report ids and
form targets come from their paths; lists are sorted by those keys. Nested form targets are retained.
The report detail preserves the YAML text, returns the workbook bytes as base64, and hashes those bytes
with SHA-256 (lowercase hex). The summary's `version` is the authored YAML version (default 1).
Absent or empty (`null`) `params`/`roles` are `[]`, `title` is `{}` and `version` is `1`.
Absent or empty `program`/`refresh` remain `null`; wrong field types stay with the consumer's schema validation.

Content is read from Git blobs at the model source's recorded SHA in the **same `GitArchiveStorage`
checkout**, then travels in that source's immutable registry sidecars. Requests only read the published
snapshot, so staged loads and newer checkout files are invisible until its model swaps in. Deletion and
configured fallback replace the contribution with the model; genuine model-load failures retain the prior contribution.

Template I/O failures never prevent a successful model load or trigger its configured model fallback. Per R171-B,
a failed template read retains that source instance's last successful **whole** catalog (possibly from an older SHA),
or an empty catalog on its first load, with a `/status.issues` entry naming the source, attempted SHA, cause and
previous catalog SHA. Model-source health still records the successful model load. Failed template SHAs are retried
every tick. An identical failure (attempted SHA, prior catalog SHA and cause) reuses its encoded result and
logs at DEBUG after the first WARN; a successful read clears that failure cache. The source retains only the
successful catalog SHA and encoded string, decoding it transiently when a new failure needs a stale catalog.
Recovery at the same SHA is not published until the next registry swap (a new commit, or another source
changing): the refresher's unchanged-version short-circuit keeps the previously served catalog and issue until then.
The model version is never altered to force a swap. An unchanged successful SHA reuses its encoded sidecar string,
without re-reading, re-encoding or re-hashing workbooks. The Git walk is restricted to the report and form trees.

For duplicate ids/targets across sources, higher numeric priority wins; ties use the later source id in
lexicographic order. List and detail use the same rule. Filesystem/resource sources contribute no templates.

Only YAML mapping validity is checked, using studio-report's `snakeyaml-engine` 3.0.1 YAML 1.2 `CoreSchema`
settings: duplicate keys and collection aliases are forbidden, as are multiple documents. An iterative event pass
limits collection nesting to 100 levels (including the root mapping) before the recursive loader runs. Deeper
documents are omitted with a source/path issue; valid sibling documents and the live model remain available.
A remaining `StackOverflowError` on the template path is also contained; other VM errors retain their normal semantics.
Non-finite `.inf`, `-.inf` and `.nan` values are accepted and represented as JSON strings; `+.inf` is refused
with a suggestion to write `.inf`. Binary values become base64 text and sets become arrays. Other unsupported
YAML value types are omitted with a diagnostic rather than Java internal text. Invalid documents and reports
without a regular workbook are omitted and diagnosed in `/status.issues[]`, with source id, path and a one-line cause.
Symlink/submodule entries throughout the report/form trees, including folders and model-root ancestors, are
diagnosed and never followed. Missing report/form trees are valid and silent. No workbook parsing, report schema validation,
program execution, rendering or write API is added. The gateway exposes no new route. Before a model loads, all three routes return 503; unknown
report ids return 404, and a missing/blank `id` returns 400. Loaded empty catalogs return empty arrays.


## Compiled lexicon archive (#273)

`GET /model/lexicon.tar.zst` serves the exact compiled bytes from the Git commit used by the served
source snapshot (`application/zstd`). Its quoted `ETag` contains `<source-Git-SHA>.<archive-blob-id>`, so changing the configured path at the
same commit also changes the validator. `If-None-Match` accepts quoted, weak, comma-separated or bare tokens and `*`; a match
returns an empty `304` with the same ETag. The route is read-only and cluster-internal like the other
`/model` reads; no gateway route is added.

The default file is **repository-relative** `generated/lexicon.tar.zst`, independent of `subdirectory`.
Override `metadata.sources.<source>.lexicon-path` (`METADATA_GIT_LEXICON_PATH`, or
`METADATA_GIT2_LEXICON_PATH` for the second built-in Git source). Absolute/escaping paths and symlinks,
including ancestor folders, are refused. A blank path disables the archive (clean `404`, no status issue). The server serves compiled content; it never compiles a lexicon.
Reads are bounded at 50 MiB. A missing archive is valid for an estate without a lexicon and yields `404`;
a read/configuration failure yields `503` and an issue on `/status`, while the live model still loads.
An old archive is never relabelled with a new source SHA. Successful reads, including absence, are cached
by source commit; I/O failures are retried each tick. A same-SHA recovery is not published until the
next registry swap (a source commit changes); the `503` and `/status` issue remain until then.
A reader started in that window serves no declared vocabulary until that swap. Bytes travel in an internal snapshot sidecar (ttr-core#314),
are published atomically with the model, and are excluded from public export bundles.

For several configured Git sources, `?source=<configured-source-id>` is required: a bare URL returns
`400` naming the ids, even if one source failed to load. With exactly one configured Git source the
bare URL selects it. A configured but unloaded source returns `503`; an unknown source is `404`
`unknown lexicon source`, and a loaded source without an archive is `404` `lexicon not found`.
With no Git source configured, a bare URL returns `404` `no lexicon source configured` (a reader error).
Read/configuration failures return `503`. Before any model is served the route returns `503`.
Invalid selectors are `400`. Source ids are `metadata.sources.<block>.id` values (`github-model`,
`second-model`). bp-dsk needs both URLs below; a bare URL there is a `400`.

Opt in the readers with **the same ordered URL list**, primary first:

```hocon
fuzzy.lexicon.archive-urls = [
  "http://metadata:7260/model/lexicon.tar.zst?source=github-model",
  "http://metadata:7260/model/lexicon.tar.zst?source=second-model"
]
resolver.lexicon-archive-urls = [
  "http://metadata:7260/model/lexicon.tar.zst?source=github-model",
  "http://metadata:7260/model/lexicon.tar.zst?source=second-model"
]
```

The environment equivalents are `FUZZY_LEXICON_ARCHIVE_URLS` and `RESOLVER_LEXICON_ARCHIVE_URLS`
(comma-separated). An explicit nonempty URL list replaces the whole mounted archive list. With URLs
unset, existing singular/plural ConfigMap path settings keep their behavior. There is no automatic
switch to a possibly older mounted archive during an HTTP outage. The readers send `If-None-Match`,
retain bytes on `304`, retry errors, and apply their existing archive validation and primary/additive
last-good rules (#209). Explicit archive absence is quiet; unknown source ids remain errors.
Each request has a 3 s connection / 5 s read timeout, a 30 s deadline enforced on body reads
(measured from request start; connect/headers are not bounded by that deadline). One body read may
overrun by up to 5 s. The body limit is 50 MiB;
redirects and incomplete responses are refused. URL transport does not change the learned overlay.
Fuzzy checks on its refresh clock (`POST /refresh` and `refreshIntervalSeconds`). If a source fails
metadata's initial load and recovers after fuzzy's first pass, fuzzy serves without its archive until
the next refresh (600 s on hartland, 3600 s by default) or an operator calls `POST /refresh`.
The resolver does one synchronous read at boot, then polls on `Dispatchers.IO` with a fixed delay of
`resolver.lexicon-archive-check-interval-seconds` (env `RESOLVER_LEXICON_ARCHIVE_CHECK_INTERVAL_SECONDS`,
default 30). Resolve/gate requests only read the last completed pass and never perform HTTP I/O.
The resolver can lag metadata by up to that interval, plus download time. This does not make separate services swap simultaneously or change deployment wiring.

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
| `METADATA_CN_PRODUCERS` | Who submits as which producer: `producer=principal[\|principal…]`, comma-separated. A principal is a service account's client id, or `user:<username>` for a person. |
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
just build-kt metadata         # compile
just test-kt metadata          # Kotest unit + component suite
just deploy-kt metadata        # Jib image + k8s/overlays/local (local K3s)
```

## Model source

Metadata serves the model of one git repository (the `github-model` source in `application.conf`),
cloned at boot and re-fetched every `METADATA_REFRESH_INTERVAL_SECONDS` (300 by default).

**The default is the public Hartland demo model** (Collite/ttr-demo, branch `master`, sub-path `model`):
it needs no token, so `docker run` or a fresh chart install has a model to explore. To serve your own
model, set the URI, the branch and the sub-path together — the branch and sub-path defaults are
Hartland's, so a URI alone would look for `master` and `model/` in your repository.

| Env | Meaning |
|---|---|
| `METADATA_GIT_REMOTE_URI` | The repository. Default `https://github.com/Collite/ttr-demo.git`. Empty disables this source; startup exits non-zero if no other content source is configured. |
| `METADATA_GIT_BRANCH` | The branch. Default `master`. Set it together with the URI. |
| `METADATA_GIT_SUBDIR` | The model's directory inside the repository. Default `model`. Set it together with the URI. |
| `METADATA_GIT_TOKEN` | A read token for a private repository (`METADATA_GIT_USERNAME` defaults to `x-access-token`). |
| `METADATA_GIT_CHECKOUT_DIR` | Where to clone (default `<tmp>/metadata-git/github-model`). |

**A second repository.** A model whose packages live in two repositories (a public one and a private
one, say) can be served by one Metadata: set `METADATA_GIT2_REMOTE_URI` and Metadata adds a second git
source, `second-model`, beside the first. It is off when the URI is unset. The two are separate
sources: each is cloned, refreshed and reported on `/status` on its own, and the reconciler merges
them by package as it merges any two sources. Metadata is ready only when both have loaded.

| Env | Meaning |
|---|---|
| `METADATA_GIT2_REMOTE_URI` | The second repository. Unset or empty = no second source. |
| `METADATA_GIT2_BRANCH` | Its branch. Default `master`. |
| `METADATA_GIT2_SUBDIR` | Its model's directory. Default `model`. |
| `METADATA_GIT2_TOKEN` | Its read token. Default: `METADATA_GIT_TOKEN`, so one token that reads both repositories is enough. |
| `METADATA_GIT2_USERNAME` / `METADATA_GIT2_CHECKOUT_DIR` | As for the first source (checkout default `<tmp>/metadata-git/second-model`). |

**No fallback model (ttr-server#180).** Metadata bundles no model. When the repository can't be loaded
(a bad token, a wrong branch or sub-path, no network), Metadata serves no model — the built-in stock
roles only, so every catalog call answers empty — and:

- `GET /ready` answers **503** until every configured source has loaded at least once and its model
  is swapped in; the body says which source has not loaded and why. A source that loads but finds
  no model objects (an empty repository or a wrong sub-path) has not loaded. Once a source has
  loaded, a later failed refresh keeps the last good model and does not affect readiness.
- `GET /status` lists every source (`sources[]`: what it reads, `loaded`, `last_attempt_at`,
  `last_success_at`, `last_error`, `consecutive_failures`, `failures_total`, `serving_fallback`).
- `GET /metrics` has `metadata_model_source_load_failures_total{source}` and
  `metadata_model_source_loaded{source}`.
- A source that has never loaded is retried every 5 s, backing off to 60 s, independent of the
  refresh interval.

A fallback is opt-in, for demos that must come up offline: `METADATA_GIT_FALLBACK=local-model`
with `METADATA_TTR_ROOT=/path/to/model` serves that directory while the repository is unavailable
(never merged with it), and `/status` reports `serving_fallback: true`.

## `/model/export`

The existing no-parameter response remains a validated TTR tar.gz; `?validate=false` skips self-validation.
`/model/export/dot` remains available. Explicit projection requests call the same `ttr-metadata` exporter as the CLI.

| Parameter | Default | Values |
|---|---|---|
| `model` | `world` | `cn`, `md`, `er`, `db`, `lexicon`, `bindings`, binding families, comma-separated selectors |
| `format` | `ttr` | `graph.json`, `defs.json`, `ttl`, `xmi`, `dot`, `ttr`; reserved `yaml`, `ossie`, `uc`, `tmdl`, `pdm`, `ldm`, `cdm` |
| `profile` | format default | Turtle `skos`/`owl`/`both`; XMI `ttr-mdg/1` (`ea-erd/1` reserved); TTR `canonical` |
| `package` | all | Comma-separated package names |
| `area` | all | Comma-separated area names |
| `since` | absent | Version in the snapshot's latest eight id sets; graph adds `delta` |
| `validate` | `true` | `false` disables the legacy no-projection TTR self-validation |

Single responses use `application/json`, `text/turtle`, `application/xml`, or `text/vnd.graphviz`; multi-file
TTR responses use `application/x-tar+gzip`. Each response has the current snapshot `ETag`. `If-None-Match`
accepts that token, quoted/weak tokens, lists or `*`, returning `304` and the ETag for valid requests.
Invalid or repeated parameters are rejected before conditional matching. Unknown `since` answers `409`.

```text
/model/export?model=world&format=graph.json
/model/export?model=world&format=defs.json
/model/export?model=cn&format=ttl&profile=owl
/model/export?format=xmi&profile=ttr-mdg/1
```

Errors are JSON: `400 xm/bad-request`, `409 xm/since-unknown` (with `since`), `422 xm/format-cannot-carry`
(with `model`, `format`, `lossiness`), `501 xm/format-not-built`, or `503 model not loaded`.
Internal projection/sidecar failures answer `500 xm/export-failed`; legacy TTR validation failures retain their
`500 errors[]` body. A cannot-carry example is
`{"error":"xm/format-cannot-carry","model":"md","format":"ttl","lossiness":"L-TTL-1"}`.

Definitions, connection/rename sidecars and id history come from the captured registry snapshot, on both initial
load and refresh. Only public connection locator fields enter `defs.json`; credentials are excluded. Deltas describe
whole-snapshot removals independently of projection scope; current-snapshot deltas have empty arrays. Archives use
zero entry timestamps. Rebuild Metadata against the containing grammar bundle because the registry/source JVM APIs changed.
The service pins internal grammar/metadata bundle 0.18.8. The copied invented `xm-world` fixture and goldens must stay in sync with ttr-core; `ExportParitySpec` runs the
published CLI in-process and compares every byte of graph, definitions and OWL outputs.
