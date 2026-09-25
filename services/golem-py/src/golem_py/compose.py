# SPDX-License-Identifier: Apache-2.0
"""`compose_structured_question` — a covered lattice becomes a question (P4.3·T4).

Deterministic, **zero LLM**. Everything it needs was decided below the door line: the
frame roles came from the core's rule pass (Q-15 RULED: rules, no LLM assist), the
bindings survived the evidence-class gate, the members are attributions on values, the
time grain is a grounded value. Compose reads; it does not decide.

Two shapes deserve naming because they are where a naive compose goes wrong:

* **Measure-as-subject.** A mention carries repeated frame roles, and 32 of 137 corpus
  mentions carry two — *"Zobraz tržby podle prodejen"* is ABOUT revenue *and*
  aggregates it. A mention contributes to EVERY role it carries; treating roles as a
  partition would drop either the subject or the measure, and which one it drops would
  depend on emission order.
* **Members belong to filters, not to mentions.** `501001` is an attribution on a
  VALUE (`er.CostCenter.code#220`), anchored to the mention whose categories scoped the
  lookup (RV-33). The filter is therefore `(attribute_ref, member_ref)`, and the
  anchoring is what makes it "the account 501001" rather than "some 501001 somewhere".

Operator composition follows contracts §6's v1 rule for retrieval — **directives MERGE** —
and deviates, deliberately and recorded, on formatting: §6 says *last-op-wins per
directive key* and a prose body has no key to win on, so formatting **accumulates in op
order** instead (see `compose_operators`). Ops are applied in the order they appear in the
question, which is the only order the lattice gives us and the one the user chose.

## ⚑ THE T1 RULING — what "over the paygrade" means without an intent classifier

The plan gives the POSTURE (`RefusalWithGaps(NO_CAPABLE_PLUGIN)`, H4) and not the
predicate. The OS Golem has no `classifyIntentKind` — that is Themis machinery, platform
side — and inventing one here would be the single biggest thing this design says not to
do. So the predicate is made of what the LATTICE can say:

> The fast path fires iff the lattice is **gap-free after the loop** (the verdict's job)
> ∧ it carries **at least one operator annotation** ∧ every such operator has a **body**
> in the loaded library ∧ **at least one of them is applicable** to what resolved.

The load-bearing half is the operator requirement, and it is principled rather than
convenient: skill entries are how an estate declares which ACTIONS its Golem can perform
(RV-35 — operators learn like any vocabulary). H1 says *Zobraz* and H5 says *Ukaž /
vývoj / porovnej*, all declared. H4 says *Proč* — "why" is in no operator library, so
the question names an action this Golem was never taught, and the honest answer is "I
understood you completely; I cannot investigate causes". No intent classification is
required to say that, and none is done.

The cases it decides: **H1 answers · H5 answers · H4 refuses.** ⚑ Its residual risk,
for Bora: an estate that authors an `op:` for an investigation-class action with no
plugin behind it would pass this predicate, and the body would then be asked to do
something no body can. That is a content question (do not declare operators you cannot
perform) rather than a code one — but it is the seam where this predicate is thinnest,
and it is where an intent signal would eventually go if one is ever wanted here.

An operator whose `requires:` is unsatisfied is **inapplicable**, not fatal: it is
dropped from the composition and recorded as a note. That is the difference T2 draws
between (c) an op with no body — we do not know what the word meant, so we refuse — and
(d) an op that cannot apply — we know exactly what it meant and can say why it did not
fire. H5 turns on this: *"porovnej s plánem"* leaves `op:compare` with one series, so
the comparison is dropped with a note while the trend still answers.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field

from golem_py.skills import LayeredSkillLibrary, SkillBody, SkillError
from golem_py.state import (
    FrameRole,
    ResolutionState,
    TargetClass,
    ValueKind,
)


@dataclass
class Filter:
    """A restriction: an attribute, and the member it is restricted to.

    A VERBATIM filter (LP contracts §2/§5) has no member: `literal` is the quoted text as
    typed — `verbatim_text`, never the span with its quotes — and `predicate` the `pred:`
    kind the question named (`starts_with`, …), or "" when it named none (§2.2's default).
    """

    ref: str
    member_ref: str = ""
    literal: str = ""
    anchor_mention_id: str = ""
    predicate: str = ""
    verbatim: bool = False


@dataclass
class TimeGrain:
    """A grounded temporal value — the grain an `op:trend` needs to exist at all."""

    ref: str
    normalized_value: str
    text: str = ""


@dataclass
class StructuredQuestion:
    """What the Golem asks OF THE DATA — over modeled entities, never over tables.

    This is the "entity-level query" the server's thesis names: the agent structures a
    question over entities and the server does the rest deterministically.
    """

    subjects: list[str] = field(default_factory=list)
    measures: list[str] = field(default_factory=list)
    groupings: list[str] = field(default_factory=list)
    filters: list[Filter] = field(default_factory=list)
    time_grain: TimeGrain | None = None
    operators: list[str] = field(default_factory=list)
    # Ops the question named, whose bodies loaded, and which could not APPLY to what
    # resolved. Dropped from the composition, carried as notes — "why the comparison is
    # missing" is part of an honest answer.
    inapplicable_operators: list[str] = field(default_factory=list)
    retrieval_directives: list[str] = field(default_factory=list)
    formatting_directives: dict[str, str] = field(default_factory=dict)

    def is_answerable(self) -> bool:
        """Something to select. A question with no measure and no subject has no shape
        the data can answer, whatever else resolved."""
        return bool(self.measures or self.subjects)


class ComposeRefused(SkillError):
    """Compose could not honour the question. Carries WHY, because the refusal renders
    it: an operator missing from the library and an unsatisfied `requires:` are
    different things to be told."""


def _refs(state: ResolutionState, role: FrameRole) -> list[str]:
    """Refs of mentions carrying `role` — a mention contributes to EVERY role it has."""
    out: list[str] = []
    for mention in state.mentions:
        if role not in mention.frame_roles:
            continue
        for binding in mention.bindings:
            if binding.target_class == TargetClass.MODEL_OBJECT and binding.ref not in out:
                out.append(binding.ref)
    return out


def operator_refs(state: ResolutionState) -> list[str]:
    """`op:` bindings in span order — the order the user said them in."""
    ops: list[str] = []
    for mention in sorted(state.mentions, key=lambda m: m.span.start):
        for binding in mention.bindings:
            if binding.target_class == TargetClass.OPERATOR and binding.ref not in ops:
                ops.append(binding.ref)
    return ops


_PRED_PREFIX = "pred:"


def _filters(state: ResolutionState) -> list[Filter]:
    out: list[Filter] = []
    # Members first: a value attributed to an attribute IS a filter, and it is the most
    # specific thing the question said.
    for value in state.values:
        if value.kind == ValueKind.UNSPECIFIED:
            # Contracts §8 — a value kind this Golem cannot read is SKIPPED, never guessed
            # at: its span is the user's text as typed, and a newer kind may keep characters
            # (quotes, for one) that must not reach a filter.
            continue
        if value.kind == ValueKind.VERBATIM:
            # review-103 L9 — the quoted text is `verbatim_text`. Before VERBATIM was known
            # here it fell to UNSPECIFIED and the SPAN became the literal, quotes included
            # (`'"Pelex"'`). No §1.4 text ⇒ nothing honest to filter on: skip.
            if not value.verbatim_text:
                continue
            predicate = (value.predicate_ref or "").removeprefix(_PRED_PREFIX)
            out.extend(
                Filter(
                    ref=attribution.attribute_ref,
                    literal=value.verbatim_text,
                    anchor_mention_id=value.anchor_mention_id,
                    predicate=predicate,
                    verbatim=True,
                )
                for attribution in value.attributions
            )
            continue
        for attribution in value.attributions:
            member_ref = attribution.binding.ref if attribution.binding else ""
            out.append(
                Filter(
                    ref=attribution.attribute_ref,
                    member_ref=member_ref,
                    literal=value.span.text,
                    anchor_mention_id=value.anchor_mention_id,
                )
            )
    # Then FILTER-role mentions that no value pinned — "costs of the cost centre",
    # with the centre itself unrestricted, is still a restriction on scope.
    anchored = {f.anchor_mention_id for f in out}
    for mention in state.mentions:
        if FrameRole.FILTER not in mention.frame_roles or mention.id in anchored:
            continue
        for binding in mention.bindings:
            if binding.target_class in (TargetClass.MODEL_OBJECT, TargetClass.MEMBER):
                out.append(Filter(ref=binding.ref, anchor_mention_id=mention.id))
    return out


def _time_grain(state: ResolutionState) -> TimeGrain | None:
    for value in state.values:
        grounding = value.grounding
        if value.kind == ValueKind.GROUNDED and grounding and grounding.kind == "DATE":
            return TimeGrain(
                ref=grounding.ref or grounding.kernel,
                normalized_value=grounding.normalized_value,
                text=value.span.text,
            )
    return None


def compose_operators(
    ops: list[str], library: LayeredSkillLibrary
) -> tuple[list[SkillBody], list[str], dict[str, str]]:
    """Resolve every `op:` to a body and compose the directives (contracts §6).

    Retrieval MERGES — two operators both narrowing the retrieval are both honoured.

    ⚠ **Formatting ACCUMULATES in op order — §6's "last-op-wins per directive key" is not
    literally implementable yet, and pretending otherwise was the bug.** A body's
    `Formatting:` section is undifferentiated prose (contracts §2: prose for the Golem),
    so there is no directive KEY for a later op to win on. This returns a dict keyed by op
    id, in question order, which is the honest shape: nothing is dropped, and the LAST
    entry is the one a renderer should prefer when two conflict. H5 is the live case —
    `op:show` says "table by default" and `op:trend` says "line chart by default", and
    both reach the envelope.

    ⚑ Bora: making §6 literal needs a KEYED formatting line in the body grammar (e.g.
    `Formatting: chart=line`), which is a change to the artifact `tatrman`'s compiler
    emits — the same `OperatorEntry` change `requires:` wants. Until then the contract's
    wording is ahead of the artifact, and this docstring is the record of that.
    """
    bodies = [library.get(op) for op in ops]  # raises SkillError on an unknown op
    retrieval = [b.retrieval for b in bodies if b.retrieval]
    formatting: dict[str, str] = {}
    for body in bodies:
        if body.formatting:
            formatting[body.op] = body.formatting
    return bodies, retrieval, formatting


# The requirement vocabulary the stdlib actually declares, each a predicate over what
# RESOLVED. ⛑ Two of these were missing and the miss was silent: an unrecognised name fell
# through the old if/elif chain and counted as SATISFIED, so `op:top-n` (`order-measure`)
# and `op:drilldown` (`parent-context`) — two of the six stdlib operators — had their
# applicability waved through. Unknown now REFUSES (below), which is the same direction
# every other unhonourable operator takes.
#
# ⚑ The predicates are read off the bodies' own prose, because that is where `requires:`
# survives: the compiler drops `SkillFile.requires` and neither `CompiledEntry` nor
# `OperatorEntry` carries it (see `skills.py`, finding 1). The structural fix is one field
# in `tatrman` — and it would let this table be checked against a declared vocabulary
# instead of tracking it.
_REQUIREMENT_CHECKS: dict[str, Callable[[StructuredQuestion], bool]] = {
    # "without a grain … nothing to group by" (trend.md)
    "time-grain": lambda q: q.time_grain is not None,
    # "exactly two comparanda" (compare.md)
    "two-series": lambda q: len(q.measures) >= 2,
    # "a ranking needs something to rank by … when it names several, ask rather than
    # choose" (top-n.md) — so exactly one measure satisfies it, and both 0 and ≥2 do not
    "order-measure": lambda q: len(q.measures) == 1,
    # "with no parent, there is no 'finer'" (drilldown.md)
    "parent-context": lambda q: bool(q.groupings),
}


def check_applicability(bodies: list[SkillBody], question: StructuredQuestion) -> list[str]:
    """`requires:` is validated at COMPOSE, not at match (contracts §2).

    A trigger word matching is a fact about the question's surface; whether the
    operator can DO anything is a fact about what resolved. `op:trend` with no time
    grain has nothing to group by, and surfacing that beats inventing a grain.

    An UNKNOWN requirement raises rather than passing: "this operator declares a
    condition we cannot evaluate" is the same class of ignorance as an operator with no
    body — we do not know whether it applies, so we do not pretend it does.
    """
    unsatisfied: list[str] = []
    for body in bodies:
        for requirement in body.requires:
            check = _REQUIREMENT_CHECKS.get(requirement)
            if check is None:
                raise ComposeRefused(
                    f"{body.op} declares requirement {requirement!r}, which this Golem "
                    f"cannot evaluate (known: {', '.join(sorted(_REQUIREMENT_CHECKS))})"
                )
            if not check(question):
                unsatisfied.append(f"{body.op} requires {requirement}")
    return unsatisfied


def compose_structured_question(
    state: ResolutionState, library: LayeredSkillLibrary
) -> StructuredQuestion:
    """The whole composition. Raises `ComposeRefused` rather than guessing."""
    question = StructuredQuestion(
        subjects=_refs(state, FrameRole.SUBJECT),
        measures=_refs(state, FrameRole.MEASURE),
        groupings=_refs(state, FrameRole.GROUPING),
        filters=_filters(state),
        time_grain=_time_grain(state),
        operators=operator_refs(state),
    )
    try:
        bodies, retrieval, formatting = compose_operators(question.operators, library)
    except SkillError as exc:
        raise ComposeRefused(str(exc)) from exc
    # Applicability is checked against the question as composed so far — a `requires:`
    # is a claim about what RESOLVED, which is exactly what this object holds.
    question.retrieval_directives = retrieval
    question.formatting_directives = formatting
    unsatisfied = check_applicability(bodies, question)
    if unsatisfied:
        inapplicable = {note.split(" ", 1)[0] for note in unsatisfied}
        question.inapplicable_operators = sorted(unsatisfied)
        applicable = [b for b in bodies if b.op not in inapplicable]
        question.operators = [b.op for b in applicable]
        question.retrieval_directives = [b.retrieval for b in applicable if b.retrieval]
        question.formatting_directives = {b.op: b.formatting for b in applicable if b.formatting}

    if not question.operators:
        # No ACTION this Golem holds a body for — the H4 case. Either the question named
        # none (the "why" class) or every one it named turned out inapplicable.
        detail = "; ".join(question.inapplicable_operators)
        raise ComposeRefused(
            "the question names no operator this Golem can perform"
            + (f" ({detail})" if detail else "")
        )
    if not question.is_answerable():
        raise ComposeRefused(
            "the question has no measure and no subject — nothing to select, whatever else resolved"
        )
    return question
