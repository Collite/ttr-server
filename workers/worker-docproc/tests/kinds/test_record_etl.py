# SPDX-License-Identifier: Apache-2.0
import json
from dataclasses import replace
from decimal import Decimal
from typing import Any

import psycopg
import pytest
from psycopg.rows import dict_row

from docproc.kinds.consolidate import Consolidator
from docproc.kinds.record_etl import RecordEtl, identifier, scalar
from docproc.runtime.registry import RunContext
from docproc.sdk.models import RunVisibility, payload_text
from tests.conftest import WORKER
from tests.extraction import complete_subject, registry
from tests.fakes import PACK, FakeWarehouse
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
    try:
        with pytest.raises(RuntimeError, match="record-etl/database-failed"):
            etl.run(replace(candidate, item=candidate.item.model_copy(update={"visibility": RunVisibility.ACTIVE})))
        with psycopg.connect(world_db, row_factory=dict_row) as conn:
            assert conn.execute("SELECT * FROM review ORDER BY row_id").fetchall() == before
    finally:
        with psycopg.connect(world_db) as conn:
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


def prepared_etl(world_db: str) -> tuple[RecordEtl, RunContext, FakeWarehouse]:
    reg = registry()
    ctx, wh = complete_subject()
    result = Consolidator(wh, reg, str(PACK)).consolidate(ctx)
    for n, a in enumerate(result.annotations):
        a.id, a.run_id = 500 + n, ctx.item.run_id
    wh.annotations.extend(result.annotations)
    base = context("review-001.en.json", "records-review")
    etlctx = replace(
        base,
        units=ctx.units,
        annotations=[a for a in result.annotations if a.type == "Record.review"],
        item=base.item.model_copy(update={"visibility": RunVisibility.ACTIVE}),
    )
    return RecordEtl(wh, reg, str(PACK), world_db), etlctx, wh


@pytest.mark.component
def test_active_fail_closed_candidate_retains_rows(world_db: str) -> None:
    from docproc.runtime.registry import KindRefused

    etl, ctx, wh = prepared_etl(world_db)
    etl.run(ctx)
    with psycopg.connect(world_db, row_factory=dict_row) as conn:
        before = conn.execute("SELECT * FROM review ORDER BY row_id").fetchall()
    a = ctx.annotations[0].model_copy(deep=True)
    p = a.payload()
    p["attributes"]["customer"] = {"qname": "unknown"}
    a.payload_json = payload_text(p)
    bad = replace(ctx, annotations=[a, ctx.annotations[1]])
    try:
        with pytest.raises(KindRefused, match="record-etl/rows-rejected: 1 records") as caught:
            etl.run(bad)
        assert caught.value.diagnostics == [{"annotationId": a.id, "rule": "record-etl/unresolved-ref"}]
        candidate = etl.run(replace(bad, item=bad.item.model_copy(update={"visibility": RunVisibility.CANDIDATE})))
        assert len(candidate.verdicts) == 1 and len(candidate.sections["outputs"]["candidateRows"]) == 1
        with psycopg.connect(world_db, row_factory=dict_row) as conn:
            assert conn.execute("SELECT * FROM review ORDER BY row_id").fetchall() == before
        assert not etl.run(ctx).verdicts
    finally:
        with psycopg.connect(world_db) as conn:
            conn.execute("DELETE FROM review")


@pytest.mark.component
def test_known_id_is_scoped_by_subject(world_db: str) -> None:
    from docproc.sdk.models import MetadataValue, UnitKind

    etl, ctx, wh = prepared_etl(world_db)
    first = etl.run(ctx).sections["outputs"]["recordRows"]
    roots = {id_: u.model_copy(deep=True) for id_, u in ctx.units.items()}
    for u in roots.values():
        if u.kind == UnitKind.DOCUMENT:
            u.metadata["subjectId"] = MetadataValue(single="other-subject")
    for d in list(wh.annotations):
        if d.type == "Decision" and d.payload().get("decider") == "consolidate/key":
            d = d.model_copy(deep=True)
            p = d.payload()
            p["factors"]["subjectId"] = "other-subject"
            d.payload_json = payload_text(p)
            wh.annotations.append(d)
    other = replace(ctx, units=roots)
    try:
        second = etl.run(other).sections["outputs"]["recordRows"]
        assert {r["rowId"] for r in first}.isdisjoint(r["rowId"] for r in second)
        assert etl.run(ctx).sections["outputs"]["recordRows"] == first
        assert etl.run(other).sections["outputs"]["recordRows"] == second
        with psycopg.connect(world_db) as conn:
            assert conn.execute("SELECT count(*) FROM review").fetchone() == (4,)
    finally:
        with psycopg.connect(world_db) as conn:
            conn.execute("DELETE FROM review")


@pytest.mark.component
def test_diff_ignores_representation_and_provenance(world_db: str, monkeypatch: pytest.MonkeyPatch) -> None:
    from datetime import datetime, timedelta, timezone

    etl, ctx, wh = prepared_etl(world_db)
    a = ctx.annotations[0].model_copy(deep=True)
    p = a.payload()
    p["attributes"]["price_paid"] = 59.9
    a.payload_json = payload_text(p)
    ctx = replace(ctx, annotations=[a, ctx.annotations[1]])
    # Use the existing optional decimal field in the frozen review model/mapping.
    schema = etl.registry.schema_for("Record.review").schema
    assert isinstance(schema, dict)
    assert "price_paid" in schema["properties"]["attributes"]["properties"]
    etl.run(ctx)
    original = psycopg.connect

    def prague(*args: Any, **kwargs: Any) -> psycopg.Connection[Any]:
        conn = original(*args, **kwargs)
        conn.execute("SET TimeZone TO 'Europe/Prague'")
        return conn

    monkeypatch.setattr(psycopg, "connect", prague)
    new = []
    for a in ctx.annotations:
        a = a.model_copy(deep=True)
        a.id += 900
        p = a.payload()
        at = datetime.fromisoformat(p["attributes"]["recorded_at"].replace("Z", "+00:00"))
        p["attributes"]["recorded_at"] = at.astimezone(timezone(timedelta(hours=2))).isoformat()
        a.payload_json = payload_text(p)
        new.append(a)
    candidate = replace(ctx, annotations=new, item=ctx.item.model_copy(update={"visibility": RunVisibility.CANDIDATE}))
    try:
        assert etl.run(candidate).sections["outputs"]["recordDiff"]["changed"] == []
        p = new[0].payload()
        p["attributes"]["rating"] = 3
        new[0].payload_json = payload_text(p)
        assert len(etl.run(candidate).sections["outputs"]["recordDiff"]["changed"]) == 1
    finally:
        with psycopg.connect(world_db) as conn:
            conn.execute("DELETE FROM review")


@pytest.mark.parametrize(
    "value,schema,code",
    [
        ("SECRET", {"format": "date"}, "invalid-date"),
        ("SECRET", {"format": "date-time"}, "invalid-date-time"),
        ("SECRET", {"x-ttr-scale": 2}, "invalid-decimal"),
    ],
)
def test_scalar_errors_are_redacted(value: str, schema: dict[str, Any], code: str) -> None:
    with pytest.raises(ValueError, match=f"^record-etl/{code}$"):
        scalar(value, schema)


@pytest.mark.component
def test_invalid_date_time_verdict_redacts_value(world_db: str, monkeypatch: pytest.MonkeyPatch) -> None:
    etl, ctx, _ = prepared_etl(world_db)
    validator = etl.registry.schema_for("Record.review")
    monkeypatch.setattr(type(validator), "validate", lambda self, value: None)
    a = ctx.annotations[0].model_copy(deep=True)
    p = a.payload()
    p["attributes"]["recorded_at"] = "SECRET"
    a.payload_json = payload_text(p)
    result = etl.run(
        replace(ctx, annotations=[a], item=ctx.item.model_copy(update={"visibility": RunVisibility.CANDIDATE}))
    )
    assert result.verdicts[0].payload()["message"] == "record-etl/invalid-date-time"
    assert "SECRET" not in result.verdicts[0].payload_json
