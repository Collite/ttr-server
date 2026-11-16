# SPDX-License-Identifier: Apache-2.0
"""The LLM lane's prompt renderer (OL-C14, OL-C15; W1c·S2·T4).

The words live in `config/llm-lane-prompt.md`; this module only fills its slots. What the
model is given is OL-C14's list and nothing else: the question as typed, the locale, the
reference date, the lattice (bindings, values, the gaps the answer carries), the composed
structured question, and the model slice. The slice rendered here is the same object whose
entity names are the allow-list the checks and the door enforce, so the prompt cannot name
an entity the query may not read.

Pure: a state, a slice and a date in, two strings out. The template is read once per
process and is injectable, so a spec can render against its own.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date
from functools import cache
from pathlib import Path

from golem_py.compose import StructuredQuestion
from golem_py.model_slice import ModelSlice
from golem_py.state import FrameRole, GapRecord, ResolutionState, Span, ValueFinding, ValueKind

DEFAULT_PROMPT_PATH = Path(__file__).resolve().parents[2] / "config" / "llm-lane-prompt.md"
_MARKERS = ("<!-- instructions -->", "<!-- input -->", "<!-- repair -->")
_SLOT = re.compile(r"\{\{(\w+)\}\}")
NONE = "(none)"


class PromptTemplateError(ValueError):
    """The prompt file is missing a part, or a slot was left unfilled."""


@dataclass(frozen=True)
class PromptTemplate:
    instructions: str
    input: str
    repair: str

    @classmethod
    def parse(cls, text: str) -> PromptTemplate:
        positions = [text.find(m) for m in _MARKERS]
        if any(p < 0 for p in positions) or positions != sorted(positions):
            raise PromptTemplateError(
                f"the lane prompt needs the markers {', '.join(_MARKERS)}, in that order"
            )
        parts = [
            text[p + len(m) : nxt].strip("\n")
            for p, m, nxt in zip(positions, _MARKERS, [*positions[1:], len(text)], strict=True)
        ]
        return cls(instructions=parts[0].strip(), input=parts[1], repair=parts[2])


@cache
def default_template() -> PromptTemplate:
    return PromptTemplate.parse(DEFAULT_PROMPT_PATH.read_text(encoding="utf-8"))


@dataclass(frozen=True)
class LanePrompt:
    """What one call sends: the instructions, and the turn as the input text."""

    instructions: str
    input: str


def _fill(text: str, slots: dict[str, str]) -> str:
    missing = sorted({m for m in _SLOT.findall(text) if m not in slots})
    if missing:
        raise PromptTemplateError(f"the lane prompt has unfilled slots: {', '.join(missing)}")
    return _SLOT.sub(lambda m: slots[m.group(1)], text)


def _span(span: Span) -> str:
    return f"{span.text!r} [{span.start}, {span.end}]"


def _roles(roles: list[FrameRole]) -> str:
    named = [r.value for r in roles if r != FrameRole.UNSPECIFIED]
    return ", ".join(named) if named else "—"


def _lines(lines: list[str]) -> str:
    return "\n".join(lines) if lines else NONE


def _bindings(state: ResolutionState) -> str:
    return _lines(
        [
            f"- {_span(m.span)} → {b.ref} ({b.target_class.value}); role: {_roles(m.frame_roles)}"
            for m in state.mentions
            for b in m.bindings
        ]
    )


def _value(v: ValueFinding) -> str:
    if v.kind == ValueKind.VERBATIM:
        what = f"VERBATIM text {v.verbatim_text or v.span.text!r}"
        if v.predicate_ref:
            what += f", predicate {v.predicate_ref}"
    elif v.grounding is not None and v.grounding.normalized_value:
        g = v.grounding
        what = f"{v.kind.value} {g.kind or 'value'} {g.normalized_value}".strip()
    else:
        what = f"{v.kind.value} (not normalised)"
    on = [a.attribute_ref for a in v.attributions if a.attribute_ref]
    if on:
        what += f"; on {', '.join(on)}"
    return f"- {_span(v.span)}: {what}"


def _gaps(carried: list[GapRecord]) -> str:
    return _lines(
        [f"- {_span(g.span)} {g.kind.value}; role: {_roles(g.frame_roles)}" for g in carried]
    )


def _question(q: StructuredQuestion | None) -> str:
    if q is None:
        return NONE
    lines = []
    if q.subjects:
        lines.append("- subjects: " + ", ".join(q.subjects))
    if q.measures:
        lines.append("- measures: " + ", ".join(q.measures))
    if q.groupings:
        lines.append("- by: " + ", ".join(q.groupings))
    for f in q.filters:
        if f.verbatim:
            lines.append(f'- where: {f.ref} {f.predicate or "="} "{f.literal}"')
        else:
            lines.append(f"- where: {f.ref} = {f.member_ref or f.literal}")
    if q.time_grain is not None:
        lines.append(f"- period: {q.time_grain.normalized_value} ({q.time_grain.ref})")
    if q.operators:
        lines.append("- operators: " + ", ".join(q.operators))
    return _lines(lines)


def _slice(s: ModelSlice) -> str:
    out: list[str] = []
    for e in s.entities:
        head = f"### {e.qname}" + (" (bound by the question)" if e.bound else "")
        out.append(head + (f" — {e.description}" if e.description else ""))
        for a in e.attributes:
            line = f"- {a.name}: {a.type or '?'}"
            if a.is_key:
                line += " [key]"
            if a.description:
                line += f" — {a.description}"
            out.append(line)
    if s.relations:
        out.append("### Relations")
        for r in s.relations:
            pairs = " and ".join(f"{a} = {b}" for a, b in r.join_pairs)
            out.append(
                f"- {r.name}: {r.from_entity} → {r.to_entity}" + (f" on {pairs}" if pairs else "")
            )
    if s.omitted_entities:
        out.append(f"({s.omitted_entities} more entities of the package are not in this slice.)")
    return _lines(out)


def render(
    state: ResolutionState,
    *,
    question: StructuredQuestion | None,
    carried: list[GapRecord],
    model_slice: ModelSlice,
    reference_date: date,
    template: PromptTemplate | None = None,
) -> LanePrompt:
    """The first call's prompt (OL-C14)."""
    t = template or default_template()
    slots = {
        "question": state.question,
        "locale": state.locale,
        "reference_date": reference_date.isoformat(),
        "bindings": _bindings(state),
        "values": _lines([_value(v) for v in state.values]),
        "gaps": _gaps(carried),
        "structured_question": _question(question),
        "packages": ", ".join(model_slice.packages) or NONE,
        "slice": _slice(model_slice),
    }
    return LanePrompt(instructions=_fill(t.instructions, {}), input=_fill(t.input, slots))


def render_repair(
    prompt: LanePrompt,
    *,
    previous_sql: str,
    door_error: str,
    template: PromptTemplate | None = None,
) -> LanePrompt:
    """The one repair call (OL-C16): the same prompt, the refused query and the door's text."""
    t = template or default_template()
    repair = _fill(t.repair, {"previous_sql": previous_sql, "door_error": door_error})
    return LanePrompt(instructions=prompt.instructions, input=f"{prompt.input}\n{repair}")
