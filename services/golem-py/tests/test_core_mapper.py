# SPDX-License-Identifier: Apache-2.0
"""P4.1·T5 — the honest mapper, tested against the SERVER's own lattice goldens.

These fixtures are not copies. `tests/lattice_fixtures.py` reads
`services/resolver/src/test/resources/lattice/*.lattice.json` — the goldens the Kotlin
core is held to (RV-P2.1/P2.5) — and parses them as proto JSON. That makes this a
CROSS-LANGUAGE conformance check on one corpus (RV-28: one corpus, one core, N shells):
if the resolver changes what it emits, this suite fails on the same day the Kotlin one
does, and neither side can drift quietly.

The spike's caveat is what T5 exists to close. Its mapper reconstructed one mention per
BINDING because the door of the day reported only what it had bound — so `G1_UNBOUND`
was not expressible at all. `h2-cs` below has a mention with ZERO bindings and a typed
G1 gap on it; mapping it correctly is the whole point.
"""

from __future__ import annotations

import pytest

from golem_py.state import Disposition, FrameRole, GapKind, TargetClass, ValueKind
from tests.lattice_fixtures import lattice_golden


def test_h1_maps_to_a_gap_free_lattice() -> None:
    state = lattice_golden("h1-cs")

    assert [m.id for m in state.mentions] == ["m1", "m2", "m3", "m4", "m5"]
    assert state.gaps == []
    assert state.lexicon_versions.lexicon_artifact_hash == "sha256:h1-lexicon"
    # RV-39: the member index versions ride the tuple per category.
    assert state.lexicon_versions.member_index_versions["md.dimension.Account.code"] == "v3"
    # The enum prefix is stripped in exactly one place, and this is what it buys.
    show = next(m for m in state.mentions if m.span.text == "Zobraz")
    assert show.bindings[0].ref == "op:show"
    assert show.bindings[0].target_class == TargetClass.OPERATOR


def test_the_measure_as_subject_mention_carries_both_roles() -> None:
    """⚑ The P0-2 finding that forced `repeated`: 32 of 137 corpus mentions carry two
    roles. A singular field would silently keep the first and lose the question's
    aggregation or its subject — and which one it loses depends on emission order."""
    state = lattice_golden("h1-cs")

    naklady = next(m for m in state.mentions if m.span.text == "náklady")
    assert naklady.frame_roles == [FrameRole.SUBJECT, FrameRole.MEASURE]


def test_h2_maps_a_mention_with_zero_bindings_to_a_typed_g1_gap() -> None:
    """THE case the pre-`p2-1` door could not express, made permanent.

    A mention with no bindings is a first-class unknown (P-3); the gap that names it is
    typed, load-bearing, and points back at the mention by id. Everything the loop does
    afterwards keys on those three facts.
    """
    state = lattice_golden("h2-cs")

    unbound = next(m for m in state.mentions if m.span.text == "čerpacích stanic")
    assert unbound.bindings == []

    gap = next(g for g in state.gaps if g.mention_id == unbound.id)
    assert gap.kind == GapKind.G1_UNBOUND
    assert gap.frame_roles == [FrameRole.SUBJECT]
    assert gap.disposition == Disposition.UNRESOLVED
    assert gap.is_load_bearing()


def test_h2_carries_a_value_gap_that_names_the_value_not_a_mention() -> None:
    """G3 sits on a VALUE (`Praze`, a LOCATION hint nothing attributed). Exactly one of
    `mention_id` / `value_id` is set, and the loop reads that to know what it is
    looking at."""
    state = lattice_golden("h2-cs")

    gap = next(g for g in state.gaps if g.kind == GapKind.G3_UNATTRIBUTED)
    assert gap.value_id == "v2" and gap.mention_id == ""

    value = next(v for v in state.values if v.id == "v2")
    assert value.kind == ValueKind.GROUNDED
    assert value.grounding is not None and value.grounding.kind == "LOCATION"
    # No anchor: nothing scoped it. That emptiness is what separates G3 from G4.
    assert value.anchor_mention_id == ""


def test_the_rung_log_arrives_with_the_cores_own_round_zero() -> None:
    state = lattice_golden("h2-cs")

    assert state.rung_log[0].rung == "core" and state.rung_log[0].round == 0
    assert [e.rung for e in state.rung_log] == ["core", "lookup", "lookup", "lookup"]
    # `čerpacích stanic` is asked as written, and only in the next round as its head (#204).
    assert state.rung_log[1].mention_ids == ["m3"]
    assert state.rung_log[2].mention_ids == ["m3"]


def test_h1prime_carries_the_method_miss_that_the_regate_pair_answers() -> None:
    state = lattice_golden("h1prime-cs")

    assert [g.kind for g in state.gaps] == [GapKind.G4_METHOD_MISS]


def test_h5_carries_three_operator_bindings_and_one_honest_unbound() -> None:
    """The operator layer through the ORDINARY path — `op:` refs are vocabulary, not a
    parallel mechanism. And `plánem` stays an honest G1 rather than being bent onto a
    plausible measure."""
    state = lattice_golden("h5-cs")

    ops = [
        b.ref for m in state.mentions for b in m.bindings if b.target_class == TargetClass.OPERATOR
    ]
    assert len(ops) == 3
    assert any(g.kind == GapKind.G1_UNBOUND and "plán" in g.span.text for g in state.gaps)


def test_an_unknown_enum_value_degrades_instead_of_raising() -> None:
    """An older client reading a newer lattice must degrade, not crash — the same
    posture the archive loader takes. `UNSPECIFIED` is the honest report."""
    from golem_py.core_client import _enum

    assert _enum(GapKind, "GapKind", 999) == GapKind.UNSPECIFIED


@pytest.mark.parametrize("case", ["h1-cs", "h1prime-cs", "h2-cs", "h5-cs"])
def test_every_golden_round_trips_through_our_json(case: str) -> None:
    """The snapshot story in one assertion: our state is one Pydantic document, so
    `model_dump_json()` → `model_validate_json()` is lossless. P4.2's resume rests on
    this."""
    from golem_py.state import ResolutionState

    state = lattice_golden(case)
    assert ResolutionState.model_validate_json(state.model_dump_json()) == state
