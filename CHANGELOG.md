# Changelog

All notable changes to what this repo **publishes** are recorded here: the
`ttr-nlp` PyPI wheel, the `org.tatrman:*` Kotlin artifacts cut from
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
  alias tables aside.
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
    serving; a not-ready Veles no longer wipes the member layer.
- **`member_index_versions`.** Now folds Veles' read-plan version into the content hash. A changed plan
  moves a category's version even when it reads the same rows, and changed rows still move it.
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
