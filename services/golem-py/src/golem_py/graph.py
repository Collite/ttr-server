# SPDX-License-Identifier: Apache-2.0
"""The RV-11 loop as a pydantic-graph.

    start ─┬─(fresh)──> call_core ──> assess_gaps ──> {ladder_loop | ask | emit | refuse}
           └─(resume)──────────────────> assess_gaps        ^         │
                                                            └─────────┘  the loop edge

Three framework facts this module is built on, all verified by inspection at the P0-3
spike and all still true at 2.22.0:

1. **No built-in persistence.** `pydantic_graph.persistence` does not exist and
   `Graph.run()` has no start-at-node argument. Resume is OURS: a `ResolutionState`
   snapshot plus a start step that routes on it. That is not a workaround — RV-11 says
   a resume rejoins `assess_gaps`, and a start step that dispatches on the pin says
   exactly that, in one readable place, where the RS-26 token contract can see it.
2. **⚑ `.to(a, b)` is a FAN-OUT, not a branch, and it fails SILENTLY** — every target
   runs. Every fork here is a `g.decision()`, and `tests/test_graph_shape.py` asserts
   it mechanically over the built graph rather than trusting review.
3. **A `@g.step` returns the plain value** wired to `g.end_node`; returning `End(x)`
   does NOT unwrap. Unwrapping happens in exactly one place: `run_turn()`.
"""

from __future__ import annotations

import time
from typing import Literal

from pydantic_graph import Graph, GraphBuilder, StepContext, TypeExpression

from golem_py.budgets import Budgets
from golem_py.compose import ComposeRefused, compose_structured_question, operator_refs
from golem_py.deps import CoreResumePort, Deps
from golem_py.errors import CoreResumeUnavailable, GateUnavailable, PickRefused, UnknownOption
from golem_py.gate_client import apply_gate_result
from golem_py.ladder import DETERMINISTIC_RUNGS
from golem_py.observability import log_node, log_turn
from golem_py.outputs import Answer, Ask, AskOption, RefusalWithGaps, TurnOutput
from golem_py.query_client import build_envelope
from golem_py.state import (
    Disposition,
    GapKind,
    GapRecord,
    Hypothesis,
    ResolutionState,
    RungLogEntry,
    SignedOption,
    Span,
    ValueKind,
)
from golem_py.verdicts import (
    askable_gaps,
    carryable_gaps,
    eligible_rungs,
)

StartVerdict = Literal["resume", "reresolve", "fresh"]


def _budgets(ctx: StepContext[ResolutionState, Deps, object]) -> Budgets:
    """Budgets are turn-scoped and the counters live on the state, so rebuilding the
    accounting per node is safe — except for the wall clock, which is anchored to the
    turn's start instant carried on the deps' clock."""
    return Budgets.for_state(ctx.state, ctx.deps.ladder, ctx.deps.clock)


def _options_for(state: ResolutionState, span: Span) -> list[SignedOption]:
    """The signed options that belong to the span being ASKED about.

    ⛑ The ask used to offer every option the core signed, whatever gap it had chosen.
    `askable_gaps` orders load-bearing-first then by span, so the gap it picks need not be
    the span the core clarified — and the user was then asked about A and offered answers
    for B. An option carrying no span is unscoped and stays offered; an option naming a
    different span is not an answer to this question.

    An empty result is honest, and already handled: it means the core did not clarify this
    span, so free text or the escape are the only answers available.
    """
    return [
        o
        for o in state.signed_options
        if o.span is None or (o.span.start, o.span.end) == (span.start, span.end)
    ]


async def _redeem_period(
    state: ResolutionState, deps: Deps, option: SignedOption, target: GapRecord | None
) -> None:
    """ttr-server#231 — a `period` pin, redeemed by the CORE and folded back by span.

    A period option is a grounding kernel's reading of a date, signed into the core's
    token. It names no ref, so there is nothing for the gate to gate: putting it to the
    gate would propose a blank ref. The core's own resume re-reads the span exactly as it
    was asked and answers with ONE value, the picked date, grounded (interval + anchor
    column). That value is folded into OUR value at the same span, keeping our id, and the
    gap on it closes.

    Anything else (a refused pick, an answer that is not one grounded date on the asked
    span) leaves the gap open with the user's answer recorded: the turn is never answered
    without the period, and the core is never asked to re-ask.
    """
    if not isinstance(deps.core, CoreResumePort):
        raise CoreResumeUnavailable()
    span = option.span or (target.span if target is not None else None)
    try:
        answer: ResolutionState | None = await deps.core.resume(
            token=state.resume_token,
            selected_option_id=option.id,
            conversation_id=state.conversation_id,
            caller_subject=state.caller_subject,
        )
    except PickRefused:
        answer = None
    folded = answer is not None and span is not None and _fold_period(state, answer, span)
    if not folded and target is not None:
        target.user_answer = option.label or option.id
    log_node(
        state,
        "apply_pin",
        option=option.id,
        period=True,
        outcome="folded" if folded else ("refused" if answer is None else "not-a-period"),
    )


def _fold_period(state: ResolutionState, answer: ResolutionState, span: Span) -> bool:
    """Fold the core's answer to a period pick into the value at [span]. True when folded.

    Accepted only as the core documents it: exactly one value, on the asked span, GROUNDED,
    a DATE with an interval and an anchor attribution. Our value keeps its id (gaps and the
    rung log name it); its `ground:` ref survives when the answer carries none."""
    at = (span.start, span.end)
    if len(answer.values) != 1 or (answer.values[0].span.start, answer.values[0].span.end) != at:
        return False
    picked = answer.values[0]
    grounding = picked.grounding
    if (
        picked.kind != ValueKind.GROUNDED
        or grounding is None
        or grounding.kind != "DATE"
        or not grounding.normalized_value
        or not any(a.attribute_ref for a in picked.attributions)
    ):
        return False
    own = next((v for v in state.values if (v.span.start, v.span.end) == at), None)
    if own is None:
        return False
    previous_ref = own.grounding.ref if own.grounding is not None else ""
    own.kind = picked.kind
    own.grounding = grounding.model_copy(update={"ref": grounding.ref or previous_ref})
    own.attributions = [a.model_copy(deep=True) for a in picked.attributions]
    state.gaps = [
        g
        for g in state.gaps
        if not (g.value_id == own.id or (not g.value_id and (g.span.start, g.span.end) == at))
    ]
    return True


def _record_ladder_noop(state: ResolutionState, note: str) -> None:
    """RV-27's "full SHAPE present" is only auditable if the no-op says so. A turn that
    had open gaps and climbed nothing writes ONE entry naming the empty rung list —
    otherwise the zero-rung default is indistinguishable from a missing ladder."""
    if any(e.rung == "ladder" for e in state.rung_log):
        return
    state.rung_log.append(
        RungLogEntry(
            round=state.next_round(),
            rung="ladder",
            action="noop",
            gaps_open=len(state.open_gaps()),
            note=note,
        )
    )


def build_graph() -> Graph[ResolutionState, Deps, None, TurnOutput]:
    g = GraphBuilder(
        state_type=ResolutionState,
        deps_type=Deps,
        output_type=Ask | Answer | RefusalWithGaps,
    )

    @g.step
    async def start(ctx: StepContext[ResolutionState, Deps, None]) -> StartVerdict:
        """THE resume fork, three ways.

        * no pin ⇒ **fresh**: the ordinary turn.
        * a pin naming a signed OPTION (or the escape) ⇒ **resume**: the deterministic
          resolve already happened, so rejoin the loop and do not pay for it twice —
          nor risk a second lattice differing from the one the user was asked about.
        * a pin carrying only FREE TEXT ⇒ **reresolve**: the user did not choose from
          what we offered, they said something new. There is nothing to gate — the
          honest move is to resolve the amended question deterministically, which is
          the same thing that would happen if they had typed it in the first place.

        It is also where the turn's clock starts. Every run passes through here — fresh
        or resumed — so anchoring the wall-clock budget once, HERE, is what makes it a
        budget for the turn rather than for whichever node last looked at it.
        """
        ctx.state.turn_started_at = ctx.deps.clock()
        pin = ctx.state.pin
        if pin is None:
            verdict: StartVerdict = "fresh"
        elif pin.is_option() or pin.is_escape():
            verdict = "resume"
        else:
            verdict = "reresolve"
        log_node(ctx.state, "start", verdict=verdict)
        return verdict

    @g.step
    async def amend_question(ctx: StepContext[ResolutionState, Deps, object]) -> None:
        """Fold a free-text answer into the question, then fall through to `call_core`.

        ⚑ RULED at P4.2·T1(e): the user was asked "what does X refer to", so their
        answer REPLACES X in the question. If the asked span cannot be found in the
        question text (a re-ask after an edit, say), the answer is APPENDED rather than
        dropped — losing what the user typed is the one outcome that is never right.
        """
        pin = ctx.state.pin
        assert pin is not None
        answer = pin.free_text.strip()
        span = ctx.state.asked_gap_span
        if span is not None and span.text and span.text in ctx.state.question:
            ctx.state.question = ctx.state.question.replace(span.text, answer, 1)
        else:
            ctx.state.question = f"{ctx.state.question} {answer}".strip()
        for gap in ctx.state.gaps:
            if span is not None and gap.span.start == span.start and gap.span.end == span.end:
                gap.user_answer = answer
        # The amended question gets a fresh lattice; the OLD one must not survive into
        # it, or a stale gap would drive the loop over a question nobody asked.
        ctx.state.pin = None
        ctx.state.mentions = []
        ctx.state.values = []
        ctx.state.gaps = []
        ctx.state.rungs_run = []
        ctx.state.signed_options = []
        log_node(ctx.state, "amend_question", question=ctx.state.question)

    @g.step
    async def apply_pin(ctx: StepContext[ResolutionState, Deps, object]) -> None:
        """Turn the user's choice into a HYPOTHESIS and put it through the gate.

        RV-7 with the user in the loop: a pin is a hypothesis with user provenance, and
        it becomes a binding only by surviving the same evidence-class gate as any
        other candidate. Binding it agent-side because "the user said so" would make the
        user the one proposer who bypasses the rules — and the user is exactly the
        proposer most likely to name something the vocabulary does not have.

        The ESCAPE ("none of these") gates nothing: it proposes no ref. It closes the
        gap as `USER_CONFIRMED_UNKNOWN`, which is an answer — the question was asked and
        settled — without pretending anything bound.
        """
        pin = ctx.state.pin
        assert pin is not None
        ctx.state.pin = None
        span = ctx.state.asked_gap_span
        target = next(
            (
                g
                for g in ctx.state.gaps
                if span is not None and g.span.start == span.start and g.span.end == span.end
            ),
            None,
        )

        if pin.is_escape():
            if target is not None:
                target.disposition = Disposition.USER_CONFIRMED_UNKNOWN
                target.user_answer = pin.free_text or "none of these"
            log_node(ctx.state, "apply_pin", outcome="escape")
            return

        option = next((o for o in ctx.state.signed_options if o.id == pin.option_id), None)
        if option is None:
            # A pin naming an option the core never signed. Refusing to invent one is
            # the whole point of storing the signed set rather than trusting the caller.
            raise UnknownOption(pin.option_id)
        if option.is_period():
            await _redeem_period(ctx.state, ctx.deps, option, target)
            return
        if ctx.deps.gate is None:
            raise GateUnavailable()

        hypothesis = Hypothesis(
            span=option.span or (target.span if target is not None else None),
            ref=option.ref,
            # ⚑ `user`, deliberately outside the four-rung vocabulary (contracts §3):
            # this proposal did not come from a rung, and recording it as one would
            # make the ladder's health numbers lie. Recorded for Bora — the proto's
            # `proposing_rung` is a free string, so this is additive, not a violation.
            proposing_rung="user",
        )
        result = await ctx.deps.gate.gate(lattice=ctx.state, hypotheses=[hypothesis])
        apply_gate_result(ctx.state, result)
        if target is not None:
            for gap in ctx.state.gaps:
                if gap.span.start == target.span.start and gap.span.end == target.span.end:
                    gap.user_answer = option.label or option.ref
        log_node(
            ctx.state,
            "apply_pin",
            ref=option.ref,
            accepted=sum(1 for o in result.outcomes if o.accepted),
        )

    @g.step
    async def call_core(ctx: StepContext[ResolutionState, Deps, object]) -> None:
        """The ONE deterministic call (`resolve.bind:v1`). Zero LLM below the door."""
        t0 = ctx.deps.clock()
        lattice = await ctx.deps.core.resolve(
            question=ctx.state.question,
            locale=ctx.state.locale,
            conversation_id=ctx.state.conversation_id,
            caller_subject=ctx.state.caller_subject,
        )
        # Merge the door's lattice in WITHOUT clobbering turn-local fields (budgets,
        # pin, identity). The lattice is the door's; the loop state is ours.
        ctx.state.parse = lattice.parse
        ctx.state.mentions = lattice.mentions
        ctx.state.values = lattice.values
        ctx.state.gaps = lattice.gaps
        ctx.state.lexicon_versions = lattice.lexicon_versions
        ctx.state.trace_id = lattice.trace_id or ctx.state.trace_id
        if lattice.resume_token:
            ctx.state.resume_token = lattice.resume_token
        # The option set the core signed into that token travels with it — a pin is
        # honoured only against this list, so losing it here would make every option
        # unhonourable at resume.
        ctx.state.signed_options = lattice.signed_options
        # The core writes round 0 of the rung log for its own pass; if it did not (an
        # older door), record it here rather than leaving the trail starting at 1.
        ctx.state.rung_log.extend(lattice.rung_log)
        if not any(e.rung == "core" for e in ctx.state.rung_log):
            ctx.state.rung_log.append(
                RungLogEntry(
                    round=0,
                    rung="core",
                    action="annotate",
                    mention_ids=[m.id for m in lattice.mentions],
                    value_ids=[v.id for v in lattice.values],
                    bindings_added=sum(len(m.bindings) for m in lattice.mentions),
                    gaps_open=len(lattice.gaps),
                    elapsed_ms=int((ctx.deps.clock() - t0) * 1000),
                    note="resolve.bind:v1 — deterministic, zero-LLM",
                )
            )
        log_node(
            ctx.state,
            "call_core",
            mentions=len(ctx.state.mentions),
            gaps=len(ctx.state.gaps),
        )

    @g.step
    async def assess_gaps(
        ctx: StepContext[ResolutionState, Deps, object],
    ) -> Literal["climb", "ask", "emit", "refuse"]:
        """The loop's objective function — a PURE verdict (see `verdicts.py`). This
        node decides nothing on its own and mutates nothing; it asks."""
        from golem_py.verdicts import assess

        verdict = assess(ctx.state, ctx.deps.ladder, _budgets(ctx))
        log_node(
            ctx.state,
            "assess_gaps",
            verdict=verdict.value,
            gaps_open=len(ctx.state.open_gaps()),
            llm_invocations=ctx.state.llm_invocations,
        )
        return verdict.value

    @g.step
    async def ladder_loop(ctx: StepContext[ResolutionState, Deps, object]) -> None:
        """Climb the eligible rungs. Every rung PROPOSES; nothing here binds (RV-7) —
        hypotheses go back through `resolve.gate:v1` and are gated by the same rules as
        any other candidate.

        With the shipped zero-rung default this node is unreachable, and that is the
        design: the SHAPE is present, the policy admits no rung. When it IS entered
        with nothing runnable, it says so rather than looping.
        """
        budgets = _budgets(ctx)
        rungs = eligible_rungs(ctx.state, ctx.deps.ladder, budgets)
        if not rungs:
            _record_ladder_noop(
                ctx.state, "no eligible rung — zero-rung policy or exhausted budget (RV-27)"
            )
            return

        for rung in rungs:
            # ⛑ Re-checked BEFORE each climb, not once at entry. `eligible_rungs` filters
            # against the counters as they stand when the ladder is entered; climbing the
            # whole list afterwards spends a budget of one on three rungs — including
            # `emulated`, the most expensive in the vocabulary. A budget is a cap on
            # spending, not a gate on starting.
            if not budgets.can_run(rung, ctx.state):
                ctx.state.rung_log.append(
                    RungLogEntry(
                        round=ctx.state.next_round(),
                        rung="ladder",
                        action="halt",
                        gaps_open=len(ctx.state.open_gaps()),
                        note=f"budget exhausted before {rung} "
                        f"(llm={ctx.state.llm_invocations}, elapsed_ms={budgets.elapsed_ms()})",
                    )
                )
                break
            t0 = ctx.deps.clock()
            ctx.state.rungs_run.append(rung)
            if rung not in DETERMINISTIC_RUNGS:
                ctx.state.llm_invocations += 1
            # ⚑ THE SEAM. A real rung proposes hypotheses here and re-gates them via
            # `ctx.deps.gate`. P4.1 stops at the seam deliberately: the rung bodies are
            # RV-P5's (Kotlin) and this service's own rung implementations are a
            # post-P4 effort — what P4 ships is the loop that would drive them, and a
            # loop with a fake rung in it would be untestable theatre.
            ctx.state.rung_log.append(
                RungLogEntry(
                    round=ctx.state.next_round(),
                    rung=rung,
                    action="climb",
                    gaps_open=len(ctx.state.open_gaps()),
                    elapsed_ms=int((ctx.deps.clock() - t0) * 1000),
                    note="seam: proposes → resolve.gate:v1 (RV-7 proposer-not-binder)",
                )
            )
        log_node(ctx.state, "ladder_loop", rungs=",".join(rungs))

    @g.step
    async def ask(ctx: StepContext[ResolutionState, Deps, object]) -> Ask:
        """Pause. The token is the core's, the snapshot is ours, and they travel side
        by side (P0-3·T5).

        The ask budget is spent HERE and the counter rides the snapshot, which is what
        makes a replayed resume unable to buy a second question: the replay reads the
        stored state, where the round is already counted.
        """
        _record_ladder_noop(ctx.state, "ask reached with no rung climbed (zero-rung default)")
        gap = askable_gaps(ctx.state, ctx.deps.ladder)[0]
        ctx.state.hitl_rounds += 1
        gap.asked_round = ctx.state.hitl_rounds
        ctx.state.asked_gap_span = gap.span
        snapshot_id = ctx.deps.snapshots.put(ctx.state)
        out = Ask(
            question=f"What does {gap.span.text!r} refer to?",
            gap_kind=gap.kind,
            options=[
                AskOption(id=o.id, label=o.label, ref=o.ref)
                for o in _options_for(ctx.state, gap.span)
            ],
            resume_token=ctx.state.resume_token,
            snapshot_id=snapshot_id,
            lattice=ctx.state,
        )
        log_node(
            ctx.state,
            "ask",
            gap_kind=gap.kind.value,
            round=ctx.state.hitl_rounds,
            snapshot=snapshot_id,
            options=len(out.options),
        )
        log_turn(ctx.state, "ask")
        return out

    @g.step
    async def emit(ctx: StepContext[ResolutionState, Deps, object]) -> Answer | RefusalWithGaps:
        """A covered lattice becomes a structured question, and — if there is a door —
        an answer (P4.3).

        The FAST PATH is the only path here: the open Golem has no plugins, so self is
        the only capability. What decides whether it fires is the T1 predicate, and it
        is deliberately made of things the LATTICE can say, because the OS Golem has no
        intent classifier (that is Themis machinery, platform side) and inventing one
        here would be the single biggest thing this design says not to do.
        """
        if ctx.state.open_gaps():
            _record_ladder_noop(ctx.state, "emitted with carryable gaps and no rung climbed")
        carried = carryable_gaps(ctx.state, ctx.deps.ladder)
        for gap in carried:
            gap.disposition = (
                Disposition.DEGRADED if gap.kind == GapKind.G5_NLP_DARK else Disposition.IGNORED
            )

        try:
            question = compose_structured_question(ctx.state, ctx.deps.skills)
        except ComposeRefused as exc:
            # Understood, but not doable. That is a REFUSAL, not an error and not a
            # guess — and it carries the lattice so the caller can see how far the
            # understanding got (H4's rendering).
            log_node(ctx.state, "emit", refused=str(exc))
            log_turn(ctx.state, "refuse")
            return RefusalWithGaps(
                reason="NO_CAPABLE_PLUGIN",
                gaps=ctx.state.gaps,
                lattice=ctx.state,
                composable_residue=[
                    op for op in operator_refs(ctx.state) if ctx.deps.skills.has(op)
                ],
                explanation=str(exc),
            )

        result = None
        if ctx.deps.query is not None:
            result = await ctx.deps.query.run(
                question=question, caller_subject=ctx.state.caller_subject
            )
        envelope = build_envelope(
            state=ctx.state, question=question, result=result, gaps_carried=carried
        )
        out = Answer(
            content=envelope.content,
            envelope=envelope,
            lattice=ctx.state,
            llm_invocations=ctx.state.llm_invocations,
            asks=ctx.state.hitl_rounds,
            gaps_carried=carried,
        )
        log_node(ctx.state, "emit", ops=",".join(question.operators), carried=len(carried))
        log_turn(ctx.state, "emit")
        return out

    @g.step
    async def refuse(ctx: StepContext[ResolutionState, Deps, object]) -> RefusalWithGaps:
        """The honest refusal, with the lattice attached: "this much I understood"."""
        _record_ladder_noop(ctx.state, "refused with no rung climbed (zero-rung default)")
        out = RefusalWithGaps(
            reason="UNRESOLVED_GAPS",
            gaps=ctx.state.open_gaps(),
            lattice=ctx.state,
            composable_residue=[op for op in operator_refs(ctx.state) if ctx.deps.skills.has(op)],
        )
        log_node(ctx.state, "refuse", gaps_open=len(ctx.state.open_gaps()))
        log_turn(ctx.state, "refuse")
        return out

    g.add(
        g.edge_from(g.start_node).to(start),
        # ⚑ Every fork is a decision node. A bare multi-target `.to(...)` here would
        # run BOTH targets, silently (P0-3's most expensive finding).
        g.edge_from(start).to(
            g.decision()
            .branch(g.match(TypeExpression[Literal["resume"]]).to(apply_pin))
            .branch(g.match(TypeExpression[Literal["reresolve"]]).to(amend_question))
            .branch(g.match(TypeExpression[Literal["fresh"]]).to(call_core))
        ),
        # A resume rejoins the loop at `assess_gaps` (RV-11) — `apply_pin` is on the way
        # in, not a second door: it gates the user's choice and hands the recomputed
        # lattice to the same assessment every other path reaches.
        g.edge_from(apply_pin).to(assess_gaps),
        g.edge_from(amend_question).to(call_core),
        g.edge_from(call_core).to(assess_gaps),
        g.edge_from(assess_gaps).to(
            g.decision()
            .branch(g.match(TypeExpression[Literal["climb"]]).to(ladder_loop))
            .branch(g.match(TypeExpression[Literal["ask"]]).to(ask))
            .branch(g.match(TypeExpression[Literal["emit"]]).to(emit))
            .branch(g.match(TypeExpression[Literal["refuse"]]).to(refuse))
        ),
        g.edge_from(ladder_loop).to(assess_gaps),  # THE loop edge
        g.edge_from(ask, emit, refuse).to(g.end_node),
    )
    return g.build()


_GRAPH: Graph[ResolutionState, Deps, None, TurnOutput] | None = None


def graph() -> Graph[ResolutionState, Deps, None, TurnOutput]:
    """The built graph, once per process. Building is pure and cheap, but the identity
    matters to the structural test, which walks THIS object."""
    global _GRAPH
    if _GRAPH is None:
        _GRAPH = build_graph()
    return _GRAPH


async def run_turn(state: ResolutionState, deps: Deps) -> TurnOutput:
    """Drive one turn. THE single place a graph result is unwrapped (framework fact 3);
    everything else in this service deals in `Ask | Answer | RefusalWithGaps`."""
    t0 = time.monotonic()
    output: TurnOutput = await graph().run(state=state, deps=deps)
    log_turn(state, "done", elapsed_ms=int((time.monotonic() - t0) * 1000))
    return output
