# worker-docproc

The stateless Python worker runtime for deterministic document processing. Designed
and planned in `project/kantheon/features/hermes/` (DX-P3). Version stays on 0.11.x.

S3.1 provides the runtime, clients, validators, probes and packaging. S3.2 registers
`import`, `lemmatize`, `classify`, `nlp-pipeline`, `diff`, and `chunk`. S3.3 adds `link`, `rule` and `record-etl`. `Noop` is test-only
and is never registered by the bootstrap.
Outward calls are held behind `Warehouse`/`Manager` protocols, exercised against
in-memory fakes until P1/P2 supply the services and phase certification runs.

The worker polls a family's `_hi` queue first, fetches units/annotations by id,
runs its immutable processor version under its timeout, validates the outputs and
loads one annotation batch through the warehouse API. Failed postconditions drop
the offending output and produce `Verdict` annotations. A redelivery after the load
finds the same run's ids and avoids a second load. The protocol artefact, completion
and queue acknowledgement commit in one pipeline-DB transaction. A unique artefact
row also suppresses duplicate completions for duplicate deliveries of a run.

Kinds run synchronously on the main thread on Linux/macOS. A POSIX interval timer
interrupts a timed-out kind; no executor thread continues writing afterward.
Heartbeat renewal runs separately. SIGTERM stops fetches and finishes the current
run; forced termination leaves unfinished work unacknowledged for redelivery.
`poll.batch` is a per-family quota: each message is leased immediately before it
runs so queued items do not consume their timeout waiting inside a worker.

## Run and configuration

```sh
uv sync --locked
PIPELINE_DB_URL='postgresql://…' DOCWH_STORE_URL='http://docwh-store:7261' \
DOCWH_PIPELINE_URL='http://docwh-pipeline:7280' uv run python -m docproc.main
```

`conf/application.conf` uses HOCON with optional environment overrides. Required:
`PIPELINE_DB_URL`, `DOCWH_STORE_URL`, `DOCWH_PIPELINE_URL`. Pool defaults to `cpu`;
`DOCPROC_FAMILIES` optionally narrows the hosted registry to comma-separated families.
`DOCPROC_CONFIG` selects another HOCON file. `DOCPROC_MAX_DELIVERIES` defaults to 10
and caps redelivery even when the manager is unreachable. Hosted manifests must
declare `budget.timeout_s` and `budget.retries`; retries are additional attempts
after the first delivery. Transport errors, store 401/429/5xx and PostgreSQL
operational failures stay leased for retry. Permanent processor lookup failures
archive with a FAILED completion; permanent annotation-load refusals fail once.
`NLP_GRPC` is required when an NLP kind runs (gRPC, normally `nlp:7271`).
`DOCPROC_PACK_PATH` names the mounted pack for classify gazetteers and `pack:` chunkers.
`LEXMATCHER_GRPC` is required for lexicon linking (normally `fuzzy:7266`); the world
connection for record-etl follows `TTR_CONN_EXTRACT_RECORDS`. Gateway settings remain P4-owned. Connection strings are excluded from configuration repr.

Service identity uses `DOCPROC_SERVICE_TOKEN`, or the file named by
`DOCPROC_SERVICE_TOKEN_FILE`; no OBO credentials. A notebook query is omitted when
none is supplied. Candidate inputs are active annotations plus the current
generation; the `generation` load/read seam is recorded in DX contracts §1.4 and
must be implemented by P1 before certification.

HTTP port `7310` exposes `/health`, `/ready`, `/status`, `/metrics`. Readiness requires
successful bootstrap (hosted manifests and mounted-pack integrity) and a successful
pipeline-DB `SELECT 1`. Registry versions are loaded only from each item's pinned
plan. Drain the families before switching the mounted pack. Telemetry defaults
off; enable `DOCPROC_TELEMETRY_ENABLED` with `OTEL_EXPORTER_OTLP_ENDPOINT` to export
the per-item `docproc.run` span and shared platform telemetry.

## Verification and fixtures

```sh
uv run --locked ruff check .
uv run --locked mypy
uv run --locked pytest                # unit + local Docker/Postgres component tests
uv run --locked pytest -m 'not component'
uv run --locked python scripts/gen_proto.py
```

Fixture source: `kantheon/shared/proto/fixtures/dx/packs/hartland-mini` and sibling
`schemas/` at merged PX commit `64ad33f6`. The mirrors are byte-identical, JSON is
canonical, documents NFC. Set `KANTHEON_DIR` to that checkout for the peer checks.
The engine `types/` mirror comes from P1 `4f0b263b`, with P3's optional `Chunk.oversize`
field. P3 supplies `chunk-validators.json`; P1's `loadSource`, `unitTree` and `loadLemmas`
fixtures are also mirrored. `when-cases.json` now has 40 Python-verified canonical cases; the Kotlin twin remains
pending P2 S2.1. `Decision.chosen` is optional for abstention. Set `KANTHEON_DIR` to the P3 canonical-fixture checkout
for complete peer checks. Runtime schemas are packaged under `_data/schemas/` and
`_data/types/`; the actual model registry is fetched from the manager, never copied
into Python classes.

Per-kind goldens cover all six documents; ids in them are stable `g:` references.
Failures print the first differing annotation and its sliced text. The fixture unit
trees follow PX's sections, including heading/table-row code-point metadata from the
original documents. The real Kotlin `ParagraphSplitter` (kantheon `4f0b263b`, Kotlin
2.0.21, `minWords=30/maxWords=200`) produced `tests/fixtures/paragraph-kotlin.json` for
all 23 sections; recursive sheet boundaries/token counts also match PX exactly.

`tests/fixtures/nlp/` holds actual gRPC replies from the local production NLP front
and the pinned Stanza/spaCy backend images, including an emoji-before-mention proof.
The recording front builds test-world lists from PX's gazetteer (including terms
lemmatized by the real model), plus explicit test calendar/modifier rules. These
lists configure the recording service; production NLP pipeline configuration is
owned by the world deployment. CI replays the replies through an in-process gRPC
servicer and never needs a live NLP service or model downloads. To record again:

```sh
# Repository root; existing local model images, loopback ports, disposable containers.
docker run --rm -d --name dx-p3-stanza -p 127.0.0.1:18090:8090 ttr-nlp-stanza:dev
docker run --rm -d --name dx-p3-spacy -p 127.0.0.1:18091:8091 ttr-nlp-spacy:dev
uv sync --directory services/nlp --locked
uv run --directory services/nlp python scripts/gen_proto.py
PYTHONPATH=services/nlp/src:services/nlp/generated services/nlp/.venv/bin/python \
  workers/worker-docproc/scripts/serve_fixture_nlp.py
# Another terminal, worker directory:
PYTHONPATH=. uv run --locked python scripts/record_nlp.py --target 127.0.0.1:17271
# Stop the recording front, then the two named containers when finished.
```

The processor protocol follows DX's summary spine (ids/hashes, no unit text).
`tests/fixtures/protocol/noop.json` is a local canonical golden; P2's processor proto
members and `runArtefact.processor.json` strict fixture remain an explicit exit gate.
The pipeline-owned `run_artefacts` table is created only by a SQL fixture in tests;
production schema migration belongs to P2 S2.2.

NLP/fuzzy/common gRPC stubs are generated from this repository's shared `.proto`
tree into the private `docproc._generated` namespace. CI regenerates them with the
locked compiler and checks for drift; generated code is excluded from Ruff/mypy.
No Kantheon proto stubs are imported.

## Structural kinds and chunkers

`import` reads the split-reanchored bundle via `GET /v1/bundles/{ref}` and retains
connector provenance (`import:<connector>@<version>`). Each invalid fact becomes
`import/rejected`; valid siblings still load. `lemmatize` batches by language and
`engine.batch` (64 by default), writes the lemma plane only on ACTIVE runs, and refuses missing
S-1 model identities or wrong result cardinality before any writes. Artefacts record
NLP calls and model identities; CANDIDATE/SHADOW lemmas are hashes in the outputs
section and never change live lemmas. Promotion must re-plan lemmatize at ACTIVE;
candidate full-text evaluation uses the previous live lemmas. Any future sink kind
must follow this ACTIVE-only world-write rule. gRPC propagates the current trace context.

`classify` evaluates ordered deterministic rules: heading regex, metadata, language,
and pack gazetteer matches; first match wins. PX's `when_heading`/`when_field` aliases
are accepted. Its `claim_body` default belongs to mini-extract; sheets are outside
that feed. `nlp-pipeline` supports the manifest's declarative `map`; PX's omitted map
uses Lookup[item]→Mention, DATE→Date and Modifier→Modifier. Invalid offsets/surfaces
are Verdicts. Relative DATE entities without normalized values become
`nlp/date-no-value`; normalized duplicate layers take precedence. `diff` aligns the
previous document version by title path/index, then content hash. Removed units
appear in the artefact only.

Built-ins: `paragraph`, `fixed-tokens`, `sentence-window`, `recursive`, `structural`,
`page`, `table-aware`. The kind produces spans over existing section units, never
CHUNK units or duplicated text. Structural/page/table strategies consume metadata
from split and degrade to recursive/one-unit/prose chunking when absent. Table row
groups repeat the header as an extra span; unavoidable oversized rows carry
`oversize: true`. Prefixes use the document/ancestor titles and current Section hint.
`paramsHash` hashes canonical manifest params (sorted, two-space JSON + newline).
Token counts for multi-span chunks use slices joined with a newline.

Tokenizers: `tiktoken:cl100k_base` (also `cl100k_base`) by default, or `whitespace`.
The encoding is vendored under `_data/tiktoken/` and `tests/fixtures/tiktoken/`, and
SHA-256 checked before loading. The image sets `TIKTOKEN_CACHE_DIR` to its baked
cache. Missing/corrupt data fails locally; the worker does not fetch tokenizer data.
Token windows respect complete Unicode code points, including astral characters.
Token windows encode the full unit once for candidate token/code-point offsets,
then recount each emitted span after separator cuts and trimming. Overlap counts
start after leading whitespace, matching the next emitted chunk. These local
checks preserve both token limits without re-encoding the remaining document.
`max_tokens`/token overlap apply to token windows; sentence windows use `n` (PX alias
`window`) and sentence overlap. Paragraph word limits default to Kotlin's 30/200;
page/paragraph/sentence strategies can omit a token cap or declare an explicit one
(which the validators enforce).

Plugins: `plugin:<entry-point>` from `docproc.chunkers`, or
`pack:chunkers/<module>.py#<Class>` inside `DOCPROC_PACK_PATH`. Trusted reviewed code
runs in process. It implements `Chunker` (`name`, `chunk(text, unit, params)` →
`list[ChunkSpan]`, optional `partial`). Runtime always checks spans, full non-blank
coverage (unless partial), declared overlap and token bounds, even if the manifest
omits postconditions. `engine.partial` (or `params.partial`/plugin partial) permits
partial coverage. Plugins receive a deep unit copy; SystemExit becomes a plugin
Verdict, and the timeout timer repeats if a plugin swallows its first signal.
Reviewed in-process plugin exception text remains in `chunk/plugin-error` diagnostics.
A failed plugin/unit yields Verdicts and no chunks for that
unit. `semantic`/`summary-prefix` are refused here; P4 hosts those in docproc-llm.
The dev-only installed test distribution is absent from the production image;
pack plugin fixtures live outside the shared pack mirror.

## Deterministic extraction

`link` makes one category-scoped BatchMatch call per item and unions it with
concept-page recall over the mention's sentence. The explicit `targets` map turns
model qnames into cn concepts; MEMBER hits supply the instance id. It enriches
unlinked mentions and gazetted concepts that still lack a member. A score/margin
failure emits a Decision without chosen and yields ABSTAINED when nothing links.
Concept-page hits are limited to the family targets. They support a member
candidate's provenance but never raise its lexical score; weak members still
abstain. The runtime preserves abstention status on load-before-ack recovery. `pick: llm` is refused.

`rule` validates the pack rules file, joins annotations by declared spatial
relations, evaluates a recursive-descent predicate parser, and expands only closed
references/literals/coalesce. It fills every bound id into derivedFrom. Repeated
surfaces of one linked member bind once per anchor, in span order. Raw anchors or
bindings with a present refinement are excluded. Function engines
come only from the `@rule_fn` registry (`normalize`, `consolidate`); arbitrary imports
are refused at bootstrap. Every emitted payload validates against the manager registry.

`normalize` merges document-local duplicate statements and scoped modifiers, keeping
alternate values in `normalize/conflicts` Decisions. `consolidate` blocks within
one subject, reads occurrence/state policy, retains temporal history and open
conflicts, and assigns evidence roles. Source/human provenance is required for
known identity. Maximum evidence overlap carries an old key across reruns; the
largest split retains it. Merge/split redirects appear in the artefact.
The frozen renderer has no recordKey/subjectId fields: companion `consolidate/key`
Decisions persist the key, subject and exact evidence set. ETL requires that Decision.
Records hang, without spans, on the newest evidence's DOCUMENT root.

`record-etl` accepts only registry entries with feedsRecords=true. It maps Refs
through dimension lookups, checks decimal scales exactly and validates dates. ACTIVE
replaces one subject's rows in one transaction under a subject advisory lock; an
insert failure rolls back the delete. Any rejected record fails the entire ACTIVE
run with `record-etl/rows-rejected`, loads no annotations and preserves the last
good world generation; the artefact records only annotation ids and rule codes. CANDIDATE/SHADOW calculate a diff and full
candidate rows in the artefact without writing the world DB. Candidate outputs are
labelled VERBOSITY_FULL and need P2's storage/redaction floor before certification.
Fresh runs emit RecordRowRef pointers for lineage. `row_id` hashes the canonical
JSON array `[entity, subject, recordKey]` (SHA-256). Diff comparison uses typed
numeric/UTC instant equality and excludes run provenance (`annotation_refs`,
`consolidation_run`). Load-before-ack recovery restores stored annotation ids/status,
but kind sections (`recordRows`, link candidates, consolidate recordKeys/redirects,
and NLP evidence) can be lost; `errors.recovery: sections-lost` marks that path
until P2 supplies durable sections.
The legacy generated review_id is a surrogate; row_id is the stable identity.

Each item requests its plan-pinned Graph from `GET /v1/plans/{planId}/graph` and
loads precisely registryVersion. This route is an explicit P2 integration seam;
missing/mismatched versions fail the message. The mounted model hash and every
indexed generated file/schema must match, including the schemas returned by the manager.

**Accepted P3 proof (review-182 R182-A):** its deterministic review DSL emits item/rating/verification only,
while Record.review requires attributes/modifiers assigned to the LLM mentor.
The six-document flow correctly reports incomplete records. A separate, explicitly
complete Statement fixture proof produces the PX row oracle with resolvable lineage
and stable ids. It does not claim the unchanged deterministic pack produces those rows.
P4 exit must prove unchanged pack → complete rows once `review-extract` supplies
the LLM-owned fields. The frozen review DDL also lacks the fixed row_id/subject_id columns and uses text
annotation_refs; `tests/fixtures/sql/record_columns.sql` shows the required migration
on the disposable world DB. The worker never changes DDL.

Validation uses Draft 2020-12 with every declared format checker present, including
RFC3339 date-time. `date_parses` applies only to Date annotations; model date fields
are checked by `schema_valid`. Schema and scalar Verdict messages contain rule/path
codes without payload values.

See [docs/README.md](docs/README.md) for integration gates and fixture provenance.

## Image and deployment templates

From the repository root:

```sh
docker build -f workers/worker-docproc/Dockerfile -t worker-docproc:dev .
helm dependency build workers/worker-docproc/k8s --skip-refresh
helm template docproc workers/worker-docproc/k8s
kubectl kustomize workers/worker-docproc/k8s/overlays/local
```

The chart uses the shared `tatrman-service` library, exposes HTTP probes only, and
reads the pipeline DB from a Secret. World-DB and service-token Secret references
are optional values. The local Kustomize overlay uses the locally built image.
Live/deployed checks remain pending until the phase's certification after P2.
