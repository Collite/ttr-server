# SPDX-License-Identifier: Apache-2.0
"""Real pgmq/Postgres; fake warehouse and manager. No deployed environment."""

from __future__ import annotations

import json
import time
from collections.abc import Iterator
from dataclasses import replace
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from docproc.bus.pgmq import DONE, Message, PgmqBus, work_queue
from docproc.config import Config
from docproc.kinds.noop import Noop
from docproc.runtime.registry import KindRegistry, KindResult, RunContext, TypeRegistryClient
from docproc.runtime.runner import ProcessorRunner
from docproc.sdk.models import Annotation, Completion, CompletionStatus, Unit, WorkItem
from tests.conftest import CONTRACTS
from tests.fakes import FakeWarehouse, PackManager

pytestmark = pytest.mark.component


@pytest.fixture
def runtime(pg_url: str) -> Iterator[tuple[ProcessorRunner, WorkItem]]:
    with psycopg.connect(pg_url) as conn:
        conn.execute((Path(__file__).parent / "fixtures/sql/run_artefacts.sql").read_text())
    bus = PgmqBus(pg_url)
    bus.ensure_queue(DONE)
    manager = PackManager()
    base = next(p for p in manager.processors.values() if p.family == "classify-sections")
    family = "rt-" + uuid4().hex[:12]
    p = base.model_copy(
        update={
            "family": family,
            "manifest_yaml": base.manifest_yaml.replace("family: classify-sections", f"family: {family}"),
        }
    )
    manager.processors = {(family, p.version_hash): p}
    manager.extra_types = [
        {
            "key": "Verdict",
            "schema": {
                "type": "object",
                "required": ["rule", "ok"],
                "properties": {"rule": {"type": "string"}, "ok": {"type": "boolean"}, "message": {"type": "string"}},
            },
        }
    ]
    warehouse = FakeWarehouse(
        [Unit(id=101, content_text="🙂 text", content_hash="sha256:unit"), Unit(id=102, content_text="more")]
    )
    registry = TypeRegistryClient(manager)
    kinds = KindRegistry()
    kinds.register("classify", Noop())  # schema's classify manifest, test-only noop implementation
    cfg = Config("cpu", pg_url, "http://fake", "http://fake", families=(family,))
    runner = ProcessorRunner(cfg, bus, warehouse, manager, kinds, registry)
    runner.bootstrap()
    for hi in (False, True):
        bus.ensure_queue(work_queue(family, hi))
    item = WorkItem.strict((CONTRACTS / "workItem.json").read_text()).model_copy(
        update={
            "step_id": family,
            "run_id": f"{family}/1",
            "family": family,
            "version_hash": p.version_hash,
            "kind": "classify",
        }
    )
    yield runner, item
    bus.close()


def enqueue(runner: ProcessorRunner, item: WorkItem, hi: bool = False, vt: int = 30) -> Message:
    runner.bus.send(work_queue(item.family, hi), item.dump())
    return runner.bus.fetch(work_queue(item.family, hi), vt)[0]


def completions(runner: ProcessorRunner, item: WorkItem) -> list[Completion]:
    found = []
    for message in runner.bus.fetch(DONE, 30, qty=100):
        completion = Completion.model_validate(message.body)
        if completion.run_id == item.run_id:
            found.append(completion)
        runner.bus.ack(message)
    return found


def test_one_item_atomic_done_and_ack(runtime: tuple[ProcessorRunner, WorkItem], pg_url: str) -> None:
    runner, item = runtime
    message = enqueue(runner, item)
    result = runner.process(message)
    assert result is not None and result.status == CompletionStatus.SUCCEEDED
    warehouse = runner.warehouse
    assert isinstance(warehouse, FakeWarehouse)
    assert warehouse.loads == 1 and len(warehouse.annotations) == 2
    assert len(completions(runner, item)) == 1
    assert runner.bus.fetch(message.queue, 30) == []
    with psycopg.connect(pg_url) as conn:
        row = conn.execute("SELECT protocol FROM pipeline.run_artefacts WHERE run_id=%s", (item.run_id,)).fetchone()
    assert row and row[0]["scope"] == {"turnId": item.run_id}
    assert "🙂 text" not in json.dumps(row[0], ensure_ascii=False)
    assert runner.ready() and runner.status()["inFlight"] == []
    runner.refresh_metrics()


def test_crash_after_load_then_redelivery(runtime: tuple[ProcessorRunner, WorkItem]) -> None:
    runner, item = runtime
    message = enqueue(runner, item)

    def crash() -> None:
        raise RuntimeError("injected crash after warehouse commit")

    runner.after_load = crash
    with pytest.raises(RuntimeError, match="injected crash"):
        runner.process(message)
    assert completions(runner, item) == []
    runner.bus.extend(message, 0)
    [again] = runner.bus.fetch(message.queue, 30)
    assert again.read_ct == 2
    result = runner.process(again)
    assert result and result.status == CompletionStatus.SUCCEEDED
    assert isinstance(runner.warehouse, FakeWarehouse) and runner.warehouse.loads == 1
    assert len(completions(runner, item)) == 1


def test_unknown_visibility_archived_once(runtime: tuple[ProcessorRunner, WorkItem]) -> None:
    runner, item = runtime
    item.visibility = "RUN_VISIBILITY_UNSPECIFIED"  # type: ignore[assignment]
    message = enqueue(runner, item)
    assert runner.process(message) is None
    assert runner.bus.fetch(message.queue, 1) == [] and completions(runner, item) == []
    assert isinstance(runner.warehouse, FakeWarehouse) and runner.warehouse.loads == 0


class SlowKind:
    def run(self, ctx: RunContext) -> KindResult:
        time.sleep(5)
        return Noop().run(ctx)


def set_budget(runner: ProcessorRunner, item: WorkItem, seconds: int) -> None:
    manager = runner.manager
    assert isinstance(manager, PackManager)
    p = manager.processor(item.family, item.version_hash)
    import yaml

    manifest = yaml.safe_load(p.manifest_yaml)
    manifest["budget"]["timeout_s"] = seconds
    p.manifest_yaml = yaml.safe_dump(manifest)


def test_timeout_fails_and_acks_without_late_writes(runtime: tuple[ProcessorRunner, WorkItem]) -> None:
    runner, item = runtime
    runner.kinds.kinds["classify"] = SlowKind()
    set_budget(runner, item, 1)
    started = time.monotonic()
    result = runner.process(enqueue(runner, item))
    assert result and result.error == "timeout" and result.status == CompletionStatus.FAILED
    assert time.monotonic() - started < 3
    assert isinstance(runner.warehouse, FakeWarehouse) and runner.warehouse.loads == 0
    assert len(completions(runner, item)) == 1
    assert runner.bus.fetch(work_queue(item.family), 1) == []


def test_hi_first_and_shutdown_stops_fetches(runtime: tuple[ProcessorRunner, WorkItem]) -> None:
    runner, item = runtime
    hi = item.model_copy(update={"step_id": "hi-" + item.step_id, "run_id": f"hi-{item.step_id}/1", "priority": "hi"})
    runner.bus.send(work_queue(item.family), item.dump())
    runner.bus.send(work_queue(item.family, True), hi.dump())
    assert runner.poll_once() == 1
    assert len(completions(runner, hi)) == 1
    assert runner.bus.metrics(work_queue(item.family)).queue_length == 1
    runner.stopping.set()
    assert runner.poll_once() == 0 and not runner.ready()


def test_heartbeat_hides_three_second_lease_for_five_seconds(runtime: tuple[ProcessorRunner, WorkItem]) -> None:
    import threading

    runner, item = runtime
    runner.kinds.kinds["classify"] = SlowKind()
    set_budget(runner, item, 6)
    # Deliberately inject slack below production's non-negative bound: 6 - 3 = 3s VT,
    # so the heartbeat must renew while the kind spends five seconds running.
    runner.config = replace(runner.config, vt_slack_s=-3)
    message = enqueue(runner, item, vt=3)
    observed: list[Message] = []

    def competitor() -> None:
        time.sleep(3.5)
        bus = PgmqBus(runner.config.pipeline_db_url)
        try:
            observed.extend(bus.fetch(message.queue, 30))
        finally:
            bus.close()

    thread = threading.Thread(target=competitor)
    thread.start()
    try:
        result = runner.process(message)
    finally:
        thread.join()
    assert result and result.status == CompletionStatus.SUCCEEDED and observed == []


class BrokenKind:
    def run(self, ctx: RunContext) -> KindResult:
        raise ValueError("kind broke")


def test_kind_error_and_transaction_rollback(
    runtime: tuple[ProcessorRunner, WorkItem], monkeypatch: pytest.MonkeyPatch, pg_url: str
) -> None:
    runner, item = runtime
    runner.kinds.kinds["classify"] = BrokenKind()
    message = enqueue(runner, item)
    real_ack = runner.bus.ack
    monkeypatch.setattr(runner.bus, "ack", lambda *args, **kwargs: False)
    with pytest.raises(RuntimeError, match="vanished"):
        runner.process(message)
    with psycopg.connect(pg_url) as conn:
        assert conn.execute("SELECT 1 FROM pipeline.run_artefacts WHERE run_id=%s", (item.run_id,)).fetchone() is None
    assert completions(runner, item) == []
    monkeypatch.setattr(runner.bus, "ack", real_ack)
    runner.bus.extend(message, 0)
    result = runner.process(runner.bus.fetch(message.queue, 30)[0])
    assert result and result.error == "ValueError: kind broke" and result.status == CompletionStatus.FAILED
    assert len(completions(runner, item)) == 1


def test_retries_exhausted_archived_with_failed_completion(runtime: tuple[ProcessorRunner, WorkItem]) -> None:
    runner, item = runtime
    message = enqueue(runner, item)
    for _ in range(2):
        runner.bus.extend(message, 0)
        message = runner.bus.fetch(message.queue, 30)[0]
    assert message.read_ct == 3
    result = runner.process(message)
    assert result and result.error == "retries exhausted"
    assert result.status == CompletionStatus.FAILED
    assert len(completions(runner, item)) == 1


def test_run_span(runtime: tuple[ProcessorRunner, WorkItem], monkeypatch: pytest.MonkeyPatch) -> None:
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    monkeypatch.setattr("docproc.runtime.runner.trace.get_tracer", provider.get_tracer)
    runner, item = runtime
    runner.process(enqueue(runner, item))
    [span] = exporter.get_finished_spans()
    assert span.name == "docproc.run" and span.attributes
    assert span.attributes["family"] == item.family
    assert span.attributes["version_hash"] == item.version_hash and span.attributes["run_id"] == item.run_id
    provider.shutdown()


class CaptureInputs:
    def __init__(self) -> None:
        self.seen: list[Annotation] = []

    def run(self, ctx: RunContext) -> KindResult:
        self.seen = ctx.annotations
        return KindResult(abstained=True)


def test_generation_and_parametrized_selector(runtime: tuple[ProcessorRunner, WorkItem]) -> None:
    from docproc.sdk.models import InputSelector

    runner, item = runtime
    warehouse = runner.warehouse
    assert isinstance(warehouse, FakeWarehouse)
    warehouse.annotations = [
        Annotation(id=1, unit_id=101, run_id="active", type="Mention", payload_json='{"kind":"item"}'),
        Annotation(id=2, unit_id=101, run_id="candidate", type="Mention", payload_json='{"kind":"item"}'),
        Annotation(id=3, unit_id=101, run_id="other-generation", type="Mention", payload_json='{"kind":"item"}'),
        Annotation(id=4, unit_id=101, run_id="active", type="Mention", payload_json='{"kind":"person"}'),
    ]
    warehouse.visibility = {"active": "ACTIVE", "candidate": "CANDIDATE", "other-generation": "CANDIDATE"}
    warehouse.generations = {"candidate": item.generation, "other-generation": "other"}
    kind = CaptureInputs()
    runner.kinds.kinds["classify"] = kind
    item.consume = InputSelector(types=["Mention[item]"], visibility="active+generation")
    result = runner.process(enqueue(runner, item))
    assert result and result.status == CompletionStatus.ABSTAINED
    assert [a.id for a in kind.seen] == [1, 2]


def test_load_persists_generation(runtime: tuple[ProcessorRunner, WorkItem]) -> None:
    runner, item = runtime
    runner.process(enqueue(runner, item))
    warehouse = runner.warehouse
    assert isinstance(warehouse, FakeWarehouse)
    assert warehouse.generations[item.run_id] == item.generation


def test_duplicate_delivery_does_not_duplicate_done(runtime: tuple[ProcessorRunner, WorkItem]) -> None:
    runner, item = runtime
    runner.process(enqueue(runner, item))
    runner.process(enqueue(runner, item))
    assert len(completions(runner, item)) == 1
    assert isinstance(runner.warehouse, FakeWarehouse) and runner.warehouse.loads == 1


def test_unsupported_kind_never_succeeds(runtime: tuple[ProcessorRunner, WorkItem]) -> None:
    runner, item = runtime
    runner.kinds.kinds.clear()
    result = runner.process(enqueue(runner, item))
    assert result and result.status == CompletionStatus.FAILED and result.error and "kind/unsupported" in result.error
    assert isinstance(runner.warehouse, FakeWarehouse) and runner.warehouse.loads == 0


def test_plan_registry_mismatch_fails_before_kind(runtime: tuple[ProcessorRunner, WorkItem]) -> None:
    runner, item = runtime
    assert isinstance(runner.manager, PackManager)
    original = runner.manager.registry
    runner.manager.registry = lambda version: {**original(version), "version": "wrong"}  # type: ignore[method-assign]
    result = runner.process(enqueue(runner, item))
    assert result and result.status == CompletionStatus.FAILED and result.error == "registry/version-mismatch"
    assert isinstance(runner.warehouse, FakeWarehouse) and runner.warehouse.loads == 0
    assert len(completions(runner, item)) == 1
