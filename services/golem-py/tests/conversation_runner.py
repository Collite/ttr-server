# SPDX-License-Identifier: Apache-2.0
"""Drive a conversation fixture through the service (P4.2·T6; graduates at P4.4).

The fixtures live at the REPO ROOT under `conformance/conversations/` — shared, because
a corpus two shells must pass cannot be owned by one of them (RV-28: one corpus, one
core, N shells). Their schema is `conformance/conversations/SCHEMA.md`: the P2 `calls:`
vocabulary, extended with the turn tools a Golem has and a door does not. A turn names a
LATTICE by golden id (`"lattice": "h2-cs"`), exactly the way the P2 gate fixtures do —
no lattice is duplicated anywhere.

⚑ **T3 ruling — this tier is HERMETIC, and that is a decision rather than a shortcut.**
The list asks for "live-ish: in-process gRPC where the P2 precedent used it". That
precedent is Kotlin driving Kotlin in one JVM; from Python there is no in-process option
— the core is a different runtime — so the choices were a recorded core or docker
compose. Recorded wins for a GATING tier: it is deterministic, it needs no images, and
it holds the shell to the core's own goldens, so a core change fails this suite the same
day it fails the Kotlin one. The live-stack run is T5's drill, recorded separately, and
it is the thing that would catch what a recording cannot.
"""

from __future__ import annotations

import json
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any

from golem_py.deps import Deps, GateResult, HypothesisOutcome
from golem_py.errors import DoorRejected, LaneServiceUnavailable
from golem_py.graph import run_turn
from golem_py.ladder import load_default
from golem_py.llm_client import LlmReply
from golem_py.model_slice import ModelSlice, SliceAttribute, SliceEntity, SliceRelation
from golem_py.outputs import TurnOutput
from golem_py.query_client import QueryResult
from golem_py.resume import resume_turn
from golem_py.settings import GolemSettings
from golem_py.snapshots import InMemorySnapshotStore
from golem_py.state import (
    Binding,
    Disposition,
    EvidenceClass,
    GapKind,
    GapRecord,
    Hypothesis,
    Pin,
    ResolutionState,
    RungLogEntry,
    SignedOption,
    SourceTag,
    Span,
    TargetClass,
)
from tests.helpers import RecordedCore, RecordedGate, fixture_library
from tests.lattice_fixtures import lattice_golden

# services/golem-py/tests → the repo root's shared corpus.
FIXTURE_DIR = Path(__file__).resolve().parents[3] / "conformance" / "conversations"
# The recorded model slices a `lane:` block names by id (OL W1c·S3).
SLICE_DIR = FIXTURE_DIR / "lane"


def fixture_ids() -> list[str]:
    return sorted(p.stem for p in FIXTURE_DIR.glob("*.json"))


def load_fixture(name: str) -> dict[str, Any]:
    data: dict[str, Any] = json.loads((FIXTURE_DIR / f"{name}.json").read_text(encoding="utf-8"))
    return data


def _span(raw: dict[str, Any]) -> Span:
    return Span(start=raw.get("start", 0), end=raw.get("end", 0), text=raw.get("text", ""))


def _strip(value: str, prefix: str) -> str:
    return value.removeprefix(prefix)


def _binding(raw: dict[str, Any]) -> Binding:
    """Fixture JSON → a Binding. Allowed here for the same reason the wire mapper is:
    this IS a recorded gate response, standing in for one the core produced."""
    return Binding(
        ref=raw["ref"],
        target_class=TargetClass(_strip(raw["target_class"], "TARGET_CLASS_")),
        evidence_class=EvidenceClass(_strip(raw["evidence_class"], "EVIDENCE_CLASS_")),
        source=SourceTag(_strip(raw["source"], "SOURCE_TAG_")),
        in_class_score=raw.get("in_class_score", 0.0),
    )


def _gap(raw: dict[str, Any]) -> GapRecord:
    return GapRecord(
        span=_span(raw.get("span", {})),
        kind=GapKind(_strip(raw["kind"], "GAP_KIND_")),
        value_id=raw.get("value_id", ""),
        mention_id=raw.get("mention_id", ""),
        disposition=Disposition(
            _strip(raw.get("disposition", "DISPOSITION_UNRESOLVED"), "DISPOSITION_")
        ),
    )


def build_core(turn: dict[str, Any]) -> RecordedCore:
    spec = turn["core"]
    lattice = lattice_golden(spec["lattice"])
    # `drop_bindings` is how H4 is expressed without a second golden: it IS H1's
    # lattice, minus the operator, because "Proč" is not one. Authoring a near-copy of
    # h1-cs would have put two files out of step the first time either moved.
    for dropped in spec.get("drop_bindings", []):
        for mention in lattice.mentions:
            mention.bindings = [b for b in mention.bindings if b.ref != dropped]
    lattice.resume_token = spec.get("resume_token", "")
    lattice.signed_options = [
        SignedOption(id=o["id"], label=o.get("label", ""), ref=o.get("ref", ""))
        for o in spec.get("options", [])
    ]
    return RecordedCore(lattice)


def build_gate(turn: dict[str, Any]) -> RecordedGate:
    spec = turn.get("gate")
    if spec is None:
        return RecordedGate()
    outcomes = [
        HypothesisOutcome(
            hypothesis=Hypothesis(
                span=_span(o["hypothesis"].get("span", {})),
                ref=o["hypothesis"].get("ref", ""),
                proposing_rung=o["hypothesis"].get("proposing_rung", ""),
            ),
            accepted=o["accepted"],
            binding=_binding(o["binding"]) if o.get("accepted") else None,
            reason=o.get("reason", ""),
        )
        for o in spec.get("outcomes", [])
    ]
    entry = spec.get("rung_log_entry")
    return RecordedGate(
        GateResult(
            gated_bindings=[o.binding for o in outcomes if o.binding is not None],
            updated_gaps=[_gap(g) for g in spec.get("updated_gaps", [])],
            rung_log_entry=RungLogEntry(**entry) if entry else None,
            outcomes=outcomes,
        )
    )


# ------------------------------------------------------------------ the lane's legs (OL W1c·S3)


class RecordedGateway:
    """`llm.v1` as recorded: each call takes the next reply, in order, and keeps its prompt.

    A reply is a JSON object the model wrote (serialised as the reply text), `{"raw": text}`
    for a text that is not one, or `{"unavailable": detail}` for a gateway that could not
    answer."""

    def __init__(self, spec: dict[str, Any]):
        self.model = spec.get("model", "")
        self.replies: list[dict[str, Any]] = list(spec.get("replies", []))
        self.prompts: list[str] = []

    async def complete_json(self, *, instructions: str, prompt: str, timeout_s: float) -> LlmReply:
        self.prompts.append(prompt)
        assert self.replies, "the lane called the gateway more often than the fixture recorded"
        reply = self.replies.pop(0)
        if "unavailable" in reply:
            raise LaneServiceUnavailable("llm.v1", reply["unavailable"])
        text = reply["raw"] if "raw" in reply else json.dumps(reply, ensure_ascii=False)
        return LlmReply(text=text, model=self.model)


@dataclass
class DoorCall:
    sql: str
    world: str
    caller_subject: str
    allowed_entities: tuple[str, ...]


class RecordedDoor:
    """`query.v1` as recorded: each call takes the next outcome — rows, a rejection (the
    door's code and text, verbatim), or `{"unavailable": detail}`."""

    def __init__(self, outcomes: Sequence[dict[str, Any]]):
        self.outcomes = list(outcomes)
        self.calls: list[DoorCall] = []

    async def run(
        self, *, sql: str, world: str, caller_subject: str, allowed_entities: Sequence[str]
    ) -> QueryResult:
        self.calls.append(DoorCall(sql, world, caller_subject, tuple(allowed_entities)))
        assert self.outcomes, "the lane called the door more often than the fixture recorded"
        outcome = self.outcomes.pop(0)
        if "rejected" in outcome:
            raise DoorRejected(outcome["rejected"]["code"], outcome["rejected"]["message"])
        if "unavailable" in outcome:
            raise LaneServiceUnavailable("query.v1", outcome["unavailable"])
        columns = list(outcome["columns"])
        return QueryResult(
            rows=[dict(zip(columns, row, strict=True)) for row in outcome["rows"]],
            columns=columns,
            provenance=dict(outcome.get("provenance", {})),
        )


def load_slice(name: str) -> ModelSlice:
    raw: dict[str, Any] = json.loads((SLICE_DIR / f"{name}.slice.json").read_text("utf-8"))
    return ModelSlice(
        packages=tuple(raw["packages"]),
        entities=tuple(
            SliceEntity(
                qname=e["qname"],
                description=e.get("description", ""),
                bound=e.get("bound", False),
                attributes=tuple(SliceAttribute(**a) for a in e.get("attributes", [])),
            )
            for e in raw["entities"]
        ),
        relations=tuple(
            SliceRelation(
                name=r["name"],
                from_entity=r["from_entity"],
                to_entity=r["to_entity"],
                join_pairs=tuple((a, b) for a, b in r.get("join_pairs", [])),
            )
            for r in raw.get("relations", [])
        ),
        omitted_entities=raw.get("omitted_entities", 0),
    )


class RecordedSlice:
    """`meta.v1` as recorded: one slice, whatever the bound refs."""

    def __init__(self, model_slice: ModelSlice):
        self.model_slice = model_slice
        self.calls = 0

    async def slice(self, *, bound_refs: Sequence[str], cap: int) -> ModelSlice:
        self.calls += 1
        return self.model_slice


@dataclass
class LaneLegs:
    """One turn's recorded lane: what it was given and what it was asked."""

    gateway: RecordedGateway
    door: RecordedDoor
    slice: RecordedSlice
    world: str
    reference_date: date

    def leftovers(self) -> list[str]:
        """Recordings the turn never consumed — a fixture that records a call the lane never
        makes would otherwise pass while saying something untrue."""
        out = [f"gateway reply {r}" for r in self.gateway.replies]
        out += [f"door outcome {o}" for o in self.door.outcomes]
        return out


def build_lane(turn: dict[str, Any]) -> LaneLegs | None:
    spec = turn.get("lane")
    if spec is None:
        return None
    return LaneLegs(
        gateway=RecordedGateway(spec.get("gateway", {})),
        door=RecordedDoor(spec.get("door", [])),
        slice=RecordedSlice(load_slice(spec["slice"])),
        world=spec["world"],
        reference_date=date.fromisoformat(spec["reference_date"]),
    )


class ConversationRun:
    """One fixture, driven end to end, keeping every turn's output for assertions."""

    def __init__(self, fixture: dict[str, Any]):
        self.fixture = fixture
        self.outputs: list[TurnOutput] = []
        self.core: RecordedCore | None = None
        self.gate: RecordedGate | None = None
        self.deps: Deps | None = None
        self.last_snapshot_id = ""
        # Gate calls counted PER TURN. A replayed resume legitimately re-gates — `Gate`
        # is stateless and idempotent by contract, which is precisely what makes an
        # at-least-once redelivery safe — so a cumulative count would read as a bug.
        self.gate_calls: list[int] = []
        self.gate_results: list[GateResult] = []
        # The hypotheses the GOLEM sent, per non-gate turn, aligned with `outputs`. A
        # fixture asserting `proposing_rung` on a resume is asserting RV-7 about the
        # user's pin — which is a claim about what we PROPOSED, not about what the
        # recorded gate happened to echo back.
        self.turn_hypotheses: list[list[Hypothesis]] = []
        # The recorded lane legs of each non-gate turn, aligned with `outputs` (None: lane off).
        self.lanes: list[LaneLegs | None] = []

    async def run(self) -> list[TurnOutput]:
        for index, turn in enumerate(self.fixture["turns"]):
            tool = turn["tool"]
            if tool == "golem.turn:v1":
                await self._turn(turn)
            elif tool == "golem.resume:v1":
                await self._resume(turn, index)
            elif tool == "resolve.gate:v1":
                await self._gate(turn)
            else:  # pragma: no cover - fixture authoring error
                raise AssertionError(f"unknown turn tool {tool!r}")
        return self.outputs

    async def _turn(self, turn: dict[str, Any]) -> None:
        self.core = build_core(turn)
        self.gate = build_gate(turn)
        self.deps = Deps(
            core=self.core,
            gate=self.gate,
            ladder=load_default(),
            snapshots=InMemorySnapshotStore(),
            skills=fixture_library(),
            clock=lambda: 1000.0,  # frozen: a byte-identical replay must not race a clock
        )
        # No `lane:` ⇒ no gateway and no door ⇒ the lane is off (OL-C11), which is how every
        # fixture written before W1c·S3 runs. With one, all three legs are the recordings.
        lane = build_lane(turn)
        if lane is not None:
            reference_date = lane.reference_date
            self.deps.settings = GolemSettings(query_world=lane.world)
            self.deps.llm = lane.gateway
            self.deps.door = lane.door
            self.deps.model_slice = lane.slice
            self.deps.reference_date = lambda: reference_date
        args = turn["args"]
        state = ResolutionState(
            question=args["text"],
            locale=args.get("locale", "cs"),
            conversation_id=args.get("conversation_id", ""),
            turn_id=args.get("turn_id", ""),
            caller_subject=args.get("caller_subject", ""),
            profile=args.get("profile", "CHAT_QUICK"),
        )
        before = self.gate.calls
        self.gate.last_hypotheses = []
        output = await run_turn(state, self.deps)
        self.outputs.append(output)
        self.lanes.append(lane)
        self.gate_calls.append(self.gate.calls - before)
        self.turn_hypotheses.append(list(self.gate.last_hypotheses))
        self.last_snapshot_id = getattr(output, "snapshot_id", "")

    async def _resume(self, turn: dict[str, Any], index: int) -> None:
        assert self.deps is not None, "a resume turn must follow a turn"
        if "gate" in turn:
            self.gate = build_gate(turn)
            self.deps.gate = self.gate
        pin_spec = turn.get("pin", {})
        pin = Pin(
            option_id=pin_spec.get("option_id", ""),
            free_text=pin_spec.get("free_text", ""),
            escape=pin_spec.get("escape", False),
        )
        assert self.gate is not None
        before = self.gate.calls
        self.gate.last_hypotheses = []
        output = await resume_turn(
            self.last_snapshot_id,
            pin,
            caller_subject=turn.get("caller_subject", ""),
            deps=self.deps,
        )
        self.outputs.append(output)
        self.lanes.append(None)
        self.gate_calls.append(self.gate.calls - before)
        self.turn_hypotheses.append(list(self.gate.last_hypotheses))
        # A resume that pauses AGAIN mints its own snapshot (the key carries the HITL
        # round), so the next resume must follow the new one. An answer or a refusal has
        # no snapshot id and must not clear the one a replay turn still needs.
        self.last_snapshot_id = getattr(output, "snapshot_id", "") or self.last_snapshot_id

    async def _gate(self, turn: dict[str, Any]) -> None:
        """A bare re-gate turn: hypotheses in, gated bindings out, folded into the
        lattice the PRIOR turn produced. Under the zero-rung default the OS Golem
        proposes nothing itself, so the hypotheses arrive from the fixture — the Kotlin
        shell (RV-P5) must reach the same outcome from its own local rung.
        """
        assert self.deps is not None and self.outputs, "a gate turn must follow a turn"
        from golem_py.gate_client import apply_gate_result

        gate = build_gate(turn)
        lattice = self.outputs[-1].lattice
        assert lattice is not None
        hypotheses = [
            Hypothesis(
                span=_span(h.get("span", {})),
                ref=h.get("ref", ""),
                correction=h.get("correction", ""),
                proposing_rung=h.get("proposing_rung", ""),
            )
            for h in turn.get("hypotheses", [])
        ]
        result = await gate.gate(lattice=lattice, hypotheses=hypotheses)
        apply_gate_result(lattice, result)
        self.gate_results.append(result)
        self.gate_calls.append(gate.calls)


async def drive(name: str) -> ConversationRun:
    run = ConversationRun(load_fixture(name))
    await run.run()
    return run
