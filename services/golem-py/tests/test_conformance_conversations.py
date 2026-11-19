# SPDX-License-Identifier: Apache-2.0
"""P4.4·T2/T3 — the conformance-conversation tier.

Every fixture in `conformance/conversations/` driven through the whole service, with
its own `expect:` block as the assertion. The fixtures are SHARED — the Kotlin Golem
(RV-P5) must pass these same files unchanged (RV-28) — so this runner asserts what the
fixture SAYS rather than what golem-py happens to produce.

The assertions here are deliberately generic. A shell-specific check would pass in one
language and be unwritable in the other, which is exactly how "one corpus, N shells"
stops being true.
"""

from __future__ import annotations

import json

import pytest

from golem_py.outputs import Answer, Ask, RefusalWithGaps
from golem_py.query_client import sql_sha256
from golem_py.state import EvidenceClass
from tests.conversation_runner import FIXTURE_DIR, drive, fixture_ids, load_fixture

EXPECTED_FIXTURES = [
    "h1-answer",
    "h1prime-regate",
    "h2-ask-pin-resume",
    "h4-refusal",
    "h5-answer-with-gap",
    # OL W1c·S3·T2 — the same goldens with the LLM lane on, its legs recorded (`lane:`).
    "ol-lane-covered",
    "ol-lane-door-repair",
    "ol-lane-query-not-written",
    "ol-lane-relative-period",
]

# ⛑ EVERY key a fixture may state, and the guard below asserts nothing outside it appears.
# A review found five keys the runner silently ignored across eleven occurrences —
# including `no_binding_below_threshold`, which SCHEMA.md names as reused VERBATIM from
# the `calls:` vocabulary and which the Kotlin tier does assert, and
# `byte_identical_to_turn`, which is the entire reason h2 has a third turn. None of them
# was a behaviour bug; every one of them was a clause of the contract that nobody
# enforced. A shared corpus that asserts less than it says is worse than a smaller one,
# because the second shell reads the file and believes it.
_TURN_KEYS = {
    "outcome",
    "llm_invocations",
    "asks",
    "no_binding_below_threshold",
    "byte_identical_to_turn",
    # the lane's legs (a turn with `lane:`): what the door and the gateway were asked
    "door_calls",
    "door_subject",
    "prompt_states",
}
_ASK_KEYS = {"gap_kind", "asked_span", "min_options", "escape_offered", "snapshot_stored"}
_ANSWER_KEYS = {
    "core_calls_total",
    "measures",
    "subjects",
    "operators",
    "inapplicable_operators",
    "member_filters",
    "gaps_carried",
    "gaps_carried_spans",
    "provenance_lexicon_artifact_hash",
    "gated_refs",
    "proposing_rung",
    # an answer the lane wrote (OL-C17)
    "lane",
    "llm_model",
    "sql",
    "rows",
    "llm_handled_spans",
}
_REFUSAL_KEYS = {
    "refusal_reason",
    "min_bindings",
    "gap_kinds",
    "composable_residue",
    "explanation_contains",
}
_GATE_KEYS = {"gated_refs", "evidence_classes", "proposing_rung", "gap_kinds"}
KNOWN_TURN_KEYS = _TURN_KEYS | _ASK_KEYS | _ANSWER_KEYS | _REFUSAL_KEYS


def test_the_corpus_holds_exactly_the_pinned_conversations() -> None:
    """A suite that silently loses a fixture reports as green. The list is pinned here
    and the CONTENT is pinned by `conformance/corpus-hashes.sha256`."""
    assert fixture_ids() == EXPECTED_FIXTURES


@pytest.mark.parametrize("name", EXPECTED_FIXTURES)
def test_every_fixture_states_its_invariants_and_names_its_corpus(name: str) -> None:
    """P2.4's rule for this corpus, extended: a fixture says what it asserts, in words
    a second shell can be held to."""
    fixture = load_fixture(name)

    assert fixture["corpus"] == "hartland_cz"
    assert fixture["invariants"], "a fixture with no stated invariant teaches nothing"
    assert fixture["turns"], "a fixture with no turns asserts nothing"


@pytest.mark.parametrize("name", EXPECTED_FIXTURES)
def test_no_fixture_states_an_expectation_the_runner_never_reads(name: str) -> None:
    """The guard the corpus was missing. Every `expect:` key must be one this runner
    actually asserts — otherwise a fixture can claim anything and stay green, which is
    how a shared corpus quietly stops binding the shell it was written for."""
    for position, turn in enumerate(load_fixture(name)["turns"], 1):
        expect = turn.get("expect", {})
        known = _GATE_KEYS if turn["tool"] == "resolve.gate:v1" else KNOWN_TURN_KEYS
        unknown = sorted(set(expect) - known)
        assert not unknown, f"{name} turn {position} states unread key(s): {unknown}"


@pytest.mark.parametrize("name", EXPECTED_FIXTURES)
def test_no_fixture_copies_a_lattice(name: str) -> None:
    """The lattices are the resolver's own goldens, named by id. A fixture that inlined
    one would drift from the core's suite the first time either moved."""
    raw = json.loads((FIXTURE_DIR / f"{name}.json").read_text(encoding="utf-8"))
    for turn in raw["turns"]:
        core = turn.get("core")
        if core is not None:
            assert isinstance(core["lattice"], str)


@pytest.mark.asyncio
@pytest.mark.parametrize("name", EXPECTED_FIXTURES)
async def test_fixture(name: str) -> None:
    run = await drive(name)
    turns = [t for t in run.fixture["turns"] if t["tool"] != "resolve.gate:v1"]

    for index, turn in enumerate(turns):
        expect = turn.get("expect", {})
        output = run.outputs[index]
        _assert_turn(name, index, expect, output, run)
        _assert_lane(name, index, expect, output, run)

    # The gate turns, asserted against the lattice they folded into.
    gate_turns = [t for t in run.fixture["turns"] if t["tool"] == "resolve.gate:v1"]
    for position, turn in enumerate(gate_turns):
        _assert_gate(turn.get("expect", {}), run, position)


def _bindings(output: object) -> list[object]:
    """Every binding in a turn's lattice — on mentions AND on value attributions."""
    lattice = getattr(output, "lattice", None)
    if lattice is None:
        return []
    found: list[object] = [b for m in lattice.mentions for b in m.bindings]
    found += [
        a.binding for v in lattice.values for a in v.attributions if a.binding is not None
    ]
    return found


def _llm_invocations(output: object) -> int:
    """An `Answer` reports its own count; an `Ask` or a refusal carries it on the lattice.
    Stating `llm_invocations: 0` on a PAUSE is a legitimate claim — H2 makes it — so the
    check cannot live inside the answer branch, which is where it used to sit."""
    lattice = getattr(output, "lattice", None)
    return int(lattice.llm_invocations) if lattice is not None else 0


def _asks(output: object) -> int:
    """HITL rounds spent this turn, off the lattice — see the `llm_invocations` note."""
    lattice = getattr(output, "lattice", None)
    return int(lattice.hitl_rounds) if lattice is not None else 0


def _assert_turn(name: str, index: int, expect: dict, output: object, run: object) -> None:  # type: ignore[type-arg]
    where = f"{name} turn {index + 1}"
    outcome = expect.get("outcome")

    # ---- outcome-independent, because the claims are (formerly unread, all six) ----
    if "llm_invocations" in expect:
        assert _llm_invocations(output) == expect["llm_invocations"], where

    if "asks" in expect:
        # H4 states `asks: 0` on a REFUSAL and h2 states `asks: 1` on an answer reached
        # through a pause; the count is the turn's, not the outcome's, so it is read off
        # the lattice rather than off `Answer.asks` (which agrees with it).
        assert _asks(output) == expect["asks"], where

    if expect.get("no_binding_below_threshold"):
        # The refusal-over-guess invariant, in this corpus's terms: WEAK never binds
        # (RV-14), and UNSPECIFIED is weaker than WEAK — `rank()` says so rather than
        # leaving it to a comparison. Nothing below the floor may sit in the lattice a
        # turn hands back, whatever that turn's outcome was.
        for binding in _bindings(output):
            evidence = binding.evidence_class  # type: ignore[attr-defined]
            assert evidence.rank() < EvidenceClass.WEAK.rank(), (
                f"{where}: {binding.ref!r} bound at {evidence.value}"  # type: ignore[attr-defined]
            )

    if "byte_identical_to_turn" in expect:
        # At-least-once delivery is the norm, so a redelivery must produce the same bytes
        # — the property P4.2·T1(c) exists for, stated in the fixture and, until now,
        # asserted nowhere. The index is 0-based over the NON-GATE turns (== `outputs`).
        other = run.outputs[expect["byte_identical_to_turn"]]  # type: ignore[attr-defined]
        assert output.model_dump_json() == other.model_dump_json(), where  # type: ignore[attr-defined]

    if "proposing_rung" in expect and outcome != "gate":
        # RV-7 about the USER's pin: what matters is what we PROPOSED, not what the
        # recorded gate echoed. A pin's rung is `user`, deliberately outside the four-rung
        # vocabulary, so the ladder's health numbers cannot be made to lie by it.
        sent = run.turn_hypotheses[index]  # type: ignore[attr-defined]
        assert sent, f"{where}: expected a hypothesis carrying a proposing rung"
        assert all(h.proposing_rung == expect["proposing_rung"] for h in sent), where

    if outcome == "ask":
        assert isinstance(output, Ask), where
        # An ask is not an answer: it must carry everything a resume needs.
        assert output.snapshot_id and output.escape, where
        if "min_options" in expect:
            assert len(output.options) >= expect["min_options"], where
        if "gap_kind" in expect:
            assert output.gap_kind.value == expect["gap_kind"].removeprefix("GAP_KIND_"), where
        if "asked_span" in expect:
            assert expect["asked_span"] in output.question, where
        if "escape_offered" in expect:
            assert bool(output.escape) == expect["escape_offered"], where
        if "snapshot_stored" in expect:
            assert bool(output.snapshot_id) == expect["snapshot_stored"], where
        return

    if outcome == "refusal":
        assert isinstance(output, RefusalWithGaps), where
        assert output.reason == expect["refusal_reason"], where
        if "min_bindings" in expect:
            bound = [b for m in output.lattice.mentions for b in m.bindings]
            assert len(bound) >= expect["min_bindings"], where
        if "gap_kinds" in expect:
            assert [g.kind.value for g in output.lattice.gaps] == [
                k.removeprefix("GAP_KIND_") for k in expect["gap_kinds"]
            ], where
        if "composable_residue" in expect:
            assert output.composable_residue == expect["composable_residue"], where
        return

    assert outcome == "answer", where
    assert isinstance(output, Answer), where
    if "core_calls_total" in expect:
        assert run.core.calls == expect["core_calls_total"], where  # type: ignore[attr-defined]
    envelope = output.envelope
    question = envelope.question if envelope else None
    for key in ("measures", "subjects", "operators", "inapplicable_operators"):
        if key in expect:
            assert question is not None and getattr(question, key) == expect[key], where
    if "member_filters" in expect:
        assert question is not None
        assert [f.member_ref for f in question.filters if f.member_ref] == expect[
            "member_filters"
        ], where
    if "gaps_carried" in expect:
        assert [g.kind.value for g in output.gaps_carried] == [
            k.removeprefix("GAP_KIND_") for k in expect["gaps_carried"]
        ], where
    if "gaps_carried_spans" in expect:
        assert [g.span.text for g in output.gaps_carried] == expect["gaps_carried_spans"], where
    if "provenance_lexicon_artifact_hash" in expect:
        assert envelope is not None
        assert (
            envelope.provenance["lexicon_artifact_hash"]
            == expect["provenance_lexicon_artifact_hash"]
        ), where
    if "gated_refs" in expect:
        bound_refs = [b.ref for m in output.lattice.mentions for b in m.bindings]
        for ref in expect["gated_refs"]:
            assert ref in bound_refs, where


def _assert_lane(name: str, index: int, expect: dict, output: object, run: object) -> None:  # type: ignore[type-arg]
    """The lane's half of a turn (OL W1c·S3): its legs, and an answer's lane provenance."""
    where = f"{name} turn {index + 1}"
    lane = run.lanes[index]  # type: ignore[attr-defined]
    stated = {"door_calls", "door_subject", "prompt_states"} & set(expect)
    if lane is None:
        assert not stated, f"{where}: states lane legs {sorted(stated)} but records no lane"
    else:
        # Every recording is consumed: a fixture recording a call the lane never makes
        # would pass while saying something untrue about the lane.
        assert lane.leftovers() == [], f"{where}: unconsumed {lane.leftovers()}"
        if "door_calls" in expect:
            assert len(lane.door.calls) == expect["door_calls"], where
        if "door_subject" in expect:
            assert lane.door.calls, f"{where}: no door call to carry a subject"
            for call in lane.door.calls:
                assert call.caller_subject == expect["door_subject"], where
                assert call.world == lane.world, where
                assert call.allowed_entities == lane.slice.model_slice.allowed_entities, where
        if "prompt_states" in expect:
            prompts = lane.gateway.prompts
            assert len(prompts) == len(expect["prompt_states"]), where
            for prompt, needles in zip(prompts, expect["prompt_states"], strict=True):
                for needle in needles:
                    assert needle in prompt, f"{where}: the prompt does not state {needle!r}"

    if isinstance(output, RefusalWithGaps) and "explanation_contains" in expect:
        for needle in expect["explanation_contains"]:
            assert needle in output.explanation, f"{where}: {output.explanation!r}"
    if not isinstance(output, Answer):
        return
    envelope = output.envelope
    if "lane" in expect:
        assert envelope is not None
        assert envelope.provenance.get("lane") == expect["lane"], where
    if "llm_model" in expect:
        assert envelope is not None
        assert envelope.provenance.get("llm_model") == expect["llm_model"], where
    if "sql" in expect:
        assert envelope is not None and envelope.sql == expect["sql"], where
        assert lane is not None and lane.door.calls[-1].sql == expect["sql"], where
        assert envelope.provenance.get("sql_sha256") == sql_sha256(expect["sql"]), where
    if "rows" in expect:
        assert envelope is not None and envelope.result is not None
        assert len(envelope.result.rows) == expect["rows"], where
    if "llm_handled_spans" in expect:
        assert envelope is not None
        assert [s.text for s in envelope.llm_handled] == expect["llm_handled_spans"], where


def _assert_gate(expect: dict, run: object, position: int) -> None:  # type: ignore[type-arg]
    result = run.gate_results[position]  # type: ignore[attr-defined]
    accepted = [o for o in result.outcomes if o.accepted]

    if "gated_refs" in expect:
        assert [o.binding.ref for o in accepted if o.binding] == expect["gated_refs"]
    if "evidence_classes" in expect:
        assert [o.binding.evidence_class.value for o in accepted if o.binding] == [
            c.removeprefix("EVIDENCE_CLASS_") for c in expect["evidence_classes"]
        ]
    if "proposing_rung" in expect:
        # A hypothesis is not evidence — but a gated binding must carry WHO proposed it.
        assert all(o.hypothesis.proposing_rung == expect["proposing_rung"] for o in accepted)
    if "gap_kinds" in expect:
        assert [g.kind.value for g in result.updated_gaps] == [
            k.removeprefix("GAP_KIND_") for k in expect["gap_kinds"]
        ]


@pytest.mark.asyncio
async def test_the_zero_rung_default_file_is_what_the_suite_runs() -> None:
    """The suite proving the SHIPPED default is the point (T3): no fixture may quietly
    enable a rung. If this ever needs relaxing, the relaxation is the finding."""
    from golem_py.ladder import DEFAULT_CONFIG_PATH, load_default

    run = await drive("h1-answer")

    assert run.deps is not None
    assert run.deps.ladder == load_default()
    assert all(p.rungs == [] for p in load_default().policy.values())
    assert DEFAULT_CONFIG_PATH.exists()
