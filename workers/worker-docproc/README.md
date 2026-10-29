# worker-docproc

The stateless Python worker runtime for deterministic document processing. Designed
and planned in `project/kantheon/features/hermes/` (DX-P3). Version stays on 0.11.x.

S3.1 provides the runtime, clients, validators, probes and packaging. S3.2 registers
the production kinds; `Noop` is test-only and is never registered by the bootstrap.
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
`DOCPROC_CONFIG` selects another HOCON file. NLP, lex-matcher, gateway, pack path and
world-DB settings are reserved for subsequent kinds; the world connection follows
`TTR_CONN_EXTRACT_RECORDS`. Connection strings are excluded from configuration repr.

Service identity uses `DOCPROC_SERVICE_TOKEN`, or the file named by
`DOCPROC_SERVICE_TOKEN_FILE`; no OBO credentials. A notebook query is omitted when
none is supplied. Candidate inputs are active annotations plus the current
generation; the `generation` load/read seam is recorded in DX contracts §1.4 and
must be implemented by P1 before certification.

HTTP port `7310` exposes `/health`, `/ready`, `/status`, `/metrics`. Readiness requires
a loaded type registry and a successful pipeline-DB `SELECT 1`. Telemetry defaults
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
P1's engine `types/`, `when-cases.json`, and `chunk-validators.json` are explicitly
skipped until supplied; then copy them byte-for-byte into the fixture mirror and
package `types/` under `src/docproc/_data/`. Runtime manifest schemas already ship
under `_data/schemas/`. Model schemas load from the manager's registry, not Python
re-statements. The test-only fake supplies minimal structural schemas until P1.

The processor protocol follows DX's summary spine (ids/hashes, no unit text).
`tests/fixtures/protocol/noop.json` is a local canonical golden; P2's processor proto
members and `runArtefact.processor.json` strict fixture remain an explicit exit gate.
The pipeline-owned `run_artefacts` table is created only by a SQL fixture in tests;
production schema migration belongs to P2 S2.2.

NLP/fuzzy/common gRPC stubs are generated from this repository's shared `.proto`
tree into the private `docproc._generated` namespace. CI regenerates them with the
locked compiler and checks for drift; generated code is excluded from Ruff/mypy.
No Kantheon proto stubs are imported.

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
