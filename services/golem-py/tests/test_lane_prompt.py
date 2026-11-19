# SPDX-License-Identifier: Apache-2.0
"""OL W1c·S2·T4 — `config/llm-lane-prompt.md` and its renderer.

The prompt carries OL-C14's inputs and OL-C15's rules and answer shape. The specs read the
rendered text for each input rather than comparing a golden, so the wording can improve
without a spec edit while no input can silently drop out.
"""

from __future__ import annotations

from datetime import date

import pytest

from golem_py.compose import Filter, StructuredQuestion, TimeGrain
from golem_py.lane_prompt import (
    PromptTemplate,
    PromptTemplateError,
    default_template,
    render,
    render_repair,
)
from golem_py.model_slice import ModelSlice, SliceAttribute, SliceEntity, SliceRelation
from golem_py.state import (
    Attribution,
    Binding,
    FrameRole,
    GapKind,
    GapRecord,
    Grounding,
    Mention,
    ResolutionState,
    Span,
    TargetClass,
    ValueFinding,
    ValueKind,
)

PERIOD = Span(start=22, end=34, text="minulý měsíc")


def _state() -> ResolutionState:
    return ResolutionState(
        question="Zobraz tržby v Praze za minulý měsíc",
        locale="cs",
        mentions=[
            Mention(
                id="m1",
                span=Span(start=7, end=12, text="tržby"),
                frame_roles=[FrameRole.SUBJECT, FrameRole.MEASURE],
                bindings=[Binding(ref="md.measure.revenue", target_class=TargetClass.MODEL_OBJECT)],
            )
        ],
        values=[
            ValueFinding(
                id="v1",
                span=Span(start=15, end=20, text="Praze"),
                kind=ValueKind.GROUNDED,
                grounding=Grounding(kind="LOCATION", normalized_value="Praha"),
                attributions=[Attribution(attribute_ref="er.entity.store.city")],
            ),
            ValueFinding(
                id="v2",
                span=Span(start=40, end=50, text='"Prime"'),
                kind=ValueKind.VERBATIM,
                verbatim_text="Prime",
                predicate_ref="pred:starts_with",
            ),
            ValueFinding(id="v3", span=PERIOD, kind=ValueKind.LITERAL),
        ],
        gaps=[
            GapRecord(
                span=PERIOD,
                kind=GapKind.G4_METHOD_MISS,
                frame_roles=[FrameRole.FILTER],
                value_id="v3",
            )
        ],
    )


SLICE = ModelSlice(
    packages=("hartland_cz",),
    entities=(
        SliceEntity(
            qname="er.entity.store_sales",
            description="one row per sale",
            attributes=(
                SliceAttribute(name="id", type="int", is_key=True),
                SliceAttribute(name="sold_on", type="date", description="the sale's day"),
            ),
            bound=True,
        ),
        SliceEntity(qname="er.entity.store"),
    ),
    relations=(
        SliceRelation(
            name="sold_at",
            from_entity="er.entity.store_sales",
            to_entity="er.entity.store",
            join_pairs=(("er.entity.store_sales.store_id", "er.entity.store.id"),),
        ),
    ),
    omitted_entities=7,
)

QUESTION = StructuredQuestion(
    subjects=["md.measure.revenue"],
    measures=["md.measure.revenue"],
    filters=[Filter(ref="er.entity.store.city", member_ref="md.member.city#Praha")],
    time_grain=TimeGrain(ref="ground:date", normalized_value="2025-01-01/2025-12-31"),
    operators=["op:show"],
)


def _render(question: StructuredQuestion | None = QUESTION):  # type: ignore[no-untyped-def]
    state = _state()
    return render(
        state,
        question=question,
        carried=state.gaps,
        model_slice=SLICE,
        reference_date=date(2026, 11, 16),
    )


def test_the_input_carries_every_input_of_OL_C14() -> None:
    text = _render().input
    for expected in (
        "Zobraz tržby v Praze za minulý měsíc",  # the question as typed
        "locale: cs",
        "2026-11-16",  # the reference date
        "'tržby' [7, 12] → md.measure.revenue (MODEL_OBJECT); role: SUBJECT, MEASURE",
        "GROUNDED LOCATION Praha; on er.entity.store.city",  # a normalised value
        "VERBATIM text 'Prime', predicate pred:starts_with",
        "'minulý měsíc' [22, 34] G4_METHOD_MISS; role: FILTER",  # the carried gap
        "- measures: md.measure.revenue",  # the composed question
        "- where: er.entity.store.city = md.member.city#Praha",
        "- period: 2025-01-01/2025-12-31",
        "package hartland_cz",
        "### er.entity.store_sales (bound by the question) — one row per sale",
        "- id: int [key]",
        "- sold_on: date — the sale's day",
        "### er.entity.store",
        "sold_at: er.entity.store_sales → er.entity.store on "
        "er.entity.store_sales.store_id = er.entity.store.id",
        "7 more entities",
    ):
        assert expected in text, expected
    assert "{{" not in text


def test_the_instructions_state_the_rules_and_the_answer_shape_of_OL_C15() -> None:
    text = _render().instructions
    for expected in ("one statement", "`SELECT`", "er.", "No parameters", "handled_spans"):
        assert expected in text, expected
    assert '{"sql": "SELECT …", "handled_spans": [[start, end]], "note":' in text
    assert "<!--" not in text  # the file's own note is never sent


def test_a_question_that_did_not_compose_says_so() -> None:
    assert "## The structured question the resolver composed\n(none)" in _render(None).input


def test_the_repair_keeps_the_prompt_and_adds_the_query_and_the_doors_text() -> None:
    first = _render()
    repaired = render_repair(
        first, previous_sql="SELECT 1 FROM er.entity.x", door_error="translator_rejected: no x"
    )
    assert repaired.instructions == first.instructions
    assert repaired.input.startswith(first.input)
    assert "SELECT 1 FROM er.entity.x" in repaired.input
    assert "translator_rejected: no x" in repaired.input


def test_a_template_missing_a_part_or_a_slot_fails_loudly() -> None:
    with pytest.raises(PromptTemplateError):
        PromptTemplate.parse("<!-- instructions -->\nx\n<!-- repair -->\ny")
    broken = PromptTemplate(instructions="x", input="{{question}} {{nobody_fills_me}}", repair="")
    with pytest.raises(PromptTemplateError, match="nobody_fills_me"):
        render(
            _state(),
            question=None,
            carried=[],
            model_slice=SLICE,
            reference_date=date(2026, 1, 1),
            template=broken,
        )


def test_the_shipped_template_parses() -> None:
    t = default_template()
    assert t.instructions and t.input and t.repair
