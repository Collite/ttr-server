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
| `src/golem_py/llm_client.py` | the `llm.v1` gateway client: a JSON object at temperature 0, model class `capable` (OL-C13) |
| `src/golem_py/lane.py` | the LLM lane at `emit`: slice → prompt → call → checks → door, one repair, the two refusals (OL-C11, C16, C18) |
| `src/golem_py/lane_checks.py` | the checks before the door, one pure function (OL-C15) |
| `src/golem_py/lane_prompt.py` + `config/llm-lane-prompt.md` | the lane's prompt: OL-C14's inputs, OL-C15's rules and answer shape, the repair part |
| `src/golem_py/model_slice.py` | the `meta.v1` model slice — bound refs' entities first, capped; its names are the ER allow-list (OL-C14) |
| `generated/` | proto stubs, regenerated from `shared/proto` + `shared/proto-llm`; `plan.v1` comes from the `ttr-plan-proto` wheel (gitignored) |

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
**and** a door are configured; with either target empty it is off. On, it also needs the
model slice — a lane that cannot read the model refuses as `LANE_UNAVAILABLE`.

| Variable | Default | What |
|---|---|---|
| `GOLEM_LLM_LANE_ENABLED` | `true` | the switch (OL-C11) |
| `GOLEM_LLM_TARGET` | — | `llm.v1`, the open LLM gateway |
| `GOLEM_LLM_MODEL_CLASS` / `GOLEM_LLM_MODEL` | `capable` / — | the model class asked for; a pinned model, if any |
| `GOLEM_QUERY_TARGET` / `GOLEM_QUERY_WORLD` / `GOLEM_QUERY_TIMEOUT_S` | — / — / `30` | `query.v1`, the door; the world every lane query runs in |
| `GOLEM_META_TARGET` / `GOLEM_META_PACKAGES` / `GOLEM_META_TIMEOUT_S` | — / — / `10` | `meta.v1`, and the packages the slice is read from |
| `GOLEM_LLM_MODEL_SLICE_ENTITIES` | `40` | the slice's cap; its entity names are the ER read allow-list |

The call runs under the ladder's `capable` timeout and counts against the profile's
`max_llm_invocations` (`CHAT_QUICK`: 2 — the write and the one repair). The prompt states a
reference date — today in UTC unless `Deps.reference_date` is injected.

## Two traps worth knowing before you edit the graph

1. **`.to(a, b)` is a fan-out, not a branch — and it fails silently.** Every target
   runs. Every fork must be a `g.decision()`; `test_no_node_is_a_fork` asserts it over
   the built graph so review is not the only line of defence.
2. **There is no built-in persistence.** `pydantic_graph.persistence` does not exist and
   `Graph.run()` has no start-at-node argument. Resume is ours: a `ResolutionState`
   snapshot plus a start step that routes on the pin. The `resume_token` stays the
   CORE's (RS-26) — signing an option set agent-side would let the agent fabricate
   "the user chose X".
