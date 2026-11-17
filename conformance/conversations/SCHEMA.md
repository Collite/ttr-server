<!-- SPDX-License-Identifier: Apache-2.0 -->
# `conversations/` — the conformance-conversation fixture schema (RV-P4.4)

A **conversation fixture** replays an ordered list of turns through a GOLEM and asserts
the outcome of each. It is the multi-turn tier above the door-level `calls:` fixtures in
`services/resolver/src/test/resources/conformance/calls/`, and it is the corpus RV-28
means by *"one corpus, one core, N shells"*: the same files must pass through the Python
OS Golem (RV-P4) and the Kotlin platform Golem (RV-P5), unchanged.

## ⚑ T1 decision — these EXTEND the `calls:` vocabulary; they are not a second schema

Recorded rather than assumed, because the alternative was live: a sibling schema would
have been easier to author and impossible to keep aligned. What is reused verbatim:
`id` · `description` · `turns[]` · `tool` · `args` · `expect` · the
`no_binding_below_threshold` invariant · naming a lattice golden by id (the P2 gate
fixture already writes `"fixture": "h1prime-cs"`). What is added, and why:

| Addition | Why it could not be reused |
|---|---|
| `tool: "golem.turn:v1"` / `"golem.resume:v1"` | the Golem is not the door; a turn is a conversation step, not an rpc |
| `core: {lattice, resume_token, options[]}` | a door fixture IS the core; a Golem fixture must say what the core answered |
| `gate: {outcomes[], updated_gaps[], rung_log_entry}` | same reason, for `resolve.gate:v1` |
| `pin: {option_id \| free_text \| escape}` | the door has no user; a conversation does |
| `invariants[]` | stated in the file, per P2.4's rule, so a second shell inherits the WORDS and not only the assertions |
| `replay_of: <n>` | at-least-once delivery is the norm; a suite that never redelivers cannot catch the bugs it causes. `<n>` is **0-based over the non-gate turns** |

**Location is the other half of the decision.** These live at the repo root under
`conformance/`, beside `corpus-hashes.sha256`, rather than inside one service's test
resources — a corpus two shells must share cannot be owned by one of them.

## The shape

```jsonc
{
  "id": "h1-answer",                     // kebab-case, unique
  "description": "what this asserts",
  "surface": "golem",
  "corpus": "hartland_cz",               // the estate the lattices come from (RV-28)
  "invariants": ["an ask is not an answer", "…"],
  "turns": [
    {
      "tool": "golem.turn:v1",
      "args": { "conversation_id": "…", "turn_id": "…", "text": "…",
                "locale": "cs", "caller_subject": "…", "profile": "CHAT_QUICK" },
      "core": {                          // what the recorded core answers with
        "lattice": "h1-cs",              // a golden id — never a copy of the lattice
        "resume_token": "…",             // only when the turn should pause
        "options": [ { "id": "opt-1", "label": "…", "ref": "…" } ]
      },
      "expect": {
        "outcome": "answer",             // answer | ask | refusal
        "llm_invocations": 0,            // asserted NUMERICALLY — 0 is the H1 claim
        "asks": 0,
        "measures": ["md.measure.cost"], // what compose selected
        "operators": ["op:show"],        // what it was taught to do
        "gaps_carried": ["GAP_KIND_G1_UNBOUND"],
        "refusal_reason": "NO_CAPABLE_PLUGIN",
        "no_binding_below_threshold": true
      }
    },
    { "tool": "golem.resume:v1", "pin": { "option_id": "opt-1" }, "gate": { … } },
    { "tool": "resolve.gate:v1", "hypotheses": [ … ], "gate": { … } }
  ]
}
```

## ⛑ Every `expect:` key, and the rule that keeps this list honest

A runner **must reject a fixture that states a key it does not assert**. This is not a
style rule: a review found five keys silently ignored across eleven occurrences here —
including `no_binding_below_threshold`, which this file lists above as reused *verbatim*
and which the Kotlin `calls:` tier does assert, and `byte_identical_to_turn`, which is
the entire reason `h2-ask-pin-resume` has a third turn. None was a behaviour bug; each
was a clause of the contract that nobody enforced. A shared corpus that asserts less than
it says is worse than a smaller one, because the second shell reads the file and believes
it. `test_no_fixture_states_an_expectation_the_runner_never_reads` is the guard; the
Kotlin shell needs its own.

| key | applies to | asserts |
|---|---|---|
| `outcome` | every turn | `answer` \| `ask` \| `refusal` |
| `llm_invocations` | every turn | the turn's count, off the lattice — a **pause** may legitimately claim 0 |
| `asks` | every turn | HITL rounds spent this turn, off the lattice. **Not `asks_total`** — one name, and this is it |
| `no_binding_below_threshold` | every turn | no binding in the lattice sits below the evidence-class floor: WEAK never binds (RV-14), and UNSPECIFIED is weaker than WEAK |
| `byte_identical_to_turn: <n>` | a replay turn | this turn's output equals turn `<n>`'s, byte for byte. **`<n>` is 0-based over the NON-GATE turns**, which is the same index `replay_of` uses |
| `gap_kind` · `asked_span` · `min_options` · `escape_offered` · `snapshot_stored` | `ask` | the pause carries what a resume needs |
| `refusal_reason` · `min_bindings` · `gap_kinds` · `composable_residue` | `refusal` | understanding proven, capability honestly absent |
| `core_calls_total` · `measures` · `subjects` · `operators` · `inapplicable_operators` · `member_filters` · `gaps_carried` · `gaps_carried_spans` · `provenance_lexicon_artifact_hash` · `gated_refs` | `answer` | what compose selected, what it dropped and why, and the RV-39 tuple that bound it |
| `proposing_rung` | `answer` after a pin, and every `resolve.gate:v1` turn | who PROPOSED — for a pin, `user`, deliberately outside the four-rung vocabulary so the ladder's health numbers cannot be made to lie by it |
| `gated_refs` · `evidence_classes` · `gap_kinds` | `resolve.gate:v1` | what survived the gate, at what class |
| `door_calls` · `door_subject` · `prompt_states` | a turn with `lane:` | the door was called this often, every time under the caller's subject, in the lane's world and with the slice as the allow-list; the n-th gateway prompt states each string of `prompt_states[n]`, and there are exactly as many prompts as entries. Every recorded reply and door outcome must be consumed |
| `lane` · `llm_model` · `sql` · `rows` · `llm_handled_spans` | `answer` through the lane | OL-C17's envelope: `provenance.lane`, `provenance.llm_model`, the SQL (it is also the last query the door ran, and `provenance.sql_sha256` hashes it), the row count, and the spans the LLM claims |
| `explanation_contains` | `refusal` | the refusal names its last error: the broken rule, the door's text, or the leg that was down |

## `lane:` — the LLM lane's legs, recorded (OL W1c·S3)

A `golem.turn:v1` turn may carry a `lane:` block. When it does, the lane is ON for that
turn (OL-C11: the switch is on, and there is a gateway and a door), and all three of its
legs are recordings:

```jsonc
"lane": {
  "slice": "cost-ledger",          // lane/<id>.slice.json: the meta.v1 model slice
  "world": "hartland_cz",          // the world every lane query runs in
  "reference_date": "2026-11-16",  // what "last month" is counted from (OL-C14)
  "gateway": { "model": "…", "replies": [ {"sql": "…", "handled_spans": [], "note": "…"},
                                          {"raw": "not an object"}, {"unavailable": "…"} ] },
  "door": [ {"columns": ["…"], "rows": [["…"]], "provenance": {}},
            {"rejected": {"code": "translator_rejected", "message": "…"}}, {"unavailable": "…"} ]
}
```

A turn without `lane:` runs with the lane OFF, which is how every fixture written before
W1c·S3 still runs. A gateway reply that is an object is sent as its JSON text. The door's
`rejected` text is handed back verbatim, as a real door's would be. **Recorded** means
*replayed*, in the same sense that the core is recorded. The four `ol-lane-*` fixtures
replay the resolver's own goldens. Their gateway replies and door results are AUTHORED
from contracts §C, because when they were written no live `llm.v1` gateway existed to
record from (W1c·S3 Found while doing). The repair fixture's door text has the shape that
bp-dsk's door returned for the same mistake. A shell with no LLM lane cannot run a turn
that carries `lane:`. It skips that fixture and says so: it does not run it with the lane off.

## The invariant vocabulary

Inherited from P2.4 — *"a hypothesis is not evidence"* — and extended by this tier:

- **an ask is not an answer.** A paused turn has produced nothing; a suite that counts
  it as output would report a Golem that only ever asks as passing.
- **a refusal is not an error.** `RefusalWithGaps` carries the lattice and proves the
  understanding. A runner that treats it as a failure cannot express H4 at all.
- **a hypothesis is not evidence — including the user's.** A pin becomes a binding only
  by surviving `resolve.gate:v1` (RV-7). The user is the proposer most likely to name
  something the vocabulary does not have.
- **the token is the core's, the snapshot is ours.** They travel side by side and are
  never merged.

## Provenance

The lattices are the resolver's own goldens under
`services/resolver/src/test/resources/lattice/` — the same files the Kotlin core is
byte-compared against (RV-P2.1 T2 / P2.5 T4), whose parses are the RV-P0.2 spike's real
cached Stanza output. Nothing here re-authors a lattice; a fixture that needed a new one
would need a new golden first, in the core's own suite, where it belongs.

Every fixture in this directory is hash-pinned in `conformance/corpus-hashes.sha256`.
