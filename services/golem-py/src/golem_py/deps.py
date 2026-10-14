# SPDX-License-Identifier: Apache-2.0
"""What a run needs injected. Ports as Protocols, so a test can hand in a recorded
core and the graph cannot tell the difference (and, more importantly, so nothing in
the graph reaches for a channel it was not given).
"""

from __future__ import annotations

import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from typing import Protocol, runtime_checkable

from golem_py.compose import StructuredQuestion
from golem_py.ladder import LadderConfig, load_default
from golem_py.query_client import QueryResult
from golem_py.settings import GolemSettings
from golem_py.skills import LayeredSkillLibrary
from golem_py.snapshots import InMemorySnapshotStore, SnapshotStore
from golem_py.state import Binding, GapRecord, Hypothesis, ResolutionState, RungLogEntry


@runtime_checkable
class CorePort(Protocol):
    """`resolve.bind:v1` — text in, lattice out. ZERO LLM below this line."""

    async def resolve(
        self,
        *,
        question: str,
        locale: str = "cs",
        conversation_id: str = "",
        caller_subject: str = "",
    ) -> ResolutionState: ...


@runtime_checkable
class CoreResumePort(Protocol):
    """`Resolve(resume{token, selected_option_id})` — the core redeeming an option it signed.

    Only a `period` pin needs it (ttr-server#231): the answer is a lattice holding exactly one
    value, the picked date, grounded. A refused pick raises `PickRefused`."""

    async def resume(
        self,
        *,
        token: str,
        selected_option_id: str = "",
        free_text_answer: str = "",
        conversation_id: str = "",
        caller_subject: str = "",
    ) -> ResolutionState: ...


@dataclass
class GateResult:
    """What `resolve.gate:v1` gave back. `outcomes` is per-hypothesis, because a
    proposer that cannot learn which of its guesses were wrong will keep making them."""

    gated_bindings: list[Binding] = field(default_factory=list)
    updated_gaps: list[GapRecord] = field(default_factory=list)
    rung_log_entry: RungLogEntry | None = None
    outcomes: list[HypothesisOutcome] = field(default_factory=list)


@dataclass
class HypothesisOutcome:
    hypothesis: Hypothesis
    accepted: bool
    binding: Binding | None = None
    # NO_CANDIDATE | WEAK | AMBIGUOUS | NO_SPAN | REF_MISMATCH | LOOKUP_FAILED
    reason: str = ""

    def is_infrastructure_failure(self) -> bool:
        """`LOOKUP_FAILED` is not a verdict about the hypothesis — the matcher could
        not be ASKED. Retrying is safe (`Gate` is stateless/idempotent), and treating
        it as NO_CANDIDATE would teach a proposer that a correct guess does not exist.
        """
        return self.reason == "LOOKUP_FAILED"


@runtime_checkable
class GatePort(Protocol):
    """`resolve.gate:v1` — the ONLY way a hypothesis can become a binding (RV-7)."""

    async def gate(
        self, *, lattice: ResolutionState, hypotheses: Sequence[Hypothesis]
    ) -> GateResult: ...


@runtime_checkable
class QueryPort(Protocol):
    """The query door. Optional: with none configured the Golem still composes and
    answers structurally — which is what the conformance tier drives, and what the
    live drill will fill in when a door that takes a structured question exists."""

    async def run(
        self, *, question: StructuredQuestion, caller_subject: str = ""
    ) -> QueryResult: ...


@dataclass
class Deps:
    """Injected per run.

    `gate` is optional at construction but not at RESUME: a pin becomes a binding only
    by surviving `resolve.gate:v1` (RV-7), so a resume without one raises rather than
    honouring the pin locally.
    """

    core: CorePort
    gate: GatePort | None = None
    ladder: LadderConfig = field(default_factory=load_default)
    settings: GolemSettings = field(default_factory=GolemSettings)
    snapshots: SnapshotStore = field(default_factory=InMemorySnapshotStore)
    # The operator bodies (RV-35). Empty is legal and honest: an estate with no
    # compiled archive has no operators, so any question naming one refuses.
    skills: LayeredSkillLibrary = field(default_factory=LayeredSkillLibrary)
    query: QueryPort | None = None
    clock: Callable[[], float] = time.monotonic
