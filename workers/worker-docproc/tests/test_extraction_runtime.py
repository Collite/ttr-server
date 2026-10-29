# SPDX-License-Identifier: Apache-2.0
"""The frozen PX deterministic flow, and a separately labelled complete-Statement row proof."""

from __future__ import annotations

import json
from collections.abc import Iterator
from uuid import uuid4

import psycopg
import pytest
import yaml
from psycopg.rows import dict_row

from docproc.bus.pgmq import DONE, PgmqBus, work_queue
from docproc.config import Config
from docproc.kinds import register_extraction, register_structural
from docproc.runtime.registry import KindRegistry, TypeRegistryClient
from docproc.runtime.runner import ProcessorRunner
from docproc.sdk.models import (
    Annotation,
    Completion,
    CompletionStatus,
    InputSelector,
    MetadataValue,
    RunVisibility,
    Unit,
    payload_text,
)
from tests.conftest import WORKER
from tests.extraction import complete_subject
from tests.fakes import PACK, FakeWarehouse, PackManager
from tests.fakes_lexmatcher import recorded_lexmatcher
from tests.fakes_nlp import recorded_nlp
from tests.pack import DOCS, bundle_of, context, units_of

pytestmark = pytest.mark.component


@pytest.fixture
def extraction(pg_url: str, world_db: str) -> Iterator[tuple[ProcessorRunner, PackManager, FakeWarehouse]]:
    with psycopg.connect(pg_url) as conn:
        conn.execute((WORKER / "tests/fixtures/sql/run_artefacts.sql").read_text())
    wh = FakeWarehouse([])
    manager = PackManager()
    prefix = "s33-" + uuid4().hex[:8] + "-"
    for p in manager.processors.values():
        m = yaml.safe_load(p.manifest_yaml)
        p.family = prefix + p.family
        m["family"] = p.family
        p.manifest_yaml = yaml.safe_dump(m)
    manager.processors = {(p.family, p.version_hash): p for p in manager.processors.values()}
    bus = PgmqBus(pg_url)
    bus.ensure_queue(DONE)
    for p in manager.hosted_processors():
        for hi in (True, False):
            bus.ensure_queue(work_queue(p.family, hi))
    reg = TypeRegistryClient(manager, str(PACK))
    kinds = KindRegistry()
    with recorded_nlp() as nlp, recorded_lexmatcher() as lex:
        register_structural(kinds, wh, manager, reg, nlp, str(PACK))
        register_extraction(kinds, wh, reg, lex, str(PACK), world_db)
        runner = ProcessorRunner(Config("cpu", pg_url, "http://fake", "http://fake"), bus, wh, manager, kinds, reg)
        runner.bootstrap()
        yield runner, manager, wh
    # The queue is shared by component fixtures. Remove only this fixture's completions.
    for message in bus.fetch(DONE, 30, qty=1000):
        if str(message.body.get("runId", "")).startswith(prefix):
            bus.ack(message)
        else:
            bus.extend(message, 0)
    bus.close()


def dispatch(
    runner: ProcessorRunner, manager: PackManager, doc: str, family: str, units: list[Unit], suffix: str = ""
) -> Completion:
    p = next(p for p in manager.hosted_processors() if p.family.endswith("-" + family))
    manifest = yaml.safe_load(p.manifest_yaml)
    item = context(doc, family).item
    step = p.family + "-" + doc + suffix
    item = item.model_copy(
        update={
            "family": p.family,
            "kind": p.kind,
            "version_hash": p.version_hash,
            "step_id": step,
            "run_id": step + "/1",
            "unit_ids": [u.id for u in units],
            "visibility": RunVisibility.ACTIVE,
            "consume": InputSelector(types=manifest["graph"]["consumes"], visibility="active"),
        }
    )
    runner.bus.send(work_queue(p.family), item.dump())
    [msg] = runner.bus.fetch(work_queue(p.family), 30)
    completion = runner.process(msg)
    assert completion and completion.status in (CompletionStatus.SUCCEEDED, CompletionStatus.ABSTAINED), completion
    return completion


@pytest.mark.parametrize("doc", DOCS)
def test_frozen_px_deterministic_flow_reports_incomplete_records(
    extraction: tuple[ProcessorRunner, PackManager, FakeWarehouse], doc: str, world_db: str
) -> None:
    runner, manager, wh = extraction
    units = units_of(doc)
    registration = json.loads((PACK / "registrations" / (doc.rsplit(".", 1)[0] + ".json")).read_text())
    units[0].metadata["subjectId"] = MetadataValue(single=registration["document"].get("subjectId"))
    wh.units = {u.id: u for u in units}
    wh.annotations = [
        Annotation(id=10 + n, unit_id=u.id, type="Section", payload_json=payload_text({"title": ""}))
        for n, u in enumerate(units[1:])
    ]
    manager.bundles["bundle:" + doc] = bundle_of(doc, units) if doc.startswith("review") else []
    dispatch(runner, manager, doc, "import", units[:1])
    dispatch(runner, manager, doc, "classify-sections", units[1:])
    dispatch(runner, manager, doc, "cz-mentions", units[1:])
    dispatch(runner, manager, doc, "link-mentions", units[1:])
    if doc.startswith("review"):
        normalized = dispatch(runner, manager, doc, "normalize-review", units[:1])
        sparse = [a for a in wh.annotations if a.id in normalized.annotation_ids and a.type == "Statement[review]"]
        assert len(sparse) == 2
        consolidated = dispatch(runner, manager, doc, "consolidate-review", units[:1])
        output = [a for a in wh.annotations if a.id in consolidated.annotation_ids]
        assert output and all(a.type == "Verdict" for a in output)
        assert all(a.payload()["rule"] == "consolidate/schema" for a in output)
    assert not any(a.type.startswith("Record.") for a in wh.annotations)
    for a in wh.annotations:
        assert set(a.derived_from) <= {a.id for a in wh.annotations}
    with psycopg.connect(world_db) as conn:
        assert conn.execute("SELECT count(*) FROM review").fetchone() == (0,)


@pytest.mark.parametrize("doc", ["review-001.en.json", "review-002.cs.json"])
def test_complete_statement_fixture_reaches_rows_with_resolvable_lineage(
    extraction: tuple[ProcessorRunner, PackManager, FakeWarehouse], doc: str, world_db: str, pg_url: str
) -> None:
    runner, manager, wh = extraction
    ctx, source = complete_subject(doc)
    wh.units, wh.annotations = source.units, source.annotations
    roots = [wh.units[uid] for uid in ctx.item.unit_ids]
    consolidated = dispatch(runner, manager, doc, "consolidate-review", roots)
    assert len([a for a in wh.annotations if a.id in consolidated.annotation_ids and a.type == "Record.review"]) == 2
    etl = dispatch(runner, manager, doc, "records-review", roots)
    assert not etl.annotation_ids
    rerun = dispatch(runner, manager, doc, "records-review", roots, "-rerun")
    with psycopg.connect(world_db, row_factory=dict_row) as conn:
        rows = conn.execute("SELECT * FROM review ORDER BY rating").fetchall()
        expected = json.loads((PACK / "golden/mini-gold-v1/records/review.rows.json").read_text())["rows"]
        subject = roots[0].metadata["subjectId"].single
        expected = sorted([r for r in expected if r["subject"] == subject], key=lambda r: r["rating"])
        assert len(rows) == len(expected) == 2
        for actual, want in zip(rows, expected, strict=True):
            assert {
                k: actual[k]
                for k in (
                    "item",
                    "customer",
                    "rating",
                    "title",
                    "body",
                    "sentiment",
                    "verification",
                    "temporal_context",
                )
            } == {
                k: want[k]
                for k in (
                    "item",
                    "customer",
                    "rating",
                    "title",
                    "body",
                    "sentiment",
                    "verification",
                    "temporal_context",
                )
            }
            assert actual["reviewed_on"].isoformat() == want["reviewed_on"]
            assert set(actual["annotation_refs"]) <= {a.id for a in wh.annotations}
        conn.execute("DELETE FROM review")
    with psycopg.connect(pg_url) as conn:
        docs = []
        for run in (etl, rerun):
            row = conn.execute("SELECT protocol FROM pipeline.run_artefacts WHERE run_id=%s", (run.run_id,)).fetchone()
            assert row is not None
            docs.append(row[0])
    outputs = [next(s["outputs"] for s in d["turns"][0]["sections"] if s["key"] == "outputs") for d in docs]
    assert outputs[0]["recordRows"] == outputs[1]["recordRows"]
    assert outputs[1]["recordDiff"] == {"added": [], "removed": [], "changed": []}
