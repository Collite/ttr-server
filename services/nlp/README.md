# nlp

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`infra/nlp/`), tag `kantheon-fork-point`, forked 2026-06-14 (service formerly named Kadmos).
> Maintained independently since the fork; do not assume parity with the ai-platform original.

The **NLP foundation service** for kantheon — multi-engine analysis (Stanza, spaCy, NameTag/MorphoDiTa via UFAL, langid) over Python/FastAPI.

## Overview

`services/nlp` provides NLP operations (tokenize, lemmatize, POS, dependency
parse, NER, language detection) with per-op-per-language routing (Czech through
the UFAL stack). It is the NLP foundation consumed by Themis (via
`tools/nlp-mcp`) and Echo.

**RG-P1.S1 (Resolution & Grounding, workstream C):**

- **gRPC is the service contract** — `org.tatrman.nlp.v1.NlpService`
  (`Analyze` / `BatchLemmatize` / `GetStatus`, plus NLS-P3's `RunPipeline` /
  `ReloadPacks` / `ReportToken`) on port **7271**. The FastAPI REST endpoint
  (port **7270**) is a **dev/health mirror only**.
- **The front is engine-free** — no in-process torch/models. Every model-bearing
  engine (MorphoDiTa, NameTag 3, Stanza, spaCy) is an **HTTP-adapter client** to
  its own backend image; only `langid` (lingua) runs in-front. Backends land in
  S2 (MorphoDiTa + NameTag 3) and S3 (Stanza + spaCy).
- **S-1 (model identity on the wire)** — every backend is launched with an
  **explicit model id**; every response echoes `used[]` (engine + model +
  version), never blank. `GetStatus` returns the capability matrix with each
  routed (language, op)'s pinning `tier` (`SELF_HOSTED_PINNED` /
  `REMOTE_UNPINNED`). Diagnostics: `RG-NLP-002` (Lindat/unpinned), `RG-NLP-003`
  (empty model), `RG-NLP-010` (degrade floor).

**NLS-P3 (the NLP suite):** the front gained the **pipeline surface** — a named
pipeline runs engine ops, then gazetteer lists, then rule phases, and answers with
an annotated document.

- **`RunPipeline`** / **`ReloadPacks`** on the same gRPC service; the REST mirror
  gains **no** pipeline surface (NL-16, and there is a test for the absence).
- **`ttr-nlp` runs in-process here.** The rule engine, gazetteers, pack loader and
  the `Document ⇄ proto` serializer all come from the wheel, which is model-free —
  that is what lets the engine-free front host them (⚑NLS-D3). The HTTP
  engine-adapter clients moved INTO the wheel at NLS-P3.3 (⚑NLS-D7):
  `ttrnlp.client.backends` owns the transport and the four response protocols,
  while `EngineRegistry`, per-op-per-language routing, the orchestrator and
  `langid` stay here. The division is "how to talk to one backend" versus "which
  backend to talk to".
- **Lanes (NL-4).** `lane: default` is Stanza + spaCy only — anyone may run it.
  `lane: option` adds the UFAL stack, whose licence is a per-deployment call
  (NL-5). The lane decides which engines are *registered*, so in the default lane
  cs `NER` is genuinely unrouted: it gets no `GetStatus` capability row, the
  response carries **`NLS-NLP-011`**, and every other phase still runs (NL-14 —
  honest degrade, never a silent one). Set it per environment with `NLP_LANE` or
  the chart's `lane` value.
- **Packs are fail-all-or-nothing (NL-15).** One bad file and nothing loads: the
  service still serves `Analyze`, reports `ready=false`, puts the diagnostics on
  `GetStatus.pack_state`, and refuses `RunPipeline` with `FAILED_PRECONDITION`.
  `ReloadPacks` validates into a new snapshot and swaps atomically; a refusal
  (`applied=false` + `NLS-PACK-010`) leaves the previous snapshot serving
  untouched. Pack **content** is per-world and never part of the suite (NL-17) —
  mount it at `packs.sources` / `lists.sources`.

> **Proto stubs.** nlp owns its generated `org.tatrman.{nlp,common}.v1`
> Python stubs under `generated/` (gitignored), produced from the shared
> `.proto` source by `scripts/gen_proto.py` (a pytest conftest regenerates them
> on demand). The `.proto` file remains the single canonical source; the Kotlin
> consumer's gRPC stubs come from `:shared:proto`.

## Supported Operations

| Operation | Description | Engines |
|-----------|-------------|---------|
| `TOKENIZE` | Tokenization | Stanza, spaCy |
| `SENTENCE_SPLIT` | Sentence boundary detection | Stanza |
| `LEMMATIZE` | Lemmatization | Stanza |
| `POS_TAG` | Part-of-speech tagging (UD + language-specific) | Stanza |
| `DEP_PARSE` | Dependency parsing | Stanza |
| `NER` | Named Entity Recognition | Stanza, spaCy, NameTag |
| `DETECT_LANGUAGE` | Language detection | langid (lingua) |

## Engines

### Stanza
- **Languages**: Czech (cs), English (en)
- **Operations**: All except NER (cs/en), with full POS and dependency parsing
- **Models**: Bundled in Docker image (pre-downloaded at build time)

### spaCy
- **Languages**: English (en)
- **Operations**: Tokenization, NER
- **Models**: `en_core_web_md` bundled in Docker image

### NameTag (UFAL)
- **Languages**: Czech (cs), English (en)
- **Operations**: NER only
- **Endpoint**: `https://lindat.mff.cuni.cz/services/nametag`
- **Rate Limit**: 5 req/min (configurable)

### langid (lingua-language-detector)
- **Languages**: Multiple (cs, en, de, sk, pl, hu, sl, hr, sr, mk, bg)
- **Operations**: DETECT_LANGUAGE only

## Configuration

Configuration is managed via `config.yaml`:

```yaml
service:
  host: "0.0.0.0"
  port: 7270

engines:
  stanza:
    enabled: true
    model_dir: "/opt/nlp-models/stanza"
  spacy:
    enabled: true
    model_name: "en_core_web_md"
  nametag:
    enabled: true
    # `/services/nametag` 301-redirects; the REST API is at `/api/recognize`.
    endpoint: "https://lindat.mff.cuni.cz/services/nametag/api/recognize"
    timeout_seconds: 30
    max_retries: 3
    rate_limit_per_minute: 5
  morphodita:
    enabled: true
    endpoint: "https://lindat.mff.cuni.cz/services/morphodita/api/tag"
    timeout_seconds: 30
    max_retries: 3
    rate_limit_per_minute: 5
  langid:
    enabled: true

# Per-operation routing: {op}.{lang} -> engine_name
op_routing:
  # Czech (cs) — UFAL stack; Stanza's cs model has no NER head, so we route
  # tokenize/sentence_split/lemmatize/POS through morphodita and NER through nametag.
  TOKENIZE.cs: "morphodita"
  SENTENCE_SPLIT.cs: "morphodita"
  LEMMATIZE.cs: "morphodita"
  POS_TAG.cs: "morphodita"
  DEP_PARSE.cs: "stanza"
  NER.cs: "nametag"
  NER.cs.fallback: ""        # No fallback — stanza-cs has no NER model.

  TOKENIZE.en: "stanza"
  LEMMATIZE.en: "stanza"
  POS_TAG.en: "stanza"
  DEP_PARSE.en: "stanza"
  NER.en: "stanza"           # Stanza for English NER
  NER.en.fallback: "spacy"   # spaCy fallback

  DETECT_LANGUAGE: "langid"

default_language: "cs"
```

#### Language tags

Routing keys use a **bare primary subtag** (`LEMMATIZE.cs`), and so does every
engine's `supported_languages()`. A request's `language` is folded to that subtag
before it is looked up, so `en-US`, `en_US` and `EN` all route exactly as `en`
does, and the response echoes the tag that actually served (`en`).

This is not cosmetic. Before the fold, a region-qualified tag matched no routing
key, no engine, no fallback and nothing in the last-resort capability scan: the
request degraded to the floor with `RG-NLP-010 unsupported (language, op)` and
returned zero tokens **without ever reaching a backend**, while the same text
under `en` parsed normally. An estate whose caller sends a locale — hartland's
Shem declares `locale_defaults: [en-US, cs-CZ]` — looked dark in both languages
for what was only an unfolded tag.

A region in a routing **key** is refused at boot (`LEMMATIZE.en-US`): requests are
folded before lookup, so such a key could never match.

### Czech morphology — the lexicon front (LM, NLS-P9.1)

A pipeline marked `morph: true` runs the curated Czech lexicon **in this
process** instead of calling a backend for tokens and lemmas:

```yaml
morph:
  sources:                                   # fail-all (NL-15); body, members, overlays
    - "/etc/nlp/morph/cs.morph.snap"
    - "/etc/nlp/morph/core-cac.morph.part"
    - "/etc/nlp/morph/dfp.morph.overlay"
  world: "dfp"                               # whose misses these are (LM-5)
  queue:
    sink: "url:http://morph-studio:8000"     # or dir:<path>, or none
    spool_dir: "/var/lib/nlp/morph-queue"    # the never-lose-a-miss fallback
  worlds:
    dfp: { spans: false, retention_days: 90 }

pipelines:
  query-patterns:
    morph: true
    ops: [NER]        # TOKENIZE + LEMMATIZE are the morph front's now
    gazetteer: [dfp-entity-aliases]
    rules: [{ pack: dfp-query-patterns, phase: query-match }]
```

Env overrides, like the lane: `NLP_MORPH_SOURCES` (comma-separated),
`NLP_MORPH_WORLD`, `NLP_MORPH_QUEUE_SINK`, `NLP_MORPH_QUEUE_SPOOL_DIR`.

What the swap does and does not change:

| | |
|---|---|
| `MORPH_TOKENIZE` + `MORPH_ANNOTATE` | replace `TOKENIZE` + `LEMMATIZE`, in-process |
| `SENTENCE_SPLIT`, `NER`, `DEP_PARSE` | still engine ops; their sentences and entities merge onto the same document, their **tokens do not** (one substrate, LM-9) |
| the lane matrix | untouched — `NER.cs` is still unrouted in the default lane and still says so with `NLS-NLP-011` |
| a non-Czech request | takes the engine path unchanged; the gate is the *snapshot's own* declared language, not config |
| `GetStatus` | gains a `LEMMATIZE.cs` capability row with `engine: lexicon`, plus `morph_state` (version, rows, forms, worlds, content hash) |
| `ReloadPacks` | re-reads packs **and** the snapshot; either refusing means neither is swapped |

Failure posture matches the pack half: a `morph: true` pipeline with no sources
declared anywhere **refuses to boot** (a typo, unfixable by waiting), while a
declared source that will not load reports `LM-MORPH-001` on
`GetStatus.morph_state`, costs only the pipelines that need it, and comes good
on the next `ReloadPacks` (a volume that has not mounted).

`ReportToken` (LM contracts §6) is the other door into the same queue: a
consumer that saw a *wrong* answer reports it, and the front spools it deduped
on `(world, token)`. A world this front does not serve gets `accepted=false`
with `LM-MORPH-007` — never another world's file (S-4). `spans: false` is the
default and the span is dropped **at the sink**, before anything is written.

## API

### POST /v1/analyze

Run NLP analysis on input text.

**Request:**
```json
{
  "text": "Které faktury Shell ještě neuhradil?",
  "language": "cs",
  "ops": ["TOKENIZE", "LEMMATIZE", "POS_TAG", "DEP_PARSE", "NER"],
  "mode": "NORMAL",
  "engineHints": {}
}
```

**Response:**
```json
{
  "language": "cs",
  "languageConfidence": 1.0,
  "engineUsed": "stanza",
  "tokens": [
    {
      "text": "Které",
      "charStart": 0,
      "charEnd": 5,
      "lemma": "který",
      "upos": "DET",
      "xpos": "派4",
      "feats": {"Number": "Plur", "Case": "Nom"},
      "depHead": 4,
      "depRelation": "det"
    },
    ...
  ],
  "sentences": [{"charStart": 0, "charEnd": 33}],
  "paragraphs": [],
  "entities": [
    {
      "text": "Shell",
      "label": "PER",
      "charStart": 13,
      "charEnd": 18,
      "normalizedValue": "",
      "sourceEngine": "nametag"
    }
  ],
  "byEngine": {},
  "traceId": "abc123...",
  "elapsedMs": 150,
  "messages": []
}
```

### GET /healthz

Health check endpoint.

### GET /readyz

Readiness check - returns 503 if no engines are available.

### GET /version

Service version and engine information.

## Local Development

```bash
# Install dependencies
cd services/nlp
uv sync

# Run service (default port 7270)
uv run python src/main.py

# Or with uvicorn directly
uvicorn nlp_service.api.routes:app --reload --port 7270
```

## Testing

```bash
# Run tests (from repo root; regenerates proto-py first)
just test-py services/nlp

# Lint (ruff)
just lint-py services/nlp
```

## Evaluation

The NLP eval corpus (`eval/corpus/seed.jsonl`, 50 hand-curated Czech questions
with expected parses + entity bindings) and harness (`eval/run_eval.py`) are
carried over **verbatim** from the ai-platform original — same code, same corpus.
Run it against a deployed service:

```bash
just eval-nlp                    # port-forwards the nlp pod (7270) + runs the harness
# or against a local instance:
uv run python eval/run_eval.py --url http://localhost:7270
```

**Baseline:** because the engine code and corpus are byte-identical to the
ai-platform original at the fork point, nlp answers identically by
construction — the Stage 2.6 Themis gate builds on this. A numeric baseline is
recorded at the first live deployment (the ai-platform original shipped no
recorded `eval/reports` metrics, and the harness needs a running service +
remote UFAL endpoints to score; live infra was unavailable in the fork session,
deferred to the deployment pipeline — Veles/Echo precedent). Reports land in
`eval/reports/` (`metrics.json` + `report.md`).

## Container & deployment

The front and each engine backend are **separate images** (RG-P1.S2/S3):

| Image | Dockerfile | Contents |
|---|---|---|
| `nlp` (front) | `Dockerfile` | engine-free front (gRPC + REST mirror); no models needed at run time |
| `nlp-morphodita` | `backends/morphodita/Dockerfile` | `morphodita_server` + baked `czech-morfflex2.0-pdtc1.0-220710` (S2) |
| `nlp-nametag3` | `backends/nametag3/Dockerfile` | `nametag3_server.py` + baked `nametag3-czech-cnec2.0-240830` + RobeCzech PLM (S2) |
| `nlp-stanza` | `backends/stanza/Dockerfile` | uniform-JSON `server.py` + baked Stanza cs+en (cs DEP_PARSE hot path) (S3) |
| `nlp-spacy` | `backends/spacy/Dockerfile` | uniform-JSON `server.py` + baked spaCy `en_core_web_md` (en NER fallback) (S3) |

### Building and publishing a backend image

```bash
just nlp-backend-image stanza 0.9.1 push          # no build-arg needed
MODEL_URL='https://lindat…/bitstreams/<uuid>/content' MODEL_SHA256=<digest> \
  just nlp-backend-image morphodita 0.9.2 push    # bakes the LINDAT model
```

`nlp-stanza` and `nlp-spacy` also have a **tag-driven lane** — `just publish
nlp-stanza` cuts `nlp-stanza/v<x.y.z>` and `release-image.yml` builds and pushes
it, like every other module. `nlp-morphodita` and `nlp-nametag3` deliberately do
**not**: CI has no `MODEL_URL` secret, so they are hand-built with the recipe
above, which fails loudly if you forget it.

⚑ **Why the recipe checks the model id.** The front addresses MorphoDiTa with an
explicit `model=` (S-1) and `morphodita_server` matches its rest_id **exactly**,
answering `400 Requested model '…' does not exist` on any mismatch — per request,
into a log nobody reads. That is not hypothetical: the image deployed to hartland
until 2026-09-03 was built before `b835ecf` (which changed the rest_id from a
generic `czech` to the pinned `MODEL_ID`), so every `/tag` call had 400ed since
July while the pod looked healthy and served its access log happily. The recipe
now cross-checks the Dockerfile's `ARG MODEL_ID`, `config.yaml`'s
`engines.morphodita.model` and the built image's own `GET /models` before it will
push.

Backend images target the **x86 cluster** (`docker buildx build --platform
linux/amd64 …`, which the recipe passes for you); UFAL models are **CC BY-NC-SA**
(FI-4 — building/running is fine, **publishing** the images is the gated legal
item). The pinned model download URLs (LINDAT) resolve; set the `MODEL_SHA256`
build-arg to the verified digest on the first build (digest-pin). Local
**offline** bring-up (front + both backends, no Lindat egress):

```bash
docker compose -f services/nlp/docker-compose.offline.yml up   # see the file header for build + hero-parse recipes
```

### Image-size strategy (cached model layer)

Each backend isolates its baked model in a dedicated stage so app/config edits
never invalidate the heavy model layer (the Metis/prophet "cached base layer"
pattern). Sizing (Q-10 §3/§5): MorphoDiTa ~250 MB / sub-10 ms / scales with
concurrency; NameTag 3 ~1.1 GB / ~72 ms p50 / ~12 rps per replica → **scale by
replicas**, ~5 s cold start. The front is **engine-free** — no torch, no models,
no per-engine native deps (a test asserts the import- and install-level
guarantee); only langid runs in it.

The **front** image (engine-free) has no model stage:

| Stage | Holds | Cache-busts on |
|-------|-------|----------------|
| `base` | python:3.13-slim + build-essential/curl | base image bump |
| `deps` | uv venv (contract + routing + langid; NO torch) from `pyproject.toml`+`uv.lock` | dependency change |
| `protogen` | generated `org.tatrman.{nlp,common}.v1` stubs (grpcio-tools) | `.proto` change |
| `runtime` | venv + generated stubs + **app `src/`** (thin) | app-source change |

The front image is small (no torch/models); the model weight now lives in the
per-engine backend images (each with its own cached model stage).

## Architecture

```
                    ┌─────────────────────────────────────────────┐
                    │             services/nlp                 │
                    │                                              │
Request ──────────► │ ┌─────────┐    ┌────────────────────────┐  │
                    │ │ FastAPI │───►│     Orchestrator        │  │
                    │ └─────────┘    │  (per-op engine routing) │  │
                    │                └───────────┬────────────┘  │
                    │                            │                 │
                    │       ┌────────────────────┼────────────────┐ │
                    │       │                    │                │ │
                    │       ▼                    ▼                ▼ │
                    │  ┌─────────┐         ┌─────────┐     ┌─────────┐ │
                    │  │  Stanza │         │  spaCy  │     │ NameTag │ │
                    │  └─────────┘         └─────────┘     └─────────┘ │
                    │                                              │
                    │       ┌─────────┐                           │
                    │       │ langid  │ (for DETECT_LANGUAGE)       │
                    │       └─────────┘                           │
                    └─────────────────────────────────────────────┘
```

## Engine Plugin Contract

To add a new engine, implement `NlpEngine` protocol:

```python
from nlp_service.engines.base import NlpEngine, NlpOp, EngineResult

class MyEngine(NlpEngine):
    @property
    def name(self) -> str:
        return "my_engine"

    def supported_languages(self) -> Set[str]:
        return {"cs", "en"}

    def supports(self, lang: str, op: NlpOp) -> bool:
        return lang in self.supported_languages() and op in {NlpOp.TOKENIZE, NlpOp.NER}

    def analyze(self, text: str, lang: str, ops: Set[NlpOp]) -> EngineResult:
        # Your implementation
        return EngineResult(...)
```

Then register it in `EngineRegistry.__init__`.