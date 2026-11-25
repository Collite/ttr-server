<!-- SPDX-License-Identifier: Apache-2.0 -->
# golem-py — the Python OS Golem

The **reference implementation of the RV-11 resolution loop**: the deterministic core
answers what it can, the Golem reads the *gaps* it left, and decides — climb the
ladder, ask one question, answer, or refuse honestly with the lattice attached.

```
start ─┬─(fresh)──> call_core ──> assess_gaps ──> {ladder_loop | ask | emit | refuse}
       └─(resume)──────────────────> assess_gaps        ^         │
                                                        └─────────┘  the loop edge
```

Written in **Pydantic AI / pydantic-graph** (RV-43), from specification only — see
[`PROVENANCE.md`](PROVENANCE.md), which governs every session that touches this tree.

## What it is, and what it is not

* It **is** the loop both Golems run. The Kotlin platform Golem (RV-P5) implements the
  same shape against the same ladder schema and the same conformance fixtures — RV-28:
  one corpus, one core, N shells.
* It **is** the open estate's posture made concrete: zero rungs by default (RV-27), one
  ask at most (⚑RV-3), and a refusal that proves understanding rather than hiding it.
* It is **not** a service with a door. There is no HTTP or gRPC front here and that is
  deliberate — the conversation surface is the platform's. golem-py is driven by its
  CLI (`src/main.py`) and by the conformance runner.
* It **answers through an LLM lane when one is configured** (OL W1c, contracts §C of the
  open/paid line). The verdicts do not change: at `emit`, a question that composed and
  carries no blocking gap is answered by ONE LLM call that writes a `SELECT` over the
  model slice's `er.*` entities; the code checks it (`lane_checks.py`) and the query door
  runs it under the CALLER's row rules. A door rejection buys one repair; anything else
  refuses with the lattice — `QUERY_NOT_WRITTEN` or `LANE_UNAVAILABLE`, never a structural
  answer dressed as data. The envelope says so: `provenance.lane = "llm"`, the model, the
  SQL and its SHA-256, and `llm_handled` — the carried gaps the LLM claims, beside the
  core's own `gaps_carried`, which it never edits.
* It is **not** an intent classifier and not a second query dialect. Compose refusals and
  blocking gaps keep the refusal and the one question, lane or no lane; with the lane off
  (the default when no gateway or no door is configured) `emit` answers structurally.
* It **never binds anything**. Every binding arrives from `resolve.bind:v1` or
  `resolve.gate:v1` (RV-7 proposer-not-binder), and
  `tests/test_single_binder_fence.py` enforces that structurally.

## Layout

| Path | What |
|---|---|
| `src/golem_py/state.py` | the `ResolutionState` lattice mirror (contracts §1, as shipped at `p2-1`) |
| `src/golem_py/graph.py` | the five-node graph; ⚑ **every fork is a `g.decision()`** |
| `src/golem_py/verdicts.py` | `assess_gaps` as a PURE function — the only place anything is decided |
| `src/golem_py/ladder.py` + `config/golem-ladder.yaml` | `golem-ladder/v1`; the shipped default is zero-rung |
| `src/golem_py/budgets.py` | the three budgets (LLM invocations · ladder wall clock · HITL rounds) |
| `src/golem_py/core_client.py` | `resolve.bind:v1` + the honest lattice mapper |
| `src/golem_py/gate_client.py` | `resolve.gate:v1` — the only route from a hypothesis to a binding |
| `src/golem_py/door_client.py` | the SQL door (`query.v1 Run`, SQL over `ER`, the allow-list as `allowed_er_reads`): `LiveSqlDoor` + the `ResultBatch` decoding (OL-C12) |
| `src/golem_py/llm_client.py` | the gateway client over HTTP `POST /v1/chat/completions` with the team's bearer key: one non-streaming call for a JSON object at temperature 0, model class `smart` (OL-C13, D-43) |
| `src/golem_py/lane.py` | the LLM lane at `emit`: slice → prompt → call → checks → door, one repair, the two refusals (OL-C11, C16, C18) |
| `src/golem_py/lane_checks.py` | the checks before the door, one pure function (OL-C15) |
| `src/golem_py/lane_prompt.py` + `config/llm-lane-prompt.md` | the lane's prompt: OL-C14's inputs, OL-C15's rules and answer shape, the repair part |
| `src/golem_py/model_slice.py` | the `meta.v1` model slice — bound refs' entities first, capped; its names are the ER allow-list (OL-C14) |
| `generated/` | proto stubs, regenerated from `shared/proto`; `plan.v1` comes from the `ttr-plan-proto` wheel (gitignored). No `llm.v1` stubs: the gateway is HTTP (D-43) |

## Running it

```bash
just test-py services/golem-py            # unit suite (no network, no cluster)
just lint-py services/golem-py            # ruff
uv run mypy                               # strict, over src/ and tests/
uv run python scripts/gen_proto.py        # regenerate the proto stubs by hand
uv run python src/main.py --question "Zobraz tržby za rok 2025" --core localhost:7276
```

The versions in `pyproject.toml` are **pinned exactly** (pydantic-ai / pydantic-graph
2.22.0). The builder API is young; the pin is load-bearing, and the structural tests in
`tests/test_graph_shape.py` are what will tell you when a bump changes edge semantics.

## The LLM lane's settings

The lane is **on** when `GOLEM_LLM_LANE_ENABLED` is true (the default) **and** a gateway
**and** a door are configured; with either target empty it is off. On, it also needs a world
and the model slice, and its gateway needs an HTTP(S) URL ending in `/v1` and a key. The CLI
checks all of it before the turn, together with the `capable` rung's clock and the prompt
file (`check_lane_config`), and exits **2** naming what is missing — never the key. A
`Deps` built in code without a slice refuses each lane turn as `LANE_UNAVAILABLE`.

| Variable | Default | What |
|---|---|---|
| `GOLEM_LLM_LANE_ENABLED` | `true` | the switch (OL-C11) |
| `GOLEM_LLM_TARGET` | — | the open LLM gateway's base URL, ending in `/v1` (bp-dsk: `http://llm-gateway.ttr-server.svc.cluster.local:7280/v1`); the client posts to `…/v1/chat/completions` |
| `GOLEM_LLM_API_KEY` | — | the team's virtual key (`ttrk-…`), sent as `Authorization: Bearer`; inject it from a Secret, never on a command line |
| `GOLEM_LLM_MODEL_CLASS` / `GOLEM_LLM_MODEL` | `smart` / — | the gateway tier asked for as `model_tags`; a pinned model, if any, is sent instead |
| `GOLEM_QUERY_TARGET` / `GOLEM_QUERY_WORLD` / `GOLEM_QUERY_TIMEOUT_S` | — / — / `30` | `query.v1`, the door; the world every lane query runs in |
| `GOLEM_META_TARGET` / `GOLEM_META_PACKAGES` / `GOLEM_META_TIMEOUT_S` | — / — / `10` | `meta.v1`, and the packages the slice is read from |
| `GOLEM_LLM_MODEL_SLICE_ENTITIES` | `40` | the slice's cap; its entity names are the ER read allow-list |
| `GOLEM_LEXICON_ARCHIVE_PATHS` | — | the lexicon archives the operator library is read from, estate first, `os.pathsep`-separated; one that cannot be read exits 2 |

The call is one request, bounded as a whole by the ladder's `capable` timeout, with no retry; it
counts against the profile's
`max_llm_invocations` (`CHAT_QUICK`: 2 — the write and the one repair). The prompt states a
reference date — today in UTC unless `Deps.reference_date` is injected.

## Running it against an estate

The image is the CLI: one turn per run, the outcome on stdout. `--json` prints the envelope
for an answer (`sql`, `provenance.lane` / `llm_model` / `sql_sha256`, `row_count`, `rows`,
`llm_handled`, `gaps_carried`), and for a refusal or an ask, the outcome without its
lattice. Five variables name the estate: `GOLEM_QUERY_TARGET`, `GOLEM_QUERY_WORLD`,
`GOLEM_LLM_TARGET`, `GOLEM_META_TARGET` and `GOLEM_META_PACKAGES`. Two more come from the
cluster: `GOLEM_LLM_API_KEY`, the team's gateway key, from a Secret; and
`GOLEM_LEXICON_ARCHIVE_PATHS`, the estate's lexicon archive, which holds the operator library.
`--subject` is the caller, and the door applies that caller's row rules to the query the
lane wrote.

```bash
docker run --rm \
  -e GOLEM_QUERY_TARGET=query:7306 -e GOLEM_QUERY_WORLD=hartland_cz \
  -e GOLEM_LLM_TARGET=http://llm-gateway:7280/v1 -e GOLEM_LLM_API_KEY \
  -e GOLEM_META_TARGET=metadata:7261 -e GOLEM_META_PACKAGES=hartland \
  -e GOLEM_LEXICON_ARCHIVE_PATHS=/lexicon/lexicon.tar.zst -v "$PWD/lexicon:/lexicon:ro" \
  ghcr.io/collite/golem-py:<version> \
  --core resolver:7276 --subject <persona> --question "Zobraz tržby za rok 2025" --json
```

(`-e GOLEM_LLM_API_KEY` with no value passes the key from your environment, so it is not on
the command line.) On a cluster, run it as a one-off Job in a throwaway namespace. The key
comes from the team's Secret, and the operator library from the estate's `hartland-lexicon`
ConfigMap (bp-dsk: namespaces `ttr-server` and `kantheon`, binary key `lexicon.tar.zst`):

```yaml
env:
  - name: GOLEM_LLM_TARGET
    value: http://llm-gateway.ttr-server.svc.cluster.local:7280/v1
  - name: GOLEM_LLM_API_KEY
    valueFrom: { secretKeyRef: { name: <team-key-secret>, key: <key> } }
  - name: GOLEM_LEXICON_ARCHIVE_PATHS
    value: /lexicon/lexicon.tar.zst
volumeMounts:
  - { name: lexicon, mountPath: /lexicon, readOnly: true }
# …and under the pod's volumes:
#   - name: lexicon
#     configMap: { name: hartland-lexicon, items: [ { key: lexicon.tar.zst, path: lexicon.tar.zst } ] }
```

A Secret or ConfigMap mounts only in its own namespace, so a throwaway namespace needs its own
copy of the key, deleted with the namespace. Leave out `GOLEM_QUERY_TARGET` or
`GOLEM_LLM_TARGET`, and the same command answers structurally, as before W1c. Leave out the
archive, and a question naming an operator (`Zobraz …` → `op:show`) refuses
`NO_CAPABLE_PLUGIN`. A question that names no operator refuses that way with or without one
(OL-C18).

### OL W1c certification

Run one disposable Job per question. Use a namespace that contains the existing team Secret
and lexicon ConfigMap. Record the archive's SHA-256 and the UTC interval of each turn with the
estate's verification evidence.

| Question | Expected result |
| --- | --- |
| `Zobraz tržby za rok 2025` | One all-channel revenue total for `[2025-01-01, 2026-01-01)`. |
| `Zobraz tržby za minulý měsíc` | One all-channel revenue total for the previous calendar month, from its first day up to the first day of the current month. Use the run's UTC reference date. The resolver leaves the period unbound and the lane carries that gap alongside the matching `llm_handled` span. |
| `Zobraz tržby podle prodejen` | The existing resolver grouping fixture (`ms-grouping-cs`). One revenue sum per selling store, with complete store keys and no date restriction. |

Before running the image, collect the expected totals and store groups from independent
read-only `query.v1 Run` calls using the certification caller and world. Save the expected
amounts and row counts before the first Job. The relative fixture also needs the live resolver's
gap and absence of a bound period. Stop for a fixture ruling if that check or a live question fails.

Copy [`k8s/certification-job.yaml`](k8s/certification-job.yaml) to a temporary file. Replace its
example digest with the verified release digest, set the question and caller, and fill the
service endpoints, world, package, and Secret and ConfigMap references for the certification
estate. Set `context`, `namespace`, and `job_file` to those run settings. In Bash:

```bash
(
  set -euo pipefail
  k=(kubectl --context "$context" -n "$namespace")
  "${k[@]}" create --dry-run=server -f "$job_file" -o name
  date -u +%FT%TZ >&2
  job=$("${k[@]}" create -f "$job_file" -o name)
  trap '"${k[@]}" delete "$job" --cascade=foreground --wait=true' EXIT
  "${k[@]}" wait --for=condition=complete --timeout=130s "$job"
  "${k[@]}" get pods -l "job-name=${job#*/}" \
    -o jsonpath='{.items[*].status.containerStatuses[*].imageID}{"\n"}' >&2
  "${k[@]}" logs "$job"
  date -u +%FT%TZ >&2
)
```

Check the image ID, answer shape, served model, SQL over `er.*`, `row_count`, `llm_handled`,
absence of truncation, and agreement with the expected rows and dates. Correlate each Job's
turn window with query-side validation for the certification caller. Verify that the Job and
its Pod are gone before the next question. Record the sanitized verdicts in the certification PR.

## Two traps worth knowing before you edit the graph

1. **`.to(a, b)` is a fan-out, not a branch — and it fails silently.** Every target
   runs. Every fork must be a `g.decision()`; `test_no_node_is_a_fork` asserts it over
   the built graph so review is not the only line of defence.
2. **There is no built-in persistence.** `pydantic_graph.persistence` does not exist and
   `Graph.run()` has no start-at-node argument. Resume is ours: a `ResolutionState`
   snapshot plus a start step that routes on the pin. The `resume_token` stays the
   CORE's (RS-26) — signing an option set agent-side would let the agent fabricate
   "the user chose X".
