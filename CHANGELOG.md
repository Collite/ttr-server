# Changelog

All notable changes to what this repo **publishes** are recorded here: the
`ttr-nlp` and `ttr-sqlalchemy` PyPI wheels, the `org.tatrman:*` Kotlin artifacts cut from
`shared/libs/kotlin`, and the wire contracts in `shared/proto` that deployed
consumers speak. Internal refactoring is out of scope; a change that a consumer
outside this repo could notice is in.

> **This file starts at NLS-P4** (2026-08-11). Everything before it is in the git
> history and in `project/`'s per-effort trackers, which stay the place where the
> *reasoning* lives — a changelog answers "what changed for me", not "why". Format
> follows `tatrman`'s, deliberately: two repos in one ecosystem with two changelog
> conventions is a small tax paid on every release.
>
> While versions are `< 1.0.0`, minor bumps may contain breaking changes.

## Unreleased

- **metadata — md catalog structure and physical bindings (#274).** The JSON index, graph and object
  routes now include loaded md objects, dimension membership, cubelet grain/measures and hierarchy
  levels. Logical-to-physical er2db/md2db edges use the existing `MAPS_TO` type. Graph scopes omit
  edges with excluded or unresolved endpoints. Valid typed binding endpoints take precedence over
  definition-graph fallbacks; ambiguous identities and duplicate binding ids are omitted and diagnosed.
  Source-precedence copies retain bindings when their model/namespace provenance matches. Md attributes
  retain localized labels, source paths match db/er paths, and hierarchy nodes expose ordered `levels`.
  Colliding md ids retain the library-selected declaration; warnings name kept/dropped sources.
  Empty hierarchy levels are omitted. The complete projection and outgoing-edge index are cached
  after package publication, including startup priming under the registry swap monitor. Viewer rendering
  remains a separate change.

- **metadata/fuzzy/resolver — compiled lexicon HTTP channel (#273).** `GET /model/lexicon.tar.zst`
  serves exact Git-commit archive bytes with SHA-plus-blob ETags and conditional 304 responses.
  Multiple configured Git sources require `?source=`; readers can opt into an ordered HTTP URL list.
  The resolver polls in the background (default 30 s); resolve/gate requests never fetch HTTP archives.
  Existing mounted
  ConfigMap paths remain the default. Archive read errors appear on `/status` without blocking the
  live model, and never expose old bytes under a new SHA. HTTP readers retain existing validation and
  last-good semantics, bound download size and total time, and retry outages. Same-SHA read repairs
  wait for the next registry swap. No deployment or default channel switch.

- **connect-openmetadata 0.1.4 — clickable Studio model links.** Add native
  `ttrStudioLink` hyperlinks to projected tables, ER/MD terms and metrics while
  retaining the existing `ttrUrl` string and foreign metadata. Both links use one
  validated Studio origin. Concepts stay in OM; with Studio configured, remove
  both link keys from planned CN terms. After deprecations, normalize Approved and Draft
  out-of-plan owned terms: ER/MD get both model links; CN/other prefixes get neither.
  Terms in every other status retain their links, including retired `/viewer?qname=` links from
  0.1.2 or earlier: editing metadata re-runs OM approval and can un-deprecate them.
  Dotted glossary names use quoted FQNs in lookups, term creation and RDF import.
  0.1.4+ reads state written by later versions, ignoring unknown fields.
  Dry-run discloses the additional normalization on stderr; stdout stays JSON-lines. Metric and table links
  outside the current export (both keys) are not migrated (follow-up #325). Without Studio, existing
  links remain unchanged. The version fingerprint forces a real projection on
  upgrade from 0.1.3; equivalent resolved workspaces share the cache identity.
  State now saves the export world; rollback to 0.1.3 or earlier needs a fresh state path.

- **connect-openmetadata 0.1.3 — Studio model links.** Replace unsupported `/viewer?qname=…`
  links with `/m/<workspace>/s/db|er|md`. `--studio-workspace` defaults to the export
  world and can name a different manifest project. Concepts stay in OM; sync with
  a Studio origin removes their obsolete Studio URL while preserving other custom
  properties. Links open whole models; exact-object navigation and new-CN diagram
  support remain follow-ups. Upgrade forces full projection through the options fingerprint.

- **metadata — report and form template reads (#272).** Cluster-internal `GET /model/reports`,
  `/model/report?id=`, and `/model/forms` serve authored templates from configured Git sources at the
  served source SHA. Report detail includes the original YAML, base64 workbook and SHA-256 checksum;
  unknown ids return 404. Content swaps with the model, and invalid YAML/missing workbooks appear in
  `/status.issues`. Template I/O failures retain the source's last successful catalog (or an empty first
  catalog) and report the stale SHA without failing the live model. YAML parsing matches the consumer's
  YAML 1.2 Core settings, with a 100-level nesting limit and stable binary/set projections. Unchanged
  successful SHAs and identical template read failures reuse their encoded catalogs; invalid configured
  roots and symlinked template folders/ancestors are diagnosed without following them. The routes are
  read-only; rendering remains with the report consumer.

- **automation — preview review repairs.** Nested context/candidate references fail closed pending
  callee pinning, action-level `due` defaults match explicit durations, and nested literals retain their
  original escapes. Invalid would-text placeholders identify the template. Fixture verification rejects
  extra files in both check and restore modes. Explicit undeclared `due` accepts durations/NULL and
  `on_due` accepts calls/NULL; all three wiring properties stay outside writer assignments while declared
  columns remain assignments. Role scope is required and prepared values retain their pinned action.
  The same kind rules apply recursively to nested `due`/`on_due`, checked once during preparation.
  Until callee types are pinned, a nested declared `due` column wired with a date also refuses.
  Fixture restore requires its README and reports restored blobs.
  Action-level `on_due` default application remains an explicit P3 obligation.

- **automation — partial action and relative duration preview.** Full H3 can preview its proposal and
  buyer ask. Action-typed calls retain supplied arguments as `deferred: true` intent; `due` preserves exact
  `amount`/`unit` evidence, including pinned working-day counts. No nested dispatch, grouped task creation
  or absolute/calendar deadline is performed. Invalid values refuse atomically even on empty routes.

- **Umbrella metadata release cohort:** pin metadata and its in-repo consumers to
  `0.11.30`, including health, charon and opt-in automation. The server and regenerated
  clients use `MetadataService` together; a pre-rename consumer image cannot call it.
  Other images retain their existing tags. The disposable kind gate verifies an
  anonymous metadata image pull and the published service's gRPC status call.

- **metadata — the model-graph service is renamed from `veles` (MR).**
  `services/metadata`, package `org.tatrman.metadata` (including `cn` and `export`),
  gRPC `org.tatrman.meta.v1.MetadataService`, charts `metadata` and `meta-mcp`, and
  image `ghcr.io/collite/metadata` replace their previous names. Rebuild JVM consumers
  for the moved `org.tatrman.metadata.client` API and regenerated gRPC stubs.
  Environment variables use `METADATA_*`, `META_MCP_SERVER_PORT` and
  `OTEL_ENABLED_METADATA` / `OTEL_ENABLED_META_MCP`; configuration keys use
  `metadata.*` / `upstream.metadata.*` (including automation's `door.metadataUrl`
  and `bundle.metadataSource`). This is a hard cut, with no legacy aliases.
  Source-load metrics change from `veles_model_source_load_failures_total` /
  `veles_model_source_loaded` to `metadata_model_source_load_failures_total` /
  `metadata_model_source_loaded`; update dashboards accordingly. The meta-mcp telemetry /
  OTel service name changes from `veles-mcp` to `meta-mcp`; the metadata chart’s default
  OTel service name changes from `veles` to `metadata`. Query retry logs use the
  operation label `metadata.get_query` instead of `veles.get_query`. The health
  status-adapter key changes from `veles` to `metadata` (roster `adapter = metadata`);
  update the health image and roster together. Ports 7260/7261/7262, proto package and message
  layouts, database schema, CN routes, and `METADATA_GIT_*` stay unchanged.
  **One-wave rollout per cluster:** the server and all consumers in ttr-server, kantheon
  and tatrman-platform (studio-bff) must roll together; the previous gRPC service path is removed (MR-2).
  No release is cut by this PR; publishing belongs to MW-P0.2.

- **resolver — the er statement lane places; it does not re-decide (grammar 0.18.12, ttr-core#308).** The er lane
  now refuses whatever md's validator refuses, and for the same reasons. It also refuses a phase whose join would fan
  out the event's rows, and a negated phase that needs a join. A negated phase reads `NOT COALESCE((…), FALSE)`. A
  member value must match its key's type (`cn/slot-mismatch`). `latest` is capped at the evaluation day, a period
  instant is read on its day through the calendar, and an instant compares as an interval. A refusal reports md's
  finding with "— and on E-R: …" appended. The door's JSON shape is unchanged. Pin: `tatrman-grammar` /
  `tatrman-ttr-metadata` 0.18.11 → 0.18.12; translator/plan-proto stay 0.12.11.
- **automation — `concat` in writer defaults.** Grammar 0.18.11's two-operand,
  NULL-strict `concat` is accepted in native writer-default and argument expressions (e.g. a `YYYY-Qn` label from
  `:as_of`). Pin: `tatrman-grammar` / `tatrman-ttr-metadata` 0.18.10 → 0.18.11; translator/plan-proto stay 0.12.11.

- **resolver — the er statement lane on `statement.lower:v1` (CN-P6 ⚑S62-1, A-CN-101).** Grammar 0.18.10's
  `ttr-concept` reads a statement on E-R when md carries no single read of it. The door's JSON now carries `lane`
  (`md` | `er`), and for an er read `erSql` — SQL over entities, every placeholder bound, which the query door runs as
  it is (`source_schema: er`) — with no `plan`; `refused` is true only when neither lane reads the statement. The
  advisory `cn/lowered-on-er` names md's reason. Pin: `tatrman-grammar` / `tatrman-ttr-metadata` 0.18.9 → 0.18.10.
- **connect-openmetadata 0.1.2 — complete sync and cache identity.** Skipped scanned
  tables/columns or mapping omissions retain the old state for retry. Summaries add
  `not_modified` to distinguish 304 from a real sync. State fingerprints mapping
  options plus connector/current OM versions; legacy state forces a full sync.
  Rollback to 0.1.1 requires a fresh state path.

- **connect-openmetadata 0.1.1 — Hartland catalog integration.** Optional
  `locators.openmetadata.schema` maps a model schema handle to the scanner's physical
  schema without changing model identities or ownership filters. HTTP 304 syncs print
  a zero-counter summary while retaining state and making no OM calls.
  Optional `--cn-name-mode qname` supports concept kinds/qualities with equal labels
  under OM's case-insensitive glossary-wide name constraint; existing label mode remains default.

- **connect-openmetadata 0.1.0 — live OM 2.0.6 conformance and image.** Adds a PostgreSQL/OpenSearch lane,
  real fixture scanning, RDF/search probes and CI image smoke. Default CN path is glossary after live RDF
  hierarchy/language loss. Corrects tableColumn custom-property registration and explicit column tag reads;
  uses stable qname catalog names for ER attributes/MD terms. Native metric parent-table assets plus
  registered ttrColumnAssets retain exact column FQNs (OM drops native column references). The image runs
  as the pinned ingestion base's openmetadata user. OpenSearch pin is 3.4.0.

- **llm-gateway — `deep` is the smart tier** (gpt-6.1-sol). The generic `deep` tier — what Golem composes the query
  on, and its ladder's `capable` rungs — moves from the `gpt-4.1` row (`azure-gpt-41`) onto `gpt-6.1-sol`
  (`azure-gpt-61-sol`, alias `smart`, ttr-server#297). `mini` and `fast` stay on the chat deployment
  (`AZURE_OPENAI_CHAT_DEPLOYMENT`); `AZURE_OPENAI_SMART_DEPLOYMENT` moves `deep` with `smart`.

### Added — XM P2 OpenMetadata writer (S2.2)

- Add `check` and `sync`: ensure string custom properties and Tatrman tags, import
  CN OWL with glossary fallback, upsert terms and native metrics, and attach
  properties, tags and asset links to scanned tables/columns.
- Reconcile by stored TTR qname (imported CN terms by IRI), preserve established
  IRIs and catalog metadata, and deprecate missing objects without deleting them.
  Unchanged repeat syncs perform no writes; dry runs and 304s do not advance state.
- Surface failed SDK patches and partial HTTP 200 import/link failures, retry 5xx
  responses with bounded backoff, and atomically save state only after success.
  Live OpenMetadata conformance remains the following Stage's gate.

### Added — XM P2 OpenMetadata connector (S2.1)

- Add the installable `ttr-connect-openmetadata` Python planner: read consistent
  world exports from a directory or HTTP, retain ETag state, and print deterministic
  glossary, metric, property, tag, asset-link and deprecation operations as JSON lines.
- Pin OpenMetadata ingestion 2.0.6 (PyPI 2.0.6.0), record inspected SDK/endpoint facts,
  and add an isolated Python CI gate. The S2.1 planner performs no OpenMetadata writes.

### Added — XM P1 external metadata export

- Extend `/model/export` with shared graph/definitions/Turtle/XMI projections, model/package/area scopes,
  bounded snapshot deltas, conditional requests and explicit lossiness/errors; retain legacy TTR and DOT routes.
- Publish full parsed definitions and captured connection/rename sidecars on initial load and refresh.
  CLI/HTTP parity tests compare bytes using the invented XM world and the same pinned grammar bundle.
- Pin the grammar/metadata API closure and parity CLI to internal bundle 0.18.8; Veles is rebuilt because registry and source snapshot JVM APIs changed.

### Other changes

- **resolver — an unbound mention may bind a value label** (md/hop HP-H). The lookup rung asks a G1 mention across
  categories in the `MODEL_OBJECT`, `OPERATOR` and now `MEMBER` classes. A `MEMBER` row is an authored word for one
  member (a value label or value alias); a data value carries no class, so lex-matcher still never offers one to a
  mention. Before, a value label under a measure could bind by no path: „Tržby z tržiště v roce 2025“ left `tržiště`
  unbound (a measure governs no value, and the rung asked for objects only), and the answer summed every channel.
  Now `tržiště` binds `er.entity.sales.channel#marketplace`, FILTER.
- **resolver — a value label binds as a member a door can render** (md/hop HP-H). A declared row whose class is
  `MEMBER` on an er attribute (a `valueLabels` entry; the archive spells it `er.entity.<e>.<a>.<code>`) is now read in
  the lattice's member spelling, `er.entity.<e>.<a>#<code>` — the ref every data member already has — by every place
  it leaves the matcher (one rule, `DeclaredMembers`):
  - the lattice binding, and a value's attribution to `er.entity.<e>.<a>`;
  - the resolution's domain binding (`target_ref`, `entity_type_ref` = the attribute) and a clarification option's
    `target_ref`, so a picked label resumes as the member;
  - the re-gate: a member-spelled hypothesis is also looked up under the label's archive category, and a hypothesis in
    the archive spelling confirms the member spelling.

  Before, the five-segment ref reached the query door as the attribute with nothing to compare to, so a question
  restricted by a value label („tržby z tržiště“ once the channel words are labels of `sales.channel`) could not take
  the fast path. `md.` members keep their spelling; an empty code or a ref already spelled as a member is left as it is.
- **veles — the cn candidate store and promotion** (CN-P5 S5.3, cn contracts §6). Producers submit candidate concepts,
  terms and relations as `cn-candidate.v1` (ttr-concept 0.18.3, `org.tatrman.concept.candidate`); a steward rejects or
  promotes them; a promotion is a model-text pull request, and the candidate is `promoted` once a model load carries
  its def. **Off by default:** on only with both a database (`METADATA_CN_DB_*`) and a token issuer
  (`METADATA_CN_AUTH_ISSUER`, `METADATA_CN_AUTH_JWKS_URI`); off, every route below answers `503 CN_STORE_OFF` and Veles
  serves the model as before.
  - REST on the HTTP port: `POST /v1/cn/candidates` (201 new; 200 for a proposal already stored, which returns the
    first candidate with `"duplicate": true`) · `GET /v1/cn/candidates?status=&producer=&stereotype=&sort=newest|evidence&limit=`
    · `GET /v1/cn/candidates/{id}` (with its audit `history`) · `POST /v1/cn/candidates/{id}/reject` `{"note"?}` ·
    `POST /v1/cn/candidates/{id}/promote`. JSON out wraps the document: `{"candidate", "state", "content_hash",
    "submitted_by", "submitted_at", "updated_at", "promotion"?}`. `state` adds `pending_merge` (a promotion opened, not
    merged yet) to the document's three statuses; the document stays `proposed` meanwhile.
  - Every call needs a verified bearer token (RS256 against the realm's JWKS). The caller is the token, never a field:
    `submit` takes a producer the registry admits **and** the caller may speak for (`METADATA_CN_PRODUCERS`,
    `producer=principal[|…]`, a principal being a service account's client id or `user:<username>` for a person;
    `manual:<p>` only by the steward `p`); `reject` / `promote` need the `cn-steward` realm
    role (`METADATA_CN_STEWARD_ROLE`). `import:*` is always refused.
  - Promotion reads the promotion source (`METADATA_CN_PROMOTION_SOURCE`, default the git source) through a checkout of
    its own, writes the patch, and gates it: the model before and after the patch, through the strict load report and
    the cn definition diagnostics. A new error refuses it (`422 CN_PROMOTION_GATE`); other new findings ride in the pull
    request, and a phase's pull request carries its er rendering. The diff and pull-request text are kept and served
    by the API; the diff's paths are the repository's (the source's sub-path included), so `git apply` takes it at the
    repository root. A GitHub pull request opens when `METADATA_CN_PR_TOKEN` is set (REST Git Data API, branched from
    the gated base commit); `METADATA_CN_INBOX_DIR` also writes `<id>.diff` / `<id>.md`. `canon_ref` = the promotion
    source's commit at the load that first carries the def, `:`, the def's qname.
  - `POST /v1/vocabularies/{id}/version` `{"version", "bundle"?}` (the `cn-importer` role) · `GET /v1/vocabularies[/{id}/version]`:
    registered versions are published in the model's `cn.vocabularyVersions` (ttr-server#281 item 1).
  - `METADATA_CN_NOTIFY_URL`: every candidate event is POSTed there, fire and forget (the steward app's queue seam).
  - The store: tables in schema `metadata` of the configured database, migrated by Flyway on first use; a database
    that is down costs the candidate API until it is back, never Veles's start.
  - The grammar bundle pin moves to 0.18.3 (`tatrman-grammar`, `tatrman-ttr-metadata`).

- **resolver — the four cn door families** (CN-P5 S5.1, cn contracts §4). The resolver's MCP server, beside
  `resolve.bind:v1` and behind the same fail-closed OBO gate, now answers `statement.validate:v1` · `statement.lower:v1` ·
  `statement.explain:v1` · `assertion.validate:v1` · `assertion.lower:v1` · `assertion.explain:v1` ·
  `concept.search:v1` · `definition.render:v1`.
  - Each frame tool wraps one of ttr-concept's pure functions (`org.tatrman:ttr-concept`, grammar bundle 0.18.2). A frame
    arrives as protobuf JSON (`org.tatrman.statement.v1.Statement`, `org.tatrman.assertion.v1.Assertion`) and an
    unknown field is an argument error. A frame the module refuses is a result, `refused: true` with its `cn/*`
    findings, never a guess; `isError` is for bad arguments, a missing model, or a resume token that does not verify.
  - `concept.search:v1` reads the vocabulary: one lex-matcher lookup, the resolution order, the lift. It returns
    concepts only, each with its stereotype, `via` and every source.
  - The model comes from `RESOLVER_CONCEPT_MODEL_PATH` (`resolver.concept.model-path`): the estate's model tree, or a
    snapshot archive of it. It is served only when its content id is the one the lexicon archive names
    (`modelSnapshotHash`); otherwise the frame tools answer `CN_MODEL_SKEW`. Unset, they answer `CN_MODEL_UNAVAILABLE`.
    Every answer names the snapshot it was read from.
  - A resume token inside a frame (`Candidate.resume_token`, `MemberRef.resume_token`) is checked with the resolver's
    codec: signature, age, the caller it was issued to, and that it offered what the slot names (`RG-RES-002`). The
    doors never mint one.
  - The resolver image now carries `ttr-concept` and, through it, `ttr-translator` (Calcite). A build guard,
    `verifyNoDbClient`, keeps any database driver or ADBC client off its runtime classpath: the doors never write.

- **lex-matcher + resolver — the declared layer reads lexicon archive v6** (CN-P4). The grammar bundle pin moves to
  0.18.1, whose `ttr-lexicon` reads `ttr-lexicon-compiled/v6`: concept entries under `cn.*` refs, the reverse index
  (`liftsTo`) on er and md elements, and the default sense on rows. lex-matcher serves a concept's terms as declared
  vocabulary under the concept's ref, as it serves an er object's. `fuzzy.match:v1` and Lookup keep their shape, and
  narrowing a match to a concept's ref works. A v5 archive still loads, with the schema-version WARN. When two archives
  carry the same ref, the merged layer now keeps its concept facts (`stereotype`, `erTarget`, `via`, `ofRef`) and the
  union of its `liftsTo`; before, it dropped them. **Rollout:** these readers ship before any estate rebuilds its
  archive as v6.
- **resolver — concepts resolve, lift and gate** (CN-P4). On an estate with a `model cn`:
  - A term that names a concept binds the concept. Its values gate as the concept's ER landing's would: "Praha"
    under "zákazníci po splatnosti" is the customer address's city.
  - An element or member hit lifts to the concepts grounded on it: its own ref, a member's attribute and that
    attribute's entity. A lift never crosses a relation or lands on a phase.
  - A span with several readings follows the cn resolution order. A phase yields to its bearer, then the default
    sense, then `sense_priority`. A concept and an element it grounds to bind the concept; one it does not ground to
    is asked about, concepts first.
  - `resolver.v1.EntityBinding` gains `concepts[]` (`ConceptReading`: `concept_qname`, `stereotype`, `via`,
    `lift_provenance[]`, `note`). This is additive; `resolve.bind:v1` JSON carries it as `concepts`.
  - A concept binding's `domain` names the concept's ER landing, never the `cn.*` ref. So does a clarification
    option that offers a concept (`object_kind` `concept`), and picking it binds the concept reading. A concept with
    no landing carries the advisory note `cn/ungrounded` or `md/er-unmapped`; its values resolve as open values.

  An estate without a `model cn` resolves exactly as before.
- **query — the stated layer is part of the compiled-plan cache key** (review-123 R3). The cache keyed a plan by
  model version, text, language and parameter signature. The detector decides the layer with `source_schema`, so one
  text stated DB and then ER was served the first caller's plan. The key now includes `source_schema`.
- **query — a statement over `obj.query` and `db.dbo` states no layer** (review-123 R9). The edge stated ER for any
  `obj.…` name. A saved query can read either layer, and the door picks the layer its body reads, so stating ER
  refused a DB-lane saved query joined to a table as mixed. The door now decides.
- **query chart — the receipts password Secret is optional** (review-123 R4). A missing `passwordSecret` held the
  query container in `CreateContainerConfigError` and took the whole door down, although receipts are best effort.
  Now the service starts, logs `Flight edge: receipts off (no QUERY_RECEIPTS_DB_PASSWORD …)` at WARN, and runs every
  statement without a receipt. A receipts host with an empty password now means receipts off.
- **query — the Flight SQL edge reads a quoted dotted schema as its two parts** (#275). The catalog lists each schema
  as one name (`er.entity`, `db.dbo`, `obj.query`), so a JDBC tool that writes SQL from it quotes the name:
  `SELECT t.* FROM "er.entity".warehouse t` (DataGrip, IntelliJ, DBeaver opening a table). The door read `"er.entity"`
  as one identifier and refused it (`translator_rejected: Object 'er.entity' not found`). The edge now unfolds it, at
  the head of a name or after the connection's own catalog: `"er.entity"` becomes `er."entity"`. The text keeps its
  length, so a refusal's position (`x-ttr-line` / `x-ttr-column`) still points into the client's text, and the
  namespace stays quoted (a reserved word such as `db."user"` still parses). A quoted name the statement defines itself
  (`AS "db.w"`) is left alone. The edge's tokenizer also treats `//` as a comment, as the door's parser does, and treats
  a comment between a name's parts as whitespace, so the world check sees `hartland_us/* */.er.entity.x`. A new spec
  runs the edge's text through the translator itself and checks the object each name resolves to.
- **`ghcr.io/collite/superset` — the operated Superset image** (BI-3, C-18): official Superset 6.1.0 + `psycopg2-binary`,
  `authlib` and `ttr-sqlalchemy` 0.11.0, all pinned; no configuration baked in. Tag-only lane
  `superset/v<superset version>-ttr<N>` (`superset/v6.1.0-ttr1` → `superset:6.1.0-ttr1`). `images/superset/README.md`.
  Since then (review-123 R12–R15): the base is pinned by digest, and what the three packages pull in is held to
  Superset's own pins, so a rebuild resolves the same set (identical to `6.1.0-ttr1`'s, so no new tag). CI builds
  the image on any change to it. The release refuses a `superset` version that is not `<x.y.z>-ttr<N>`.
  `compose/superset/` now builds on this image rather than repeating its install (`SUPERSET_IMAGE` build arg; a wheel
  in `wheels/` still replaces `ttr-sqlalchemy`; the `TTR_SQLALCHEMY` build arg is gone).
- **query — the Flight SQL edge states the layer a statement names** (BI-0 memo S-8, found live on the first deploy).
  The edge sent statements with `source_schema` unset, and the door's detector resolves bare table names only, so
  `SELECT … FROM db.dbo.warehouse` (or `er.entity.warehouse`) was refused `detection_failed` ("warehouse exist in
  multiple schemas (db, er)") on any model whose entities share names with its tables. Now `er.…` / `obj.…` names
  state ER, `db.…` names state DB, and a statement naming both `er` and `db` states ER (refused as mixed: see
  `docs/jdbc.md` for which code). The stated layer is the statement's, so its bare names are read in it too
  (`db.dbo.inventory i JOIN warehouse w` reads the table). Only a statement with no qualified name stays unstated. The
  schema probe for a logical query's columns goes the same way.
- **`ttr-sqlalchemy` — a new PyPI wheel: a DB-API driver and the `tatrman://` SQLAlchemy dialect** (BI-2) over the
  Flight SQL edge, for Superset, pandas and anything that takes a SQLAlchemy URL. Python ≥ 3.10, SQLAlchemy 1.4 or
  2.x, pyarrow ≥ 16; no gRPC stubs (it speaks Flight SQL through `pyarrow.flight`). Tag lane
  `python-sqlalchemy/v<x.y.z>[-RELEASE]`. Guide: `shared/libs/python/ttr-sqlalchemy/README.md`.
  - `tatrman://<host>[:<port>]/<world>?tls=…`: one URL = one world; `connect_args` `token`, or `user` / `password`
    (exchanged for a bearer at connect).
  - Introspection from the edge's catalog only: schemas `er.entity` · `obj.query` · `db.dbo`, entities and bound tables
    as tables, saved queries as views, Arrow-typed columns, primary keys, foreign keys from relations with join pairs.
    Every identifier quoted; a dotted schema as two names (`"er"."entity"."warehouse"`). Each relation is its own
    foreign key: two relations to one table are two keys.
  - `:name` parameters are written in as literals (the edge takes no bound parameters); a name with no value is
    refused `parameter_unbound` before anything is sent.
  - A result is read whole before its first row, so a stream the edge fails on its receipt (`world_mismatch`) yields
    no rows. The cursor carries the closing metadata: `correlation_id`, `connection_id`, `top_n_applied`, `notices`.
  - Refusals keep the edge's code and sentence (`err.code`, `err.message`) under the PEP 249 classes.
  - **Superset's engine spec** (`superset.db_engine_specs: tatrman`, tested on Superset 6.1.0). A Tatrman database
    reads as its viewer: the viewer's token comes from Superset's per-database OAuth2
    (`DATABASE_OAUTH2_CLIENTS["tatrman"]`, Keycloak's authorization code; `issuer` derives the endpoints). A database
    with impersonation off, or with no client to get a token from, refuses to connect (`BI_REQUIRE_IMPERSONATION =
    False` lifts that, for a local trial). An answer the door's row cap cut is refused as a `RESULT_TOO_LARGE_ERROR`
    warning naming the cap. Time grains are day, month, quarter and year, with no week yet.
    Every alias is quoted; refusals map to Superset's DB-API errors with the edge's code and sentence.
    `docs/superset.md` is rewritten to match; `compose/superset/` runs it locally (not a production topology).
- **query — an Arrow Flight SQL edge for BI tools** (BI-1). Opt-in, port 7308 (`QUERY_FLIGHT_ENABLED`), for the stock
  Flight SQL JDBC driver (DBeaver, IntelliJ), ADBC and the `tatrman://` dialect. Every statement is the door's own
  `Run` with the caller's verified identity. Client guide: `docs/jdbc.md`; operator's: `docs/operations.md` § BI.
  - **Identity:** a realm bearer (verified as query-mcp verifies one), or a user name and password the edge exchanges
    for one (Keycloak's direct-access grant, client `jdbc`). No anonymous mode; an enabled edge needs
    `QUERY_FLIGHT_AUTH_ISSUER`.
  - **Worlds:** `RunRequest.world` / `RunSavedQueryRequest.world` (field 8, additive) pick the connection through
    `QUERY_WORLDS` (`world=connection_id,…`); an unknown world is refused `unknown_world`. The edge reads the world
    from the `world` property (or the JDBC `catalog`), and refuses a statement naming another catalog
    (`world_mismatch`).
  - **Row ask:** every statement asks for as many rows as its caller's ceiling allows (`row_window.limit`
    2147483647), so it gets `VALIDATE_MAX_TOP_N` or its role's higher value; its own `LIMIT` lowers that.
  - **Catalog:** packages come from every kind it lists (entities, queries, tables, views), so a package of only
    saved queries is listed. A saved query's probed columns are kept per model version and role set, probed four at
    a time. Foreign keys carry a name per relation (`fk_key_name`). A catalog qualifier is recognised with every part
    quoted (`"hartland_us"."er"."entity"."warehouse"`).
  - **Results:** the closing metadata's `notices` carry the warnings of every batch (a worker's `blob_too_large` on
    the batch it concerns), once each; an answer with no rows still has its columns; a client that stops reading for
    5 minutes is ended `TIMED_OUT` (`client_stalled`).
  - **Receipts:** one `bi_receipts` row per statement (schema `query`, created on first use; best effort), read with
    `GET /receipts` by `query-platform-admin`. A receipt keeps the statement's notices (`notices` jsonb, the closing
    metadata's list), from migration V2.
  - **Lifecycle:** an enabled edge that cannot listen fails the service's start, and `/ready` is not ready while it
    is not running. An unread statement waits `pending-ttl-seconds` from its `FlightInfo` (not from its start).
  - **Chart:** `query.flight.*`, `query.worlds`, and the `tatrman-service` library's `ports.extra` (default renders
    unchanged). The edge is single-replica: the chart refuses `flight.enabled` with `replicaCount` above 1.
- **The door's small contract drift, fixed** (BI-1, C-10/C-11):
  - **query — `parameter_unbound` is emitted.** `Run` (and so `RunSavedQuery`) refuses a parameter the plan reads
    with no value, before dispatch; it used to bind NULL and answer zero rows. An explicit NULL (`is_null`) is a
    value. `Compile` still reports such a parameter in `required_parameters`. The proto now says the SQL spelling is
    `{name}`.
  - **workers — `ExecutionOptions.row_limit` is exact** (Postgres, MSSQL, Polars). It was checked per batch, so a
    limit below the batch size let the whole batch through.
  - **workers — an empty result names its columns** (Postgres, MSSQL): the tail of a stream that sent no data batch
    carries a zero-row batch of the statement's schema in `arrow_ipc`, as Polars already sent one. After data
    batches the tail stays empty.
  - **validate — `VALIDATE_MAX_TOP_N_BY_ROLE`** (`role:rows,…`) raises the row ceiling for callers holding a role;
    the default stays everyone's. A role name may contain `:` (the rows follow the last one). A malformed entry fails
    the start.
  - **wire — `ResultBatch.notices`** (field 9, `Notice{code, requested, applied}`): query puts `top_n_applied` there as
    well as in `messages` when the cap cut the answer. `requested` is 0 when the caller stated no number: no row
    window, or the largest one.
  - **translate — readable refusals.** Every refusal is one line without Calcite's exception class, position prose
    or repeats (`Object 'warehouse' not found within 'er' — did you mean: …`); the position moves to
    **`ResponseMessage.line` / `column`** (fields 5/6, additive), which query keeps on `translator_rejected`. "Did
    you mean: ?" with nothing to suggest is gone.

- **translate — a bare `JOIN` that stays a Cartesian product is refused** (#232, part 2). On `ttr-translator` /
  `ttr-plan-proto` **0.12.6**, translate sets its `refuseUnconditionedBareJoins` on `ParseToRelNode`, `Translate` and
  `Explain`. (0.12.5 must not be run with the option: it turned a WHERE filter reading both sides of an FK-only bare
  join into the join's whole condition, dropping the foreign key; ttr-core#223.)
  - A SQL `JOIN` with no `ON` that the model cannot condition fails with `join_unconditioned`. That covers a join with
    no relation, more than one relation, or one whose relation declares no join pairs, when no table foreign key
    completes it either. The message names the sides: `a JOIN without ON that the model cannot condition: no relation
    or foreign key joins entity.date_dim and entity.item; write the ON`.
  - It is decided per join where the physical stage runs. With a `DB` target, that is the call itself. On query's two
    calls, the ER half answers its plan with the join marked (`JoinNode.condition_required`), and the DB re-entry
    refuses it. validate's ER pass keeps the mark through a row policy or a mask.
  - **The switch:** `translate.refuse-unconditioned-bare-joins` (env `TRANSLATE_REFUSE_UNCONDITIONED_BARE_JOINS`),
    default `true`. `false` is the behaviour before: the join runs, with its `JOIN_NO_RELATION` warning.
  - **Explain** shows the refusal the parse gives, where it showed the Cartesian plan the parse refuses.
  - query answers it as `translator_rejected` with that text. query-mcp `compile` answers it with the code itself.
  - **Not refused:**
    - the comma list and `CROSS JOIN`, the author's own Cartesian products, which keep their `JOIN_NO_RELATION`
      warning;
    - a bare join a table FK conditions;
    - a bare INNER join its `WHERE` conditions: a top-level comparison between its two sides (`=`, `<`, `>`, …; not
      `<>`, not an `OR`). Its plan is the one the switch off gives, so an FK-only relation keeps its foreign key;
    - a plan with no mark, such as `RunSavedQuery`'s stored plan.
  - veles parses saved queries with the translator's default and is unchanged; its own join check stands.
  - **validate — a mask no longer gives a join with no condition an empty one.** The mask rewrite set every join's
    condition, so a Cartesian product or a marked bare join came back from validate's ER pass with an empty condition,
    which the DB re-entry cannot decode: with any MASK rule such a statement failed `rel_node_schema_resolution_failed`
    (an FK-only bare join, which the re-entry would have conditioned, included).
  - **Deploy: restart query after translate rolls out.** query's compiled-plan cache keys carry no translator
    version, so for up to an hour (its expire-after-write) a cached plan from the previous translate keeps running:
    a bare join it ran unrefused still runs.

- **translate, query, query-mcp** — a join the model cannot condition is no longer silent (#232). The translator leaves
  it a Cartesian product with a warning, and translate used to answer a successful parse with the plan alone.
  - **translate**: `ParseToRelNode` and `Translate` now carry the translator's join warnings in `messages`, in its own
    code and text: `JOIN_NO_RELATION`, `JOIN_AMBIGUOUS_RELATIONS` and `JOIN_KEY_NAME_COLLISION` at WARNING,
    `JOIN_RELATION_WITHOUT_PAIRS` (a join left to its foreign key, which conditions it) at INFO.
  - **query**: the WARNINGs on the plan that runs reach the caller, from the DB parse (on the ER path, the DB re-entry).
    `Run` puts them on `context.warnings` (`source_service = "translate"`, `source_stage = "expand_joins"`), and
    `Compile` puts them in `messages`. A cached plan keeps them, so a cache hit says what the miss said. The INFO is not
    passed on: that join was conditioned, and on a model whose relations declare no join pairs it would be said of
    every join.
  - **query-mcp `query` reads `pipelineWarnings` from every batch it collected**, each warning once, where it read the
    last batch alone. The notes a hop makes on the stream ride its first batch: query's compile, cache and join
    notes, dispatch's, and the worker's unsupported-type warnings. So an answer of more than one batch lost them all,
    and an answer cut at `row_limit`, the likely shape of a Cartesian product, has no last batch to read.
  - **query-mcp `compile`** puts the parse's join WARNINGs in `messages` (`severity: "warn"`) beside `ok: true`, where
    it used to answer `messages: []`.

- **query** — a SQL text that reads a saved query by name (`obj.…`) is no longer cached. Translate inlines the read
  query's stored plan as it parses (since the run-time fill-in), so the cached plan froze the body as of that model,
  and the cache key's model version (often unset by callers) does not move with a model swap: a changed, FAILED or
  removed read query kept being served until an operator refresh or the TTL. Such a text is recompiled every run, like
  an expanded logical body. `RunSavedQuery`'s stored plan is keyed by its own bytes and stays cached.

- **query — `RunSavedQuery`** (new RPC, LQ-T7·S2, L-31): run a saved query by name, from the plan veles stored when it
  parsed it. The request is `RunSavedQueryRequest` (`qualified_name`, `context`, `execution_options`, `bypass_cache`,
  `row_window`). There is no re-parse of the query's text, and a query it reads is already inlined.
  - The stored plan joins `Run` as a `REL_NODE` source in its own lane. A logical query (`er.query.<name>`) takes the
    ER half: Validate pass 1, MAP_TO_PHYSICAL, then Validate pass 2. A db query takes the DB half. The caller's roles,
    row policies and row window apply exactly as for `Run`, and `context.parameters` bind declared parameters by
    name (`parameter_unbound` from Validate otherwise).
  - Refusals come as an error batch: `saved_query_not_found`, `saved_query_pending`, `saved_query_parse_failed`, and
    `saved_query_unavailable` when no metadata service is reachable.
  - Entitlement is Validate's alone for now. Nothing calls it yet; it is for automation agents and workflows.
  - query now reads veles' `GetQuery` for it: `veles.host` / `veles.port` (`VELES_GRPC_HOST` / `VELES_GRPC_PORT`,
    default `veles:7261`, the in-cluster service name).

- **metadata (veles), translate, query** — pins `ttr-translator` / `ttr-plan-proto` **0.12.4** (LQ-T6, L-30): a logical
  query read by another one is a temporary entity whose relations follow its columns. `FROM obj.query.spend_per_shopper
  r JOIN shopper`, where the query passes `sale.shopper_id` through as its `GROUP BY` key, is conditioned through the
  relation `sale → shopper` it inherits, FK-only relations included; veles stores it PARSED where it refused it
  (`logical_query_join_unresolved: JOIN_NO_RELATION … a derived table`). A query that passes no attribute through
  inherits nothing and is refused as before. An FK-only join to an entity stacked on a `GROUP BY` query is conditioned
  too (ttr-core#204). Every other join is decided exactly as on 0.12.3.

- **metadata (veles) — the grammar bundle 0.16.0** (`ttr-parser` … `ttr-metadata`, `ttr-snapshot`, `ttr-lexicon`). A
  model may now carry TTR-M 0.16: `model automation` files, the automation members of a `def world` (`def identity`,
  `def grant`, `def deployment`, `def process`), `writable:` on a world storage and `states:` / `transitions:` on an
  attribute. Veles loads them instead of refusing the file; the automation objects are the `automation` tier of the
  snapshot (not yet served over gRPC). A malformed grant or deployment is dropped with a load error
  (`ttr/automation-grant-dropped`, `ttr/automation-deployment-dropped`). A model without automation files loads as
  before.
- **translate** — at run time a saved query can read another one (LQ, L-28). The handle translate parses with now
  serves every PARSED saved query under its obj name (`obj.query.<name>` for a logical or `model query` query,
  `obj.<schema>.<name>` for a db query), with its stored plan as the body, as veles' worker does during its
  dependency-ordered parse. A query's text sent through `query.Run` (a Golem pattern, the lattice, free SQL) that
  reads another saved query therefore parses, with the body inlined from the stored plan: validate sees the tables
  and entities it really reads, so row policies and masks apply. Before, it failed `Object '<name>' not found within
  'obj.<namespace>'`. A FAILED or PENDING query has no stored plan in the snapshot and is not served; text that names
  no saved query parses byte-identically. Schema detection leaves `obj.*` references out of its verdict (the stated
  schema stands; tables and entities beside them still vote); it reported each one `schema_object_unknown`, and
  `query.Run` refused the text before the parse. `/status`'s `saved_query_count` now counts the queries served under
  `obj.query`; it was always 0.
- **metadata (veles)** — a saved query with a string literal outside Latin-1 (`'Poštovné'`, a `TRANSLATE` over `čšž`)
  parses again. Since the dependency-ordered parse, the worker's first Calcite use in the process was
  `Translator.savedQueryReferences`, which on 0.12.1 (unlike the parse entry points) did not promote Calcite's literal
  charset to UTF-8 before Calcite freezes it, so Calcite stayed on ISO-8859-1 and every such body was FAILED: `Failed to
  encode … in character set 'ISO-8859-1'`, or a validation error naming none of it (`Cannot apply 'CHAR_LENGTH' to
  arguments of type 'CHAR_LENGTH(<UNKNOWN>)'`, when the typed-placeholder retry hit the literal). Pins `ttr-translator`
  / `ttr-plan-proto` **0.12.3**, whose `savedQueryReferences` promotes the charset first, and the worker promotes it
  itself before anything else; a refresh or a restart did not help, as every start ran the same order.

- **metadata (veles)** — a logical query whose bare `JOIN` the model cannot condition is refused:
  `logical_query_join_unresolved`, carrying the translator's joiner text (`JOIN_NO_RELATION`,
  `JOIN_AMBIGUOUS_RELATIONS`, `JOIN_KEY_NAME_COLLISION`). The translator leaves such a join unconditioned and warns;
  the worker stored the plan anyway, so an entity stacked on the query answered every reader with the Cartesian
  product, and nobody was told. An FK-only relation (`JOIN_RELATION_WITHOUT_PAIRS`, INFO) is conditioned from its
  foreign key at MAP_TO_PHYSICAL, so a logical query's body is also taken to the physical level once at parse time:
  one foreign key between the tables parses; two (`JOIN_AMBIGUOUS_RELATIONS` there) are refused the same way. A db
  query is not checked: its joins are its author's SQL (a comma join conditioned in WHERE, a deliberate CROSS JOIN),
  which model joins do not touch. Found by the independent review of the logical-queries adoption.

### metadata / query / validate — logical queries: saved queries over entities, entities stacked on them

A saved query declared in a `model er` file is a **logical query** (`er.query.<name>`): written over entities —
entity and attribute names, a bare `JOIN` conditioned from the relations — and usable through an entity stacked on it
(`binding: { target: { query: er.query.<name> } }`), whose attributes bind the query's output columns. Pins
`ttr-translator` / `ttr-plan-proto` **0.12.0** and the grammar bundle (`ttr-metadata`, …) **0.15.2**, where the
lane, the strict parse and the expansion live.

- **metadata (`veles`) — each saved query parses in the lane it is declared in.** A logical query is stored at the
  ER level (entity scans kept, bare joins conditioned); every other query in the db lane, as before. Both parse
  strictly, so a body that reads the other lane, or both, fails by name: `lane_mismatch` (a db query reading
  entities), `mixed_lanes` (entities and tables in one body), `logical_query_parameters` (a logical query takes
  no parameters). The codes land in `GetQuery` / `ListQueries`' `parse_error_message`. The worker reads the
  model through the query path's own adapter (`SnapshotModelHandle` over the model's snapshot), so a query parses
  against what the translate service sees. A db-lane query that parsed before stores the same plan. Two failures
  read differently: a body naming an unknown table is `lane_mismatch: … neither entities nor tables: <name>` (with
  a did-you-mean) where it was `validation_failed: … Object '<name>' not found`, and a saved query that reads no
  table at all (`select 1`) is refused (`lane_mismatch`) where it parsed.
- **metadata — an entity stacked on a query is checked against the query's output.** Each attribute's bound column
  (or its own name, when unbound) must be an output column of the stored body; one that is not is reported by
  `GetStatus` as a WARNING `entity_query_output_mismatch` naming the entity, the attribute, the column and the
  body's columns. The query itself stays PARSED.
- **metadata — `GetSnapshot` and `GetObject` serve relations with their detail** (`RelationDetail`: the entities and
  the `join:` pairs); they were descriptor-only. The translator conditions a bare entity `JOIN` from these, so a
  join onto a stacked entity — which has no foreign key, only `join:` pairs — is conditioned. A relation bound to
  a foreign key carries no pairs and its joins are conditioned from the key as before; an estate whose relations
  declare `join:` pairs now has its bare joins conditioned from them at the ER level.
- **metadata — the `GetSnapshot` ETag names the serving process** (`<version>.q<n>.<epoch>`): a restarted server
  settles on the same version and generation, and a consumer holding the previous process's snapshot was told
  "not modified". The ETag stays opaque.
- **translate — `ParseRequest.expand_logical_bodies = 6`, `TranslateRequest.expand_logical_bodies = 8`** (wire,
  additive). Set on a `target_schema = ER` parse, the ER half expands every entity stacked on a logical query in
  place: a boundary Project stamped `expanded_from = <entity>` over the query's body. A no-op for a DB target.
- **translate — a TransDSL `query_ref` to a logical query is refused** (`query_ref_logical_query`): a logical
  query is read through the entity stacked on it, and its ER-level plan has no physical form on that path.
- **query — the ER half of the two-pass path asks for the expansion** (`Run`, `Compile`). The DB re-entry closes
  the boundary itself: the physical plan is the one the unexpanded path produces. An ER plan carrying an expanded
  body is not cached — the body would freeze in it, while unexpanded every run's re-entry reads it from the live
  model — so a query over a stacked entity recompiles on every run. The one-pass DB path and query-mcp's `compile`
  are unchanged.
- **validate — pass 1 secures a stacked entity and the entities its body reads.** A policy keyed on
  `er.entity.<x>` matches a scan of `x` and a boundary stamped `expanded_from = x`; its Filter goes directly above
  the matched node, and a node is wrapped once (an entity read inside a body and at top level is two nodes). The
  receipt (`security_applied`) names the policy's own key — the stacked entity for a boundary match.
  `used_objects` lists the stacked entity beside the entities its body reads. The mixed-layer check is unchanged:
  an expanded body is pure ER.
- **validate — column rules (DENY / MASK) keep the ground they had before the expansion.** A stacked entity's
  boundary stands where its scan stood — its attributes are the columns it exposes — and the logical query's body
  under it is neither read nor rewritten by them: a MASK on a stacked attribute masks the consumers above the
  boundary (so a row policy placed above it still filters on the real value), and a rule on an entity the body
  reads does not reach through the stacked entity.

### metadata — a saved query can read another saved query (`obj.<namespace>.<name>`)

A SQL body names another saved query as `obj.<namespace>.<name>`: `er.query.x` (a logical query) and `query.x` (a
`model query` file) are `obj.query.x`, a db query `db.<schema>.x` is `obj.<schema>.x`. Pins `ttr-translator` /
`ttr-plan-proto` **0.12.1** (the reader's row type comes from the referenced query's stored plan; references are
extracted before parsing; a referenced body with parameters is refused) and the grammar bundle (`ttr-metadata`, …)
**0.15.4** (a `def query` in a `model db` file loads as a db query instead of `ttr/wrong-file-kind`, and so do a
`drill_map` there and an `er2cnc_role` in a `model cnc` file; `def area` and the `def project` header are legal
beside any `model` directive).

- **metadata (`veles`) — saved queries parse in dependency order.** A query parses after every saved query it reads,
  against a handle that serves their stored plans; the reader's stored plan has those bodies inlined, so nothing at
  run time looks a saved query up. The lane is decided after inlining, as before: a logical query may read a logical
  one; reading a db query is `lane_mismatch`. Refused, each naming what it read: `saved_query_unknown`,
  `saved_query_ambiguous` (two saved queries share the obj name — e.g. a logical and a `model query` query of one
  name), `query_reference_cycle` (with the path), `saved_query_failed` (with the referenced query's failure) and
  `saved_query_parameters`. A model whose bodies read no saved query parses exactly as before, in one pass.
- **`translate-snapshot`** — `SnapshotModelHandle.from(snapshot, savedQueriesAsObj = false)`: with `true`, every
  parsed saved query is also served under its obj name (a name two queries share under neither). Only the metadata
  worker sets it; the translate service keeps the default, so a saved query is never run by name (LQ-6).

### resolver + lex-matcher — several declared lexicon archives, loaded as one layer (#209)

An estate whose packages come from two sources (Veles' `METADATA_GIT2_*`) got a lexicon for only one of them: both
services read ONE compiled archive, so the second source's words bound nothing. A word every reading of which lies in
another area's package then looked exactly like an unknown word, and an agent asked the user what it meant instead of
refusing it as another area's question.

- **A list of archives.** The resolver takes `resolver.lexicon-archive-paths` (`RESOLVER_LEXICON_ARCHIVE_PATHS`) and
  lex-matcher `fuzzy.lexicon.archive-paths` (`FUZZY_LEXICON_ARCHIVE_PATHS`): comma-separated from the environment, or a
  HOCON list. The singular keys (`RESOLVER_LEXICON_ARCHIVE_PATH`, `FUZZY_LEXICON_ARCHIVE_PATH`) keep working as a
  one-element list. When the list is set it is the whole answer; a singular path it does not name is reported at
  startup and not read.
- **One declared layer, exactly as one build.** The archives merge by the compiler's own rule: one row per (normalized
  term, lang, target ref), DECLARED over METADATA, then the widest method, then the earliest provenance — the same table
  one build over all of the packages would write, with the same entry-table hash (`lexicon_artifact_hash`). A form two
  packages declare for two objects gets both readings. The stdlib rows every archive embeds (operator words, grounding
  and predicate triggers) are served once, so they add no duplicate candidate and move no score. Per-ref facts
  (`targets`) are copied; a ref in two archives takes the union of its `reachedFrom`. A fact the archives disagree on
  is WARNed and the first archive's value is kept. That includes a `reachedFrom` entry's `mandatory`, which the
  Binder's T3 rule decides on.
- **The snapshot hash covers the archives served.** With several archives the archive hash (the resolver's registry
  `snapshot_hash`, lex-matcher's reload clock) is a hash over the ids of the archives actually served, in declared
  order, so a change to any one is a new snapshot and lex-matcher's refresh picks it up. One served archive keeps its
  own id, as with one archive configured.
- **The first archive is the primary; the others degrade alone.** The primary keeps the single-archive posture: absent,
  unreadable or not a lexicon, it is WARNed and the layer keeps what it had loaded (nothing, at boot). An additional
  archive that is absent, unreadable, not a lexicon, undecodable, or declares a different `schemaVersion` from the
  primary's is served from its last good copy when it has one (WARN — a transient failure drops no served word), and
  otherwise skipped with an ERROR naming it and the reason. A broken or drifted additional archive costs only its own
  words. Each new layer logs `serving N of M lexicon archives` (with `skipped: …` when degraded), and lex-matcher
  exports the gauge `fuzzy_lexicon_archives_skipped`. With additional archives configured, a primary that cannot be read
  at boot is an ERROR naming it and every additional archive it leaves unserved (none is served without it), and the
  gauge counts those. The load lines count and name the archives merged, never just the configured ones:
  lex-matcher's `Compiled lexicon loaded: … from N of M archive(s)` and the resolver's
  `declared vocabulary from N of M lexicon archive(s) [...]`, each with `; skipped: …` when degraded.
- **One merge, in two services.** The merge lives in each reader as the same file (RS-24: no service-to-service
  import). The resolver's `verifyDeclaredLayerTwin` task, part of `check`, fails the build when the two copies differ
  in anything but their `package` line. Collite/ttr-core#190 asks for the merge in `ttr-lexicon` itself, beside the
  compiler's precedence rule.
- **Charts.** The `tatrman-service` library mounts `lexicon.configMapNames` beside `lexicon.configMapName`: the first
  ConfigMap where it always was, each further one at `<mountPath>.d/<configMapName>`. The resolver and lex-matcher charts
  then render the singular env var naming the FIRST archive — so an image older than this change still boots on it
  during a rolling deploy — and the list var naming all of them. A single-ConfigMap render is byte-identical to before.
- Unchanged: lex-matcher's `overlay-archive-path` (the LEARNED layer) stays its own source. The grounding kernels
  (chrono, money, geo) still read one archive: they read only the `ground:` slice, which every archive embeds from the
  stdlib.
### resolver — coordinated places take the reading they share: „Tržby pro Brno a Liberec“ is two stores (ttr-demo#62)

The coordination ruling — of two mentions joined by „a“ / „nebo“ / "and" / "or", the ambiguous one takes the reading
where they intersect, and nothing is asked — covered mentions of model objects only. On the Hartland demo's Czech world
„Tržby pro Brno a Liberec v roce 2025“ read `Brno` as the distribution centre "Brno DC" (the all-channel revenue's fact
reaches the warehouse, not the store) and left `Liberec` a bare place with a G3: the question could be answered as Brno
DC's revenue alone.

- **A place coordinated with a governed place is governed by the same anchor.** `Liberec` hangs on `Brno` by `conj`,
  not on `Tržby`, so it was nobody's governed value. A conjunct the NER typed a place or a person is now proposed as the
  anchor's governed pair, with the same scope, exactly like the place it is coordinated with — when the anchor took the
  value it is coordinated with. In „Tržby za elektroniku a Brno“ a measure takes no `elektroniku`, so `Brno` stays the
  bare place it was (a G3, or what a named-member profile reads it as).
- **The conjuncts meet where their readings intersect.** A conjunct's readings are what its own verdict admits with no
  scope applied: each half of its span gated on its own rows, as the broad pass gates it (never one tie band over two
  lookups' scores). One member entity and nothing else is one reading; several entities, or a declared model object
  tied with the value, make the conjunct ambiguous, and an ambiguous conjunct never imposes its entity on the others.
  When one conjunct has one reading, another has several, and the one is among the several, both keep that entity's
  members in place of their scope: `Brno` and `Liberec` bind `store.store_name`.
- **Conjuncts that already agree take their entity too** (ruled 2026-10-16). Two or more conjuncts with the same one
  reading keep its members even where the measure's scope does not reach it: „Tržby pro Liberec a Olomouc v roce 2025“
  (both only stores) binds two stores, where it was two G3s.
- Otherwise nothing changes — both ambiguous with the same readings („Brno a Plzeň“: each a store and a DC, read as
  two DCs by the measure's scope), unambiguous conjuncts that disagree, an empty intersection, a conjunct with no member
  reading — and the scope reads each conjunct as before (the Binder asks where it still ties).
- **An entity governor keeps its own scope.** The rule overrides only a scope the sentence did not name (a measure's
  fact); „Tržby prodejen Brno a Liberec“ reads both by the store word, as before, and now binds `Liberec` too. An anchor
  word declared for several entities governs too: it named an axis, only an ambiguous one.
- **Downstream.** Two `store.store_name` members under the all-channel revenue, whose fact has no relation to the
  store, is the intended reading: the query door finds no such relation and the question goes to the composer, never to
  a fast-path number.
- **Deploy with the query door's fix for several members of one attribute.** A coordination now often binds two
  members of the same attribute in one gap-free lattice („Tržby z prodejen pro Brno a Liberec“: two `store.store_name`
  under the store channel's revenue). The consumer's fast path must read them as one restriction (`IN`, one row per
  member), not as two equalities joined by `AND`, which match no row. Do not deploy this resolver ahead of that fix.
- Unchanged: a lone place („Tržby pro Brno“ → "Brno DC"). „Tržby v Brně a Liberci“ still reads "Brno DC" and leaves
  „Liberci“ a place: the store names are matched `TYPOS(1)` on the written form, and „Liberci“ is two edits from
  "Liberec", so no member row reaches it and there is nothing to intersect.
- No wire change.

### resolver — an adjective in front of a declared phrase is a mention of its own: „Měsíční“ is the month, never dropped (#237)
### resolver — a word in front of a declared phrase is a mention of its own: „Měsíční“ is the month, never dropped (#237)

„Měsíční tržby z tržiště v roce 2025“ came back with `tržby z tržiště` (the marketplace revenue measure), `roce` and
`2025` — and nothing for „Měsíční“: no mention, no gap. A caller reading the lattice answered one total for a question
asking for months. A declared multi-word phrase names its own extent, so the adjective in front of it was in no span,
and the mention layer headed its leftovers at nouns only. „Akční tržby z tržiště“, "Promotional marketplace revenue"
and "Stores with no marketplace revenue" lost their restriction the same way, and so did a modifier under a governed
value or a proper-noun run.

- **One rule for the words a mention stands without** (`SpanProposal.detachable`), read by the retry (#204) and by
  the new path alike: a head's determiners, its BARE adjectives and their conjuncts („Měsíční a čtvrtletní“). An
  adjective with words of its own stays: what it governs is a mention or a value of its own, and "How many" / "How
  much" is the count the slot hint reads. A word that restricts nothing vanishes by one test too
  (`FrameRolePreps.restrictsNothing`): the `silent-modifiers` list, and now the `count-heads` (`many`, `kolik`).
- Such a word in front of a declared phrase — in no span, hanging from a word a gated span covers — is proposed as an
  unbound mention over its own words, flagged `DomainSpanCandidate.leftOut`; the lookup round asks it like any leftover.
- **What it may bind is one rule too** (`MentionLayer.bindsModifier`), applied by the lookup round and by the re-gate
  (`resolve.gate:v1`): an OPERATOR („Průměrné“ → `op:avg`), or — for a calendar adjective — an ATTRIBUTE, declared so
  or of no declared kind, which R6" makes the GROUPING. Only a row that is the word's whole form: a partial match of a
  longer term („Dnešní“ ~ `není skladem`, the stock measure; „měsíčních“ ~ `po měsících`) is no reading of it. An
  adjective that reads as any other attribute stays a G1: no frame rule places it, and a bound mention with no role is
  one no consumer reads. A refused row is recorded on the gap as a hypothesis tried.
- R6" reads the calendar adjective in an adjective's place (`amod`, or a conjunct of one), by its surface as well as its
  lemma, against a `grain-adjectives` table that now holds Czech stems (`měsíčn*`): an inflected form whose lemma the
  analyser left blank still groups. It also fires on an attribute whose kind the registry did not declare.
- A calendar adjective is never offered to the grounding triggers, declared word or left out: „Čtvrtletní“ is one edit
  from the chrono trigger `čtvrtletí`, „Pololetní“ from `pololetí`, and neither names a period.
- A left-out mention is never the SUBJECT (R9: `FrameRoles.Input.subject`). The lattice names the left-out mentions in
  one rung-log entry, `{rung: "core", action: "left-out-modifier", mention_ids: [...]}`, which is what the re-gate reads.
- First-person possessives join `silent-modifiers` (cs `naše` and its forms, en `our`): with determiners left out
  too, „naše tržby z tržiště“ would otherwise ask what `naše` means.
- On the Hartland demo estate as of ttr-demo#74, which declares the Czech grain adjectives in every case form,
  „Měsíční“ binds `date_dim.month` as a declared word; on an estate that declares the grains only as adverbs, the
  lookup round binds it through `měsíčně`. „Denní“ and „Pololetní“ (no day or half-year grain) and „Akční“ /
  "Promotional" are G1s.
- Unchanged: a word in an operator slot that is no operator (`dalších 10 prodejen`, #58); a word a quoted literal hangs
  from; a universal-typed word („Letošní“, "Last year's"); an adjective inside a one-word anchor's own phrase.
- Known limits: `Resolution` (the pre-lattice outcome) carries no binding a lookup round makes for an unbound mention —
  a leftover or a left-out word; the lattice does. No consumer in this repository reads `Resolution.bindings` (golem-py
  reads `resolution_state` and `awaiting`). An adjective whose only words of its own are adverbs ("very large") is not
  proposed.
- No wire change: the rung-log action is a new value of an existing string field.

### resolver — a store word reads by its role: a filter asks, a word that governs a store name is the dimension (ttr-demo#62)

Where the lexicon gives one word two readings — a dimension (`er.entity.store`) and a fact a channel term is pinned to
(`er.entity.store_sales`) — the sentence's slot decides. Counting and grouping already chose the dimension and comparing
chose like with like. Two places did not follow the ruling:

- **A filter now asks.** „Tržby prodejen v roce 2025“ / "Revenue of stores in 2025" bound the channel without a question,
  because a filter under a measure head preferred the fact. A filter no longer decides a dimension-vs-fact tie (the fact
  itself or one of its measures): the mention keeps both readings, a `GAP_KIND_G2_AMBIGUOUS`, and the clarification
  offers both, each with its `object_kind`. The reachability rule still runs, so a reach the model declares can still
  prove the readings equal (collapse to the dimension, `equivalents`) or the fact the wrong one. A filter tie with no
  dimension in it (an attribute and a measure) is decided as before. Declared phrases („tržby z prodejen“, "store
  revenue") never reach the tie: the phrase is one candidate with one target.
- **A word that governs a value names the object with members.** That slot rule looked for the value's
  `anchorHeadToken`, which span proposal never sets on a governed value, so it never fired. It now reads the parse: a
  governed value whose head hangs from the span's head. „Tržby prodejny Praha v roce 2025“, "Revenue of stores in Praha"
  and "Stores in TN" bind the store dimension (the name or state binds its member as before), where the first two bound
  the channel and the last asked between the two readings. A noun hanging by `conj` is the word's sibling, not
  its value, and does not count ("Compare stores and warehouses").
- **Coordination: only an unambiguous sibling decides** (ruled 2026-10-14). Of two mentions joined by „a“ / „nebo“
  / "and" / "or", the one with two readings takes the reading they share („Srovnej prodejny a web“, "Revenue of
  stores or web": the channel; „Srovnej prodejny a sklady“: the dimension), as before. A sibling with two readings
  of its own (an entity with its own measure counts as one) now lends no kind, so nothing is preferred and the
  question asks; before, a sibling whose two readings shared a kind still decided.
- **In coordination a measure stands for its fact.** Where the store word's channel reading is the store-channel
  revenue (a `measure` of `store_sales`) and its sibling is a channel fact (`web`, `tržiště`: `entity_with_measures`),
  the two intersect on the channel: „Srovnej prodejny a web“, „Tržby prodejen a webu / webu a prodejen / prodejen
  a tržiště v roce 2025“ bind the store revenue instead of asking. A sibling that is a measure lends its fact's kind
  the same way. Two readings that both belong to the sibling's family still tie, and the tie asks. Only the
  coordination slot reads a measure this way.
- No wire change.
### resolver — a grounding kernel's question reaches the user (#231)

chrono answers `AWAITING_CLARIFICATION` when a date has two readings: a bare future month ("Marketplace revenue in
November" in October is November of this year or of last), and, with #214, the ambiguous „minulý rok / měsíc /
čtvrtletí“. The resolver dropped that answer as "not grounded", so a caller got a DATE with no range and nothing to ask.

- **Fresh:** the kernel's question becomes the resolver's own `awaiting`, under its own resume token. One option per kernel
  option: `id` `P:` + the kernel's id (`P:202511`; `@<span start>` is appended only if two spans offer the same id),
  `label` as the kernel wrote it, `span` the date it answers, `object_kind: "period"`, and the new **`Option.grounding`**
  (`kernel`, `kind`, and `normalized_value`, the kernel's interval). `target_ref`, `resolved_id`, `entity_type_ref`
  and `member_of` stay empty. The lattice carries the value as before plus a `G2_AMBIGUOUS` gap on it. The answer is an
  `awaiting` and not a `resolution`, exactly as when the gate asks.
- **Order:** when the gate also asks, both questions travel in one `awaiting` under one token. The gate's options come first,
  where they always were, then each kernel question in span order. A caller asks one span at a time, and the token redeems
  one pick per span. The MCP door's summary now lists them as successive questions.
- **Resume:** picking a `P:` option puts the kernel's question back to it with `clarification_answer_id`. The question is the
  SIGNED one: the same span, question text, earlier questions, reference date, locale and package. The resume request's own
  context is ignored, because a later reference date or a longer conversation can make the same words name another period.
  The answer is grounded through the OK path: a DATE binding in `resolution`, and in `resolution_state` exactly one value
  (GROUNDED, the interval, the anchor column as its attribution, no gaps). A caller folds it into its lattice by span.
  It never asks again. A pick the kernel will not ground is `FAILED_PRECONDITION`, and a kernel that cannot be reached is
  `UNAVAILABLE`.
- **Wire:** additive. `Option.grounding = 9`. The resume token gains `kernelQuestions` and `kernelOptionId`, which are
  encoded only when present, so a token with no kernel question is byte-for-byte unchanged. A resolver older than this one
  cannot read a token that carries a kernel question.
- **A period with nothing to count from is named.** chrono refuses an anaphoric period with no single period of its unit in
  the conversation ("the previous year" after a question naming two years) as `UNGROUNDABLE`, code `no_anchor` (#214). The
  value stays an ungrounded DATE, and the lattice now names it with a round-0 rung-log entry
  `{rung: "core", action: "ground-unanchored", value_ids: [<the value>]}`, one per value. A consumer that would read an
  ungrounded period against today must not read this one so; it asks which period is meant. Additive: a new rung-log
  action, no new field, and no entry at all on a lattice without such a value.
- **golem-py** (the reference golem) keeps an option's `object_kind`, and redeems a `period` pick through the core's
  `Resolve(resume{token, selected_option_id})`, folding the answered date into its own value by span and closing the G2.
  It used to put the pick to the gate as a hypothesis with a blank ref. A refused pick (`FAILED_PRECONDITION`) leaves the
  gap open with the user's answer recorded.
### chrono — a relative period reads by its kind: the reference date, the conversation, or ask (#214)

"Marketplace revenue in 2025" → "And the year before?" grounded to the current year, and "And the previous
year?" to the year before the reference: every relative year, month and quarter read against
`reference_datetime`, whatever the conversation was about. The scope word now decides what a year, month or
quarter is read against.

| Kind | Read against | Year | Month | Quarter |
|---|---|---|---|---|
| deictic | the reference date | this year, last year, the year before last; letos, letošní, tohoroční, loni, vloni, loňský, minuloroční, předloni, předloňský | this month, last month, the month before last; tento měsíc, předminulý měsíc | this quarter, last quarter, past quarter, the quarter before last; toto čtvrtletí, poslední čtvrtletí, předminulé čtvrtletí |
| anaphoric | the period of that unit the conversation is about; the reference date when it names none | the previous year, the prior year, the year before; předchozí rok, předešlý rok | the previous month, the prior month, the month before; předchozí měsíc, předešlý měsíc | the previous quarter, the prior quarter, the quarter before; předchozí čtvrtletí, předešlé čtvrtletí |
| ambiguous | ask when the two readings differ | minulý rok (every case form) | minulý měsíc | minulé čtvrtletí |

- **The conversation's period** of a unit is the one the nearest question naming a period of that unit names:
  `GroundRequest.question_text` first, then `GroundingContext.prior_questions`, newest first. A year is named by
  a written year ("in 2025", „v roce 2025“, "March 2025", "Q2 2025"), a month by a month with its year or a
  yearless month in a question whose year is known, a quarter by "Q2 2025", "2025Q2", „2. čtvrtletí 2025“ or
  "the second quarter of 2025". An earlier question's own relative word is read the way chrono read it then,
  so the words chain: 2025 → "And the previous year?" (2024) → "And the previous year?" (2023).
- **No anchor, no fallback:** when the nearest question naming the unit names several periods ("in 2024 and in
  2025"), a four-digit number it does not say is a year ("Marketplace revenue 2024 vs 2025"), or a period chrono
  asked about, an anaphoric word — and the anaphoric half of „minulý …“ — is `UNGROUNDABLE` with the new
  message code `no_anchor`, which says why. It is never read against the reference date, and never handed to the
  LLM fallback.
- **A year chrono asked about is no year to lean on:** a month or quarter in it („A v březnu minulého roku?“, which
  asked) is no anchor for the next anaphoric word („A předchozí měsíc?“ is `no_anchor`, not February of the
  reference year), and a date that names no year after it („A minulý rok?“, then „A v září?“) is `no_anchor` too,
  not the reference year's September — both readings of the asked year differ from the reference year. A question
  naming several years still leans a yearless date on neither: the reference year, as before.
- **„minulý …“ asks only when its readings differ:** `AWAITING_CLARIFICATION` with the deictic reading first and
  the anaphoric one second. With no period of that unit in the conversation, or with the current one, it grounds
  without asking. A month in an ambiguous year („v březnu minulého roku“) asks between the two Marches.
- **Clarification options:** an option's `id` is its period code — `yyyy` for a year (a year interval still
  carries no `period_code`; the id is made from its span), `yyyyMM`, `yyyyQn`, `yyyyMMdd` for a day — and
  `clarification_answer_id` resumes to it. The resume re-reads the span, so it must carry the same
  `question_text` and `prior_questions` as the request that asked. The `label` names the period in the request's
  language: "2025", "February 2025", "Q1 2025"; for a `cs` locale „únor 2025“, „1. čtvrtletí 2025“. It no longer
  carries the code (the bare future month was "December 2026 (202612)", now "December 2026").
- **Unchanged:** weeks keep reading against the reference date; a rolling window ("last 3 months", „posledních
  30 dní“) is not a period word; a span that writes out its year is that year (#213); „poslední“ is a scope word
  for a quarter only. „letos po měsících“ is now the year; it was the current month.
- The resolver still drops a kernel's `AWAITING_CLARIFICATION` (#231), so until it carries the question to the
  user, a „minulý …“ that asks reaches the consumer as an ungrounded date.

### resolver — a value's attribution names a member, never a model object („v roce 2025“, „marketplace revenu“, #227)

„Porovnej tržby z tržiště a z webu v roce 2025“ and „10 nejprodávanějších produktů na tržišti v roce 2025“ came back with
`roce` as a VALUE attributed to `er.entity.date_dim.year` — the column itself, bound as if it were one of its own
members — and "marketplace revenu in 2025 by month" did the same with the all-channel revenue measure on `revenu`. The
contract (`resolver.proto`) is that an attribution binds a member (`<attribute_ref>#<id>`); a consumer that reads values
as restrictions compares the column to its own name. In „Tržby z tržiště po čtvrtletích v roce 2025“ the same `roce` was
a mention bound to the `ground:chrono` trigger, FILTER, with `2025` narrowed to chrono.

The noun `roce` hangs from decides it. Under a declared multi-word phrase (`po čtvrtletích`) nothing proposes it, so the
mention layer takes it and asks its trigger. Under a one-word anchor of an entity (`webu`, `produktů`) span proposal
offers it as that entity's governed value; its open sibling reads it as `date_dim.year` (`za rok`, by the lemma), the
lattice made every value-origin span a value whatever it read as, and the trigger was never asked — only mentions were
trigger spans. `revenu`, tagged PROPN, took the proper-noun path to the same place.

- A word proposed as a value (an anchor's governed argument or its open sibling, a proper noun, a NER entity) that no
  member reads is now a MENTION when:
  - a grounding trigger claims it, whether the lookup read it as a model object or found nothing. It is exactly the
    mention the mention layer makes of the same word elsewhere: bound by the trigger alone, and absent from
    `Resolution.bindings` too. „v roce 2025“ now reads the same under `webu` and `produktů` as under
    `po čtvrtletích`, on an estate that declares `za rok` and on one that does not: `roce` → `ground:chrono`, FILTER,
    `2025` narrowed to it;
  - otherwise, its best reading is a model object: a mention bound to that object, with the evidence it had (`revenu`
    → `er.entity.channel_sales.ext_sales_price`, `letech` in „v letech 2024 a 2025“ → `er.entity.date_dim.year`).
- Such a word is now asked about grounding triggers on the broad pass, as the mention it would become (unanchored, so a
  trigger it matches fuzzily classes the same under any noun): one more slot per value-shaped span on the same
  `BatchMatch`. A literal run, a quoted literal, a member phrase, a named member, a dual reading and a word with no head
  are not asked.
- A value never carries a model object, on the door as in the lattice. Every gated span is settled once its contenders
  are in — after the broad pass and after every lookup round — before the lattice, the round planner or
  `Resolution.bindings` reads it. A value no mention can be (a code, a dual reading) loses its model-object readings: one
  that read only as an object is unattributed (G3/G4) and asked again like any other, rather than bound to the column;
  a tie only an object made is no tie. `MODEL_OBJECT` also joins the classes that are never an attribution
  (`OPERATOR`, `GROUNDING_TRIGGER`, `STRING_PREDICATE`), the backstop for the re-gate.
- Unchanged: a member still binds as a value (`pro Brno DC` → `warehouse_name#Brno DC`); a value-shaped word that found
  nothing and no trigger claims stays an unattributed value; grounded values; a declared anchor.
- Not in this change: a typo inside a declared multi-word phrase ("marketplace revenu") is still not tried as the
  phrase — span proposal matches declared phrases word for word — so `revenu` binds the all-channel measure on its own.
- No wire change.

### logging-config — logging a response can no longer fail the call, and a failed call's status names its exception (#197)

`ResolverService/Resolve` closed `UNKNOWN` with an empty message on some turns: „10 nejprodávanějších produktů na tržišti
v roce 2025“ every time, and "Marketplace revenue in 2025 for Memphis DC" only in the minutes after a restart. Since #202
the close line names the cause: `java.lang.StackOverflowError at java.base/java.util.regex.Pattern$Branch.match`.
- **Why it happened:** both turns ended in a clarification, and the resume token signs every option offered (8 450
  characters for 20 options).
  - With `grpc.in` at DEBUG, `IncomingCallLoggingInterceptor` renders each response for its log line and masks secret
    fields first. The mask walked a quoted value with a regex that recursed once per character, so a long token
    overflowed the stack of the thread sending the response.
  - The pipeline had already answered; the log line failed the call. grpc-kotlin closes a thrown call with
    `Status.fromThrowable`, which is `UNKNOWN` with no description.
- **Why only sometimes:** the overflow length depends on how warm the JVM is. On a 1 MB stack it was about 1 000
  characters cold and about 4 100 warm, hence the window after a restart.

- **The mask:** it walks a quoted value with a possessive loop, which `java.util.regex` runs iteratively, so a value of
  any length is masked. It matches exactly what it matched before.
- **Rendering:** rendering a payload for a log line never throws into the call. A payload that cannot be rendered is
  logged as `<unrenderable payload: <exception class>>`.
- **Status description:** a call that closes with a cause and no description (a handler that threw) now gets one: the
  exception's class, then the first line of its message with secrets masked and cut at 200 characters. Example:
  `java.lang.IllegalStateException: …`.
  - A status that already has a description is passed on unchanged.
  - The serving side's WARN close line is unchanged: class and throw site only, with the message and stack at DEBUG.
  - The message does reach the caller's logs: a Kotlin caller's `OutgoingCallLoggingInterceptor` logs the description
    at WARN, and `query` forwards a downstream failure's message in-band, so query-mcp returns it as the tool's error
    text. That is intended: what crosses is one line, secrets masked, at most 200 characters.
- **Scope:** every service that installs `IncomingCallLoggingInterceptor` (chrono, dispatch, geo, money, query, resolver,
  translate, validate, worker-mssql, worker-postgres). No proto change.

### resolver — a compared value names its own object, never the operator („Porovnej tržby z tržiště a z webu“, #207)

„Porovnej tržby z tržiště a z webu v roce 2025“ came back with `webu` → `op:compare` in `Resolution.bindings`
(`Domain.entity_type_ref`), while the binding's own candidate and the lattice both said web revenue. Without the operator
word („Tržby z tržiště a z webu v roce 2025“, „Srovnání …“, „Podíl webu na tržbách“) the same binding was named
`unknown`. The lattice was right throughout; the door was not.

The value binds in a lookup round, which asks the matcher across all categories, and lex-matcher answers such a lookup
with `category = "unknown"` on every row: it has no category to stamp. The door named a declared binding by the type that
claims its category, so it named this one `unknown`. An imperative operator word (`Porovnej`, `Srovnej`, `Compare`) is
confirmed the same cross-category way (#58), and its type was registered for the request under that row's category, so
the operator claimed `unknown` and every round-bound value in the question was named after it.

- An operator confirmed for a command or count word is registered under its own ref (`op:compare`), which is the
  matcher's category key for it. The broad pass now asks the word in that category and binds it there; it used to ask `unknown`,
  which answers nothing, and bind it one lookup round later. The binding is the same; the rung log shows it under the
  core pass.
- A declared binding whose category no declared type claims is named by the object it targets (the part of the target
  ref before `#`), which is what its candidate and the lattice name. A clarification option is named the same way.
  Broad-pass bindings are named exactly as before: their category is always a declared type's.
- No wire change.

### resolver — a year the NER typed a number is a date on its anchor („Store revenue 2025“, „za 2025“, #206)

"Marketplace revenue 2025", "Store revenue 2025" and „Tržby z tržiště 2025“ left the year grounded but unattributed
(`GAP_KIND_G3_UNATTRIBUTED`), while "… in 2025" attributed it. „Tržby z prodejen za 2025“ was worse: the year was dropped
with no gap at all, and the consumer's default period answered instead. The NER is where they part: Stanza types the bare
year `CARDINAL`; NameTag types it `n_` / `nc` (a number) bare and after *za*, *ve*, *během*, but `ty` in „v roce 2025“.
A number is never offered to the date kernel; after a noun with no preposition it reads as a code (#139), and after a
preposition nothing read it.

- A number the NER typed `MISC` is now offered to the date kernel as a DATE when it is one four-digit token in 1900–2999
  with nothing but punctuation and at most one preposition hanging off it, and either
  - **no preposition**: it hangs off a word of a mention whose top binding is a **measure** (object kind `measure`) and
    stands right after that mention (punctuation aside); or
  - **a temporal preposition**: its preposition (lemma or surface) is in the new `frame-roles.temporal-preps` table —
    shipped `cs = [za, v, ve, během]`, `en = [in, for, during]`; an estate override without the key offers nothing.
- It stays a DATE only if the kernel grounds it to an anchor column; otherwise it is exactly what the NER said. Attributed,
  it is the same value `in 2025` gives.
- Unchanged: a number with a unit under it („Tržby za 2000 Kč“ — Stanza makes `2000` the head with `Kč` beneath), before
  the measure (a count; „v 2025 kusech“), a bare number after anything that is not a measure (`Revenue of store 1042`,
  „Prodejna 1042“, an `entity_with_measures` such as `Store sales 2025`, which stays G3), and anything not a four-digit
  year (`top 10`, `501001`). Range prepositions (*od*, *do*, *since*, *until*, *through*) are deliberately not in the
  table: the kernel reads the year alone, and „do 2025“ is not the year 2025. A bare year the parse hangs off one measure
  while it stands after another (`Revenue and stock on hand 2025`) is ambiguous and stays G3.
- No wire change.
### resolver — a misspelt word of a longer member value is not offered as that value (#208)

"Marketplace revenue in 2025 for Allen" asked whether `Allen` meant the warehouse Allentown DC or the product
"ablen stpriationought": `allen` is one edit from `ablen`, and the product's other word is not in the question at all.
`fuzzy.match:v2` scores how well the question's words are matched, so a one-word question one edit from any word of any
name scores 0.85 whatever the rest of the name says, and the two sat in one tie band. Alone in its band, the same kind
of row bound outright ("for Ablin" became a filter on that product).

A member row is now WEAK, so never bound and never offered, when both hold:

- every word of the question it matched, it matched by a **typo** (no exact and no prefix match), and
- those words carry **no more than half** of the value's weight: the row's `provenance.coverage` (the IDF-weighted share
  of the value the question reached) is at most 0.5.

So `Allen` binds Allentown DC (the start of `Allentown`, typed right). Unchanged: one exact word of a name ("Memphis" →
Memphis DC); a typo on the word that carries most of a name ("Alentown" → Allentown DC: `DC` ends every warehouse name,
coverage 0.68); a misspelt whole name ("Dalas DC"); a typo next to an exact word ("Voltaic Monitr"); declared terms; and
rows with no per-word provenance (the v1 engine). No wire change: lex-matcher already sends both fields on every v2 row.
### resolver — an article no longer stops a mention binding, and an unknown modifier is reported, not swallowed (#204)

In English, a mention with an article or an adjective in it did not bind, even when its head noun does on its own:
"the marketplace", "the web", "Total revenue" and "What share" all came back `G1_UNBOUND`, while "marketplace", "web",
"Revenue" and "share" bind. Span proposal folds a noun's `det` and `amod` dependents into its mention, the matcher was
asked about the whole phrase, and the estate declares the word, not the phrase. A follow-up like "And on the web?"
could not read as a change of view, and "What share of …" lost its ratio operator.

- **A mention whose phrase binds nothing is asked again as its head**, without its determiners (with whatever hangs
  from them) and its bare adjectives. A phrase that binds, or is ambiguous, on its own keeps its answer; a phrase the
  estate declared word for word ("marketplace revenue") is never asked without some of its words. A binding reached
  this way has a `producer.algorithm` ending in `+core`.
- **What becomes of the words left out:**
  - **Silent:** a closed, per-language list of words that restrict nothing may vanish. The mention keeps its span and
    binds with no gap ("Total revenue", "the marketplace", "What share"). The list is data, `silent-modifiers` in
    `frame-roles.conf`: en `the`, `a`, `an`, `what`, `which`, `total`, `overall`, `entire`, `whole`; cs `celkov*`,
    `úhrnn*`, `vešker*`, `kter*`, `jak*` (surface or lemma, case and diacritics folded, `*` a prefix). An override file
    without the key keeps the shipped table.
  - **Residue:** every other word left out (a determiner such as `no`, `all`, `these`, or an adjective such as
    "promotional", „akčních“) surfaces as an unbound mention of its own, `G1_UNBOUND` over its own words, with no frame
    role. The head then binds over its own words only, so "Podíl akčních tržeb" reads revenue and the ratio bound,
    „akčních“ not understood: a consumer asks or composes rather than answering the unrestricted number.
- **A modifier the lexicon knows cancels the retry.** Each left-out adjective, and each left-out determiner that is not
  silent, is asked across every category and class. If a row that is not member data is that word's whole form at the
  bind floor (a term, an operator, a grounding trigger), nothing is left out and the mention stays as it was ("the
  largest stores": `largest` is an operator word). A longer term that only holds the word does not count: under
  `fuzzy.match:v2` the one-word question "total" scores 1.0 against any longer term containing it, and "Total revenue"
  still binds. A declared modifier ("Monthly") was never folded in the first place. An adjective with words of its own
  ("How many stores") is kept.
- **Asked only when it decides something.** That question reaches every class, member data included, so it is put
  only for a head about to bind without the word: never for a phrase that bound as written, nor for a head that found
  nothing.
- **Where it is asked:** an anchored mention in the broad pass, as one more slot of the same `BatchMatch`, scoped to the
  anchor's categories (the left-out words are asked about after the gate, for the heads it took); any mention still
  `G1` (including the nouns no anchor proposes, such as "What share") in the lookup round, as its head only in a round
  after its phrase missed, and after every other gap's own question in that round.
- The rule is the same in every language. Czech has no articles, but „Celkové tržby“ now binds with no gap, and
  „nejvyšší podíl“ binds `podíl` with „nejvyšší“ reported.
- No wire change. One new config table (`frame-roles.silent-modifiers`).
### query-mcp — an MCP session nobody uses expires, and a `DELETE` ends one at once

query-mcp kept every MCP session it ever opened. The MCP SDK ends a session only when the client sends `DELETE`
with the session's `mcp-session-id`. A client that opens a session per call and only closes its connection left
behind, on every call, the session's transport, a per-session SDK server with its notification job, and the
session's standalone GET stream, parked on a socket in `CLOSE_WAIT`. The SDK's own close only closes that stream's
output, which does not end its handler, so even a `DELETE` left the stream behind.

- **`ktor-configurator`** gains `Application.mcpStreamableHttpWithSessionExpiry(path, idleTimeout, sweepInterval,
  timeSource, block)`. It mounts the SDK's Streamable HTTP transport and returns an `McpSessionRegistry` (open count,
  `expireIdle()`). A background sweep (every `idleTimeout / 4`, clamped to 1–30 s) closes each session that no POST
  or DELETE has used for `idleTimeout`: it ends the GET stream, closes the SDK server (cancelling its notification
  job) and drops the transport. A request still carrying the old id is answered 404, which by the MCP spec tells the
  client to start a new session. A session with a request in flight is never expired; its clock restarts when the
  request ends. A `DELETE` takes the session out of the registry before the SDK answers, and the rest is closed
  after. Each removal logs one INFO line with the reason and the number of sessions still open.
- **query-mcp** mounts its transport with it. New config **`query-mcp.mcp.session-idle-timeout-seconds`**
  (`QUERY_MCP_SESSION_IDLE_TIMEOUT_SECONDS`), default **600**, must be positive. New gauge
  **`query_mcp_open_sessions`**; the startup line names the timeout.
- **Upgrade:** a client that keeps one session across calls and goes quiet for longer than the timeout gets 404 on
  its next call and must initialize again, as after a restart. No wire change. The repo's other MCP doors still
  mount the SDK transport directly.
### chrono — a span that writes out its year is that year, or nothing (#213)

With the lexicon's `ground:chrono` trigger slice loaded, a span that said both a period word and a year read as a
period around `reference_datetime`: "the year 2025", "calendar year 2025" and "rok 2025" grounded to the reference
year, "week 12 of 2025" to the reference week, "the last quarter of 2025" to the quarter before the reference. The
rules that read a period relative to the reference no longer read a span that writes out its own year.

- **A year-only span is that year:** "the year 2025", "year to date 2025", "v roce 2025", "rok 2025", and with a
  scope word beside it, "this year 2025" and "minulý rok 2024".
- **A period *of* a stated year is ungrounded:** "the second quarter of 2025", "the last week of 2025", "week 12 of
  2025", "the first month of 2025", "the second half of 2025", "druhé pololetí 2025". No rule reads one yet, and
  the whole year would be a confident wrong number; a date value with no range lets the consumer fall through. Some
  of these were the whole of 2025 before ("the second half of 2025"); none is now. "Monthly", "quarterly" and the
  Czech adjectives (*měsíční*, *čtvrtletní*, …) name a grain, not a period, and do not count.
- **Unchanged:** a rule that reads the period itself still wins ("the month of March 2025", "15.3.2025",
  "fiscal year 2025"); a span with no year of its own still reads at the reference ("last week", "this month",
  *minulý měsíc*, a bare "fiscal year" or "quarter").
### A calendar word inside a grain is the grain, not a date

English NER types the calendar word of a grain phrase a date, and the resolver grounded it as one beside the
grouping the estate declared. "Marketplace revenue by month in 2025" restricted the question to the current
month AND 2025; "Store revenue by month" to the current month alone; and "Marketplace revenue by quarter in
2025", where the NER typed `quarter in 2025` as one span, was grounded as the current quarter, with 2025 lost.
Where a declared phrase the matcher confirms as a model object shares words with a date, the words are now the
phrase's: the date keeps only the rest (`2025`), and a date with nothing left is not grounded at all. A word
that bounds the date stays with it ("by month since 2024" keeps `since 2024`), and the cut date drops the NER's
normalisation of the wider span. A declared one-word grain the NER typed a date ("Monthly marketplace revenue")
is proposed and binds like any other declared word, and a calendar adjective naming an attribute is a
`FRAME_ROLE_GROUPING`: one listed in the new `frame-roles.grain-adjectives` table, or one the NER typed a date
by itself. Any other adjective naming an attribute keeps the role it had. A phrase the matcher does not confirm
takes nothing, so a grain the estate never declared reads as before.
### Veles serves the layouts committed next to the model

A layout — a schema, the cards it shows, where they sit — saved from the Studio Viewer as a `.ttrg` (with its
`.ttrl` sidecar when there is one) and committed under the model root is now served to every viewer of that
model. Veles reads the layout files with each model load, resolves their cards against that model, and answers
two new read routes (VL contracts §6):

- `GET /model/layouts` — every layout, by schema then name: `name`, `schema`, `objectCount`, `missingCount`,
  `ambiguousCount`, `hasPositions`, `source` (the path under the model root), and the `modelVersion`.
- `GET /model/layout?name=` — one layout: its cards as full qnames, their positions, `mode`, `skin`,
  `collapsed`, the references it names that the model lacks (`missing`) or holds more than once
  (`ambiguous`, with the candidates), relations it lists (`ignored`), where its positions came from
  (`positionsFrom`: `in-file` | `sidecar` | `null`), `source` and `modelVersion`. Every field is always
  present, empty ones as `[]`, `{}` or `null`. A name that is not an identifier is `400 {"error":"bad name"}`;
  an unknown one `404 {"error":"not found"}`. Both routes answer `503` until a model is loaded.

A layout file that does not parse, or cannot be read, is left out and listed in `/status` under the new
`issues` array (`layout layouts/sales.ttrg: …`; a sidecar's finding names the `.ttrl`); the model loads
regardless. Two files that declare the same layout name are both left out, each listed with the other's path —
which one a viewer gets never depends on file order. A sidecar the viewer opens Veles serves too: what it does
not use (a `viewport`, an unknown canvas property) is skipped. Both routes are GET-only, like the four read
routes before them; committing to the model's repository is how a layout is published. Requires the TTR
toolchain 0.15.0.

### A date with no year takes the year the conversation is about

chrono read a month or day that names no year at the reference year, even when the question around it named
one: in "Which products sold out in October 2025? — all of October", the bare "October" was October of the
current year. A yearless date now takes its year from, nearest first, the span's own words ("last year in
October"), the question it is in, and the user's earlier questions; the first text that names a year decides,
and one naming several leaves the date as before. A bare number is not read as a year (it looks the same as an
amount), and relative periods ("last month") stay relative to `reference_datetime`.

Czech says a year as an adjective as often as an adverb, and every form counts, in any case: *letošní prodeje*,
*tohoroční*, *loňské výpadky zásob*, *vloňský*, *minuloroční*, *předešlý rok*, *předloňský*, *předminuloroční*.
The one-word adjectives the resolver did not yet type as a period (*vloňský*, *tohoroční*, *minuloroční*,
*předminuloroční*, *předešlý rok/měsíc*) now are, and chrono grounds each to its year. A bare *roční* stays
"annual".

- **`grounding.v1` `GroundingContext.prior_questions`** (8, new): the user's earlier questions, newest first.
- **`resolver.v1` `ResolveContext.prior_questions`** (4, new): the resolver passes it through to the grounding
  kernels unchanged. Additive: a caller that does not set it gets the question-only reading.

### Shipped defaults name no pilot estate

Defaults baked into the images named one pilot deployment. They are now neutral; a deployment that set its own values
is unaffected.

- **dispatch `world.default-connection`** is `mssql-default` (was the pilot's connection id), and **worker-mssql's
  shipped env-driven connection template** has the same id, so the two still pair up with no further config when
  `MSSQL_DB_*` activates the template. Upgrade: a deployment that routed to the old id by default and served it from
  its own connection block sets `WORLD_DEFAULT_CONNECTION` (or renames the block).
- **geo's Nominatim User-Agent** is `tatrman-geo/1.0 (+https://github.com/Collite/ttr-server)`, in the service default
  and in the chart (`geo.nominatim.userAgent`, was `tatrman-geo/1.0`); it still identifies the application as the OSM
  usage policy requires, and `GEO_NOMINATIM_USER_AGENT` / the chart value still override it.
- **identity's Keycloak realm** defaults to `tatrman` (the umbrella chart's dev IdP realm; was the pilot's). It is read
  only in `db` mode; `KEYCLOAK_REALM` sets it.
- **identity's ERP user source ships no SQL.** Its four reads were one ERP's schema, hard-coded, with a fallback email
  domain to match. They are now configuration, `erp-database.queries.{users, roles, user-roles, role-hierarchy}`
  (`ERP_USERS_QUERY`, `ERP_ROLES_QUERY`, `ERP_USER_ROLES_QUERY`, `ERP_ROLE_HIERARCHY_QUERY`), each returning the columns
  `infra/identity/README.md` lists, plus `erp-database.fallback-email-domain` (`ERP_FALLBACK_EMAIL_DOMAIN`). While any
  query is unset the ERP source is skipped with a WARN and no ERP connection is opened. **Upgrade:** a `db`-mode
  deployment that runs the ERP sync (`WHOIS_ERP_SYNC_ENABLED=true`) sets the four queries for its ERP, and the fallback
  domain if it relied on one; without a domain a user with no email is skipped rather than merged with others on a
  blank email. `json` mode (the chart default) is unaffected.

### meta-mcp — tool descriptions give Hartland examples

The examples in the meta-mcp tool descriptions and manifests (the package filter, `get_model`'s package list,
`resolve_area`'s area, `get_object`'s ids, `get_roles_for_entity`'s qname) named a client's model packages; they now
name the public Hartland demo model that Veles serves by default (`hartland`, `er.entity.item`,
`db.dbo.customer_address`, `er.entity.store_sales`). The `meta.v1` proto comments follow. No behaviour or wire change.

### veles — a model source that fails to load no longer hides behind a bundled model (#180)

When the git model source could not be loaded (a bad token, a wrong branch, no network), Veles quietly served the
sample model it bundled, and `/ready` reported UP. It now serves only what it was configured to serve.

- **No fallback by default, and no bundled model.** A failed source leaves Veles with no model (the built-in stock roles
  only). A fallback is opt-in: `METADATA_GIT_FALLBACK=local-model` with `METADATA_TTR_ROOT=<dir>` serves that directory
  while the repository is unavailable, never merged with it.
- **`/ready` waits for the configured source.** 503 until every configured source has loaded at least once (a load
  that finds no model objects does not count) and its model is swapped in; the body names the source and its last
  error. An empty `METADATA_GIT_REMOTE_URI` configures no source: an error at startup, and never ready.
- **`/status`** gains `ready` and `sources[]` (what each source reads, credentials redacted; `loaded`,
  `last_attempt_at`, `last_success_at`, `last_error`, `consecutive_failures`, `failures_total`, `serving_fallback`);
  **`/metrics`** gains `veles_model_source_load_failures_total{source}` and `veles_model_source_loaded{source}`.
- A source that has never loaded is retried from 5 s backing off to 60 s, instead of waiting for the next scheduled
  refresh.
- **The default model source is the public Hartland demo model**: `https://github.com/Collite/ttr-demo.git`, branch
  `master`, sub-path `model` — in Veles' own defaults and in the chart (`veles.metadata.gitRemoteUri`, and the new
  `veles.metadata.gitBranch` / `gitSubdirectory`, which render nothing when `veles.extraEnv` already sets
  `METADATA_GIT_BRANCH` / `METADATA_GIT_SUBDIR`). Set all three to serve your own model: an install that overrides only
  the URI now looks for branch `master` and sub-path `model` in it.

### resolver — a Czech date the NER typed in parts is grounded as one date („v říjnu 2025“)

*„Které položky tam byly v říjnu 2025 vyprodané?“* grounded **„říjnu“ as October of the current year** beside a
separate whole 2025. NameTag (CNEC 2.0) types a written date two ways, and which one is not predictable from the words:
„v květnu 2025“ comes back as one container entity (`cnec:T|B-tm`), „v říjnu 2025“ as two bare ones, a month (`cnec:tm`)
and a year (`cnec:ty`). Each universal was grounded on its own, so chrono got a month with no year and read it against
the reference date, as it must for a bare month.

- `UniversalExtraction.extractUniversal(parse, text)` joins bare `td` (day) / `tm` (month) / `ty` (year) DATE parts that
  touch across whitespace only, in calendar order, into the one date the user wrote („říjnu 2025“, „15. října 2025“). The
  joined binding carries NameTag's own container code (`cnec:T|B-<first part>`), so both shapes read the same downstream.
- Two years („2024 a 2025“), a year before a month, a coordination („v říjnu a listopadu 2025“) and parts separated by
  anything but whitespace stay as they came. Without the question text (the parameter defaults to blank) nothing is joined.
- chrono itself needed no change: it already reads every cs case form with a year (nominative, genitive, locative,
  „za …“, „během …“), and a bare month stays reference-relative. Both are now pinned by a table over all twelve months.
- No wire change.

### resolver — a mention is also looked up by its dictionary form

On a Czech estate, *"… za produkty začínající na „Voltaic“ podle kategorie"* filtered the CATEGORY
column by the prefix (0 rows), and *"hodnota portfolií našich klientů"* left both objects unbound.
The estate declares an object by its citation form (`produkt`, `portfolio`, `klient` — every entity
label is one) with method `EXACT`, and the broad pass asked the matcher only about the words as
written: `produkty` never met `produkt`, and bound the next row in reach instead (the English alias
*product category*, by token + typo).

- **One more slot in the same `BatchMatch`** for an anchor phrase whose single content word (its
  determiners left out) has a lemma that differs from what was written. Its rows are folded back
  onto the mention's own slot before anything reads the response; the batch shape every positional
  reader sees is unchanged. A phrase of several content words asks nothing.
- **A dictionary-form row does less than the same row met as written.** Only declared/learned rows
  whose authored method is `EXACT` or `TYPOS(n)` and that carry no RV-44 matching profile; minus
  0.1 (wider than the tie band, so an equally good written row wins); classed at most
  `DECLARED_ALIAS` (`LEARNED_ALIAS` for a learned row), never `EXACT`; the binding's
  `producer.algorithm` ends in `+lemma`. Member rows are untouched — the matcher lemmatises member
  queries itself.
- No wire change, no config.
### query-mcp, resolver — an MCP tool call reads its own caller's identity, and no one else's

Under concurrent calls an MCP door could evaluate a tool call with **no** identity (a caller who
sent a bearer was refused `missing_user_identity`) or with **another in-flight caller's** headers.
The doors stashed `Authorization` / `X-User-Id` in a thread-local from a Ktor interceptor and read
it in the tool handler. The MCP SDK runs that handler inline on the request's coroutine, but the
coroutine suspends on the way (reading the body; query-mcp's bearer check hops to
`Dispatchers.IO`) and resumes on whatever worker thread is free. That thread's slot was empty or
held a different request's headers. With row policies keyed on the caller's roles, the second case
is a wrong-identity read.

- **`ktor-configurator`** gains `McpRequestHeaders`, a coroutine context element holding one
  request's `Authorization` and `X-User-Id`, plus `Application.installMcpRequestHeaders()`, which
  runs the rest of each call's pipeline inside it. Handlers read `McpRequestHeaders.current()`.
  Outside a request both are null, so the identity gates fail closed. The element travels with the
  request's coroutine and its children, so dispatcher hops and `withTimeout` keep it. Its
  `toString` never prints a credential.
- **query-mcp** and the **resolver** door use it. `installQueryMcp` / `installResolveDoor` lose
  their `RequestContext` parameter, and the internal `mcp-identity` `RequestContext` (the
  thread-local) is removed. `IdentityGate` is unchanged.
- No wire or config change.
### llm-client + llm-gateway — a long completion on a reasoning model is no longer cut off at 2000 tokens or 15 s

A plan composer prompting a reasoning model (gpt-5-mini) failed in two ways. The client sent `max_tokens = 2000`
on every call, and on a reasoning model that budget also pays for the hidden reasoning, so a long prompt could spend
all of it and come back with nothing usable. Writing those 2000 tokens takes about 15 s, which is exactly the
gateway's chain wall-clock budget: under load the caller got `502 upstream timeout` for a call the model would have
finished.

- **`org.tatrman:llm-client`:** `LlmGatewayEndpoint` gains `maxTokens` (default `DEFAULT_MAX_TOKENS` = 2000, as before)
  and `reasoningEffort` (default null). On `complete` / `completeWithMeta` both parameters are now nullable: null means
  the endpoint's value, and a per-call value still overrides it. They are resolved in the method body, so a consumer's
  mock of the client never evaluates the endpoint. `reasoning_effort` goes on the wire only when set (blank counts as
  unset); there is never an explicit `null`. `LlmGatewayPromptExecutor` sends the Koog prompt's `params.maxTokens` when
  it has one, the endpoint's otherwise. Source-compatible: existing callers see no change.
- **llm-gateway:** the chain wall-clock budget (`retry.wallClockBudgetMs`, also the upstream request timeout) can be set
  with `LLM_GATEWAY_RETRY_WALL_CLOCK_BUDGET_MS`. Default unchanged at 15 000.
- **llm-gateway:** `reasoning_effort` is dropped for a non-reasoning model (it would 400 there), the mirror of the existing
  reasoning-model normalisation. Reasoning models receive it as sent.

### resolver — Czech grain phrases: a declared phrase matches as written, and `po` is decided by two signals

On a Czech estate, *"Tržby z tržiště v roce 2025 po měsících"* (by month) answered one total, and
the same question without diacritics failed downstream with a value-less month filter.

- **The anchor index matches a declared word by the token's surface as well as its lemma.** The
  estate declared `po měsících`; MorphoDiTa lemmatises it to `po měsíc`, so the phrase never formed
  and the bare `měsících` bound the time trigger. Lemma-form declarations match as before.
- **R3', the distributive preposition** (`frame-roles.conf` `distributive-preps`, cs: `po`). Czech
  `po` reads "per X" (GROUPING) or "after X" (FILTER). Two signals decide, for a mention that is
  neither a grounding trigger nor a member: A, the estate wrote the preposition into the declared
  phrase and no value hangs on it; B, the noun's `Number` (plural = GROUPING, singular = FILTER).
  Agreement or one voice decides; both silent keeps FILTER. When they **disagree**, the mention
  carries **both GROUPING and FILTER**, a pair no other rule produces, meaning "undecided": a
  consumer should route the question to a reader that can weigh the sentence.
- `FrameRoles.Input` gains `charEnd` (defaulted). A config without `distributive-preps` never fires
  R3'.
- No wire change.

### resolver — a place's member reading takes the words the parse adds to its name (✅UD-9, #118)

When the NER typed only part of a name as the place, the member reading covered only that part.
NameTag types `Praha` in `Tržby z tržiště pro Praha DC` and the parse hangs `DC` on it. The reading
looked up `Praha`, which can still bind the DC, but `DC` was left over as an unbound mention (a
G1), and the turn asked what „DC“ meant.

- The reading now extends rightward over the words the parse attaches to the name: a nominal whose
  head is already in the run, by `flat`, `flat:name`, `flat:foreign`, `compound`, `nmod` or `appos`.
  The run stops at the first word that fails, an anchor word, a quoted literal, or another NER
  entity. It never extends to the left.
- Where the NER already types the whole name (`Dallas DC`), nothing changes.
- No wire change.

### resolver — a place under a measure is looked up as a member of the measure's fact (✅UD-8, #118)

The member reading of a place (UD-P1) was offered only to the argument of a single-word anchor that
holds values. Two common shapes got none: a place under a **measure** (`Marketplace revenue for
Memphis DC`), because a measure holds no values, and a place under a **multi-word** anchor
(`Tržby z tržiště … pro Brno DC`), because a multi-word anchor governed nothing. The NER typed the
DC name a place, so it stayed one, with a G3. The lookup round could not help either: it only
attaches an answer to a span the domain proposed, and none was.

- A place a measure governs gets the dual reading, with the measure's declared owner (its fact) as
  the governor. Its member counts when the fact reaches the member's entity (✅UD-7), so `Marketplace
  revenue for Dallas DC` binds `warehouse.warehouse_name` through the warehouse's declared relation
  from `catalog_sales`, and `Marketplace revenue for Nashville` (a store, which that fact does not
  reach) stays a place.
- A multi-word anchor's head governs a place in the same way.
- Nothing else is proposed. A measure's other arguments and a multi-word anchor's are values of no
  governed path, as before (`revenue for books`, `v roce 2025`).
- No wire change.

### nlp — OpenTelemetry is off unless a collector is named

The front defaulted its collector to `localhost:4317` and built the log, trace and metric exporters
in every pod. Where no collector runs, which is any deployment with the chart's `telemetry` left at
its default, each exporter retried forever: an ERROR/WARNING pair every few seconds in the pod log.

- With no `OTEL_EXPORTER_OTLP_HOST` (the chart renders it only for `telemetry.enabled` with
  `telemetry.otlpHost`), the front builds no exporters and does not instrument FastAPI or httpx.
  Its request span and counters run against the OpenTelemetry API's no-ops.
- The chart now **fails the render** for `telemetry.enabled: true` without `telemetry.otlpHost`,
  instead of rendering nothing and leaving telemetry silently off.
- A deployment that sets both keys is unchanged.

### `ttr-nlp` — a MorphoDiTa token reads like UD Czech: `feats`, and the POS UD gives it

The MorphoDiTa adapter (`parse_morphodita_vertical`, which the nlp front's `option` lane uses for
Czech tokens, lemmas and POS) sent a coarse `upos` and **no `feats` at all**, so the same question
read differently on the two lanes: no `Mood=Imp` on `Zobraz`, no `PronType=Int,Rel` on `Který`, and
every numeral `NUM`. The PDT-C tag and the lemma now become UD Czech-PDT's reading, the one the
Stanza lane already gives (new module `ttrnlp.client.pdt`, `pdt_to_ud`).

- **`Token.feats` is filled** from the tag's positions (gender, animacy, number, case, person,
  tense, degree, polarity, voice, aspect, possessor) plus what the subclass implies (`VerbForm`,
  `Mood`, `NumType`, `NumForm`, `AdpType`, `Variant`, `Abbr`); `PronType`, `Poss`, `Reflex` and
  `NameType` come from the lemma and its name markers.
- **Some `upos` values change**, each to what UD Czech says: ordinals `NUM` → `ADJ`
  (`prvních`, `NumType=Ord`); quantifiers `kolik`/`několik` `NUM` → `DET`; `dvakrát` → `ADV`;
  determiners (`který`, `jaký`, `ten`, `náš`, `svůj`, `žádný`, `všechen`, `každý`) → `DET`;
  every form of `být` → `AUX`; a passive participle (`prodáno`) → `ADJ`; subordinating
  conjunctions (`že`, `kdyby`, `než`) → `SCONJ`; abbreviations (`DPH`, `atd.`) from `X` to the POS
  they stand for, with `Abbr=Yes`; `%`, `§`, `+`, `$` → `SYM`. A caller that treats `NUM` as a
  literal no longer sees `prvních` or `Kolik` as a value, the same as on the Stanza lane.
- **A numeral's value leaves its lemma**: `pět-1`5` → `pět`, `druhý`2` → `druhý` (also in
  `BatchLemmatize`, which lex-matcher lemmatizes queries with).
- Not derived, because neither the tag nor the lemma settles them: a conditional's person and
  number (`bych`), `Animacy` on `kdo`/`co`, `Style`.

Held to Stanza by a parity test on 22 live sentences: where the two engines' PDT tags agree,
`upos` agrees on every token and `feats` on all but a listed, justified set.
### resolver — an operator word the tagger tags correctly reaches the lattice (#58)

An operator word became a mention only when the tagger called it a noun (Czech `Zobraz`). The
English imperative `Show` (a verb) and the Czech count adjective `nejlepších` (an adjective) became
nothing, and in `nejlepších 10 prodejen` the `10` was looked up as a store and left a G4.

- Before span proposal, the resolver looks up the words in two slots with `Lookup`, restricted to
  `target_classes = [TARGET_CLASS_OPERATOR]`: an imperative verb (`Mood=Imp`), and an adjective
  beside a number that counts a noun (`nejlepších 10 prodejen`, `10 nejlepších prodejen`,
  `the 10 largest stores`). At most 4 words per question, concurrently, within the lookup rung's
  `budgetMs`, and not at all when the rung is off.
- A confirmed word is a mention bound to its operator (`op:show`, `op:top-n`, …), and a count
  beside a count word is that operator's argument, with no gap. A command word scopes no literal.
- A word the matcher does not confirm leaves no mention and **no gap**: a verb or an adjective is
  not an unknown noun, and asking about every command in a question would be noise. This deviates
  from #58's proposed gap on a miss.
- Only operator rows are accepted, so this path cannot bind a model object or a member (Q-20's
  over-generation guard). Across the 47 recorded frame-role questions it asks about 20 words; with
  the stdlib skills, 18 are operator words and the matcher drops the other 2.
- Needs a lex-matcher that serves `Lookup` with class filtering (the lookup rung already does), and
  one that answers within the budget. `RESOLVER_LOOKUP_BUDGET_MS` now overrides
  `resolver.lookup-budget-ms` (default 250) for an estate whose matcher is slower: on hartland a
  `Lookup` takes 300–500 ms, because lex-matcher lemmatizes each query through nlp, so every lookup
  round there is abandoned today, with or without this change.
- No wire change.

### resolver — a number that names something is a gap, not a silent grounding (#139)

A number the NER types (NameTag `cnec:n…`, Stanza `CARDINAL`) is a universal `MISC`, so no path
proposes it for a lookup, and a grounded `MISC` counted as self-sufficient. In `účet 501001` /
`account 501001` the account code therefore left with no attribution and no gap: it was lost, and
nothing asked about it.

- Such a value now gets a **G3** (`GAP_KIND_G3_UNATTRIBUTED`) when the parse shows it names
  something. Its head token carries a digit and comes **after** the noun it hangs off, with no
  preposition of its own (`account 501001`, `store 42`, `účet 501001`). It stays
  `VALUE_KIND_GROUNDED`; only the gap is new.
- Counts and thresholds are unchanged and still need nothing: a count before its noun
  (`top 10 products`, `10 nejlepších prodejen`), cs `Top 10`, `sales above 1000` (a preposition),
  an amount a verb takes, a bare number, and a spelled-out numeral. So are dates and amounts.
- The first cut (resolver 0.11.6) decided by relation (`nummod` = a count). Live, cs Stanza hangs
  the code in `Náklady na účet 501001 v roce 2025` off `účet` as `nummod`, and that code stayed
  silent. Word order is what separates the two.
- No wire change.

### resolver — a place is taken only by a member of an entity its anchor reaches (#118, ✅UD-7)

The member reading of a place or person name (UD-P1) asks two questions: the anchor's own member
vocabularies, and every member vocabulary. On a live estate the second one answered
`Stores in Paris` with a return reason ("Parts missing", a fuzzy match) and `Stores in Dallas` with a
warehouse ("Dallas DC"), and the answer took the span. Nothing was left to ask about, and the query
filtered by the wrong thing.

- A member now counts for that reading only when its entity is reached from the anchor's owner: the
  owner itself, its owning entity, or an entity that declares a relation from it (`Customers in TN`
  still binds `customer_address.state`). Other member rows are dropped before the Binder decides.
- `Stores in Paris` and `Stores in Dallas` stay places, as they were before UD-P1.
- Under a fact (`Sales in TN`), the ask is limited to the owners the fact reaches. A fact that
  reaches only one of them binds it. With no NER entity on the value, nothing changes: the ask still
  covers every owner.
- Values the NER did not type a place or a person are unchanged. No wire change.

### `ttr-nlp` — a NameTag entity ends where its last word does (#118)

`parse_nametag_conll` ended an entity at `start + len(" ".join(words))`. NameTag splits a hyphenated
name into three words (`Frýdku`, `-`, `Místku`), and an abbreviation into word and period
(`Ústí n. L.`), so the joined spelling was longer than the question and the entity's end ran into
the next word. The end is now the source position of the last word, and `text` is the source slice
(`original[char_start:char_end] == text`). An entity whose words were not all found in the source
keeps the old spelling and end. Single-spaced names are unchanged.

### resolver — a place or person name scoped to an entity is looked up as that entity's member first (#118, UD-P1)

The governed argument of a value-bearing anchor now gets a member reading even when the NER types it
a place or a person. In `Stores in TN`, `TN` is `GPE`, and `store.state` holds `TN`.

- **A member was found:** the member wins the span. The value is a `VALUE_KIND_LITERAL` finding with
  the member's attribution. There is no grounded place beside it, and no universal binding in
  `Resolution`. An ambiguity among members asks by owner (`member_of` per option). The member
  reading replaces the NER entity it was proposed over, even where the NER engine's offsets and
  the parse's tokens disagree on where the name ends.
- **No member was found:** the NER reading stands, exactly as before (`VALUE_KIND_GROUNDED`, G3).
  A hit on a declared term (a model object's alias, an operator word) is not a member: a place
  spelled like a declared term stays a place.
- **Nothing changes** for bare, unanchored and pre-modifier places (`TN`, `Nashville stores`), for
  dates and amounts, or for an argument of an operator or a measure.
- Each superseded universal is logged at INFO, with no user text:
  `seam: <type> [start,end) superseded by <origin> (<n> contenders) conversation_id=<id>`. The
  span's text is logged at DEBUG.
- No wire change.

### resolver — a coarse `MISC` entity with no CNEC code is a domain value, not a universal one (#118, UD-P0)

`UniversalClassifier` no longer types a coarse `MISC` as universal unless a code says it is a number.
This covers the label from Stanza/spaCy/LLM-emulated NER, and a NameTag entity whose tag was lost
(`cnec:` with no code).

- The name is proposed to the domain gate against every declared type, as a `cnec:o*` object always
  was. It is no longer extracted as a universal `MISC` binding.
- If it is proposed as a value and nothing binds it, the value carries a **G3** gap. Before, it was
  a grounded `MISC` with no gap at all. As a pre-modifier of an anchor (`Orion prodejny`) it joins
  the anchor's phrase instead, as any word with no entity does, and is not a value of its own.
- Numbers are unchanged: `cnec:n*`, `NUMBER`, `CARDINAL`, `ORDINAL` and `PERCENT` stay universal
  `MISC`, and so does a coarse `MISC` whose text has a digit and no letter (`501001`). An engine
  with no number label (the LLM-emulated NER) sends its numbers that way.

### resolver — a quoted literal with no trigger is looked up among its head's members (✅LP-13)

The quotes mean "this span is a value". With a `pred:` trigger the literal is a filter pattern, as
before. With **no** trigger, and attributed to a name attribute that owns a member vocabulary, it is
looked up in that vocabulary with its exact text. This is the first tier of the `lookup` rung, and it
reads MV's scope rule.

- **One match:** the literal binds. It becomes a LITERAL value with a member binding
  (`<attribute>#<id>`) on the same quoted span, and it keeps its `verbatim_text`.
- **Several matches:** G2 on the value, and the door asks.
- **No match, no vocabulary, or no answer from the matcher:** the VERBATIM value stays, with
  `pred:contains`. Nothing gets worse than it was.

**Wire (additive).** `ValueFinding.predicate_implied = 9` is true when `predicate_ref` carries §2.2's
default because the question named no predicate. `verbatim_text` is now also set on a LITERAL value
that a quoted lookup bound, and the re-gate refuses hypotheses on any value that carries it.

### `resolver.v1` — quoted literals, corrected (LP review-103)

What a consumer of the lattice will notice:

- **`ValueFinding.predicate_ref` is now set whenever a VERBATIM value is attributed.** With no trigger in
  the question, the resolver writes §2.2's default itself: `pred:equals` when the literal landed on the
  head's code, and `pred:contains` for its name or for an attribute the user named. Absent now means only
  "headless" (G3), where there is no facet to default from. Consumers no longer have to guess the facet
  from the literal's shape (closes ⚑LPQ-7). A blank `predicate_ref` from an older resolver still means
  "default".
- **Three more predicate refs can arrive:** `pred:not_starts_with`, `pred:not_ends_with` and
  `pred:not_equals`. They come from negated slice forms (*nezačínající na*, *not starting with*) and
  from a negator right before a trigger (*do not start with*). A consumer that does not know a ref must
  refuse it, as it already does.
- **A predicate form fires only when the question contains the whole form.** A fragment such as *named*
  (of *named exactly*) or *s názvem* (of *s názvem přesně*) no longer fires `equals`. Windows are now up
  to three words wide, the widest form ttr-lexicon's RG-LEX-032 admits.
- **The attribution window is measured edge to edge**, from the literal's delimiter to the nearest word
  of the mention. A head on the left wins, and one on the right is used only when there is none on the
  left. Predicates are taken from the left only. A mention bound to an **attribute** is its own answer
  (*s názvem "Valmy"* → the name attribute).
- **The code test is the head's pattern OR the fallback shape.** The fallback still needs a digit, except
  under a head that declares a code and no name.
- Words inside quotes are never mentions, and a phrase is cut at a literal rather than dropped.
- A target class this build has no constant for (a newer matcher) is carried through as its number and
  is never attributable, instead of failing the whole resolve.
- Literal spans are in the parse's code-point offsets, like every other span in the lattice. U+0085
  opens and closes a literal, as the shared corpus's fixture 29 pins.
### resolver — a value governed by an entity is looked up in that entity's member vocabularies (MV-T3)

The registry now learns every member vocabulary from the compiled lexicon archive
(`ttr-lexicon-compiled/v5`). A value governed by an anchor is looked up where that anchor's values
live.

**Behaviour**

- **Registry.** Every indexed attribute becomes a registry type, whether or not it has lexicon terms.
  Its category is its own ref, its owner is its entity, and it has no anchors. Before, an attribute
  without terms was absent from the registry. No question could reach its vocabulary except the
  cross-category lookup round, which attributed the value to every attribute holding it.
- **Governed lookup.** A value governed by an anchor (`stores in TN`) is gated to the anchor's own
  categories plus its entity's member vocabularies (`store.state`, `store.store_name`). Nothing else is
  added: `TN` under `stores` is not looked up in `customer_address.state`. The same rule scopes the
  anchored lookup round and `resolve.gate:v1`.
- **Re-gate.** A hypothesis naming an entity is confirmed by a member of that entity's own
  vocabularies (`…Account.code#501001` confirms `…Account`). A member of another entity's vocabulary
  still contradicts it.
- **Entity names.** A member binding's and a member option's `entity_type_ref` is now the owning
  entity (`er.entity.store`), not the attribute. `Option.member_of` still names the attribute, and the
  resume token now SIGNS it, so a resumed pin keeps its attribute. `Domain.member_of` carries it on the
  binding (fresh and resumed). `Binding.ref` is `<attribute>#<value>` and `Attribution.attribute_ref` is
  the attribute.
- **Member identity is (vocabulary, value).** Two rows are one answer only when both the category and
  the id agree. The same value in `store.state` and `warehouse.state` is two readings (the id alone
  used to merge them), and an option's id is `M:<category>#<value>`.
- **Unchanged.** A value whose governor owns no member vocabulary (`customers in TN`) is still found by
  the open lookup and decided by the governor's declared relations, as before. A v4 archive gets exactly
  the pre-MV answer.

**Wire.**
- `resolver.v1.EntityType.member_vocabulary = 10` (**additive**): the per-request registry override
  can mark a type as a member vocabulary, as the archive does.
- `resolver.v1.Domain.member_of = 6` (**additive**): the attribute a member binding is a value of.
- ⚠ A member option's resume token carries `memberOf`. A resolver older than this release refuses such
  a token (RG-RES-002) during a rolling deploy, and the user asks again. Other tokens are unchanged
  byte for byte.

### lex-matcher — member vocabularies are loaded from Veles, keyed by attribute (MV-T2)

The `metadata` loader (`FUZZY_LOADER_SOURCE=metadata`) now reads Veles' `ListMemberVocabularies`
instead of walking fuzzy-tagged columns and composing `SELECT pk, col FROM table`.

**Behaviour**

- **Categories.** One category per member vocabulary, named by the attribute's ref
  (`er.<ns>.<entity>.<attribute>`, or `db.<ns>.<table>.<column>` on a db-only estate). Two entities over
  one table are two categories, and a view- or query-backed entity reads its own population.
  ⚠ Category names change from the column form (`db.dbo.store.s_state`) to the attribute form
  (`er.entity.store.state`) for every estate that indexed an ER attribute.
- **Read plans.** Each vocabulary's rows come from the read plan Veles rendered for the warehouse's
  dialect. The loader always sends that dialect (`POSTGRESQL` / `MSSQL`) and composes no SQL of its own,
  alias tables aside. A plan always runs as a query, including one that opens with `WITH` or `(`.
- **Members are values.** A vocabulary's candidates are its attribute's DISTINCT values, and a
  candidate's id is the value as the warehouse stores it. The read plan returns one row per entity row,
  so three stores in Tennessee used to be three `TN` candidates with three keys. ⚠ `candidate_id`
  (and so `resolved_id` and the member half of `Binding.ref`) changes from the entity's key to the
  value. That id is what a consumer compares the attribute against, so a key there filtered
  `state = '7'`. `GetStatus` sizes count values.
- **Match methods.** Every member row carries its vocabulary's match method, so the dispatcher holds an
  `EXACT` code to exact equality (`tn` finds `TN`; `tx` does not). Before, member rows carried no method
  and every one was matched partially. A method the matcher does not know is matched `EXACT`, with a
  WARN.
- **`GetStatus`.**
  - `CategoryStatus.match_method = 5` (**additive**) shows each member category's method.
  - Warnings carry Veles' own diagnostics for vocabularies it could not plan: `RG-FUZ-001` (no single
    key) and `RG-FUZ-003` (no read plan).
  - New `RG-FUZ-004`: there is no listing at all. Causes are a Veles older than member vocabularies
    (UNIMPLEMENTED), a model that is not loaded yet, or a transport failure. The previous load keeps
    serving; a not-ready Veles no longer wipes the member layer. Before any listing, the service still
    comes up: it is ready, it serves its declared lexicon and overlay, and it retries the listing after
    5 s, doubling up to the refresh interval. The warning says no member vocabulary has loaded yet.
  - A vocabulary that is listed but unreadable this time keeps its previous rows. That covers no read
    plan (a query-backed entity in Veles' parse window) and a read plan the warehouse refused. Only a
    vocabulary Veles stops listing leaves the layer.
- **`member_index_versions`.** Now folds the read plan's identity (plan, key, method) into the content
  hash. A changed plan moves a category's version even when it reads the same rows, and changed rows
  still move it. An unrelated model edit does not move it: Veles' own item version hashes the model
  version too, which is why it is not the one folded in.
- **Matching.** A member row is held to its own method but takes part in neither `method_override` (a
  lookup round's override applies to declared terms only) nor the uniqueness margin (two near-identical
  values are a clarification, not a veto). An `EXACT` or `TYPOS(n)` vocabulary is scored with the same
  headroom a declared term gets, so an exact value ranked just past the limit is still found. A
  cross-category lookup reports each member's own category, where it used to report `unknown`.
- **Refresh.** One refresh at a time: `POST /refresh` waits for a scheduled refresh in flight.
- **NULL rows.** A row with a NULL key or value is skipped. Before, one NULL lost the whole category.

**Config and metrics**

- `fuzzy.metadata.schema` / `FUZZY_METADATA_SCHEMA` are removed.
- `FUZZY_METADATA_NAMESPACE` now guards the attribute's namespace.
- `fuzzy_loader_skipped_total{no_pk|composite_pk|…}` is replaced by
  `member_vocabulary_skipped_total{reason}`, with reasons `no_key`, `no_read_plan`, `wrong_source`,
  `sql_failed` and `alias_sql_failed`.
- The static catalog (`fuzzy-catalog.json`) gains an optional `methods` map. A member category it does
  not name is `EXACT`.

**Release together with Veles (MV-T1).** A Veles without `ListMemberVocabularies` leaves this loader on
its previous load (`RG-FUZ-004`). A pre-MV lex-matcher against an MV Veles would index `EXACT` carriers
with no method, i.e. partially.

### `meta.v1` — member vocabularies (MV-T1): `ListMemberVocabularies`, and `SearchHints.indexed`

A member vocabulary is the set of values one attribute takes over its entity's population, indexed for
matching. Until now the lex-matcher built these itself: it listed "fuzzy columns" and composed its own
SQL per physical column. So two entities over one table shared one index, and a view- or query-backed
entity was indexed over its whole base table. Veles now publishes one vocabulary per indexed
**attribute**, together with the read plan the translator renders from the entity.

**Wire (additive)**

- `VelesService.ListMemberVocabularies` returns `MemberVocabulary` items, one per carrier with a member
  vocabulary, ordered by `category` and paged by it. Each item carries:
  - `category`: the attribute's dotted qname, byte-equal to its compiled-lexicon target ref;
  - `attribute` and `entity` (its owner), plus `key_attribute`;
  - `read_sql`: `SELECT <key>, <value> … GROUP BY … ORDER BY <key>` rendered for the request's `dialect`
    (a `translate.v1.SqlDialect` value name);
  - `match_method`, `version` (SHA-256 over model version, `read_sql`, key and method), and `diagnostics`.
- `SearchHints.indexed = 8` and `match_method = 9`. `fuzzy = 6` now means "the match method is partial"
  (TYPOS/TOKENS). For every carrier that does not author both a method and the deprecated boolean, it
  reads the same as before.
- `ListObjectsRequest.indexed_only = 14`. `fuzzy_only = 12` is its deprecated alias for one release,
  and one WARN per process is logged when it is used. ⚠ `method: EXACT` carriers are now indexed and are
  therefore listed.
- Diagnostics: `RG-FUZ-001` is reworded from "fuzzy column" to "member vocabulary". New `RG-FUZ-003`
  (WARNING) means a vocabulary has no read plan, and names why: an expression-mapped attribute, an owner
  outside the translator's default namespace, or the translator's own error.

**How `read_sql` is rendered.** Veles renders it through `SnapshotModelHandle` over its own
`GetSnapshot`. That is the exact adapter the translate service builds for every ER query, so an index
reads an entity the way a query over it does. The adapter moved from `services/translate` into the new
`shared/libs/kotlin/translate-snapshot` for this reason.
- A query-backed entity renders over its saved query once that query has parsed (#112). The listing is
  cached per `GetSnapshot` ETag, so it follows the parse instead of freezing the first answer.
- Two carriers are listed with `RG-FUZ-003` rather than read some other way, because a query could not
  read them by name either: an expression-mapped attribute (the translator's ER catalog has no
  expressions; the rest of its entity renders, #113), and a carrier whose owner lives outside
  `er.entity` / `db.dbo`, the one namespace per schema the translator resolves an unqualified name in.
- A carrier that is its entity's own key (a code entity keyed by its code) renders as two columns, the
  second aliased.

**Consumers:** built on `org.tatrman:*:0.13.6`, where `SearchHints.indexed` + `matchMethod` split the old
`fuzzy` bit.

### `meta.v1` GetSnapshot — the snapshot carries every parsed query's plan, and its ETag moves while they land (#112)

An ER entity mapped to a saved query (`binding: { target: { query: … } }`, the shape a
row-filtered entity is expanded into) could not be queried in the ER lane at all: the translate
service builds its model from `GetSnapshot`, and the snapshot never set
`QueryDetail.canonical_form`, so the entity expanded into an empty plan and every query over it
failed with `sql_unparse_failed: PlanNode case 'NODE_NOT_SET'`.

**Behaviour**

- `GetSnapshot` sets `QueryDetail.canonical_form` for every query the parse worker has parsed —
  the plan `GetQuery(include_canonical_form)` returns.
- The plans land *after* a model swap, so `GetSnapshotResponse.etag` is now
  `<ModelVersion.value>.q<n>` while Veles tracks live parse state, and moves as they land. A
  consumer that polled during the parse window gets the complete snapshot on its next conditional
  read instead of keeping the plan-less one until the model next changes. It settles once parsing
  is done. Without a live parse state (fixture boots) it stays the bare version.
- A plan is only ever served for the model it was parsed against. Right after a model swap, until
  parsing for the new model starts, a saved query is reported PENDING with no plan, where it used to
  carry the previous model's. A late parse job from the previous model can no longer overwrite the
  current result either.
- **The ETag is no longer the model version.** It never was a documented one to rely on, but the
  proto comment said so: read the version from `snapshot.model.version` or
  `GetStatus.model_version`. `validate` did read it as the version; it now reads `GetStatus`
  (which also stops it shipping the whole model per `Validate`).

### translate — an expression-mapped attribute no longer makes its entity unqueryable (#113)

An ER attribute may be mapped to an expression instead of a column
(`target: { expression: "…" }`). The translate service kept such an attribute in its entity's
ER catalog, although nothing can compute it — the translator's `ModelHandle` has no way to carry
an expression. So it rode along in every scan of the entity, the physical table was asked for a
column that does not exist, and **every** ER query over the entity failed with
`sql_unparse_failed: field [<attr>] not found`, including queries that never named it.

**Behaviour**

- The snapshot adapter (`SnapshotModelHandle`) leaves out of the ER catalog every attribute whose
  `er2db_attribute` mapping names no column. The rest of the entity is queryable again.
- A query that names the expression attribute itself fails as an unknown column, at
  validation. Querying expression attributes needs translator support and is not part of this
  change.
- An attribute with no mapping at all is unchanged: it still reads the column of the same name.

### `query.v1` / `validate.v1` — a caller-stated row window, a ceiling, and a warning when the cap binds

`validate` caps every answer at `default-top-n` rows by injecting a `LIMIT`, and the cap was a
hard ceiling a caller could only lower: `effectiveCap = min(requested, default)`. It also said
nothing when it applied, so a capped answer and a complete one looked the same to the caller —
and there was no way to ask for the next page.

**Wire (additive — no message changed meaning)**

- `RunRequest.row_window` (`RowWindow {limit, offset}`) — the rows the caller wants. `query`
  puts it on the plan as the root `LimitOffsetNode` and forwards `limit` as
  `ValidationOptions.default_top_n`. Unset is exactly the old behaviour. Paging over it is only
  sound for an answer with a **total** order (an `ORDER BY` ending in a unique column).
- `ValidationOptions.default_top_n` is documented as what it always was in practice: the rows
  the caller *asked for* (0 = unstated).

**Behaviour**

- `validate` gains **`max-top-n`** (`VALIDATE_MAX_TOP_N`) beside `default-top-n`:
  `cap = min(requested > 0 ? requested : default-top-n, max-top-n)`. **Unset, `max-top-n` equals
  `default-top-n`** — the old rule, unchanged for every deployment that does not set it.
  `/status` reports both.
- `validate` raises a **`top_n_applied` WARNING** when the cap bounds a plan below what was asked
  (a limit injected where the caller stated none, or one lowered), naming the cap and the request.
- `query` passes that warning on **on the last `ResultBatch`, and only when the answer reached the
  cap** — the validator sees the plan, not the data, so a 5-row answer under a 200-row cap is
  complete and says nothing. A full page carrying `top_n_applied` means the estate's ceiling, not
  the end of the data.
- `query` refuses a negative `limit`/`offset` in-band with `invalid_row_window`.

### `resolver.v1` — mention homonymy: slots, declared equivalence (MH-P1)

One word claimed by two objects — a dimension and the fact a channel vocabulary is
pinned to — used to reach the Binder as two unrelated identities in one tie band and
leave as a clarification. It now gets decided from the sentence's syntax and the
model's declared relations, and refuses only when the declarations say the two
readings differ.

**Wire (additive, J-v2 — no message changed meaning)**

- `EntityType.reached_from` (`repeated Reach {fact_ref, mandatory}`) — the facts with
  a declared relation TO this ref, with the to-side lower bound. Supplied by both
  channels: the per-request registry override, and the compiled lexicon archive's
  `targets[ref].reachedFrom` at schema `ttr-lexicon-compiled/v3`.
- `Binding.equivalents` (`repeated EquivalentReading {ref, rule}`) — a reading the
  Binder proved **equal by declaration** to this binding and suppressed
  (`rule = "reach-equal"`). Disclosure, not a second binding: surface it, do not
  re-plan on it. Two readings can be declared-equal and still differ on dirty data.
- `Option.object_kind` — a clarification option's species, so a residual question can
  be worded *"the stores (a dimension), or the Stores channel (sales)?"*.

**Behaviour**

- A tie between candidates of **different** mention kinds is decided by the syntactic
  slot (count head · group-by · governed value · filter under a measure head ·
  coordination). A **same-kind** tie is genuine homonymy and still refuses.
- A `{dimension, fact}` tie collapses to the dimension when the model declares that
  every row of the fact the clause is about carries that dimension — and is forced to
  a clarification when the key is nullable, because then the two readings select
  different rows.
- Nothing else moves: the frozen frame-role corpora and the four hero lattice goldens
  are unchanged.

**Compatibility.** Every input is defaulted and absent-tolerant, so an estate that
declared nothing behaves exactly as before: a pre-v3 archive, a registry with no
`object_kind`, a `frame-roles.conf` with no `count-heads`, a re-gate with no parse —
each leaves both rules inert.

⚠ **"No reader gate" is a fact about the FIELD, not about every estate.** The v2 → v3
crossing needs none — `reachedFrom` is a defaulted field inside `targets`, so a v2
archive decodes in a v3 reader and a v3 archive is read by a v2 reader as a v2 one.
An estate still on **v1** is a different crossing: v1 → v3 adds the `targets` map
itself, which is the v1 → v2 step, and that one **does** carry the mention-facet
reader gate (readers before producers). Such an estate must either take the v2 step
first or carry that gate with its v3 rebuild — check the archive's `schemaVersion`
before rebuilding, not the resolver's version.

**Config.** `frame-roles.conf` gains `count-heads` beside the two prep tables. A file
written before this release loads unchanged and never fires the count slot.

**Requires** the tatrman toolchain at `0.13.3` (`ttr-lexicon` `Reach` +
`TargetFacts.reachedFrom`). ⚠ That cut also makes `ttr-metadata` parse a relation's
authored `cardinality:`, which the file loader previously hardcoded to
`(0, -1, 0, -1)` — any reader of a loaded `Relation.cardinality` now gets real
numbers where it used to get zeros.

Detail: [`docs/resolver-slots-and-equivalence.md`](docs/resolver-slots-and-equivalence.md).

### `ttr-nlp` 0.1.0 — the first wheel (NLS-P0…P4)

The NLP suite as a library: an annotation model, a JAPE-class rule engine,
gazetteers, the pack loader and validator, and the clients. Apache-2.0, imported
as `ttrnlp`.

**In this release**

- **Annotation model** — engine JSON → a pinned gatenlp `Document`
  (`gatenlp==1.0.8`, an exact pin: the compiler targets PAMPAC internals). One
  annotation per (type, span); duplicates are dropped and counted rather than
  stacked.
- **The rule DSL** — YAML with JAPE's vocabulary (phases, `input:` visibility,
  the five control styles, priorities, bindings), a JSON Schema, cross-checks,
  and a compiler onto PAMPAC. The right-hand side is `add` / `update` over a
  closed set of getters: **no code in a pack, ever**.
- **The JAPE-exact executor** — `appelt` tie-broken longest → priority → file
  order, plus `brill` / `all` / `first` / `once`. 35 conformance cases.
- **Gazetteers** — one YAML file per list, four matching modes (`lemma`, `ci`,
  `fold-diacritics`, `exact`), multi-token terms, longest-match, provenance on
  every `Lookup`. **No scoring** (NL-17) — approximate matching stays world-side.
- **Pack loading** — fail-all-or-nothing over directories, files and `http(s)`
  URLs; an immutable snapshot with a content-keyed `state_id`.
- **`ttr-nlp validate`** — the CLI, running the same code path as the service's
  boot load and `ReloadPacks`. `--model` adds the query/parameter cross-check.
- **`Document ⇄ proto`** — the `org.tatrman.nlp.v1` annotation surface, with the
  output filters.
- **`NlpClient`** — an async gRPC client whose `run_pipeline` returns a
  `Document`, not a wire message.
- **`ttrnlp.client.backends`** — the HTTP engine-adapter clients (moved here from
  `services/nlp` at NLS-P3.3).

**Not in this release**

- Czech morphology (`ttrnlp.morph`) — NLS-P7…P9.
- Anything trained on the CAC corpus — post-v1 by ruling (⚑GXP-D5).
- Rule-pack and list **content**: never part of the suite (NL-17). Each world
  maintains its own.

**Extras.** `[grpc]` for the client and the serializer, `[http]` for the backend
adapters. The core install is deliberately model-free — `gatenlp`, `pydantic`,
`pyyaml`, `jsonschema` and nothing else — which is what lets the rule engine run
in-process inside the engine-free `nlp` front.

### `org.tatrman.nlp.v1` — the pipeline surface (NLS-P3.1)

**Additive only**: 155 insertions, no existing field number, name or type
touched. `services/nlp/tests/test_contract_shapes.py` freezes every message's
fields as literals, so a renumbering has to be written down to pass.

- **New rpcs** — `RunPipeline` (run a named pipeline, get an annotated document),
  `ReloadPacks` (re-read the configured pack sources; sources never come from the
  request), `ReportToken` (the LM queue sink's front door — answers
  `accepted=false` until NLS-P9 wires it).
- **New messages** — `FeatureValue` / `FeatureValueList` / `Annotation` /
  `AnnotationSet` / `AnnotatedDocument`; `RunPipelineRequest` / `Response` /
  `PhaseTrace`; `ReloadPacksRequest` / `Response` / `PackDiagnostic`;
  `PipelineInfo` / `PackState`; `ReportTokenRequest` / `Response`.
- **`StatusResponse` gains fields 3–5** — `lane`, `pipelines`, `pack_state`.
  Fields 1–2 are untouched.

`Analyze`, `BatchLemmatize` and `GetStatus` request/response shapes are
byte-untouched. Themis, Echo and kantheon need no change.

### `services/nlp` — lanes and the pipeline surface (NLS-P3.2)

Deployment-visible, so it is here even though the image is not a published
artifact.

- **`lane: default | option` (NL-4).** `default` is Stanza + spaCy only — anyone
  may run it. `option` adds the UFAL stack, whose licence is a per-deployment
  decision (NL-5). Set it with `NLP_LANE` or the chart's `lane` value.
- **⚠ `config.yaml`'s `op_routing` is now the DEFAULT-lane table.** The Czech
  MorphoDiTa/NameTag routing moved verbatim into `lane_overrides.option`. **A
  deployment that wants today's behaviour must set `lane: option`.** Leaving it
  at `default` is a working service with no Czech NER: cs `NER` gets no
  `GetStatus` capability row, responses carry `NLS-NLP-011`, and every other
  phase still runs (NL-14).
- **New config sections** — `packs.sources`, `lists.sources`, `pipelines`. All
  optional; a front with no packs serves `Analyze` exactly as before.
- **New chart values** — `lane`, `packs.configMapName` / `mountPath`,
  `lists.configMapName` / `mountPath`. The mounts are off unless a configmap is
  named, so a chart that has never heard of a rule pack renders byte-identically.
- **New diagnostics** — `NLS-NLP-011` (op unrouted in the active lane),
  `NLS-PACK-010` (reload refused), `LM-MORPH-007` (token report not sunk).
