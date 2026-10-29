# SPDX-License-Identifier: Apache-2.0
import json
from dataclasses import replace
from decimal import Decimal

import psycopg
import pytest
from psycopg.rows import dict_row

from docproc.kinds.consolidate import Consolidator
from docproc.kinds.record_etl import RecordEtl, identifier, scalar
from docproc.sdk.models import RunVisibility, payload_text
from tests.conftest import WORKER
from tests.extraction import complete_subject, registry
from tests.fakes import PACK
from tests.pack import context


def test_exact_scale_and_safe_identifiers() -> None:
    assert scalar("1.20", {"x-ttr-scale": 2}) == Decimal("1.20")
    for number in ("1.201", "NaN", "Infinity"):
        with pytest.raises(ValueError):
            scalar(number, {"x-ttr-scale": 2})
    with pytest.raises(ValueError):
        identifier("review; DROP TABLE item")
    with pytest.raises(ValueError):
        scalar("2026-10-29", {"format": "date-time"})


@pytest.mark.component
def test_rows_rerun_candidate_and_rollback(world_db: str) -> None:
    reg = registry()
    ctx, wh = complete_subject()
    consolidated = Consolidator(wh, reg, str(PACK)).consolidate(ctx)
    for n, a in enumerate(consolidated.annotations):
        a.id, a.run_id = 500 + n, ctx.item.run_id
    wh.annotations.extend(consolidated.annotations)
    base = context("review-001.en.json", "records-review")
    etlctx = replace(
        base, units=ctx.units, annotations=[a for a in consolidated.annotations if a.type == "Record.review"]
    )
    etlctx = replace(etlctx, item=etlctx.item.model_copy(update={"visibility": RunVisibility.ACTIVE}))
    etl = RecordEtl(wh, reg, str(PACK), world_db)
    first = etl.run(etlctx)
    assert not first.verdicts and len(first.sections["outputs"]["recordRows"]) == 2
    assert first.sections["outputs"]["recordRows"] == json.loads(
        (WORKER / "tests/goldens/record_etl/review-001.en.json.json").read_text()
    )
    with psycopg.connect(world_db, row_factory=dict_row) as conn:
        initial = conn.execute("SELECT * FROM review ORDER BY row_id").fetchall()
    assert all(set(r["annotation_refs"]) <= {a.id for a in wh.annotations} for r in initial)
    assert {r["rating"] for r in initial} == {2, 5}
    assert all(r["subject_id"] == "HL-1001" for r in initial)
    second = etl.run(etlctx)
    assert second.sections["outputs"]["recordDiff"] == {"added": [], "removed": [], "changed": []}
    assert first.sections["outputs"]["recordRows"] == second.sections["outputs"]["recordRows"]
    # Candidate computes changed data, stores it only in the artefact, leaves ACTIVE rows byte-identical.
    a = etlctx.annotations[0].model_copy(deep=True)
    payload = a.payload()
    payload["attributes"]["rating"] = 3
    a.payload_json = payload_text(payload)
    candidate = replace(
        etlctx,
        annotations=[a, etlctx.annotations[1]],
        item=etlctx.item.model_copy(update={"visibility": RunVisibility.CANDIDATE}),
    )
    with psycopg.connect(world_db, row_factory=dict_row) as conn:
        before = conn.execute("SELECT * FROM review ORDER BY row_id").fetchall()
    planned = etl.run(candidate)
    assert planned.sections["outputs"]["recordDiff"]["changed"]
    assert planned.sections["outputs"]["candidateRows"]
    with psycopg.connect(world_db, row_factory=dict_row) as conn:
        assert conn.execute("SELECT * FROM review ORDER BY row_id").fetchall() == before
    # A second row insert fails after the subject delete and first insert: the transaction restores everything.
    with psycopg.connect(world_db) as conn:
        conn.execute("ALTER TABLE review ADD CONSTRAINT test_reject_rating CHECK (rating <> 3)")
    with pytest.raises(RuntimeError, match="record-etl/database-failed"):
        etl.run(replace(candidate, item=candidate.item.model_copy(update={"visibility": RunVisibility.ACTIVE})))
    with psycopg.connect(world_db, row_factory=dict_row) as conn:
        assert conn.execute("SELECT * FROM review ORDER BY row_id").fetchall() == before
        conn.execute("ALTER TABLE review DROP CONSTRAINT test_reject_rating")
        conn.execute("DELETE FROM review")


@pytest.mark.component
def test_feeds_records_and_decimal_rejection(world_db: str) -> None:
    ctx, wh = complete_subject()
    reg = registry()
    base = context("review-001.en.json", "records-review")
    etlctx = replace(base, units=ctx.units, annotations=[])
    reg.entries["Record.review"]["feedsRecords"] = False
    with pytest.raises(ValueError, match="feeds-records-required"):
        RecordEtl(wh, reg, str(PACK), world_db).run(etlctx)
