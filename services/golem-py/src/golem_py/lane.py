# SPDX-License-Identifier: Apache-2.0
"""The LLM lane — what `emit` does when the lane is on (OL-C11…C18, W1c·S2·T6).

    on  =  GOLEM_LLM_LANE_ENABLED  ∧  a gateway  ∧  a door                  (OL-C11)

The verdicts do not change and nothing here binds. `emit` is reached exactly as before;
with the lane on, a question that composed and carries no blocking gap is answered by ONE
LLM call that writes SQL over the model slice, the checks of OL-C15 and then the door,
which runs it under the CALLER's row rules. Off, `emit` answers structurally, as it did
before W1c.

The sequence, and every way out of it (OL-C16):

    slice ─ prompt ─ call ─ checks ─ door ──────────────────────────────▶ Answer
                      │       │        └ rejected ─ call ─ checks ─ door ▶ Answer
                      │       │                      │       │        └ rejected ▶ QUERY_NOT_WRITTEN
                      │       └ rule broken ▶ QUERY_NOT_WRITTEN (no repair: the door never saw it)
                      └ budget spent ▶ QUERY_NOT_WRITTEN
    any leg unreachable (meta.v1 · llm.v1 · query.v1) ▶ LANE_UNAVAILABLE

Each call counts one against `max_llm_invocations` on the state, the same counter the
ladder spends — a lane call is an LLM invocation like any other. A refusal carries the
lattice and the last error; it never carries rows, and it is never a structural answer
dressed as data.
"""

from __future__ import annotations

from golem_py.budgets import Budgets
from golem_py.compose import StructuredQuestion, composable_residue
from golem_py.deps import Deps
from golem_py.errors import (
    ConfigInvalid,
    DoorRejected,
    LaneReplyRefused,
    LaneServiceUnavailable,
)
from golem_py.ladder import LadderConfig
from golem_py.lane_checks import LaneQuery, check_reply, literal_sources
from golem_py.lane_prompt import (
    LanePrompt,
    PromptTemplateError,
    load_template,
    render,
    render_repair,
)
from golem_py.llm_client import LlmReply
from golem_py.model_slice import ModelSlice
from golem_py.observability import log_node
from golem_py.outputs import Answer, RefusalReason, RefusalWithGaps
from golem_py.query_client import QueryResult, build_lane_envelope
from golem_py.state import GapRecord, ResolutionState, Span
from golem_py.verdicts import blocking_gaps

# The lane's calls run under the `capable` rung's clock (OL-C13).
LANE_RUNG = "capable"
# One write and at most one repair (OL-C16).
MAX_CALLS = 2


def lane_on(deps: Deps) -> bool:
    """OL-C11. The model slice is not part of the switch: a lane that is on but cannot read
    the model refuses as unavailable rather than falling back to a structural answer."""
    return deps.settings.llm_lane_enabled and deps.llm is not None and deps.door is not None


def check_lane_config(ladder: LadderConfig) -> None:
    """What a lane that is on needs before its first turn, checked at STARTUP (the
    `ConfigInvalid` convention): a clock for its rung and a prompt file that parses. Without
    them every lane turn would fail the same way mid-conversation, and nothing would name why."""
    if ladder.timeout_ms(LANE_RUNG) <= 0:
        raise ConfigInvalid(
            f"the LLM lane runs under the {LANE_RUNG!r} rung's clock and the ladder gives it "
            f"none: set rungs.{LANE_RUNG}.timeout_ms or rung_timeouts_ms.{LANE_RUNG}"
        )
    try:
        load_template()
    except (OSError, PromptTemplateError) as exc:
        raise ConfigInvalid(f"the LLM lane's prompt file: {exc}") from exc


def lane_applies(state: ResolutionState, deps: Deps) -> bool:
    """The lane answers only what `emit` would answer anyway — a blocking gap (reachable
    here under a best-effort posture) keeps today's path (OL-C18)."""
    return lane_on(deps) and not blocking_gaps(state, deps.ladder)


def bound_refs(state: ResolutionState) -> list[str]:
    """Every ref the lattice bound, in lattice order, once — the slice ranks their entities
    first (OL-C14)."""
    refs: list[str] = []
    for m in state.mentions:
        refs += [b.ref for b in m.bindings]
    for v in state.values:
        refs += [a.attribute_ref for a in v.attributions]
    return list(dict.fromkeys(r for r in refs if r))


def _refusal(
    state: ResolutionState, deps: Deps, reason: RefusalReason, error: str
) -> RefusalWithGaps:
    log_node(state, "lane", outcome=reason, llm_invocations=state.llm_invocations)
    return RefusalWithGaps(
        reason=reason,
        gaps=state.gaps,
        lattice=state,
        composable_residue=composable_residue(state, deps.skills),
        explanation=error,
    )


async def _call(state: ResolutionState, deps: Deps, prompt: LanePrompt) -> LlmReply:
    assert deps.llm is not None
    state.llm_invocations += 1
    return await deps.llm.complete_json(
        instructions=prompt.instructions,
        prompt=prompt.input,
        timeout_s=deps.ladder.timeout_ms(LANE_RUNG) / 1000,
    )


async def _slice(state: ResolutionState, deps: Deps) -> ModelSlice:
    if deps.model_slice is None:
        raise LaneServiceUnavailable("meta.v1", "no model slice is configured")
    return await deps.model_slice.slice(
        bound_refs=bound_refs(state), cap=deps.settings.llm_model_slice_entities
    )


async def run_lane(
    state: ResolutionState,
    deps: Deps,
    *,
    question: StructuredQuestion,
    carried: list[GapRecord],
) -> Answer | RefusalWithGaps:
    """One turn's lane (OL-C16). Returns the answer or one of the two refusals."""
    assert deps.door is not None
    budgets = Budgets.for_state(state, deps.ladder, deps.clock)
    try:
        model_slice = await _slice(state, deps)
        first = render(
            state,
            question=question,
            carried=carried,
            model_slice=model_slice,
            reference_date=deps.reference_date(),
        )
        prompt = first
        last_error = ""
        for attempt in range(MAX_CALLS):
            if not budgets.llm_left(state):
                spent = f"the LLM budget is spent ({state.llm_invocations} invocations)"
                return _refusal(
                    state,
                    deps,
                    "QUERY_NOT_WRITTEN",
                    f"{spent}; the door said: {last_error}" if last_error else spent,
                )
            reply = await _call(state, deps, prompt)
            query: LaneQuery = check_reply(
                reply.text,
                allowed_entities=model_slice.allowed_entities,
                carried=[g.span for g in carried],
                literal_sources=literal_sources(state),
            )
            try:
                result: QueryResult = await deps.door.run(
                    sql=query.sql,
                    world=deps.settings.query_world,
                    caller_subject=state.caller_subject,
                    allowed_entities=model_slice.allowed_entities,
                )
            except DoorRejected as exc:
                last_error = f"{exc.code}: {exc.message}"
                log_node(state, "lane", attempt=attempt + 1, door="rejected", code=exc.code)
                prompt = render_repair(first, previous_sql=query.sql, door_error=last_error)
                continue
            return _answer(state, deps, question, carried, query, reply, result)
        return _refusal(
            state,
            deps,
            "QUERY_NOT_WRITTEN",
            f"the door rejected the repaired query: {last_error}",
        )
    except LaneReplyRefused as exc:
        return _refusal(state, deps, "QUERY_NOT_WRITTEN", str(exc))
    except LaneServiceUnavailable as exc:
        return _refusal(state, deps, "LANE_UNAVAILABLE", str(exc))


def _answer(
    state: ResolutionState,
    deps: Deps,
    question: StructuredQuestion,
    carried: list[GapRecord],
    query: LaneQuery,
    reply: LlmReply,
    result: QueryResult,
) -> Answer:
    by_span = {(g.span.start, g.span.end): g.span for g in carried}
    handled: list[Span] = [by_span[s].model_copy() for s in query.handled_spans]
    envelope = build_lane_envelope(
        state=state,
        question=question,
        result=result,
        gaps_carried=carried,
        sql=query.sql,
        llm_model=reply.model or deps.settings.llm_model,
        llm_handled=handled,
    )
    log_node(
        state,
        "lane",
        outcome="answer",
        rows=len(result.rows),
        handled=len(handled),
        llm_invocations=state.llm_invocations,
    )
    return Answer(
        content=envelope.content,
        envelope=envelope,
        lattice=state,
        llm_invocations=state.llm_invocations,
        asks=state.hitl_rounds,
        gaps_carried=carried,
    )
