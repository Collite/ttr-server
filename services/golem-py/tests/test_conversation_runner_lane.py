# SPDX-License-Identifier: Apache-2.0
"""review-343 T1 — a resume after a lane turn runs with ITS OWN lane, or with none.

The h2 conversation (ask → pin → resume) with a `lane:` block on the first turn. The first
turn asks (a blocking gap), so its lane is never called. The resume reaches `emit`, where the
lane would answer if it were on. Before the fix, the resume inherited the first turn's legs
and ran with the lane on while the runner recorded it as off.
"""

from __future__ import annotations

import copy
from typing import Any

from golem_py.outputs import Answer, Ask
from tests.conversation_runner import ConversationRun, load_fixture

SQL = (
    "SELECT a.code, SUM(e.amount) AS naklady FROM er.entity.cost_entry e "
    "JOIN er.entity.account a ON e.account_id = a.account_id GROUP BY a.code"
)


def _lane(replies: list[dict[str, Any]], door: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "slice": "cost-ledger",
        "world": "hartland_cz",
        "reference_date": "2026-11-16",
        "gateway": {"model": "capable-fixture", "replies": replies},
        "door": door,
    }


def _h2(resume_lane: dict[str, Any] | None) -> dict[str, Any]:
    fixture = copy.deepcopy(load_fixture("h2-ask-pin-resume"))
    turns = [t for t in fixture["turns"] if "replay_of" not in t][:2]
    turns[0]["lane"] = _lane([], [])
    if resume_lane is not None:
        turns[1]["lane"] = resume_lane
    fixture["turns"] = turns
    return fixture


async def test_a_resume_without_a_lane_block_runs_with_the_lane_off() -> None:
    run = ConversationRun(_h2(resume_lane=None))
    outputs = await run.run()

    first, resumed = outputs
    assert isinstance(first, Ask) and isinstance(resumed, Answer)
    lane = run.lanes[0]
    assert lane is not None and lane.gateway.prompts == [] and lane.door.calls == []
    assert run.lanes[1] is None
    assert resumed.envelope is not None and resumed.envelope.sql == ""  # answered structurally
    assert "lane" not in resumed.envelope.provenance


async def test_a_resume_with_its_own_lane_block_answers_through_it() -> None:
    door = [{"columns": ["code", "naklady"], "rows": [["501001", 10.0]]}]
    run = ConversationRun(_h2(resume_lane=_lane([{"sql": SQL, "handled_spans": []}], door)))
    outputs = await run.run()

    resumed = outputs[1]
    assert isinstance(resumed, Answer) and resumed.envelope is not None
    first_lane, resume_lane = run.lanes
    assert first_lane is not None and first_lane.gateway.prompts == []
    assert resume_lane is not None and resume_lane is not first_lane
    assert len(resume_lane.gateway.prompts) == 1 and resume_lane.leftovers() == []
    assert resume_lane.door.calls[0].caller_subject == "user-a"
    assert resumed.envelope.sql == SQL and resumed.envelope.provenance["lane"] == "llm"
