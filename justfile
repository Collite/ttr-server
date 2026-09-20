# tatrman-server — task runner
# Run `just` to list recipes.
#
# Recipe conventions (synced across kantheon/modeler/tatrman/tatrman-server,
# 2026-07-16 — see project/ for the cross-repo decision):
#   lint / build / test    bare = everything; `just build veles` (name) or
#                           `just build services/veles` (path) = one module.
#   lint-kt/build-kt/test-kt, lint-py/build-py/test-py   same name/path/bare
#                           rules, scoped to one language lane.
#   publish                 unified release entry point — see its own doc comment.

set shell := ["bash", "-uc"]

# The container-image modules release-image.yml builds (per-module `<module>/v*`
# tag → ghcr.io/collite/<module>). Kept in lockstep with release-image.yml's map.
# `nlp-stanza`/`nlp-spacy` are the two NLP BACKENDS that need no build-arg;
# `nlp-morphodita`/`nlp-nametag3` bake a LINDAT model and are deliberately NOT here —
# CI cannot build them without its URL. All four: `just nlp-backend-image`.
image_modules := "veles query translate validate dispatch charon lex-matcher llm-gateway nlp nlp-stanza nlp-spacy golem-py morph-studio chrono geo money grounding-mcp resolver meta-mcp query-mcp lex-matcher-mcp nlp-mcp worker-postgres worker-mssql worker-polars identity health"

# The pure-Python wheels publish-python.yml builds (`python-<lane>/v*` tag →
# PyPI). Kept in lockstep with that workflow's tag map — the wheel-name → tag-
# prefix mapping is in `publish` itself, since the two differ (`ttr-nlp` ships
# from the `python-nlp` tag lane). `otel-config` is shared config, not a
# published distribution, and is deliberately absent.
wheel_modules := "ttr-nlp"

# List available recipes
default:
    @just --list

# ── Module resolution ─────────────────────────────────────────────────────────

# Resolve a bare module name to its on-disk path, searching services/ workers/
# tools/ infra/ shared/libs/{kotlin,python}/. A path (contains "/") passes
# through unchanged.
_resolve name:
    #!/usr/bin/env bash
    set -euo pipefail
    if [[ "{{name}}" == *"/"* ]]; then
        echo "{{name}}"
        exit 0
    fi
    roots=()
    for d in services workers tools infra shared/libs/kotlin shared/libs/python; do
        [ -d "$d" ] && roots+=("$d")
    done
    path=$(find "${roots[@]}" -mindepth 1 -maxdepth 1 -type d -name "{{name}}" -print -quit 2>/dev/null || true)
    if [ -z "$path" ]; then
        echo "❌ Module '{{name}}' not found under services/, workers/, tools/, infra/, shared/libs/{kotlin,python}/" >&2
        exit 1
    fi
    echo "$path"

# Which lane a resolved path builds under (kt | py).
_lang path:
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -f "{{path}}/build.gradle.kts" ]; then echo kt
    elif [ -f "{{path}}/pyproject.toml" ]; then echo py
    else
        echo "❌ Can't tell what language {{path}} is (no build.gradle.kts / pyproject.toml)" >&2
        exit 1
    fi

# ── lint / build / test — bare = everything, name/path = one module ───────────

# Lint everything (Kotlin + Python). One module: `just lint veles`.
lint module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        just lint-kt
        just lint-py
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    lang=$(just _lang "$path")
    case "$lang" in
        kt) just lint-kt "$path" ;;
        py) just lint-py "$path" ;;
    esac

# Build everything (Kotlin + Python). One module: `just build veles`.
build module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        just build-kt
        just build-py
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    lang=$(just _lang "$path")
    case "$lang" in
        kt) just build-kt "$path" ;;
        py) just build-py "$path" ;;
    esac

# Test everything (Kotlin + Python). One module: `just test veles`.
test module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        just test-kt
        just test-py
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    lang=$(just _lang "$path")
    case "$lang" in
        kt) just test-kt "$path" ;;
        py) just test-py "$path" ;;
    esac

# ── Kotlin lane ──────────────────────────────────────────────────────────────

# ktlint check across every module, or one: `just lint-kt` / `just lint-kt veles`.
# Autofix: `just fmt` (whole repo) — see below.
lint-kt module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then ./gradlew ktlintCheck
    else
        path=$(just _resolve "{{module}}")
        gradle_path=":$(echo "$path" | sed 's|/|:|g')"
        ./gradlew "${gradle_path}:ktlintCheck"
    fi

# ktlint autoformat (whole repo — no single-module form; matches the old `fmt`).
fmt:
    ./gradlew ktlintFormat

# Full Gradle build (compile + the mocked `test` gate + ktlint — what CI runs),
# or one module: `just build-kt` / `just build-kt veles` / `just build-kt services/veles`.
build-kt module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then ./gradlew build
    else
        path=$(just _resolve "{{module}}")
        gradle_path=":$(echo "$path" | sed 's|/|:|g')"
        ./gradlew "${gradle_path}:build"
    fi

# Mocked unit/PR gate only (no ktlint, no componentTest), or one module.
test-kt module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then ./gradlew test
    else
        path=$(just _resolve "{{module}}")
        gradle_path=":$(echo "$path" | sed 's|/|:|g')"
        ./gradlew "${gradle_path}:test"
    fi

# The real-dependency (Testcontainers) component tier — separate from `test`, and
# GATING: ci.yml's `component-test` job runs exactly this command on every PR.
# Needs a running Docker daemon (postgres:16-alpine, redis:7-alpine).
test-component:
    # `--continue`: one module's failure must not hide the other eight. The tier's
    # first CI run stopped at translate and left the rest of the estate unknown.
    ./gradlew componentTest --continue

# ── Python lane (uv + ruff + pytest) — services/nlp, services/golem-py, ───────
#    workers/worker-polars, shared/libs/python/{ttr-nlp,ttr-morph} ──────────────
#
# `ttr-morph` is in these loops but deliberately NOT in `wheel_modules`: it is
# not published (⚑LMP-D4). Its artifact is the morph/v* snapshot, cut by
# publish-morph.yml, and the package itself only ever ships inside the
# nlp-morph-tools image.

# ruff lint every Python module, or one: `just lint-py` / `just lint-py nlp`.
lint-py module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        for d in services/nlp services/golem-py workers/worker-polars shared/libs/python/ttr-nlp shared/libs/python/ttr-morph; do just lint-py "$d"; done
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    cd "$path" && uv run ruff check .

# Resolve + install the frozen lock (what the Dockerfile's `uv sync --frozen`
# does) for every Python module, or one.
build-py module="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        for d in services/nlp services/golem-py services/morph-studio workers/worker-polars shared/libs/python/ttr-nlp shared/libs/python/ttr-morph; do just build-py "$d"; done
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    cd "$path" && uv sync --frozen

# pytest every Python module, or one; trailing args pass through, e.g.
# `just test-py workers/worker-polars -m component`.
test-py module="" *args:
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "{{module}}" ]; then
        for d in services/nlp services/golem-py services/morph-studio workers/worker-polars shared/libs/python/ttr-nlp shared/libs/python/ttr-morph; do just test-py "$d" {{args}}; done
        exit 0
    fi
    path=$(just _resolve "{{module}}")
    cd "$path" && uv run pytest {{args}}

# ── Morphology lexicon (LM — the morph/v* artifact) ──────────────────────────
#
# The two recipes an analyst runs, and the two publish-morph.yml runs. Same
# reader, same checks: a layer that validates here compiles in CI, which is the
# only promise that makes local editing worth anything.
#
# Layer files live in `shared/libs/python/ttr-morph/lexicon/cs/` (the hand seed
# and the importer output, from NLS-P8.3). Pass a glob to work on a subset.
#
# ⚑ THE LAYER ORDER IS THE PRECEDENCE, and it lives in `lexicon/cs/LAYERS`
# (NLS-P8.4) — read that file before changing anything here. It is a list rather
# than a glob for two reasons that each cost an artifact once: a glob has no
# opinion about order, and the glob this replaced silently missed the hand seed.
# Both the recipes below and publish-morph.yml read the same file.

morph_dir := "shared/libs/python/ttr-morph"
morph_lexicon := morph_dir + "/lexicon/cs"
morph_freq := morph_lexicon + "/cac-freq.tsv"

# Validate layer files: schema, licence boundary, and whether each declared
# pattern regenerates its declared forms. `just morph-validate 'path/*.yaml'`.
morph-validate layers="":
    #!/usr/bin/env bash
    set -euo pipefail
    root=$(git rev-parse --show-toplevel)
    # ⚑ NOT `ls`: it SORTS its arguments, which would re-order the layer list
    # and therefore the precedence, without saying so.
    files="{{layers}}"
    [ -n "$files" ] || files=$(grep -v '^\s*\(#\|$\)' "$root/{{morph_lexicon}}/LAYERS" \
        | sed "s|^|$root/{{morph_lexicon}}/|" | tr '\n' ' ')
    cd {{morph_dir}} && uv run ttr-morph validate $files

# Compile the snapshot into dist/morph/ — the body, one member file per
# share-alike layer, and NOTICE-morph.md. Version is the morph/v* tag being
# rehearsed; the real one comes from the tag in publish-morph.yml.
morph-compile version="0.0.0" layers="":
    #!/usr/bin/env bash
    set -euo pipefail
    root=$(git rev-parse --show-toplevel)
    files="{{layers}}"
    [ -n "$files" ] || files=$(grep -v '^\s*\(#\|$\)' "$root/{{morph_lexicon}}/LAYERS" \
        | sed "s|^|$root/{{morph_lexicon}}/|" | tr '\n' ' ')
    mkdir -p "$root/dist/morph"
    cd {{morph_dir}} && uv run ttr-morph compile \
        $files \
        -o "$root/dist/morph/cs.morph.snap" \
        --snapshot-version "{{version}}" \
        --freq "$root/{{morph_freq}}"

# Measure a compiled snapshot: the S-7 named cases and target coverage always,
# the contracts §11 corpus metrics only with `cac=<dir>` (the TEST side of the
# frozen split — read `eval/harness.py` before pointing this anywhere).
morph-eval cac="" gate="":
    #!/usr/bin/env bash
    set -euo pipefail
    root=$(git rev-parse --show-toplevel)
    args=""
    for f in "$root"/dist/morph/cs.morph.snap "$root"/dist/morph/*.morph.part; do
        [ -f "$f" ] && args="$args --snapshot $f"
    done
    [ -n "$args" ] || { echo "nothing compiled — run `just morph-compile` first"; exit 2; }
    [ -z "{{cac}}" ] || args="$args --cac {{cac}}"
    [ -z "{{gate}}" ] || args="$args --gate"
    cd {{morph_dir}} && uv run ttr-morph eval $args

# Run morph-studio locally (NLS-P9.2): SQLite under `dist/`, Q-7 writing into
# `dist/morph-studio/overlay`, no front to reload. `world=` is passed through —
# one instance serves one world (LM-5), and the service refuses to boot without
# one.
run-morph-studio world="dfp":
    #!/usr/bin/env bash
    set -euo pipefail
    root=$(git rev-parse --show-toplevel)
    mkdir -p "$root/dist/morph-studio"
    export MORPH_WORLD="{{world}}"
    export MORPH_STUDIO_DB_URL="sqlite+pysqlite:///$root/dist/morph-studio/studio.db"
    export MORPH_STUDIO_OVERLAY_DIR="$root/dist/morph-studio/overlay"
    export MORPH_STUDIO_EXPORT_DIR="$root/dist/morph-studio/export"
    # Serve the built FI-7 frontend from the same process if it has been built
    # (`just fe-build`). Unset otherwise — the API alone is a supported way to
    # run this, and the `dfp` world has no authoring UI at all by ruling (LM-12).
    [ -d "$root/services/morph-studio/frontend/dist" ] \
        && export MORPH_STUDIO_STATIC_DIR="$root/services/morph-studio/frontend/dist" \
        || true
    cd services/morph-studio && uv run python src/main.py

# ── morph-studio frontend (FI-7 — NLS-P9.3) ──────────────────────────────────

# `services/morph-studio/frontend` is the only TypeScript in this repo. It is a
# vite + Vue 3 app built to `frontend/dist`, which the BACKEND serves — one
# deployable, no second web server (see `api._mount_frontend`).

fe_dir := "services/morph-studio/frontend"

# Install once; every other recipe assumes it.
fe-install:
    cd {{fe_dir}} && npm install

# The dev server, proxying /v1 to a `just run-morph-studio` on :7290.
fe-dev:
    cd {{fe_dir}} && npm run dev

# Type-check and bundle. `just run-morph-studio` picks the output up.
fe-build:
    cd {{fe_dir}} && npm run build

# The component tier (vitest + @vue/test-utils, jsdom). No backend, no browser.
fe-test *args:
    cd {{fe_dir}} && npx vitest run {{args}}

# Regenerate the API client from the service's OpenAPI document.
#
# ⚑ Two steps, and both matter: the document is dumped from the app (no server,
# no database) and THEN the TypeScript is generated from it. The committed
# `frontend/openapi.json` is what `tests/test_openapi.py` compares against, so a
# backend change that skips this recipe fails the python suite rather than
# silently shipping a stale client.
fe-api:
    cd services/morph-studio && uv run python scripts/dump_openapi.py frontend/openapi.json
    cd {{fe_dir}} && npm run api

# ── Conformance (RG-P6.S2 — the three-tier instrument) ───────────────────────

# The grounding eval corpus (RG-P3.S2.T7): consolidate the per-service goldens
# (chrono/geo/money) + the hand-authored supplemental into the bulk + e2e corpora.
eval-grounding-build:
    cd eval/grounding && python3 build_corpus.py

# Grounding HERMETIC tier: corpus-validity + the pure report logic. No deployed
# stack, no live service — the only network touch is a one-time install of the
# PINNED test deps (requirements-test.txt, RG-P6 review H), skipped once installed.
eval-grounding-test:
    #!/usr/bin/env bash
    set -euo pipefail
    cd eval/grounding
    test -d .venv || python3 -m venv .venv
    # Install only when the pinned marker is absent, and always from the pinned file
    # so a gate run is reproducible (no unpinned/floating pytest).
    if ! .venv/bin/python -c 'import pytest, pytest_asyncio' 2>/dev/null; then
        .venv/bin/pip -q install -r requirements-test.txt
    fi
    .venv/bin/python -m pytest tests/ -q

# The LIVE grounding eval (bulk → grounding-mcp, e2e → Golem /v2/chat). Needs a
# deployed stack, so it is NOT gating — it is the non-gating extended tier
# (RG-P6.S2.T3 / SV-P4). Bulk gate: pass-rate ≥ 80%, LLM-fallback ≤ 10%.
eval-grounding:
    cd eval/grounding && .venv/bin/python run_eval.py

# RV-P8.3.T4 — the emulation-quality snapshot. LIVE: needs an nlp front with
# `LLM_EMULATED` enabled and a reachable llm-gateway, so it is NOT gating and
# never will be — the whole point is measuring a hosted model, which is exactly
# what a gate must not depend on. The harness is committed and re-runnable; the
# snapshot it writes is dated and is not.
#
#   URL=http://localhost:7270 just eval-nlp-emulation-quality
#
# ⚑ Note `eval/README.md` also documents a `just eval-nlp` that does not exist in
# this justfile (pre-existing drift, not RV-P8's to guess at — it describes a
# port-forward this checkout cannot verify).
eval-nlp-emulation-quality url="http://localhost:7270" out="eval/reports/emulation-quality.md":
    cd services/nlp && uv run python eval/run_eval.py --emulation-quality \
        --url {{url}} --output-md {{out}} --output-json eval/reports/emulation-quality.json

# RG-P6.S2.T1 — the GATING service-level conformance tier (the SV-P3 instrument):
# the four service-level corpora — ENTITIES_ONLY (resolver), Q-17 match-quality
# (fuzzy), hartland_cz declared layer (fuzzy, RV-P1.4 T7), grounding hermetic —
# run self-contained, no DFP dependency. Green is required; CI gates on it.
# Provenance + corpus hashes: conformance/README.md.
conformance-service-level:
    #!/usr/bin/env bash
    set -euo pipefail
    just conformance-verify-hashes
    # One --tests filter per gradle invocation, deliberately NOT collapsed into a single call.
    # Kotest 6.x's JUnit-Platform engine does not OR multiple Gradle `--tests` filters on one
    # test task — passing more than one makes Gradle report "No tests found for given includes"
    # and the whole task fails. A single filter per task works, so each spec runs on its own.
    ./gradlew :services:resolver:test --tests '*Q20ParityTest*'
    ./gradlew :services:resolver:test --tests '*CallsSeedConformanceTest*'
    ./gradlew :services:resolver:test --tests '*RefusalOverGuessConformanceTest*'
    # RV-P2.5.T6 — the P2 phase gate joins the gating tier. Hermetic (in-process gRPC, faked
    # nlp/fuzzy), so it belongs here rather than in the SV-P4 live tier: the H1' re-gate pair
    # over the wire, the four hero lattices, and the two named issues.md regressions.
    ./gradlew :services:resolver:test --tests '*GateConformanceTest*'
    ./gradlew :services:resolver:test --tests '*LatticeGoldenTest*'
    ./gradlew :services:resolver:test --tests '*IssuesRegressionTest*'
    ./gradlew :services:lex-matcher:test --tests '*MatchQualityCorpusTest*'
    ./gradlew :services:lex-matcher:test --tests '*LexiconConformanceTest*'
    # RV-P7.4 T6 — the LEARNED overlay joins the gating tier, on its recorded promotion
    # criterion: `OverlayDrillTest` is fed by `drill/overlay-final.json`, which is the exact
    # document kantheon's H2H3DrillSpec produced from a real H2 conversation — so this corpus
    # is a statement about the LOOP now, not about the reader. Hermetic (no cluster, no Golem).
    ./gradlew :services:lex-matcher:test --tests '*OverlayConformanceTest*'
    ./gradlew :services:lex-matcher:test --tests '*OverlayDrillTest*'
    just eval-grounding-test
    # RV-P4.4.T3 — the conformance-CONVERSATION tier (the P4 phase gate). Hermetic: the
    # five hero conversations in conformance/conversations/ driven through golem-py
    # against the resolver's OWN lattice goldens, so a core change fails this the same
    # day it fails the Kotlin tier. The SAME fixtures are what RV-P5's Kotlin Golem must
    # pass (RV-28: one corpus, N shells).
    just test-py services/golem-py

# RV-P7.4 T5 — this repo's half of the H2->H3 drill: load the overlay kantheon's half grew from
# a real conversation, and prove H3 (the same term binds at LEARNED with zero asks) and the
# negative suppression. The handoff document is `drill/overlay-final.json`; regenerate it with
# `just rv-drill` in kantheon and copy it here in the SAME change.
rv-drill:
    ./gradlew :services:lex-matcher:test --tests '*OverlayDrillTest*' --rerun-tasks

# Pin the three-tier corpora by content hash (RG-P6 review I): the recorded provenance
# in conformance/README.md is now ENFORCED — a silent corpus edit (even whitespace /
# reordering that a semantic test would miss) fails the gate here.
conformance-verify-hashes:
    #!/usr/bin/env bash
    set -euo pipefail
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum -c conformance/corpus-hashes.sha256
    else
        shasum -a 256 -c conformance/corpus-hashes.sha256
    fi

# ── NLP backend images (services/nlp/backends/*) ────────────────────────────────
#
# The FOUR engines the `nlp` service calls, not the service itself: `just publish
# nlp` ships the front; this ships a backend. They differ from every other module
# here in two ways, which is why they need their own recipe:
#
#   - they live one level deeper (`services/nlp/backends/<name>`) and publish as
#     `ghcr.io/collite/nlp-<name>`, so the image name is not the directory name;
#   - morphodita and nametag3 BAKE a LINDAT model, fetched at build time from a
#     DSpace bitstream URL that is not in this repo (and, for morphodita, under
#     CC BY-NC-SA — see its Dockerfile header before publishing anywhere public).
#     CI cannot build those two without the URL, so they are hand-built here.
#
# `nlp-stanza` and `nlp-spacy` need no build-arg and DO have a tag-driven lane
# (`just publish nlp-stanza` → release-image.yml). This recipe is their local
# path and the only path for the other two.
#
# ⚑ Why this recipe exists at all (2026-09-03). The deployed `nlp-morphodita:0.9.1`
# was hand-built before b835ecf (2026-07-16) — the commit that changed the server's
# rest_id from a generic `czech` to the pinned MODEL_ID — and nothing ever rebuilt
# it. The front asks for `model=czech-morfflex2.0-pdtc1.0-220710`; the deployed
# server registers `czech`; `morphodita_server` does an exact-match lookup and
# answers `400 Requested model '…' does not exist` to every call, silently, for
# seven weeks. `verify` below is that bug turned into a build-time check.
#
#   just nlp-backend-image stanza 0.9.1
#   just nlp-backend-image stanza 0.9.1 push
#   MODEL_URL='https://lindat.mff.cuni.cz/repository/server/api/core/bitstreams/<uuid>/content' \
#     MODEL_SHA256=<digest> just nlp-backend-image morphodita 0.9.2 push
nlp-backend-image name version="dev" push="no":
    #!/usr/bin/env bash
    set -euo pipefail

    NAME="{{name}}"
    case "$NAME" in
        morphodita|nametag3|spacy|stanza) ;;
        *) echo "❌ '$NAME' is not an NLP backend. Valid: morphodita nametag3 spacy stanza" >&2
           echo "   (Did you mean the FRONT? That is 'just publish nlp'.)" >&2; exit 1 ;;
    esac

    DIR="services/nlp/backends/${NAME}"
    IMG="ghcr.io/collite/nlp-${NAME}:{{version}}"
    [ -f "$DIR/Dockerfile" ] || { echo "❌ No Dockerfile at $DIR" >&2; exit 1; }

    # linux/amd64 for every one of them: the cluster is x86, and MorphoDiTa's
    # build hard-codes x86 SSE flags (its Dockerfile header). On an arm64 dev box
    # this goes through QEMU — slow, and correct.
    BUILD_ARGS=(--platform linux/amd64)

    # The two model-baked ones. MODEL_URL is REQUIRED — their Dockerfiles fail
    # loudly on the placeholder, but failing here is faster and says why.
    if [ "$NAME" = "morphodita" ] || [ "$NAME" = "nametag3" ]; then
        if [ -z "${MODEL_URL:-}" ]; then
            echo "❌ $NAME bakes a LINDAT model — set MODEL_URL to the resolved DSpace" >&2
            echo "   bitstream-CONTENT url (the legacy /xmlui/bitstream/... url returns the" >&2
            echo "   SPA's HTML, not the archive). See $DIR/Dockerfile." >&2
            exit 1
        fi
        BUILD_ARGS+=(--build-arg "MODEL_URL=${MODEL_URL}")
        if [ -n "${MODEL_SHA256:-}" ]; then
            BUILD_ARGS+=(--build-arg "MODEL_SHA256=${MODEL_SHA256}")
        else
            echo "⚠️  MODEL_SHA256 unset — the model download will NOT be digest-pinned."
        fi
    fi

    echo "→ building $IMG  (context $DIR, linux/amd64)"
    docker buildx build "${BUILD_ARGS[@]}" --load -t "$IMG" "$DIR"

    # ---- the rest_id check (morphodita only; nametag3's server exposes no /models)
    #
    # Three names must agree, and the failure when they do not is invisible in
    # every log except a 400 buried in a DEBUG payload:
    #   1. the Dockerfile's ARG MODEL_ID   — what the server REGISTERS
    #   2. config.yaml engines.<name>.model — what the front ASKS FOR
    #   3. GET /models on the built image   — the ground truth
    if [ "$NAME" = "morphodita" ]; then
        DOCKER_ID=$(grep -m1 -E '^ARG MODEL_ID=' "$DIR/Dockerfile" | cut -d= -f2)
        FRONT_ID=$(awk -v b="  ${NAME}:" '$0==b{f=1;next} /^  [a-z0-9_-]+:/{f=0} f' services/nlp/config.yaml \
                   | grep -m1 -E '^[[:space:]]+model:' | sed -E 's/^[[:space:]]*model:[[:space:]]*"([^"]+)".*/\1/')
        if [ "$DOCKER_ID" != "$FRONT_ID" ]; then
            echo "❌ model-id mismatch BEFORE the image even runs:" >&2
            echo "     Dockerfile ARG MODEL_ID = $DOCKER_ID" >&2
            echo "     config.yaml  .model     = $FRONT_ID" >&2
            echo "   morphodita_server matches the rest_id EXACTLY — fix one of them." >&2
            exit 1
        fi

        echo "→ verifying the built image registers '$DOCKER_ID' as its rest_id"
        CID=$(docker run -d --rm -p 18099:8080 "$IMG")
        trap 'docker stop "$CID" >/dev/null 2>&1 || true' EXIT
        for _ in $(seq 1 30); do
            curl -fsS --max-time 2 "http://127.0.0.1:18099/models" >/dev/null 2>&1 && break
            sleep 1
        done
        MODELS=$(curl -fsS --max-time 5 "http://127.0.0.1:18099/models" || echo '{}')
        if ! echo "$MODELS" | grep -q "\"$DOCKER_ID\""; then
            echo "❌ the built image does NOT serve '$DOCKER_ID'. /models says:" >&2
            echo "   $MODELS" >&2
            echo "   Every front call would 400 with \"Requested model '…' does not exist\"." >&2
            exit 1
        fi
        echo "✓ /models serves $DOCKER_ID"
    fi

    if [ "{{push}}" = "push" ]; then
        if [ "{{version}}" = "dev" ]; then
            echo "❌ refusing to push the ':dev' tag — pass a real version." >&2; exit 1
        fi
        echo "→ pushing $IMG  [GHCR, and a spent version number does not come back]"
        docker push "$IMG"
        echo "✓ pushed $IMG"
        echo "  Roll it:  kubectl -n ttr-server set image deploy/nlp-${NAME} ${NAME}=${IMG}"
        echo "  (or bump the tag in olymp and let ArgoCD sync — the estate's normal path)"
    else
        echo "✓ built $IMG (not pushed — append 'push' to publish)"
    fi

# ── protos — the wire-compatibility baseline (RV-P2.1) ──────────────────────────
#
# `shared/proto/compat/wire-baseline.desc` is the record of every message, field
# number, type and enum value that has already SHIPPED. ProtoCompatSpec diffs the
# current protos against it on every `./gradlew build` and fails on anything that is
# not purely additive (J-v2) — a removed field, a re-used tag, a renamed field (proto3
# JSON is name-keyed), a moved enum value.
#
# Regenerating the baseline is how you ACCEPT a wire change, so it is a deliberate
# commit and never automatic — the same discipline as `charts-golden`. Run this, read
# the diff, and commit it together with the proto change it accepts.
proto-baseline:
    ./gradlew :shared:proto:refreshProtoBaseline
    @echo "→ shared/proto/compat/wire-baseline.desc refreshed. REVIEW what it now accepts before committing."

# ── charts — the SV-P4 umbrella (helm/tatrman-server) ───────────────────────────
#
# The product install: one umbrella chart subsuming the full service roster as
# file:// subcharts over the tatrman-service library. `just charts` is the CI gate;
# scripts/helm-deps.sh vendors the (gitignored) subchart deps offline.

# Lint the umbrella + verify golden templates are current (the `helm` CI gate).
charts:
    helm lint helm/tatrman-server
    scripts/helm-golden.sh --check

# Regenerate the golden template output (after an intended chart change; review the diff).
charts-golden:
    scripts/helm-golden.sh

# Publish the umbrella chart to GHCR OCI [GATE — IRREVERSIBLE]. Runs the full gate
# (lint + golden), vendors deps, packages, and pushes to oci://ghcr.io/collite/charts.
# Auth: user `Collite` + $COLLITE_PAT (a PAT with write:packages). Per RO-24 / rule 8,
# a chart version is immutable once pushed and is cut from MASTER after the fold — the
# recipe refuses a non-master branch unless ALLOW_NONMASTER=1.
charts-publish:
    #!/usr/bin/env bash
    set -euo pipefail
    : "${COLLITE_PAT:?COLLITE_PAT (a PAT with write:packages) must be set}"
    # Gate preconditions — never publish un-gated.
    helm lint helm/tatrman-server
    scripts/helm-golden.sh --check
    scripts/helm-deps.sh
    ver=$(grep -E '^version:' helm/tatrman-server/Chart.yaml | awk '{print $2}')
    branch=$(git rev-parse --abbrev-ref HEAD)
    if [ "$branch" != "master" ] && [ "${ALLOW_NONMASTER:-}" != "1" ]; then
        echo "⚑ On '$branch', not master — chart publishes are cut from master after the fold (RO-24/rule 8)." >&2
        echo "  Re-run on master, or set ALLOW_NONMASTER=1 to override." >&2
        exit 1
    fi
    echo "→ packaging tatrman-server-${ver}.tgz"
    helm package helm/tatrman-server --destination /tmp
    echo "→ ghcr.io login as Collite"
    echo "$COLLITE_PAT" | helm registry login ghcr.io -u Collite --password-stdin
    echo "→ pushing oci://ghcr.io/collite/charts/tatrman-server:${ver}  [IRREVERSIBLE]"
    helm push "/tmp/tatrman-server-${ver}.tgz" oci://ghcr.io/collite/charts
    echo "✓ pushed. Verify: helm pull oci://ghcr.io/collite/charts/tatrman-server --version ${ver}"

# ── release guard ────────────────────────────────────────────────────────────────
# A tag names a COMMIT, and `git tag` reads it from the checkout you are standing in. A `main`
# that has not pulled the merge you just made tags the PREVIOUS commit under a NEW version —
# the image builds, deploys, and reproduces the bug verbatim (ai-platform 2026-09-20:
# glossary/v0.11.7 == v0.11.6, one golden-set run wasted). Ancestry is not enough — a stale
# HEAD *is* an ancestor of origin/main — so this fetches and demands equality with the
# branch's origin counterpart, and says which way the two differ and what to run.
# Every recipe that cuts a tag or pushes a versioned artifact calls this first.
_release-guard:
    #!/usr/bin/env bash
    set -euo pipefail
    BRANCH=$(git rev-parse --abbrev-ref HEAD)
    if [ "$BRANCH" = "HEAD" ]; then
        echo "❌ detached HEAD — check out the branch you mean to release from." >&2; exit 1
    fi
    if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
        echo "❌ tracked files are modified — a tag publishes the COMMIT, not your tree:" >&2
        git status --short --untracked-files=no >&2; exit 1
    fi
    if ! git fetch -q origin "$BRANCH"; then
        echo "❌ git fetch origin $BRANCH failed — cannot prove HEAD is current, refusing to tag." >&2; exit 1
    fi
    LOCAL=$(git rev-parse HEAD); REMOTE=$(git rev-parse "origin/$BRANCH")
    if [ "$LOCAL" != "$REMOTE" ]; then
        echo "❌ HEAD ${LOCAL:0:8} ≠ origin/$BRANCH ${REMOTE:0:8} — a tag cut here names the wrong commit." >&2
        if git merge-base --is-ancestor HEAD "origin/$BRANCH"; then
            echo "   Your checkout is BEHIND origin. The release would be MISSING:" >&2
            git log --oneline "HEAD..origin/$BRANCH" | sed 's/^/     /' >&2
            echo "   Run: git pull --ff-only origin $BRANCH" >&2
        elif git merge-base --is-ancestor "origin/$BRANCH" HEAD; then
            echo "   Your checkout is AHEAD of origin. The workflow checks the tag out from the remote and would not find:" >&2
            git log --oneline "origin/$BRANCH..HEAD" | sed 's/^/     /' >&2
            echo "   Run: git push origin $BRANCH" >&2
        else
            echo "   The two have DIVERGED — reconcile (pull --rebase, then push) before tagging." >&2
        fi
        exit 1
    fi
    echo "✅ HEAD ${LOCAL:0:8} == origin/$BRANCH — tagging what origin has."

# ── publish — unified release entry point ───────────────────────────────────────
#
# Tags the repo; the matching GitHub Actions workflow does the actual
# build+publish when it sees the tag — publish.yml for `bundle server-libs`
# (Maven), publish-python.yml for `wheel <name>` (PyPI), release-image.yml for
# everything else (GHCR container images).
#
# Internal targets (GH Packages staging / GHCR) get EVERY tag. The external
# targets (Maven Central for `bundle server-libs`, PyPI for `wheel …` —
# container images have no external registry) only fire when the tag is marked
# RELEASE — a published RELEASE version is ALWAYS the bare `x.y.z` (the
# `-RELEASE` marker is stripped before it ever reaches a registry; see
# publish.yml). This is the 2026-07-16 change: previously bare tags went public
# and `-rc` suffixes stayed internal — inverted, because internal patches vastly
# outnumber real releases, and a release now needs to be marked explicitly.
#
# `what`: one of the image modules (by name or path — see `image_modules`;
#   GHCR only, RELEASE accepted for interface uniformity but changes nothing),
#   `bundle server-libs` (the 14-module Maven library set — GH Packages always,
#   + Maven Central on RELEASE), or `wheel <name>` (a pure-Python distribution —
#   see `wheel_modules`; a bare tag BUILDS ONLY, PyPI upload on RELEASE).
#
# ⚑ `nlp` and `wheel ttr-nlp` are different artifacts and one letter apart in
#   intent. `just publish nlp` ships the nlp SERVICE IMAGE to GHCR (internal,
#   deletable); `just publish wheel ttr-nlp release` ships the ttr-nlp WHEEL to
#   public PyPI (permanent, and a spent version number never comes back). The
#   confirm prompt names which one you are about to cut — read it.
#
# Usage:
#   just publish veles                          # internal (GHCR), patch bump
#   just publish veles set 0.9.2                 # internal, explicit version
#   just publish services/query patch          # image module, path form
#   just publish bundle server-libs                 # internal, patch bump
#   just publish bundle server-libs release set 0.9.2  # + Maven Central, explicit
#   just publish wheel ttr-nlp                   # build-only proof, no upload
#   just publish wheel ttr-nlp release set 0.1.0     # + PyPI (PUBLIC), explicit
publish *args:
    #!/usr/bin/env bash
    set -euo pipefail

    ARGS=({{args}})
    WHAT="${ARGS[0]:-}"
    NEXT=1
    if [ "$WHAT" = "bundle" ] || [ "$WHAT" = "wheel" ]; then
        WHAT="$WHAT ${ARGS[1]:-}"
        NEXT=2
    fi
    if [ -z "$WHAT" ] || [ "$WHAT" = "bundle " ] || [ "$WHAT" = "wheel " ]; then
        echo "❌ Usage: just publish <module|path|bundle server-libs|wheel NAME> [release] [major|minor|patch|set VERSION]" >&2
        echo "   Wheels: {{wheel_modules}}" >&2
        exit 1
    fi
    REST=("${ARGS[@]:$NEXT}")

    RELEASE=false
    if [ "${REST[0]:-}" = "release" ]; then
        RELEASE=true
        REST=("${REST[@]:1}")
    fi
    LEVEL="${REST[0]:-patch}"
    CUSTOM_VERSION="${REST[1]:-}"

    case "$LEVEL" in
        major|minor|patch|set) ;;
        *) echo "❌ Level must be 'major', 'minor', 'patch', or 'set'."; exit 1 ;;
    esac
    if [ "$LEVEL" = "set" ] && [ -z "$CUSTOM_VERSION" ]; then
        echo "❌ 'set' requires a version. E.g. just publish $WHAT set 0.9.2"; exit 1
    fi

    # Resolve WHAT -> tag PREFIX + human description.
    if [ "$WHAT" = "bundle server-libs" ]; then
        PREFIX=server-libs
        # The set is Gradle-derived (build.gradle.kts `publishableLibs`) — this count is the
        # human echo in the confirm prompt, keep it in sync when publishableLibs changes.
        # 14 since CH-P2 (+transfer-core; server-proto now also carries transfer.v1+metis.v1).
        DESC="the 14 org.tatrman:* Maven libs (publishableLibs — incl. server-proto + transfer-core)"
    elif [ "${WHAT%% *}" = "wheel" ]; then
        WHEEL_NAME="${WHAT#wheel }"
        if ! echo " {{wheel_modules}} " | grep -q " $WHEEL_NAME "; then
            echo "❌ '$WHEEL_NAME' is not a publishable wheel." >&2
            echo "   Valid: {{wheel_modules}}" >&2
            exit 1
        fi
        # Wheel name != tag prefix: the distribution is `ttr-nlp`, the tag lane
        # is `python-nlp`. publish-python.yml owns the mapping; this mirrors it.
        case "$WHEEL_NAME" in
            ttr-nlp) PREFIX=python-nlp ;;
            *) echo "❌ No tag prefix known for wheel '$WHEEL_NAME' — add it here and in publish-python.yml." >&2; exit 1 ;;
        esac
        DESC="the ${WHEEL_NAME} wheel (shared/libs/python/${WHEEL_NAME}) → PyPI"
    else
        # The NLP backends live one level deeper than every other module and their
        # image name is `nlp-<dir>`, so `_resolve`'s one-level search cannot find
        # them and `basename` would give the wrong name. Map them explicitly.
        case "$WHAT" in
            nlp-stanza|nlp-spacy)
                MOD_NAME="$WHAT"
                MOD_PATH="services/nlp/backends/${WHAT#nlp-}" ;;
            nlp-morphodita|nlp-nametag3)
                echo "❌ '$WHAT' bakes a LINDAT model and has no CI lane — CI has no MODEL_URL." >&2
                echo "   Build it here instead:" >&2
                echo "     MODEL_URL=... MODEL_SHA256=... just nlp-backend-image ${WHAT#nlp-} <version> push" >&2
                exit 1 ;;
            *)
                MOD_PATH=$(just _resolve "$WHAT")
                MOD_NAME=$(basename "$MOD_PATH") ;;
        esac
        if ! echo " {{image_modules}} " | grep -q " $MOD_NAME "; then
            echo "❌ '$MOD_NAME' ($MOD_PATH) is not a publishable image module." >&2
            echo "   Valid: {{image_modules}}" >&2
            exit 1
        fi
        PREFIX="$MOD_NAME"
        DESC="the ${MOD_NAME} container image → ghcr.io/collite/${MOD_NAME}"
    fi

    # A release must come from a clean, committed state — CI checks out the tag,
    # and pushing the tag carries its commit to the remote.
    if [ -n "$(git status --porcelain)" ]; then
        echo "❌ Working tree is dirty — commit or stash before cutting a release."; exit 1
    fi

    BRANCH=$(git rev-parse --abbrev-ref HEAD)
    if [ "$BRANCH" != "master" ] && [ "$BRANCH" != "main" ]; then
        read -p "⚠️  On branch '$BRANCH', not master. Tag this commit anyway? [y/N] " -n 1 -r; echo ""
        [[ ${REPLY:-} =~ ^[Yy]$ ]] || { echo "❌ Aborting."; exit 1; }
    fi

    # HEAD == origin/<branch>, after a fetch — a checkout that has not pulled tags the previous
    # commit under this version.
    just _release-guard

    # Single version line per prefix — internal and RELEASE tags share it (a
    # RELEASE tag always mints a brand-new number, never reuses one already spent
    # by an internal tag), so a stripped RELEASE version never collides with an
    # already-published internal one on the same registry.
    LATEST=$(git tag -l "${PREFIX}/v*" | sed -E "s|^${PREFIX}/v||; s/-RELEASE\$//" | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -n 1 || true)
    LATEST="${LATEST:-0.0.0}"

    if [ "$LEVEL" = "set" ]; then
        if ! [[ "$CUSTOM_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "❌ '$CUSTOM_VERSION' is not a valid bare semver (X.Y.Z) — RELEASE markers are added automatically, don't include one."; exit 1
        fi
        NEW_VERSION="$CUSTOM_VERSION"
    else
        IFS='.' read -r MAJOR MINOR PATCH <<< "$LATEST"
        case "$LEVEL" in
            major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
            minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
            patch) PATCH=$((PATCH + 1)) ;;
        esac
        NEW_VERSION="${MAJOR}.${MINOR}.${PATCH}"
    fi

    if git rev-parse -q --verify "refs/tags/${PREFIX}/v${NEW_VERSION}" >/dev/null || \
       git rev-parse -q --verify "refs/tags/${PREFIX}/v${NEW_VERSION}-RELEASE" >/dev/null; then
        echo "❌ Version ${NEW_VERSION} already used (as a bare or RELEASE tag) for ${PREFIX}."; exit 1
    fi

    NEW_TAG="${PREFIX}/v${NEW_VERSION}"
    [ "$RELEASE" = true ] && NEW_TAG="${NEW_TAG}-RELEASE"

    if [ "$WHAT" = "bundle server-libs" ]; then
        if [ "$RELEASE" = true ]; then
            LANES="GH Packages (internal) + Maven Central (PUBLIC — counts against Central quota) — published as bare ${NEW_VERSION}"
        else
            LANES="GH Packages (internal) ONLY — not marked RELEASE, no Central step runs"
        fi
    elif [ "${WHAT%% *}" = "wheel" ]; then
        # PyPI has no internal staging equivalent, so a bare tag has no upload
        # lane at all — it runs the whole BUILD and stops. That is the point of
        # cutting one: it proves the wheel builds and passes its content checks
        # (incl. the bundled proto stubs) without spending a public version.
        if [ "$RELEASE" = true ]; then
            LANES="PyPI (PUBLIC — permanent, a spent version NEVER comes back) — published as bare ${NEW_VERSION}"
        else
            LANES="BUILD + content checks ONLY — not marked RELEASE, nothing is uploaded anywhere"
        fi
    else
        LANES="ghcr.io/collite/${PREFIX}:${NEW_VERSION} (internal registry — no external lane exists for images)"
    fi

    echo "────────────────────────────────────────────────────────────"
    echo "  Latest published : ${LATEST}"
    echo "  New version      : ${NEW_VERSION}   →  tag ${NEW_TAG}"
    echo "  Commit           : $(git rev-parse --short HEAD) on ${BRANCH}"
    echo "  Publishes        : ${DESC}"
    echo "  Lanes            : ${LANES}"
    echo "  ⚠️  Published registry versions are PERMANENT — they cannot be deleted."
    echo "────────────────────────────────────────────────────────────"
    read -p "Create and push ${NEW_TAG}? [y/N] " -n 1 -r; echo ""
    [[ ${REPLY:-} =~ ^[Yy]$ ]] || { echo "❌ Aborting."; exit 1; }

    git tag -a "${NEW_TAG}" -m "Release ${NEW_VERSION}"
    git push origin "${NEW_TAG}"
    echo "✅ Pushed ${NEW_TAG} — the matching workflow will publish: ${LANES}"
    echo "   Watch it: gh run watch  (or the repo's Actions tab)"
