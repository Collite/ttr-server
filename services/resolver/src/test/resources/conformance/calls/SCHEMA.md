# `calls:` conformance-fixture schema (RG-P6)

A **conversation fixture** replays an ordered list of tool calls against the `resolve.bind:v1`
door and asserts the outcome of each turn. This is the assertion vocabulary the three-tier
conformance harness (RG-P6.S2) consumes; the **refusal-over-guess** seeds authored in S1.T3 live
here from day one and are extended into the full E2E core tier by SV-P4 (with the reference Golem).

```jsonc
{
  "id": "kebab-case-id",                 // unique fixture id
  "description": "what this asserts",
  "seed_only": true,                     // optional. true → a placeholder seed whose LIVE drive is
                                         //   SV-P4 (needs nlp/fuzzy/grounding or a dark-geo deploy);
                                         //   the harness validates its shape but does not run it.
  "turns": [
    {
      "tool": "resolve.bind:v1",          // the door tool id (naming ledger)
      "args": { "conversation_id": "…", "text": "…" },   // the MCP tool args, verbatim
      "scenario": "ambiguous_member",     // S1 seed: names the core behavior to replay.
                                          //   S2 REPLACES this with a live pipeline run driven by
                                          //   fixture-carried nlp-parse + fuzzy-match data — the
                                          //   scenario tag is the seam, not the final input.
      "expect": {
        "outcome": "clarification",       // clarification | resolution | empty | error
        "no_binding_below_threshold": true,   // the refusal-over-guess invariant (always assert true)
        "min_options": 2,                 // clarification only — at least this many distinct options
        "min_bindings": 2,                // resolution only — at least this many bindings (seed hint)
        "degraded_allowed": true,         // resolution only — a degraded/floor outcome is acceptable
        "error_code": "INVALID_ARGUMENT"  // error only
      }
    }
  ]
}
```

## `resolve.gate:v1` turns (RV-P2.4)

A turn may name the **re-gate sibling** instead of the door. Such a turn gates the lattice the
PRIOR turn produced, so it carries `hypotheses` rather than `args` — it has no question of its own
and no conversation to name — plus the `lookups` the matcher should answer with when the core turns
those hypotheses into lexicon questions.

```jsonc
{
  "surface": "grpc",                     // fixture-level. The gate rpc has no MCP door (P2.4 built
                                         //   the rpc; a lattice-shaped MCP arg surface is its own
                                         //   design work), so these turns drive gRPC directly.
  "turns": [
    { "tool": "resolve.bind:v1", "args": { … }, "fixture": "h1prime-cs", "expect": { … } },
    {
      "tool": "resolve.gate:v1",
      "hypotheses": [                     // `org.tatrman.resolver.v1.Hypothesis`, verbatim
        { "span": { "start": 20, "end": 26, "text": "5010O1" },
          "correction": "501001", "ref": "md.dimension.Account.code", "proposing_rung": "local" }
      ],
      "lookups": { "501001": [ /* FuzzyMatch rows, verbatim */ ] },
      "expect": {
        "outcome": "resolution",
        "no_binding_below_threshold": true,
        "gated_refs": ["md.dimension.Account.code#501001"],   // what SURVIVED the gate
        "evidence_classes": ["EVIDENCE_CLASS_EXACT"],         // the gate's evidence, per binding
        "proposing_rung": "local",                            // provenance: who proposed it
        "gap_kinds": []                                       // recomputed after gating
      }
    }
  ]
}
```

The invariant a gate turn adds to the suite's refusal-over-guess vocabulary: **a hypothesis is not
evidence.** `gated_refs` may only list bindings that came out of the same evidence-class gate as
any other candidate; a rung's confidence buys nothing (RV-7).

## Quoted literals (LP contracts §2)

A turn may assert what the lattice made of a **quoted** span. The vocabulary is small on purpose:
a literal has no binding to describe, and the whole claim is that nothing happened to it.

```jsonc
"expect": {
  "outcome": "empty",
  "no_binding_below_threshold": true,
  "verbatim": [
    { "text": "Valmy Oil",                    // §1.4 text — delimiters off, edges trimmed
      "span": { "start": 20, "end": 31 },     // the span as TYPED — delimiters included
      "attributions": 0,                      // 0 ⇒ headless; the estate declared no name column
      "gap_kind": "GAP_KIND_G3_UNATTRIBUTED" } // the gap on that value, "" for none
  ],
  "no_span_inside_literal": true              // no mention and no other value overlaps the literal
}
```

`no_span_inside_literal` is the one that matters, and it is the refusal-over-guess invariant
restated for a span the user already explained: the n-gram floor, the proper-noun path and NER
would all reach for a quoted name, and none of them may. **An ATTRIBUTED literal still is not
expressible here, and the reason has changed.** It used to be ⚑LPQ-5 — the model's
`semantics { name: }` reached the resolver only through the per-request `Registry` override, and
the door has no argument for one. LP-P2b closed that: the compiled lexicon archive carries the
facet (`TargetFacts.nameRef/codeRef/codeFormat`, `ttr-lexicon-compiled/v4`) and the snapshot
channel projects it. What is left is that these fixtures drive the door with a STUB registry and no
archive on disk, so there is no `targets` map to read — a fixture-harness limit, not a missing
capability. Attribution and `predicate_ref` are covered at the pipeline seam
(`VerbatimLatticeTest`, which now asserts the hero's `pred:starts_with` end to end) and at the
snapshot channel (`LexiconArchiveRegistrySourceTest`, through the real packer). The hero door turn
lands with LP-P3, which builds an estate archive for this harness.

## Outcomes
- **`clarification`** — `AwaitingClarification`: options + an opaque `resumeToken`. The door offers a
  choice; it does **not** bind. (Instance ambiguity → refuse over guess.)
- **`resolution`** — a `Resolution` carrying ≥1 binding, each with provenance (score ≥ bind threshold).
- **`empty`** — a `Resolution` with **zero** bindings: the core found nothing confident to bind and says
  so honestly rather than guessing. (Below-threshold → refuse over guess.)
- **`error`** — `isError=true` (bad args / identity refusal), with an `errorCode`.

## The refusal-over-guess invariant (`no_binding_below_threshold`)
Every fixture asserts it: the door must **never** surface a domain binding whose provenance score is
below the bind threshold, and must **never** turn an ambiguous or below-threshold span into a guessed
binding. Ambiguity resolves to `clarification`; no-confident-match resolves to `empty`. This is the
door's signature guarantee (RS-27) and the gate the SV-P3 parity bar checks.
