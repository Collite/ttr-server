# SPDX-License-Identifier: Apache-2.0
"""What a turn can END as. Three outcomes, and none of them is an error.

* `Ask` — the pause (RV-17). Carries the CORE's signed `resume_token` (RS-26) and OUR
  snapshot id, side by side and never merged: signing the option set agent-side would
  let the agent fabricate "the user chose X".
* `Answer` — a completed turn, with the lattice and any carried gap notes attached.
* `RefusalWithGaps` — the honest refusal (H4). "I understood you completely; I cannot
  do this." The lattice rides along, which is what makes it a refusal rather than a
  failure.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

from golem_py.query_client import AnswerEnvelope
from golem_py.state import GapKind, GapRecord, ResolutionState

# OL-C16 adds the LLM lane's two: `QUERY_NOT_WRITTEN` (budget spent, a second door
# rejection, an invalid object or a broken rule) and `LANE_UNAVAILABLE` (a leg of the lane
# could not be reached). Both carry the lattice and the last error as `explanation`.
RefusalReason = Literal[
    "NO_CAPABLE_PLUGIN", "UNRESOLVED_GAPS", "QUERY_NOT_WRITTEN", "LANE_UNAVAILABLE"
]


class AskOption(BaseModel):
    """One of the options the CORE signed into the resume token. `id` is what travels
    back — never the label, and never a ref the agent made up."""

    id: str
    label: str = ""
    ref: str = ""


class Ask(BaseModel):
    question: str
    gap_kind: GapKind
    options: list[AskOption] = Field(default_factory=list)
    # The core's, opaque here. Empty when the core issued none — which is honest: an
    # ask about a G1 the core never clarified has no signed option set behind it.
    resume_token: str = ""
    # Ours. The state lives in the snapshot store under this id (P4.2); the id travels,
    # the state does not.
    snapshot_id: str = ""
    # The escape hatch, always offered (RV-15): a user who cannot answer must be able
    # to say so, and "none of these" is a legitimate answer that closes the gap as
    # USER_CONFIRMED_UNKNOWN rather than leaving it open forever.
    escape: str = "none of these"
    lattice: ResolutionState | None = None


class Answer(BaseModel):
    model_config = {"arbitrary_types_allowed": True}

    content: str
    # contracts §6: {content, formatting_directives?, provenance, gaps_carried[]}.
    envelope: AnswerEnvelope | None = None
    lattice: ResolutionState
    llm_invocations: int = 0
    asks: int = 0
    # RV-19: gaps an answer carried rather than blocked on (DEGRADED / IGNORED notes).
    gaps_carried: list[GapRecord] = Field(default_factory=list)


class RefusalWithGaps(BaseModel):
    reason: RefusalReason
    gaps: list[GapRecord]
    lattice: ResolutionState
    # What it CAN do, when it can do anything: the operators it DOES hold bodies for.
    # Structured, not apology prose — "I cannot investigate causes" is a capability
    # statement, and an apology is not one.
    composable_residue: list[str] = Field(default_factory=list)
    # Why compose refused, in the vocabulary of the predicate (an unknown `op:`, an
    # unsatisfied `requires:`, nothing to select). A caller acts on these differently.
    # For the lane's refusals: the last error (the broken rule, the door's text, the leg
    # that was unreachable).
    explanation: str = ""


TurnOutput = Ask | Answer | RefusalWithGaps
