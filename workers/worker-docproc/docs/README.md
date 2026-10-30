# docproc integration notes

Source plan: DX-P3 under `project/kantheon/features/hermes/` (outside this repo).
The README documents configuration, kinds, chunk SPI and the mounted pack.

S3.3 local checks use real PostgreSQL 18/pgmq and generated gRPC clients with replay
servers. `tests/extraction.py` supplies explicitly complete Statement inputs from
the PX row oracle; `test_extraction_runtime.py` distinguishes that row proof from
the incomplete frozen deterministic flow. Goldens retain Unicode/code-point spans.
`tests/fixtures/lexmatcher/README.md` distinguishes actual Hartland 0.11.30 capture
from authored PX positive cases. The historical 0.11.5/archive-v6 pin is not claimed.

Pending before phase exit/integration/certification:

- P4 exit carry C1 (review-182 R182-A): unchanged pack → complete rows after
  `review-extract` supplies LLM-owned fields. P3's revised, separately labelled
  sparse-flow and complete-Statement proofs are accepted; no invented values.
- P1 exit: annotations/linked MENTIONS, page findSimilar/conceptRef, active generation
  reads and load semantics on the real service.
- P2: plan-pinned graph route/registryVersion; execute shared when cases on Kotlin;
  strict processor artefact fixture/proto sections and production artefact migration;
  candidate promotion replans ACTIVE record-etl and lemmatize; decide the zero-Record
  dispatch case when consolidation emits only Verdicts. Candidate row artefacts contain
  record data at FULL verbosity and require the storage/redaction floor.
- Before world deployment, apply a reviewed pack-owned DDL migration for fixed row
  identity/subject/provenance columns (row_id includes entity, subject and recordKey) (`tests/fixtures/sql/record_columns.sql` is the
  explicit local demonstration). Add valid-time columns to mappings for state packs.
- Generated schemas currently have no recordKey/subjectId or Statement.conflicts.
  Companion Decisions preserve these facts without modifying generated schemas or
  their pinned content hash. A future renderer change must deliberately migrate
  consumers and regenerate the pack, rather than patching schemas in the worker.
- Load-before-ack recovery infers abstention from Abstentions or unchosen Decisions
  from explicitly registered abstaining deciders (`link/rule`); normalize conflict
  Decisions do not change a successful run into an abstention. It preserves stored ids/status and emits
  `errors.recovery: sections-lost`. P2 must make all kind sections durable, including
  recordRows, link candidates, consolidate recordKeys/redirects and NLP evidence.
- review-182 re-review/PASS after F1–F27 fixes, canonical PEER_REF merge pin, internal 0.11.x image release,
  olymp CPU pool/Secret/pack-mount PR and final live lineage/emoji certification after P2.

No live certification or deployment is performed by this stage. The only Hartland
read was the explicitly requested read-only matcher fixture capture; its temporary
port-forward was closed.
