# SPDX-License-Identifier: Apache-2.0
"""All six PX documents through the real worker/pgmq path; no deployed services."""

from __future__ import annotations

import json
from collections.abc import Iterator
from uuid import uuid4

import psycopg
import pytest
import yaml

from docproc.bus.pgmq import DONE, PgmqBus, work_queue
from docproc.config import Config
from docproc.kinds import register_structural
from docproc.runtime.registry import KindRegistry, TypeRegistryClient
from docproc.runtime.runner import ProcessorRunner
from docproc.sdk.models import Annotation, Completion, CompletionStatus, Unit, payload_text
from tests.conftest import WORKER
from tests.fakes import PACK, FakeWarehouse, PackManager
from tests.fakes_nlp import recorded_nlp
from tests.pack import DOCS, bundle_of, context, units_of

pytestmark = pytest.mark.component


@pytest.fixture
def structural(pg_url: str) -> Iterator[tuple[ProcessorRunner, PackManager, FakeWarehouse]]:
    with psycopg.connect(pg_url) as conn:
        conn.execute((WORKER / "tests/fixtures/sql/run_artefacts.sql").read_text())
    warehouse = FakeWarehouse([])
    manager = PackManager()
    manager.processors = {
        k: p
        for k, p in manager.processors.items()
        if p.kind in ("chunk", "lemmatize", "import", "classify", "nlp-pipeline")
    }
    prefix = "s32-" + uuid4().hex[:8] + "-"
    for p in manager.processors.values():
        manifest = yaml.safe_load(p.manifest_yaml)
        p.family = prefix + p.family
        manifest["family"] = p.family
        p.manifest_yaml = yaml.safe_dump(manifest)
    manager.processors = {(p.family, p.version_hash): p for p in manager.processors.values()}
    bus = PgmqBus(pg_url)
    bus.ensure_queue(DONE)
    for p in manager.hosted_processors():
        for hi in (True, False):
            bus.ensure_queue(work_queue(p.family, hi))
    registry = TypeRegistryClient(manager)
    kinds = KindRegistry()
    with recorded_nlp() as nlp:
        register_structural(kinds, warehouse, manager, registry, nlp, str(PACK))
        runner = ProcessorRunner(
            Config("cpu", pg_url, "http://fake", "http://fake"), bus, warehouse, manager, kinds, registry
        )
        runner.bootstrap()
        yield runner, manager, warehouse
    bus.close()


def dispatch(runner: ProcessorRunner, manager: PackManager, doc: str, family: str, units: list[Unit]) -> Completion:
    processor = next(p for p in manager.hosted_processors() if p.family.endswith("-" + family))
    base = context(doc, family).item
    step = processor.family + "-" + doc
    item = base.model_copy(
        update={
            "family": processor.family,
            "kind": processor.kind,
            "version_hash": processor.version_hash,
            "step_id": step,
            "run_id": step + "/1",
            "unit_ids": [u.id for u in units],
        }
    )
    runner.bus.send(work_queue(item.family), item.dump())
    [message] = runner.bus.fetch(work_queue(item.family), 30)
    completion = runner.process(message)
    assert completion is not None and completion.status == CompletionStatus.SUCCEEDED, completion
    return completion


@pytest.mark.parametrize("doc", DOCS)
def test_mini_rag_worker_half(
    structural: tuple[ProcessorRunner, PackManager, FakeWarehouse], doc: str, pg_url: str
) -> None:
    runner, manager, warehouse = structural
    units = units_of(doc)
    warehouse.units = {u.id: u for u in units}
    warehouse.annotations = [
        Annotation(
            id=700 + u.idx,
            unit_id=u.id,
            type="Section",
            payload_json=payload_text({"title": u.metadata["title"].single or ""}),
            run_id="split",
        )
        for u in units[1:]
    ]
    completed = []
    for family in ("chunk-recursive", "chunk-sentence", "lemmatize"):
        result = dispatch(runner, manager, doc, family, units[1:])
        completed.append(result.run_id)
        assert result.usage.nlp_calls == (
            0 if family == "chunk-recursive" else len(units) - 1 if family == "chunk-sentence" else 1
        )
        if family.startswith("chunk"):
            outputs = [a for a in warehouse.annotations if a.id in result.annotation_ids]
            assert outputs and all(a.type == "Chunk" and a.spans for a in outputs)
            assert all(
                a.derived_from and not any(key in a.payload() for key in ("text", "contentText")) for a in outputs
            )
        else:
            assert not result.annotation_ids and set(warehouse.lemmas) == {u.id for u in units[1:]}
            assert all(v["lemmatizer"].startswith("stanza:") for v in warehouse.lemmas.values())
        with psycopg.connect(pg_url) as conn:
            [protocol] = conn.execute(
                "SELECT protocol FROM pipeline.run_artefacts WHERE run_id=%s", (result.run_id,)
            ).fetchone() or [None]
        assert protocol is not None
        nlp_section = next(s["nlpCalls"] for s in protocol["turns"][0]["sections"] if s["key"] == "nlp-calls")
        if result.usage.nlp_calls:
            assert nlp_section["used"] and all(ev["model"] for ev in nlp_section["used"])
        assert units[1].content_text not in json.dumps(protocol, ensure_ascii=False)
    done = []
    for msg in runner.bus.fetch(DONE, 30, qty=100):
        if msg.body["runId"] in completed:
            done.append(msg.body["runId"])
        runner.bus.ack(msg)
    assert sorted(done) == sorted(completed)


def test_import_runtime_keeps_connector_provenance_and_expands_document(
    structural: tuple[ProcessorRunner, PackManager, FakeWarehouse],
) -> None:
    runner, manager, warehouse = structural
    doc = "review-002.cs.json"
    units = units_of(doc)
    warehouse.units = {u.id: u for u in units}
    manager.bundles["bundle:" + doc] = bundle_of(doc, units)
    result = dispatch(runner, manager, doc, "import", units[:1])
    outputs = [a for a in warehouse.annotations if a.id in result.annotation_ids]
    assert len(outputs) == 2 and all(a.producer == "import:json-reviews@1" and not a.producer_version for a in outputs)
    assert all(a.unit_id in {u.id for u in units[1:]} and a.run_id == result.run_id for a in outputs)
    assert warehouse.generations[result.run_id]
