# SPDX-License-Identifier: Apache-2.0
"""P4.1·T3 — `assess_gaps` as a pure function, over synthetic lattices.

Testing the verdict directly (rather than only through the graph) is deliberate: the
verdict is the ONLY place the Golem decides anything, and a decision that can only be
observed by running a graph is a decision nobody will change with confidence.
"""

from __future__ import annotations

import yaml

from golem_py.budgets import Budgets
from golem_py.ladder import DEFAULT_CONFIG_PATH, LadderConfig, load_default
from golem_py.state import (
    Disposition,
    FrameRole,
    GapKind,
    GapRecord,
    Mention,
    ResolutionState,
    Span,
)
from golem_py.verdicts import Verdict, askable_gaps, assess, carryable_gaps


def _state(*gaps: GapRecord, **kwargs: object) -> ResolutionState:
    return ResolutionState(
        mentions=[Mention(id="m1", span=Span(start=0, end=3, text="abc"))],
        gaps=list(gaps),
        **kwargs,  # type: ignore[arg-type]
    )


def _gap(kind: GapKind, *roles: FrameRole, text: str = "abc") -> GapRecord:
    return GapRecord(
        span=Span(start=0, end=len(text), text=text), kind=kind, frame_roles=list(roles)
    )


def _budgets(state: ResolutionState, ladder: LadderConfig) -> Budgets:
    return Budgets.for_state(state, ladder)


def test_no_open_gaps_emits() -> None:
    ladder = load_default()
    state = _state()
    assert assess(state, ladder, _budgets(state, ladder)) == Verdict.EMIT


def test_a_load_bearing_gap_asks_when_the_hitl_budget_allows() -> None:
    ladder = load_default()
    state = _state(_gap(GapKind.G1_UNBOUND, FrameRole.SUBJECT))
    assert assess(state, ladder, _budgets(state, ladder)) == Verdict.ASK


def test_the_same_gap_refuses_once_the_ask_budget_is_spent() -> None:
    """CHAT_QUICK gets ONE ask (⚑RV-3). The second time round the honest answer is the
    refusal with the lattice attached, not a second question."""
    ladder = load_default()
    state = _state(_gap(GapKind.G1_UNBOUND, FrameRole.SUBJECT), hitl_rounds=1)
    assert assess(state, ladder, _budgets(state, ladder)) == Verdict.REFUSE


def test_a_g5_gap_never_blocks_and_never_asks() -> None:
    """G5_NLP_DARK is the capability matrix speaking, not the user: no question the
    user can answer would fix it. The honest banner IS the answer (RV-19)."""
    ladder = load_default()
    state = _state(_gap(GapKind.G5_NLP_DARK, FrameRole.SUBJECT))

    assert assess(state, ladder, _budgets(state, ladder)) == Verdict.EMIT
    assert askable_gaps(state, ladder) == []
    assert len(carryable_gaps(state, ladder)) == 1


def test_an_unbindable_filter_is_carried_rather_than_sinking_the_answer() -> None:
    """H5's shape: *"porovnej s plánem"* leaves an honest G1 in FILTER position. The
    answer carries it as a note — refusing the whole question over one unbindable
    filter would throw away four correct bindings to protect one."""
    ladder = load_default()
    state = _state(_gap(GapKind.G1_UNBOUND, FrameRole.FILTER))

    assert assess(state, ladder, _budgets(state, ladder)) == Verdict.EMIT
    assert len(carryable_gaps(state, ladder)) == 1


def test_a_non_load_bearing_g3_is_carried_rather_than_asked_about() -> None:
    ladder = load_default()
    state = _state(_gap(GapKind.G3_UNATTRIBUTED, FrameRole.FILTER))

    assert assess(state, ladder, _budgets(state, ladder)) == Verdict.EMIT
    assert len(carryable_gaps(state, ladder)) == 1


def test_a_load_bearing_g3_is_asked_about() -> None:
    ladder = load_default()
    state = _state(_gap(GapKind.G3_UNATTRIBUTED, FrameRole.SUBJECT))
    assert assess(state, ladder, _budgets(state, ladder)) == Verdict.ASK


def test_an_ambiguous_mention_asks_whatever_its_role() -> None:
    """ttr-server#236: a bare store word in FILTER position ("Revenue of stores in 2025") is a
    G2 between two model objects — the store dimension and the Stores channel. The role says
    where the word sits, not whether it is decided: carried, compose reads every candidate into
    that role (one restriction per reading with nothing to restrict by, a breakdown by each, a
    sum of each). The Kotlin Golem asks; so does this one."""
    ladder = load_default()
    for role in (FrameRole.FILTER, FrameRole.GROUPING, FrameRole.MEASURE):
        gap = GapRecord(
            span=Span(start=11, end=17, text="stores"),
            kind=GapKind.G2_AMBIGUOUS,
            frame_roles=[role],
            mention_id="m1",
        )
        state = _state(gap)

        assert gap.is_load_bearing(), role
        assert assess(state, ladder, _budgets(state, ladder)) == Verdict.ASK, role
        assert carryable_gaps(state, ladder) == [], role


def test_an_ambiguous_value_asks_whatever_its_role() -> None:
    """The same question on the value layer: several candidate members on one value, none
    dominant. Carried, each member would become an equality filter, AND-ed — empty on one
    attribute, silently narrower on two. Asked, as the Kotlin Golem asks it."""
    ladder = load_default()
    for roles in ([], [FrameRole.FILTER]):
        gap = GapRecord(
            span=Span(start=31, end=33, text="DC"),
            kind=GapKind.G2_AMBIGUOUS,
            frame_roles=list(roles),
            value_id="v2",
        )
        state = _state(gap)

        assert gap.is_load_bearing(), roles
        assert assess(state, ladder, _budgets(state, ladder)) == Verdict.ASK, roles


def test_a_closed_gap_stops_driving_the_loop() -> None:
    ladder = load_default()
    gap = _gap(GapKind.G1_UNBOUND, FrameRole.SUBJECT)
    gap.disposition = Disposition.USER_CONFIRMED_UNKNOWN
    state = _state(gap)
    assert assess(state, ladder, _budgets(state, ladder)) == Verdict.EMIT


def test_ask_order_spends_the_single_question_on_a_load_bearing_gap() -> None:
    """One ask per round, so the ORDER decides what the question is spent on. A
    load-bearing gap outranks a merely askable one wherever it sits in the sentence."""
    ladder = load_default()
    late_subject = GapRecord(
        span=Span(start=40, end=50, text="čerpacích"),
        kind=GapKind.G1_UNBOUND,
        frame_roles=[FrameRole.SUBJECT],
    )
    early_other = GapRecord(
        span=Span(start=0, end=5, text="Praze"),
        kind=GapKind.G4_METHOD_MISS,
        frame_roles=[FrameRole.FILTER],
    )
    state = _state(early_other, late_subject)

    assert askable_gaps(state, ladder)[0].span.text == "čerpacích"


def test_an_enabled_rung_climbs_and_a_spent_llm_budget_stops_it(tmp_path) -> None:  # type: ignore[no-untyped-def]
    raw = yaml.safe_load(DEFAULT_CONFIG_PATH.read_text(encoding="utf-8"))
    raw["policy"]["G1_UNBOUND"]["rungs"] = ["local"]
    path = tmp_path / "l.yaml"
    path.write_text(yaml.safe_dump(raw), encoding="utf-8")
    ladder = LadderConfig.load(path)

    state = _state(_gap(GapKind.G1_UNBOUND, FrameRole.SUBJECT))
    assert assess(state, ladder, _budgets(state, ladder)) == Verdict.CLIMB

    state.llm_invocations = 2  # CHAT_QUICK's ceiling
    assert assess(state, ladder, _budgets(state, ladder)) == Verdict.ASK


def test_a_deterministic_rung_is_bounded_by_the_wall_clock_not_the_llm_budget(tmp_path) -> None:  # type: ignore[no-untyped-def]
    """RV-33: `lookup` is not an LLM rung. Spending the invocation budget must not
    disable it — and the wall clock must."""
    raw = yaml.safe_load(DEFAULT_CONFIG_PATH.read_text(encoding="utf-8"))
    raw["policy"]["G1_UNBOUND"]["rungs"] = ["lookup"]
    path = tmp_path / "l.yaml"
    path.write_text(yaml.safe_dump(raw), encoding="utf-8")
    ladder = LadderConfig.load(path)

    state = _state(_gap(GapKind.G1_UNBOUND, FrameRole.SUBJECT), llm_invocations=99)
    assert assess(state, ladder, _budgets(state, ladder)) == Verdict.CLIMB

    now = [0.0]
    budgets = Budgets(profile=ladder.profile("CHAT_QUICK"), clock=lambda: now[0])
    now[0] = 6.0  # past CHAT_QUICK's 5 000 ms ladder budget
    assert assess(state, ladder, budgets) == Verdict.ASK


def test_a_rung_already_run_this_turn_is_not_offered_again(tmp_path) -> None:  # type: ignore[no-untyped-def]
    """The loop's termination guarantee. `lookup` spends no LLM budget, so without
    "at most once per turn" a deterministic rung that proposes nothing would stay
    eligible forever and the loop edge would spin."""
    raw = yaml.safe_load(DEFAULT_CONFIG_PATH.read_text(encoding="utf-8"))
    raw["policy"]["G1_UNBOUND"]["rungs"] = ["lookup"]
    path = tmp_path / "l.yaml"
    path.write_text(yaml.safe_dump(raw), encoding="utf-8")
    ladder = LadderConfig.load(path)

    state = _state(_gap(GapKind.G1_UNBOUND, FrameRole.SUBJECT), rungs_run=["lookup"])
    assert assess(state, ladder, _budgets(state, ladder)) == Verdict.ASK


def test_the_best_effort_posture_answers_where_strict_refuses(tmp_path) -> None:  # type: ignore[no-untyped-def]
    raw = yaml.safe_load(DEFAULT_CONFIG_PATH.read_text(encoding="utf-8"))
    raw["profiles"]["CHAT_QUICK"]["terminal"] = "human_profiles"
    path = tmp_path / "l.yaml"
    path.write_text(yaml.safe_dump(raw), encoding="utf-8")
    ladder = LadderConfig.load(path)

    # A LOAD-BEARING gap with the ask budget spent: strict refuses, best-effort answers
    # over the gap note. A non-load-bearing gap is carried under either posture.
    state = _state(_gap(GapKind.G1_UNBOUND, FrameRole.SUBJECT), hitl_rounds=1)
    assert assess(state, ladder, _budgets(state, ladder)) == Verdict.EMIT
    assert assess(state, load_default(), _budgets(state, load_default())) == Verdict.REFUSE
