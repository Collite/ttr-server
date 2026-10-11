# Deploying morph-studio, per world

> The editorial service for the Czech morphology lexicon (LM) — ⚑LMP-D5.
> Reference stack: `services/morph-studio/docker-compose.gate7.yml`, which is
> also the arc-gate-7 test fixture, so this document describes something that is
> verified rather than something that was written down.

**One instance per world** (LM-5/S-4). A world's queue, its overlay and its
export are its own; queues never cross worlds. `MORPH_WORLD` is required and has
no default — an instance that guessed would write one world's vocabulary into
another's file, and there is no repair for that after the fact.

## What is actually being deployed

Four things, and the interesting part is the two arrows between them:

```
                      ┌──────────────────────┐
  a query misses ───▶ │  nlp front           │
                      │  morph: true         │
                      └──┬────────────────▲──┘
     POST /v1/ingest     │                │  ReloadPacks (gRPC)
                      ┌──▼────────────────┴──┐
                      │  morph-studio        │──▶ Postgres (working store)
                      │  guesser → [LLM]     │
                      └──────────┬───────────┘
                                 │ writes {world}.morph.overlay
                      ┌──────────▼───────────┐
                      │  SHARED VOLUME       │◀── the front reads it
                      └──────────────────────┘
```

The front POSTs the tokens it could not analyse. The studio runs the cascade,
writes the world's overlay into a directory the front also mounts, and asks the
front to re-read it. Nothing travels over the wire as *lexicon data*: a runtime
that accepted lexicon rows over an API would be a runtime whose lexicon nobody
can reproduce.

## ⚑⚑ The two mounts

This is the part every deployment gets wrong once.

| | what it is | who writes it | mode |
|---|---|---|---|
| `/etc/nlp/morph` | the released `morph/v*` core artifact | the release lane | read-only |
| `/etc/nlp/overlay` | one world's live overlay | that world's studio | read-only for the front, read-write for the studio |

They cannot be the same directory. The core is a versioned artifact — identical
bytes on every deployment of a given tag — and the overlay is a file that changes
whenever a reviewer acts. Mounting them together means either the studio writes
into the released artifact's directory or the front cannot see the overlay.

**And both must exist before the front boots.** The front loads its morph
sources *fail-all* (NL-15): one unreadable path and nothing loads, not even the
core, and every `morph: true` pipeline answers `FAILED_PRECONDITION`. Since a
deployment must list the overlay among those sources — or it never serves — a
world that has never auto-validated anything would have no Czech lexicon at all.
So **morph-studio writes its overlay at boot, empty if need be** (`_seed_overlay`),
and the front must start after it. In the reference stack that is
`depends_on: {morph-studio: {condition: service_healthy}}`; in Kubernetes it is
an init container or simply tolerating one restart.

## Configuration

| Variable | |
|---|---|
| `MORPH_WORLD` | **required** — this instance's world |
| `MORPH_STUDIO_DB_URL` | `postgresql+psycopg://…` from a Secret |
| `MORPH_STUDIO_OVERLAY_DIR` | the shared directory the front also mounts |
| `MORPH_STUDIO_EXPORT_DIR` | where `POST /v1/export?write` puts layer files |
| `MORPH_STUDIO_FRONT_TARGET` | the front's gRPC `host:port`, for `ReloadPacks` |
| `MORPH_STUDIO_PROVISIONAL` | Q-7 on/off, default on |
| `MORPH_STUDIO_VOCABULARY` | the world's model vocabulary (LM-10 routing) |
| `MORPH_STUDIO_MODE` | `studio` (default) or `dfp` (LM-12) |
| `MORPH_STUDIO_STATIC_DIR` | the built FI-7 frontend; set in the image |
| `MORPH_LLM_URL` / `_MODEL` / `_API_KEY` | the classifier leg — **no URL, no leg** |

And on the front:

```yaml
morph:
  sources: [".../cs.morph.snap", ".../core-*.morph.part", ".../{world}.morph.overlay"]
  world: "{world}"
  queue:
    sink: "url:http://morph-studio:7290/v1/ingest"
    spool_dir: "/var/lib/nlp/morph-queue"
```

`spool_dir` is what makes the `url:` sink safe: a studio that is down or
restarting costs latency in the enrichment loop and never a lost miss.

## Two things the containers must own

Both were found by running the gate rather than by reading the code, and both
are the same shape — a directory an unprivileged process has to write:

* **morph-studio** creates `/var/lib/morph-studio/{overlay,export}` in the image
  and chowns them. Docker initialises an empty named volume from the image's
  contents at that path, *ownership included*; a path absent from the image
  produces a root-owned volume the service cannot write.
* **the front** creates `/var/lib/nlp/morph-queue` the same way. (A queue write
  failure no longer costs the answer either — see `SpoolSink.drain` — but the
  queue should still work.)

## The status machine, and what deployment can and cannot do

`proposed → auto-validated → verified → published`, plus `rejected` (terminal)
and `shadowed`. **The export gate is `verified`**: `POST /v1/export` emits
nothing below it, and there is no edge from `proposed` to `published` for
anything to take. Served as data at `GET /v1/machine`, which is where the UI's
chips and buttons come from.

**Q-7, narrowly.** An auto-validated *proper noun for a world's entity layer* may
go live before a human sees it: the overlay carries it with `provenance:
provisional`, which travels into `Lookup` so a consumer can always tell.
Verifying makes it permanent; rejecting retracts it and names the retraction in
the file's header. It never touches a core snapshot.
`MORPH_STUDIO_PROVISIONAL=false` turns it off entirely.

## The `dfp` lane (LM-12)

That world's analysts edit their layer as files in their own model repository,
so there is no authoring UI: `MORPH_STUDIO_MODE=dfp`, no
`MORPH_STUDIO_STATIC_DIR`, and the queue emits proposed-entry fragments through
`POST /v1/export/proposals` instead of rows in a browser. A studio with no
frontend is a supported deployment, not a broken one.

## Bringing it up, and proving it works

```bash
just morph-compile                       # dist/morph — the core artifact
just fe-build                            # the FI-7 bundle
docker build -t nlp:dev          -f services/nlp/Dockerfile .
docker build -t morph-studio:dev -f services/morph-studio/Dockerfile .

docker compose -f services/morph-studio/docker-compose.gate7.yml up -d

GATE7_FRONT=localhost:7371 GATE7_STUDIO=http://localhost:7390 \
  just test-py services/morph-studio -m component
```

That last command is **arc gate 7**: it walks the life of *Kauflandu* from LM
detailed-design §9 across both services — miss → spool → ingest → guesser →
auto-validate → provisional overlay → re-lookup → verify → permanent → a form
nobody ever reported → the next query reaching its `QueryPattern` through the
expanded world — and then runs the S-7 named cases and the export gate against
the same served stack.

The UI is on <http://localhost:7390/> while the stack is up.

⚑ The stack does **not** publish its Postgres. The other component tier — the
alembic up/down pair — creates and drops databases, so it wants a database of
its own: `docker-compose.dev.yml` publishes one on 55433 for it. (The gate's
ports are 737x/7390 rather than the front's own 727x for the same family of
reason: a developer's machine usually already has something there.)

## What is not here yet

There is **no Helm chart and no olymp app entry** for morph-studio. The image
lane is wired (`release-image.yml`); the deployment lane is not. When it lands it
needs the two mounts above — and the front's own chart needs the second of them,
which the shared `tatrman-service` library chart does not yet offer.
