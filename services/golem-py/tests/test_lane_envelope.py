# SPDX-License-Identifier: Apache-2.0
"""OL W1c·S2·T7 — the envelope of an answer the lane wrote (OL-C17)."""

from __future__ import annotations

import hashlib

from golem_py.compose import StructuredQuestion
from golem_py.query_client import QueryResult, build_envelope, build_lane_envelope
from golem_py.state import GapKind, GapRecord, LexiconVersions, ResolutionState, Span

PERIOD = Span(start=22, end=34, text="minulý měsíc")
SQL = "SELECT SUM(s.amount) AS revenue FROM er.entity.store_sales s"


def _state() -> ResolutionState:
    return ResolutionState(
        trace_id="t-1", lexicon_versions=LexiconVersions(lexicon_artifact_hash="sha256:lex")
    )


def test_the_lane_adds_its_provenance_the_sql_and_the_claim_beside_gaps_carried() -> None:
    carried = [GapRecord(span=PERIOD, kind=GapKind.G4_METHOD_MISS)]
    result = QueryResult(rows=[{"revenue": 10}], columns=["revenue"], provenance={"x": "y"})
    envelope = build_lane_envelope(
        state=_state(),
        question=StructuredQuestion(measures=["md.measure.revenue"]),
        result=result,
        gaps_carried=carried,
        sql=SQL,
        llm_model="gpt-x",
        llm_handled=[PERIOD],
    )

    assert envelope.provenance["lane"] == "llm"
    assert envelope.provenance["llm_model"] == "gpt-x"
    assert envelope.provenance["sql_sha256"] == hashlib.sha256(SQL.encode()).hexdigest()
    # §6's provenance is kept, the door's with it.
    assert envelope.provenance["lexicon_artifact_hash"] == "sha256:lex"
    assert envelope.provenance["x"] == "y"
    assert envelope.sql == SQL
    assert envelope.llm_handled == [PERIOD]
    # The core's account is unchanged: the claim sits beside it, it does not close it.
    assert envelope.gaps_carried == carried
    assert envelope.result is result
    assert "rows: 1" in envelope.content and "llm handled: minulý měsíc" in envelope.content


def test_a_structural_answer_carries_no_lane_fields() -> None:
    envelope = build_envelope(
        state=_state(), question=StructuredQuestion(), result=None, gaps_carried=[]
    )
    assert "lane" not in envelope.provenance
    assert envelope.sql == ""
    assert envelope.llm_handled == []
