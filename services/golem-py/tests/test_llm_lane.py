# SPDX-License-Identifier: Apache-2.0
"""OL W1c·S2·T2/T3 — the LLM lane at `emit` (OL-C11, C15…C18).

Recorded turns through the whole graph: a recorded core, and fakes for the gateway, the door
and the model slice that replay what they are given and record what they were asked. No
network — two specs reach the S1 clients over a loopback port nothing listens on, to show
their unreachability arrives as the lane's refusal.

T2 (lane OFF) is mostly the pre-existing suite, which runs with no gateway and no door and is
not edited; the specs here pin the three ways the switch is off, and the two paths OL-C18 keeps
out of the lane even when it is on.
"""

from __future__ import annotations

import dataclasses
import json
from collections.abc import Sequence
from datetime import date

import pytest

from golem_py.deps import Deps
from golem_py.door_client import LiveSqlDoor
from golem_py.errors import DoorRejected, LaneServiceUnavailable
from golem_py.graph import run_turn
from golem_py.ladder import load_default
from golem_py.llm_client import SERVICE, LiveLlmGateway, LlmReply
from golem_py.model_slice import ModelSlice, SliceAttribute, SliceEntity, SliceRelation
from golem_py.outputs import Answer, RefusalWithGaps
from golem_py.query_client import QueryResult
from golem_py.settings import GolemSettings
from golem_py.state import (
    Binding,
    EvidenceClass,
    FrameRole,
    GapKind,
    GapRecord,
    Mention,
    ResolutionState,
    SourceTag,
    Span,
    TargetClass,
    ValueFinding,
    ValueKind,
)
from tests.grpc_fakes import unused_target
from tests.helpers import RecordedCore, deps, h1_lattice

TODAY = date(2026, 11, 16)
SUBJECT = "user:jana"
WORLD = "hartland_cz"
SALES = "er.entity.store_sales"
STORE = "er.entity.store"
SLICE = ModelSlice(
    packages=("hartland_cz",),
    entities=(
        SliceEntity(
            qname=SALES,
            attributes=(
                SliceAttribute(name="amount", type="decimal"),
                SliceAttribute(name="sold_on", type="date"),
                SliceAttribute(name="store_id", type="int"),
            ),
        ),
        SliceEntity(qname=STORE, attributes=(SliceAttribute(name="id", type="int", is_key=True),)),
    ),
    relations=(
        SliceRelation(
            name="sold_at",
            from_entity=SALES,
            to_entity=STORE,
            join_pairs=((f"{SALES}.store_id", f"{STORE}.id"),),
        ),
    ),
)
COVERED_SQL = f"SELECT SUM(s.amount) AS revenue FROM {SALES} s"
PERIOD = Span(start=22, end=34, text="minulý měsíc")
PERIOD_SQL = (
    f"SELECT SUM(s.amount) AS revenue FROM {SALES} s "
    "WHERE s.sold_on >= DATE '2026-10-01' AND s.sold_on < DATE '2026-11-01'"
)
ROWS = QueryResult(rows=[{"revenue": 1234.5}], columns=["revenue"])


# ------------------------------------------------------------------ fakes


class FakeGateway:
    """Replays reply texts (or raises), recording every call."""

    def __init__(self, *replies: str | Exception, model: str = "gw-capable-1"):
        self.replies = list(replies)
        self.model = model
        self.calls: list[dict[str, object]] = []

    async def complete_json(self, *, instructions: str, prompt: str, timeout_s: float) -> LlmReply:
        self.calls.append({"instructions": instructions, "prompt": prompt, "timeout_s": timeout_s})
        reply = self.replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        return LlmReply(text=reply, model=self.model)


class FakeDoor:
    """Replays outcomes (a result, or an exception to raise), recording every call."""

    def __init__(self, *outcomes: QueryResult | Exception):
        self.outcomes = list(outcomes)
        self.calls: list[dict[str, object]] = []

    async def run(
        self, *, sql: str, world: str, caller_subject: str, allowed_entities: Sequence[str]
    ) -> QueryResult:
        self.calls.append(
            {
                "sql": sql,
                "world": world,
                "caller_subject": caller_subject,
                "allowed_entities": tuple(allowed_entities),
            }
        )
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


class FakeSlice:
    def __init__(self, model_slice: ModelSlice | Exception = SLICE):
        self.model_slice = model_slice
        self.calls: list[dict[str, object]] = []

    async def slice(self, *, bound_refs: Sequence[str], cap: int) -> ModelSlice:
        self.calls.append({"bound_refs": list(bound_refs), "cap": cap})
        if isinstance(self.model_slice, Exception):
            raise self.model_slice
        return self.model_slice


def lane_deps(
    lattice: ResolutionState,
    *,
    llm: object | None = None,
    door: object | None = None,
    model_slice: object | None = None,
    enabled: bool = True,
    ladder: object | None = None,
) -> Deps:
    base = deps(RecordedCore(lattice), ladder=ladder)  # type: ignore[arg-type]
    return dataclasses.replace(
        base,
        settings=GolemSettings(llm_lane_enabled=enabled, query_world=WORLD, llm_model="pinned"),
        llm=llm,  # type: ignore[arg-type]
        door=door,  # type: ignore[arg-type]
        model_slice=model_slice,  # type: ignore[arg-type]
        reference_date=lambda: TODAY,
    )


def reply(sql: str, spans: list[list[int]] | None = None, note: str = "") -> str:
    return json.dumps({"sql": sql, "handled_spans": spans or [], "note": note})


def period_lattice() -> ResolutionState:
    """ "Zobraz tržby za minulý měsíc" — the core bound the action and the measure, and left
    the relative period as an unnormalised value with a G4 in FILTER position: not
    load-bearing, so carried (RV-19)."""
    return ResolutionState(
        question="Zobraz tržby za minulý měsíc",
        mentions=[
            Mention(
                id="m0",
                span=Span(start=0, end=6, text="Zobraz"),
                lemma="zobrazit",
                bindings=[
                    Binding(
                        ref="op:show",
                        target_class=TargetClass.OPERATOR,
                        evidence_class=EvidenceClass.EXACT,
                        source=SourceTag.DECLARED,
                        in_class_score=1.0,
                    )
                ],
            ),
            Mention(
                id="m1",
                span=Span(start=7, end=12, text="tržby"),
                lemma="tržba",
                frame_roles=[FrameRole.SUBJECT, FrameRole.MEASURE],
                bindings=[
                    Binding(
                        ref="md.measure.revenue",
                        target_class=TargetClass.MODEL_OBJECT,
                        evidence_class=EvidenceClass.DECLARED_ALIAS,
                        source=SourceTag.DECLARED,
                        in_class_score=1.0,
                    )
                ],
            ),
        ],
        values=[ValueFinding(id="v1", span=PERIOD, kind=ValueKind.LITERAL)],
        gaps=[
            GapRecord(
                span=PERIOD,
                kind=GapKind.G4_METHOD_MISS,
                frame_roles=[FrameRole.FILTER],
                value_id="v1",
            )
        ],
        trace_id="t-period",
    )


def _state(
    lattice: ResolutionState, *, llm_invocations: int = 0, hitl_rounds: int = 0
) -> ResolutionState:
    return ResolutionState(
        question=lattice.question or "Zobraz tržby za rok 2025",
        caller_subject=SUBJECT,
        llm_invocations=llm_invocations,
        hitl_rounds=hitl_rounds,
    )


# ------------------------------------------------------------------ T2: lane off


@pytest.mark.parametrize(
    "which", ["no gateway", "no door", "switched off"], ids=lambda w: w.replace(" ", "-")
)
async def test_with_the_lane_off_emit_answers_structurally_as_before(which: str) -> None:
    gateway = FakeGateway(reply(COVERED_SQL))
    door = FakeDoor(ROWS)
    d = lane_deps(
        h1_lattice(),
        llm=None if which == "no gateway" else gateway,
        door=None if which == "no door" else door,
        model_slice=FakeSlice(),
        enabled=which != "switched off",
    )

    out = await run_turn(_state(h1_lattice()), d)

    assert isinstance(out, Answer)
    assert out.envelope is not None
    assert out.envelope.sql == "" and "lane" not in out.envelope.provenance
    assert out.envelope.result is None  # no structured door either: nothing ran
    assert out.llm_invocations == 0
    assert gateway.calls == [] and door.calls == []


async def test_a_compose_refusal_stays_todays_refusal_with_the_lane_on() -> None:
    """OL-C18: H4 ("Proč …") names an action with no body. The lane is not asked."""
    lattice = h1_lattice()
    lattice.mentions[1].bindings[0].ref = "op:why"
    gateway, door = FakeGateway(reply(COVERED_SQL)), FakeDoor(ROWS)

    out = await run_turn(
        _state(lattice), lane_deps(lattice, llm=gateway, door=door, model_slice=FakeSlice())
    )

    assert isinstance(out, RefusalWithGaps)
    assert out.reason == "NO_CAPABLE_PLUGIN"
    assert gateway.calls == [] and door.calls == []


async def test_a_blocking_gap_under_a_best_effort_posture_stays_out_of_the_lane() -> None:
    """OL-C18: best-effort reaches `emit` over a blocking gap; the lane does not take it."""
    ladder = load_default().model_copy(deep=True)
    ladder.profiles["CHAT_QUICK"].terminal = "human_profiles"
    # H1, which composes, plus a content word the core could not bind and gave no role: a
    # load-bearing G1 (P4.2·T6). The ask is spent, so best-effort emits over it.
    lattice = h1_lattice()
    lattice.mentions.append(Mention(id="m9", span=Span(start=40, end=45, text="xyzzy")))
    lattice.gaps.append(
        GapRecord(
            span=Span(start=40, end=45, text="xyzzy"), kind=GapKind.G1_UNBOUND, mention_id="m9"
        )
    )
    gateway, door = FakeGateway(reply(COVERED_SQL)), FakeDoor(ROWS)
    d = lane_deps(lattice, llm=gateway, door=door, model_slice=FakeSlice(), ladder=ladder)

    out = await run_turn(_state(lattice, hitl_rounds=1), d)

    assert isinstance(out, Answer), out  # today's best-effort answer, structural
    assert out.envelope is not None and out.envelope.sql == ""
    assert gateway.calls == [] and door.calls == []


# ------------------------------------------------------------------ T3 (a): covered


async def test_a_covered_lattice_is_one_call_and_the_checked_sql_runs_at_the_door() -> None:
    gateway, door, slicer = FakeGateway(reply(COVERED_SQL)), FakeDoor(ROWS), FakeSlice()

    out = await run_turn(
        _state(h1_lattice()), lane_deps(h1_lattice(), llm=gateway, door=door, model_slice=slicer)
    )

    assert isinstance(out, Answer), out
    assert len(gateway.calls) == 1
    assert gateway.calls[0]["timeout_s"] == 10.0  # the `capable` rung's clock
    assert door.calls == [
        {
            "sql": COVERED_SQL,
            "world": WORLD,
            "caller_subject": SUBJECT,  # the caller's row rules, not the Golem's
            "allowed_entities": (SALES, STORE),  # the slice's names
        }
    ]
    assert slicer.calls == [{"bound_refs": ["md.measure.revenue", "op:show"], "cap": 40}]
    envelope = out.envelope
    assert envelope is not None
    assert envelope.result is not None and envelope.result.rows == [{"revenue": 1234.5}]
    assert envelope.sql == COVERED_SQL
    assert envelope.provenance["lane"] == "llm"
    assert envelope.provenance["llm_model"] == "gw-capable-1"
    assert len(envelope.provenance["sql_sha256"]) == 64
    assert envelope.llm_handled == []
    assert out.llm_invocations == 1


# ------------------------------------------------------------------ T3 (b): the carried period


async def test_a_relative_period_the_core_carried_is_answered_through_the_lane() -> None:
    """THE recorded turn of the DONE line: "Zobraz tržby za minulý měsíc", the period left
    as a carried gap by the core, answered through the lane (fake gateway, fake door), with
    the SQL and `llm_handled` on the envelope — and `gaps_carried` still the core's account."""
    gateway = FakeGateway(reply(PERIOD_SQL, [[22, 34]], note="revenue in October 2026"))
    door = FakeDoor(ROWS)
    lattice = period_lattice()

    out = await run_turn(
        _state(lattice), lane_deps(lattice, llm=gateway, door=door, model_slice=FakeSlice())
    )

    prompt = str(gateway.calls[0]["prompt"])
    assert "'minulý měsíc' [22, 34] G4_METHOD_MISS; role: FILTER" in prompt
    assert "reference date (today, for relative periods): 2026-11-16" in prompt
    assert isinstance(out, Answer), out
    envelope = out.envelope
    assert envelope is not None
    assert envelope.sql == PERIOD_SQL and door.calls[0]["sql"] == PERIOD_SQL
    assert envelope.llm_handled == [PERIOD]
    assert [g.span for g in envelope.gaps_carried] == [PERIOD]  # the claim sits beside it
    assert [g.span for g in out.gaps_carried] == [PERIOD]
    assert envelope.result is not None and envelope.result.rows
    assert "llm handled: minulý měsíc" in out.content


# ------------------------------------------------------------------ T3 (c): one repair


async def test_a_door_rejection_buys_one_repair_call_carrying_the_doors_text() -> None:
    bad = f"SELECT SUM(s.amount) FROM {SALES} s GROUP BY s.nope"
    gateway = FakeGateway(reply(bad), reply(COVERED_SQL))
    door = FakeDoor(DoorRejected("translator_rejected", "unknown attribute nope"), ROWS)

    out = await run_turn(
        _state(h1_lattice()),
        lane_deps(h1_lattice(), llm=gateway, door=door, model_slice=FakeSlice()),
    )

    assert isinstance(out, Answer), out
    assert len(gateway.calls) == 2
    repair = str(gateway.calls[1]["prompt"])
    assert bad in repair and "translator_rejected: unknown attribute nope" in repair
    assert repair.startswith(str(gateway.calls[0]["prompt"]))
    assert [c["sql"] for c in door.calls] == [bad, COVERED_SQL]
    assert out.envelope is not None and out.envelope.sql == COVERED_SQL
    assert out.llm_invocations == 2


# ------------------------------------------------------------------ T3 (d): QUERY_NOT_WRITTEN


async def test_a_second_rejection_is_query_not_written_with_the_lattice_and_the_last_error() -> (
    None
):
    gateway = FakeGateway(reply(COVERED_SQL), reply(COVERED_SQL))
    door = FakeDoor(
        DoorRejected("translator_rejected", "first"),
        DoorRejected("validation_failed", "second"),
    )

    out = await run_turn(
        _state(h1_lattice()),
        lane_deps(h1_lattice(), llm=gateway, door=door, model_slice=FakeSlice()),
    )

    assert isinstance(out, RefusalWithGaps)
    assert out.reason == "QUERY_NOT_WRITTEN"
    assert "validation_failed: second" in out.explanation
    assert out.lattice.mentions  # "this much I understood"
    assert len(gateway.calls) == 2 and len(door.calls) == 2


@pytest.mark.parametrize(
    ("case", "text", "rule"),
    [
        ("an invalid object", "here is your query: SELECT 1", "invalid_object"),
        ("a name outside the slice", reply("SELECT 1 FROM er.entity.customer"), "outside_slice"),
        ("a non-SELECT", reply(f"DELETE FROM {SALES}"), "not_select"),
        (
            "an invented member",
            reply(f"SELECT 1 FROM {STORE} st WHERE st.id = 'Ostrava'"),
            "literal",
        ),
    ],
    ids=lambda v: v if isinstance(v, str) and " " not in v else None,
)
async def test_a_reply_that_breaks_a_rule_never_reaches_the_door(
    case: str, text: str, rule: str
) -> None:
    gateway, door = FakeGateway(text), FakeDoor(ROWS)

    out = await run_turn(
        _state(h1_lattice()),
        lane_deps(h1_lattice(), llm=gateway, door=door, model_slice=FakeSlice()),
    )

    assert isinstance(out, RefusalWithGaps), case
    assert out.reason == "QUERY_NOT_WRITTEN"
    assert rule in out.explanation
    assert door.calls == []  # never rows from a query the checks refused
    assert len(gateway.calls) == 1  # no repair: the door never saw it


async def test_a_spent_budget_is_query_not_written_and_calls_nobody() -> None:
    gateway, door = FakeGateway(reply(COVERED_SQL)), FakeDoor(ROWS)

    out = await run_turn(
        _state(h1_lattice(), llm_invocations=2),  # CHAT_QUICK's max_llm_invocations
        lane_deps(h1_lattice(), llm=gateway, door=door, model_slice=FakeSlice()),
    )

    assert isinstance(out, RefusalWithGaps)
    assert out.reason == "QUERY_NOT_WRITTEN" and "budget" in out.explanation
    assert gateway.calls == [] and door.calls == []


async def test_a_budget_spent_by_the_first_call_buys_no_repair() -> None:
    gateway = FakeGateway(reply(COVERED_SQL), reply(COVERED_SQL))
    door = FakeDoor(DoorRejected("translator_rejected", "no such column"), ROWS)

    out = await run_turn(
        _state(h1_lattice(), llm_invocations=1),
        lane_deps(h1_lattice(), llm=gateway, door=door, model_slice=FakeSlice()),
    )

    assert isinstance(out, RefusalWithGaps)
    assert out.reason == "QUERY_NOT_WRITTEN"
    assert "budget" in out.explanation and "no such column" in out.explanation
    assert len(gateway.calls) == 1 and len(door.calls) == 1


# ------------------------------------------------------------------ T3 (e): LANE_UNAVAILABLE


@pytest.mark.parametrize(
    "leg",
    ["gateway", "door", "meta", "no slice configured", "live gateway down", "live door down"],
    ids=lambda v: v.replace(" ", "-"),
)
async def test_an_unreachable_leg_is_lane_unavailable(leg: str) -> None:
    gateway: object = FakeGateway(reply(COVERED_SQL))
    door: object = FakeDoor(ROWS)
    slicer: object | None = FakeSlice()
    if leg == "gateway":
        gateway = FakeGateway(LaneServiceUnavailable(SERVICE, "HTTP 503"))
    elif leg == "door":
        door = FakeDoor(LaneServiceUnavailable("query.v1", "UNAVAILABLE"))
    elif leg == "meta":
        slicer = FakeSlice(LaneServiceUnavailable("meta.v1", "UNAVAILABLE"))
    elif leg == "no slice configured":
        slicer = None
    elif leg == "live gateway down":
        gateway = LiveLlmGateway(f"http://{unused_target()}/v1", api_key="ttrk-test")
    else:
        door = LiveSqlDoor(unused_target(), timeout_s=2.0)

    out = await run_turn(
        _state(h1_lattice()),
        lane_deps(h1_lattice(), llm=gateway, door=door, model_slice=slicer),
    )

    assert isinstance(out, RefusalWithGaps), out
    assert out.reason == "LANE_UNAVAILABLE"
    service = {"gateway": SERVICE, "live gateway down": SERVICE, "door": "query.v1"}
    assert service.get(leg, "meta.v1" if "slice" in leg or leg == "meta" else "query.v1") in (
        out.explanation
    )
    assert out.lattice.mentions
