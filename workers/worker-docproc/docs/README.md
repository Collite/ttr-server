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

- Accept/resolve the frozen deterministic pack's required-field gap. No invented
  sentiment/default record fields; incomplete records remain Verdicts.
- P1 exit: annotations/linked MENTIONS, page findSimilar/conceptRef, active generation
  reads and load semantics on the real service.
- P2: plan-pinned graph route/registryVersion; execute shared when cases on Kotlin;
  strict processor artefact fixture/proto sections and production artefact migration;
  candidate promotion replans ACTIVE record-etl. Candidate row artefacts contain
  record data at FULL verbosity and require the storage/redaction floor.
- Before world deployment, apply a reviewed pack-owned DDL migration for fixed row
  identity/subject/provenance columns (`tests/fixtures/sql/record_columns.sql` is the
  explicit local demonstration). Add valid-time columns to mappings for state packs.
- Generated schemas currently have no recordKey/subjectId or Statement.conflicts.
  Companion Decisions preserve these facts without modifying generated schemas or
  their pinned content hash. A future renderer change must deliberately migrate
  consumers and regenerate the pack, rather than patching schemas in the worker.
- Load-before-ack recovery preserves stored ids/status; per-call NLP evidence lost
  before artefact persistence still needs P2's durable evidence integration.
- Independent exit review, canonical PEER_REF merge pin, internal 0.11.x image release,
  olymp CPU pool/Secret/pack-mount PR and final live lineage/emoji certification after P2.

No live certification or deployment is performed by this stage. The only Hartland
read was the explicitly requested read-only matcher fixture capture; its temporary
port-forward was closed.
