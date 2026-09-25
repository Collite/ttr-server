# PROVENANCE — the Python OS Golem spike (and everything that grows from it)

**Status:** in force from the first line of the spike (RV-P0.3·T1, 2026-08-02) and from
the first commit of this service (RV-P4.1·T1, 2026-08-06).
**Home:** `tatrman-server/services/golem-py/` — this file GRADUATED here with the code,
as the spike said it would. It is not a spike artifact and it is not archived: it
governs every session that touches this tree.

## Statement of independent authorship

Everything in this directory — the spike it grew out of and this service — was written
from **specification only**. No source code,
test fixture, prompt, or configuration file from any pre-existing agent implementation
was read, opened, copied, adapted, translated, or consulted while authoring it.

The Python OS Golem is an **independent implementation** of a specification that the
Tatrman project owns.

## Why this file exists (RV-43 / ⚑RV-7)

The reference Golem is deliberately written in **Pydantic AI / pydantic-graph**, not in
the framework used by the legacy agent. This is a licensing decision made structural
rather than procedural: a different framework, a different language idiom, and a
different graph model make independent authorship visible at a glance instead of
resting on assertions in a document.

**RO-19 is cited here.** RO-19 recorded that a textbook rewrite is the default path and
that a contribution from the legacy estate would be an "accelerant". **That accelerant
path is CLOSED** (RV-43). It is not being used, and nothing in this directory derives
from it. The only live RO-19 leg is the *extended-tier evaluation corpus*, which is a
scoring input, never a source.

## The specification — the complete list of what MAY be consulted

| Source | What it supplies |
|---|---|
| `project/kantheon/features/resolving/design/design.md` **v3.1** | the RV-11 loop shape, the heroes, the ladder semantics |
| `project/kantheon/features/resolving/contracts.md` | `ResolutionState` / `Mention` / `Binding` / `GapRecord` shapes, the door contract, budgets (§3) |
| `tatrman-server` **`.proto` files** | the wire contract. Generating stubs from a shared proto is contract conformance, not code reuse — the proto IS the interface both sides implement |
| Public **Pydantic AI / pydantic-graph** documentation (via context7) | framework API |
| `Collite/ttr-demo` `model/` + `model/lexicon/` | the fixture estate (`hartland_cz`) |

## Off-limits — the complete list

| Source | Why |
|---|---|
| the **legacy `ai-platform` repository**, in whole or in part | the thing being independently reimplemented |
| **`EXAMPLES.md`'s "Python + LangGraph agent" row** and anything it points at | it is a pointer *into* the legacy agent |
| any legacy **prompt text**, **eval fixture**, or **agent config** | expression, not interface |
| the legacy Golem's **conversation corpora** | fixtures are `hartland_cz` only |

## Compliance record

- **2026-08-02 · RV-P0.3, this spike.** Clean-room observed. The legacy `ai-platform`
  repository was **not opened at any point** in the session that produced this
  directory — not read, not searched, not listed. `EXAMPLES.md` was not opened.
  Framework API came from context7 (`/pydantic/pydantic-ai`) and from the installed
  package's own behaviour under test. The contract mirror in `agent.py` was typed from
  `contracts.md` §1. The fixture question is hero H1 from `design.md`.
- **2026-08-06 · RV-P4.1, the graduation into `services/golem-py/`.** Clean-room
  observed. The legacy `ai-platform` repository was not opened, listed or searched in
  the session that produced this service; `EXAMPLES.md` was not opened. What WAS read:
  `contracts.md` (§1 lattice, §3 ladder, §6 Golem surfaces), `design.md` v3.1, the
  shared `.proto` tree, this repo's own Python conventions (`services/nlp`), and the
  resolver's committed lattice goldens under
  `services/resolver/src/test/resources/lattice/` — which are this repo's fixtures for
  the contract this service implements, produced by RV-P2 in the open, and named on the
  MAY-consult list as `hartland_cz`-lineage estate fixtures. Framework behaviour was
  established by inspecting the installed `pydantic-graph` package under test.
- **2026-09-25 · LP review-103 L9 (VERBATIM values).** Clean-room observed. The legacy
  `ai-platform` repository was not opened, listed or searched in the session that made this
  change; `EXAMPLES.md` was not opened. What WAS read: the shared `.proto` tree
  (`ValueFinding.verbatim_text` / `predicate_ref`, `VALUE_KIND_VERBATIM`), the resolver's
  `LatticeAssembler` (the producer of the VERBATIM value, this repo), the LP `contracts.md`
  §2/§5/§8 and `review-103.md` (Tatrman project documents), and this directory's own code.
- Sessions that touch this directory later **append a dated line here**. A session that
  cannot attest to the above must say so explicitly rather than leave the record silent.
