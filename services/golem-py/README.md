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

## Two traps worth knowing before you edit the graph

1. **`.to(a, b)` is a fan-out, not a branch — and it fails silently.** Every target
   runs. Every fork must be a `g.decision()`; `test_no_node_is_a_fork` asserts it over
   the built graph so review is not the only line of defence.
2. **There is no built-in persistence.** `pydantic_graph.persistence` does not exist and
   `Graph.run()` has no start-at-node argument. Resume is ours: a `ResolutionState`
   snapshot plus a start step that routes on the pin. The `resume_token` stays the
   CORE's (RS-26) — signing an option set agent-side would let the agent fabricate
   "the user chose X".
