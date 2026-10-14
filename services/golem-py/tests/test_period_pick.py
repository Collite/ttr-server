# SPDX-License-Identifier: Apache-2.0
"""ttr-server#231 — a `period` option, from the core's awaiting to a folded date.

A grounding kernel's question ("which November?") reaches the caller as the core's own
`awaiting`: `P:` options with `object_kind: "period"` and NO ref. A pick on one is
redeemed by the core's resume (`Resolve(resume{token, selected_option_id})`), which answers
with one grounded date value; the caller folds it into its own value by span. The gate has
nothing to gate: putting the pick to it would propose a blank ref.
"""

from __future__ import annotations

import pytest
from org.tatrman.resolver.v1 import resolver_pb2

from golem_py.core_client import options_from_response, response_to_state
from golem_py.errors import CoreResumeUnavailable, PickRefused
from golem_py.graph import run_turn
from golem_py.state import (
    Attribution,
    Binding,
    EvidenceClass,
    FrameRole,
    GapKind,
    GapRecord,
    Grounding,
    Mention,
    Pin,
    ResolutionState,
    SignedOption,
    SourceTag,
    Span,
    TargetClass,
    ValueFinding,
    ValueKind,
)
from tests.helpers import RecordedCore, RecordedGate

QUESTION = "Tržby z tržiště v listopadu"
MONTH = Span(start=18, end=27, text="listopadu")
NOV_2025 = "2025-11-01T00:00:00Z/2025-12-01T00:00:00Z"


def _period_options() -> list[SignedOption]:
    return [
        SignedOption(id="P:202611", label="listopad 2026", span=MONTH, object_kind="period"),
        SignedOption(id="P:202511", label="listopad 2025", span=MONTH, object_kind="period"),
    ]


def _asked_state(pin: str) -> ResolutionState:
    """The paused turn as the snapshot holds it: the month asked about, the user's pin."""
    return ResolutionState(
        question=QUESTION,
        conversation_id="c-period",
        turn_id="t-1",
        caller_subject="user-a",
        mentions=[
            Mention(
                id="m0",
                span=Span(start=0, end=15, text="Tržby z tržiště"),
                frame_roles=[FrameRole.SUBJECT],
                bindings=[
                    Binding(
                        ref="er.entity.web_sales.ext_sales_price",
                        target_class=TargetClass.MODEL_OBJECT,
                        evidence_class=EvidenceClass.EXACT,
                        source=SourceTag.DECLARED,
                        in_class_score=1.0,
                    )
                ],
            )
        ],
        values=[
            ValueFinding(
                id="v1",
                span=MONTH,
                kind=ValueKind.GROUNDED,
                grounding=Grounding(kernel="nametag3", kind="DATE", ref="ground:chrono"),
            )
        ],
        gaps=[GapRecord(span=MONTH, kind=GapKind.G2_AMBIGUOUS, value_id="v1")],
        resume_token="signed-by-the-core",
        signed_options=_period_options(),
        asked_gap_span=MONTH,
        hitl_rounds=1,
        pin=Pin(option_id=pin),
    )


def _picked(normalized: str = NOV_2025, span: Span = MONTH) -> ResolutionState:
    """The core's answer to a period pick: one value, the date, grounded and anchored."""
    return ResolutionState(
        values=[
            ValueFinding(
                id="v1",
                span=span,
                kind=ValueKind.GROUNDED,
                grounding=Grounding(kernel="chrono", kind="DATE", normalized_value=normalized),
                attributions=[Attribution(attribute_ref="er.entity.date_dim.cal_date")],
            )
        ]
    )


class ResumingCore(RecordedCore):
    """A core that also redeems its own signed options, recording what it was handed."""

    def __init__(self, answer: ResolutionState | None, *, refuse: bool = False):
        super().__init__(ResolutionState())
        self.answer = answer
        self.refuse = refuse
        self.resumes: list[dict[str, str]] = []

    async def resume(
        self,
        *,
        token: str,
        selected_option_id: str = "",
        free_text_answer: str = "",
        conversation_id: str = "",
        caller_subject: str = "",
    ) -> ResolutionState:
        self.resumes.append(
            {
                "token": token,
                "selected_option_id": selected_option_id,
                "caller_subject": caller_subject,
            }
        )
        if self.refuse:
            raise PickRefused(selected_option_id, "chrono answered UNGROUNDABLE")
        assert self.answer is not None
        return self.answer.model_copy(deep=True)


def _lattice(out: object) -> ResolutionState:
    lattice = getattr(out, "lattice", None)
    assert isinstance(lattice, ResolutionState)
    return lattice


def _deps(core: object, gate: object | None = None) -> object:
    from golem_py.deps import Deps
    from golem_py.ladder import load_default
    from tests.helpers import fixture_library

    return Deps(
        core=core,  # type: ignore[arg-type]
        gate=gate,  # type: ignore[arg-type]
        ladder=load_default(),
        skills=fixture_library(),
    )


# ------------------------------------------------------------------ the mapper


def _awaiting() -> resolver_pb2.ResolveResponse:
    span = resolver_pb2.Span(start=MONTH.start, end=MONTH.end, text=MONTH.text)
    return resolver_pb2.ResolveResponse(
        awaiting=resolver_pb2.AwaitingClarification(
            resume_token="signed-by-the-core",
            options=[
                resolver_pb2.Option(
                    id="P:202611", label="listopad 2026", span=span, object_kind="period"
                ),
                resolver_pb2.Option(
                    id="P:202511", label="listopad 2025", span=span, object_kind="period"
                ),
            ],
        )
    )


def test_a_period_option_keeps_its_kind_and_invents_no_ref() -> None:
    state = response_to_state(_awaiting(), question=QUESTION)

    assert [(o.id, o.object_kind, o.ref) for o in state.signed_options] == [
        ("P:202611", "period", ""),
        ("P:202511", "period", ""),
    ]
    assert all(o.is_period() for o in state.signed_options)
    assert [o["object_kind"] for o in options_from_response(_awaiting())] == ["period", "period"]


# ------------------------------------------------------------------ the pick


@pytest.mark.asyncio
async def test_a_period_pick_is_redeemed_by_the_core_and_folded_by_span() -> None:
    core = ResumingCore(_picked())
    gate = RecordedGate()

    out = await run_turn(_asked_state("P:202511"), _deps(core, gate))  # type: ignore[arg-type]

    assert gate.calls == 0, "a period has no ref: nothing for the gate to gate"
    assert core.resumes == [
        {
            "token": "signed-by-the-core",
            "selected_option_id": "P:202511",
            "caller_subject": "user-a",
        }
    ]
    assert core.calls == 0, "the resume rejoins the loop; the question is not resolved again"
    lattice = _lattice(out)
    value = next(v for v in lattice.values if v.span.text == "listopadu")
    assert value.id == "v1"  # OUR id: gaps and the rung log name it
    assert value.grounding is not None
    assert value.grounding.normalized_value == NOV_2025
    assert value.grounding.kernel == "chrono"
    assert value.grounding.ref == "ground:chrono"  # the trigger ref survives the fold
    assert [a.attribute_ref for a in value.attributions] == ["er.entity.date_dim.cal_date"]
    assert [g for g in lattice.gaps if g.value_id == "v1"] == []


@pytest.mark.asyncio
async def test_a_refused_pick_leaves_the_gap_open_with_the_users_answer() -> None:
    core = ResumingCore(None, refuse=True)
    gate = RecordedGate()

    out = await run_turn(_asked_state("P:202511"), _deps(core, gate))  # type: ignore[arg-type]

    assert gate.calls == 0
    assert len(core.resumes) == 1
    lattice = _lattice(out)
    gap = next(g for g in lattice.gaps if g.value_id == "v1")
    assert gap.user_answer == "listopad 2025"
    value = next(v for v in lattice.values if v.id == "v1")
    assert value.grounding is not None and value.grounding.normalized_value == ""


@pytest.mark.asyncio
async def test_an_answer_that_is_not_one_grounded_date_on_the_asked_span_is_not_folded() -> None:
    elsewhere = Span(start=0, end=5, text="Tržby")
    for answer in (_picked(normalized=""), _picked(span=elsewhere), ResolutionState()):
        out = await run_turn(_asked_state("P:202511"), _deps(ResumingCore(answer)))  # type: ignore[arg-type]
        lattice = _lattice(out)
        value = next(v for v in lattice.values if v.id == "v1")
        assert value.grounding is not None and value.grounding.normalized_value == ""
        assert [g.value_id for g in lattice.gaps] == ["v1"]


@pytest.mark.asyncio
async def test_a_period_pick_with_a_core_that_cannot_resume_raises() -> None:
    with pytest.raises(CoreResumeUnavailable):
        await run_turn(
            _asked_state("P:202511"),
            _deps(RecordedCore(ResolutionState()), RecordedGate()),  # type: ignore[arg-type]
        )
