# SPDX-License-Identifier: Apache-2.0
"""review-103 L9 — a QUOTED literal (LP contracts §2) through the reference Golem.

golem-py did not know `VALUE_KIND_VERBATIM`: it mapped it to UNSPECIFIED, and compose then
built a filter whose literal was the SPAN — the user's text with its quotes (`'"Pelex"'`).
Now a VERBATIM value filters on `verbatim_text` (never the span), carries its `pred:`
kind, and a value kind this Golem cannot read is skipped (contracts §8) instead of being
turned into a filter.

The lattice is the resolver's own h1 golden with one VERBATIM value added in exactly the
shape `LatticeAssembler` emits it: the span keeps the delimiters, `verbatim_text` is the
§1.4 text, the attribution carries `attribute_ref` and NO binding, `predicate_ref` is set
only when the question named an operator.
"""

from __future__ import annotations

from typing import Any

from golem_py.compose import StructuredQuestion, compose_structured_question
from golem_py.core_client import lattice_from_proto
from golem_py.query_client import render_content
from golem_py.state import ResolutionState, ValueKind
from tests.helpers import fixture_library
from tests.lattice_fixtures import lattice_proto

ATTRIBUTE = "md.dimension.Account.name"
QUOTED = '"Pelex"'


def _h1_with_value(
    *,
    kind: int | None = None,
    predicate_ref: str | None = "pred:starts_with",
    attributed: bool = True,
    verbatim_text: str | None = "Pelex",
) -> ResolutionState:
    msg: Any = lattice_proto("h1-cs")
    anchor = next(m.id for m in msg.mentions if any("Account" in b.ref for b in m.bindings))
    value = msg.values.add()
    value.id = f"v{len(msg.values)}"
    value.span.start = 200
    value.span.end = 200 + len(QUOTED)
    value.span.text = QUOTED  # the span is what the user TYPED, delimiters included
    kinds = value.DESCRIPTOR.fields_by_name["kind"].enum_type.values_by_name
    value.kind = kind if kind is not None else kinds["VALUE_KIND_VERBATIM"].number
    if verbatim_text is not None:
        value.verbatim_text = verbatim_text
    if predicate_ref is not None:
        value.predicate_ref = predicate_ref
    if attributed:
        value.attributions.add().attribute_ref = ATTRIBUTE
        value.anchor_mention_id = anchor
    return lattice_from_proto(msg)


def _compose(state: ResolutionState) -> StructuredQuestion:
    return compose_structured_question(state, fixture_library())


def test_the_mapper_reads_a_verbatim_value_field_for_field() -> None:
    value = _h1_with_value().values[-1]

    assert value.kind == ValueKind.VERBATIM
    assert value.span.text == QUOTED
    assert value.verbatim_text == "Pelex"
    assert value.predicate_ref == "pred:starts_with"
    assert [a.attribute_ref for a in value.attributions] == [ATTRIBUTE]
    assert value.attributions[0].binding is None


def test_compose_filters_on_the_verbatim_text_never_on_the_quoted_span() -> None:
    question = _compose(_h1_with_value())

    verbatim = [f for f in question.filters if f.verbatim]
    assert len(verbatim) == 1
    assert verbatim[0].ref == ATTRIBUTE
    assert verbatim[0].literal == "Pelex"
    assert verbatim[0].predicate == "starts_with"
    assert verbatim[0].member_ref == ""
    # No filter anywhere carries the user's delimiters.
    assert not any('"' in f.literal for f in question.filters)
    # …and h1's own member filter is untouched.
    assert any(f.member_ref == "md.dimension.Account.code#501001" for f in question.filters)


def test_an_absent_predicate_stays_blank_not_equals() -> None:
    """Absent `predicate_ref` means §2.2's default applies — a consumer that read a blank
    as an operator would silently narrow every unqualified literal to an exact match."""
    filters = [f for f in _compose(_h1_with_value(predicate_ref=None)).filters if f.verbatim]

    assert [(f.literal, f.predicate) for f in filters] == [("Pelex", "")]


def test_an_unattributed_verbatim_value_adds_no_filter() -> None:
    """G3 — nothing says which column; the gap is the resolver's to report, and compose
    must not invent a restriction for it."""
    assert not [f for f in _compose(_h1_with_value(attributed=False)).filters if f.verbatim]


def test_a_verbatim_value_without_its_text_is_skipped_not_filtered_on_the_span() -> None:
    question = _compose(_h1_with_value(verbatim_text=None))

    assert not [f for f in question.filters if f.verbatim]
    assert not any('"' in f.literal for f in question.filters)


def test_an_unknown_value_kind_is_skipped_and_compose_continues() -> None:
    """Contracts §8: unknown kind ⇒ value skipped, DATA_QUERY continues. A newer kind
    maps to UNSPECIFIED here; its span is raw user text and must not become a filter."""
    state = _h1_with_value(kind=99)
    assert state.values[-1].kind == ValueKind.UNSPECIFIED

    question = _compose(state)

    assert not any(f.ref == ATTRIBUTE for f in question.filters)
    assert question.measures == ["md.measure.cost"]  # the rest of the question still composes


def test_the_envelope_shows_the_text_as_typed_not_a_bare_attribute() -> None:
    content = render_content(_compose(_h1_with_value()), None)

    assert f'{ATTRIBUTE} starts_with "Pelex"' in content
    assert "md.dimension.Account.code#501001" in content
