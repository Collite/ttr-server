# SPDX-License-Identifier: Apache-2.0
"""Real pgmq/Postgres; fake warehouse and manager. No deployed environment."""

from __future__ import annotations

import json
import time
from collections.abc import Iterator
from dataclasses import replace
from pathlib import Path
from typing import Any, cast
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
    for _ in range(3):
        runner.bus.extend(message, 0)
        message = runner.bus.fetch(message.queue, 30)[0]
    assert message.read_ct == 4
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
    runner.registry.loaded = False
    original = runner.manager.registry
    runner.manager.registry = lambda version: {**original(version), "version": "wrong"}  # type: ignore[method-assign]
    result = runner.process(enqueue(runner, item))
    assert result and result.status == CompletionStatus.FAILED and result.error == "registry/version-mismatch"
    assert isinstance(runner.warehouse, FakeWarehouse) and runner.warehouse.loads == 0
    assert len(completions(runner, item)) == 1


def artefact(runner: ProcessorRunner, item: WorkItem) -> dict[str, Any]:
    with psycopg.connect(runner.config.pipeline_db_url) as conn:
        row = conn.execute("SELECT protocol FROM pipeline.run_artefacts WHERE run_id=%s", (item.run_id,)).fetchone()
    assert row
    return cast(dict[str, Any], row[0])


def section(runner: ProcessorRunner, item: WorkItem, key: str) -> dict[str, Any]:
    return next(s[key] for s in artefact(runner, item)["turns"][0]["sections"] if s["key"] == key)


def test_permanent_processor_failure_archived(
    runtime: tuple[ProcessorRunner, WorkItem], monkeypatch: pytest.MonkeyPatch
) -> None:
    from docproc.sdk.store import StoreError

    runner, item = runtime

    def missing(*args: Any) -> None:
        raise StoreError(404, "processor/missing", "SECRET body")

    monkeypatch.setattr(runner.manager, "processor", missing)
    monkeypatch.setattr(runner.warehouse, "iter_annotations", lambda *args: pytest.fail("store read"))
    message = enqueue(runner, item)
    result = runner.process(message)
    assert result is not None
    assert result.error == "processor/unavailable: 404 processor/missing"
    assert result is not None
    assert result.status == CompletionStatus.FAILED
    assert len(completions(runner, item)) == 1
    with psycopg.connect(runner.config.pipeline_db_url) as conn:
        assert conn.execute(f"SELECT count(*) FROM pgmq.a_{message.queue}").fetchone() == (1,)
    assert "SECRET" not in json.dumps(artefact(runner, item))


@pytest.mark.parametrize("failure", ["connect", "503"])
def test_transient_graph_failure_redelivers(
    runtime: tuple[ProcessorRunner, WorkItem], monkeypatch: pytest.MonkeyPatch, failure: str
) -> None:
    import httpx

    from docproc.sdk.store import StoreError

    runner, item = runtime
    original = runner.manager.graph

    def unavailable(*args: Any) -> None:
        if failure == "connect":
            raise httpx.ConnectError("SECRET")
        raise StoreError(503, "unavailable", "SECRET")

    monkeypatch.setattr(runner.manager, "graph", unavailable)
    message = enqueue(runner, item)
    with pytest.raises((httpx.ConnectError, StoreError)):
        runner.process(message)
    assert completions(runner, item) == []
    with psycopg.connect(runner.config.pipeline_db_url) as conn:
        assert conn.execute("SELECT 1 FROM pipeline.run_artefacts WHERE run_id=%s", (item.run_id,)).fetchone() is None
    assert runner.bus.fetch(message.queue, 30) == []  # remains leased
    monkeypatch.setattr(runner.manager, "graph", original)
    runner.bus.extend(message, 0)
    result = runner.process(runner.bus.fetch(message.queue, 30)[0])
    assert result is not None
    assert result.status == CompletionStatus.SUCCEEDED


def test_store_refusal_finishes_once(
    runtime: tuple[ProcessorRunner, WorkItem], monkeypatch: pytest.MonkeyPatch
) -> None:
    from docproc.sdk.store import SchemaRejected

    runner, item = runtime
    calls = []

    class Counted:
        def run(self, ctx: RunContext) -> KindResult:
            calls.append(1)
            return Noop().run(ctx)

    runner.kinds.kinds["classify"] = Counted()

    def refused(*args: Any) -> None:
        raise SchemaRejected(422, "annotation/schema", "SECRET")

    monkeypatch.setattr(runner.warehouse, "load_annotations", refused)
    message = enqueue(runner, item)
    result = runner.process(message)
    assert result is not None
    assert result.status == CompletionStatus.FAILED and result.error == "store/annotation/schema"
    assert calls == [1] and len(completions(runner, item)) == 1
    assert runner.bus.metrics(message.queue).queue_length == 0


def test_delivery_limit_before_manager(
    runtime: tuple[ProcessorRunner, WorkItem], monkeypatch: pytest.MonkeyPatch
) -> None:
    runner, item = runtime
    monkeypatch.setattr(runner.manager, "processor", lambda *args: pytest.fail("manager called"))
    message = replace(enqueue(runner, item), read_ct=runner.config.max_deliveries + 1)
    result = runner.process(message)
    assert result is not None
    assert result.status == CompletionStatus.FAILED and result.error == "deliveries exhausted"
    assert len(completions(runner, item)) == 1


def test_bootstrap_v2_and_registry_cached(
    runtime: tuple[ProcessorRunner, WorkItem], monkeypatch: pytest.MonkeyPatch
) -> None:
    from tests.fakes import PACK

    runner, item = runtime
    calls, verifies = [], []
    original = runner.manager.registry

    def v2(version: str) -> dict[str, Any]:
        calls.append(version)
        assert version == "v2"
        return original(version)

    monkeypatch.setattr(runner.manager, "registry", v2)
    monkeypatch.setattr(runner.manager, "graph", lambda plan: {"registryVersion": "v2"})
    from docproc.runtime.model_hash import verify_pack

    def counted(pack: Path) -> None:
        verifies.append(1)
        verify_pack(pack)

    monkeypatch.setattr("docproc.runtime.model_hash.verify_pack", counted)
    runner.registry = TypeRegistryClient(runner.manager, str(PACK))
    runner.validators.registry = runner.registry
    runner.bootstrap()
    assert runner.ready() and calls == []
    result = runner.process(enqueue(runner, item))
    assert result is not None and result.status == CompletionStatus.SUCCEEDED
    second = item.model_copy(update={"run_id": item.step_id + "-second/1", "step_id": item.step_id + "-second"})
    result = runner.process(enqueue(runner, second))
    assert result is not None and result.status == CompletionStatus.SUCCEEDED
    assert calls == ["v2"] and verifies == [1]


@pytest.mark.parametrize("retries", [0, 2])
def test_retry_budget_counts_initial_attempt(
    runtime: tuple[ProcessorRunner, WorkItem], monkeypatch: pytest.MonkeyPatch, retries: int
) -> None:
    import yaml

    from docproc.sdk.store import StoreError

    runner, item = runtime
    processor = runner.manager.processor(item.family, item.version_hash)
    manifest = yaml.safe_load(processor.manifest_yaml)
    manifest["budget"]["retries"] = retries
    processor.manifest_yaml = yaml.safe_dump(manifest)
    calls = []

    class Counted:
        def run(self, ctx: RunContext) -> KindResult:
            calls.append(1)
            return Noop().run(ctx)

    runner.kinds.kinds["classify"] = Counted()

    def unavailable(*args: Any) -> None:
        raise StoreError(503, "outage", "")

    monkeypatch.setattr(runner.warehouse, "load_annotations", unavailable)
    message = enqueue(runner, item)
    for _ in range(retries + 1):
        with pytest.raises(StoreError):
            runner.process(message)
        runner.bus.extend(message, 0)
        message = runner.bus.fetch(message.queue, 30)[0]
    result = runner.process(message)
    assert result is not None
    assert result.error == "retries exhausted" and len(calls) == retries + 1
    assert len(completions(runner, item)) == 1


def test_poll_and_metrics_isolate_family_failures(
    runtime: tuple[ProcessorRunner, WorkItem], monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    runner, item = runtime
    p = runner.processors[0]
    broken = p.model_copy(update={"family": "broken", "manifest_yaml": p.manifest_yaml.replace(p.family, "broken")})
    runner.processors.insert(0, broken)
    original = runner.bus.fetch_prefer_hi

    def fetch(family: str, *args: Any, **kwargs: Any) -> list[Message]:
        if family == "broken":
            raise RuntimeError("SECRET")
        return original(family, *args, **kwargs)

    monkeypatch.setattr(runner.bus, "fetch_prefer_hi", fetch)
    enqueue(runner, item)  # release the lease for poll_once
    with psycopg.connect(runner.config.pipeline_db_url) as conn:
        conn.execute(f"UPDATE pgmq.q_{work_queue(item.family)} SET vt=now()")
    assert runner.poll_once() == 1 and len(completions(runner, item)) == 1
    runner.refresh_metrics()
    assert "RuntimeError" in caplog.text and "SECRET" not in caplog.text


def test_missing_hi_lane_reads_normal(runtime: tuple[ProcessorRunner, WorkItem]) -> None:
    runner, item = runtime
    with psycopg.connect(runner.config.pipeline_db_url) as conn:
        conn.execute("SELECT pgmq.drop_queue(%s)", (work_queue(item.family, True),))
    runner.bus.send(work_queue(item.family), item.dump())
    assert runner.poll_once() == 1 and len(completions(runner, item)) == 1


@pytest.mark.parametrize(
    "type_,payload,status",
    [
        ("Decision", {"decider": "link/rule", "score": 0}, CompletionStatus.ABSTAINED),
        ("Abstention", {"reason": "unknown"}, CompletionStatus.ABSTAINED),
        ("Verdict", {"rule": "record-etl/row", "ok": False}, CompletionStatus.SUCCEEDED),
    ],
)
def test_recovery_marks_lost_sections(
    runtime: tuple[ProcessorRunner, WorkItem], type_: str, payload: dict[str, Any], status: CompletionStatus
) -> None:
    from docproc.sdk.models import payload_text

    runner, item = runtime
    assert isinstance(runner.warehouse, FakeWarehouse)
    runner.warehouse.annotations = [
        Annotation(id=55, unit_id=101, run_id=item.run_id, type=type_, payload_json=payload_text(payload))
    ]
    result = runner.process(enqueue(runner, item))
    assert result is not None
    assert result.status == status
    assert section(runner, item, "errors")["recovery"] == "sections-lost"


def test_timeout_repeats_after_swallowed_signal(runtime: tuple[ProcessorRunner, WorkItem]) -> None:
    runner, item = runtime

    class SwallowOnce:
        def run(self, ctx: RunContext) -> KindResult:
            try:
                time.sleep(5)
            except BaseException:
                pass
            time.sleep(5)
            return KindResult()

    runner.kinds.kinds["classify"] = SwallowOnce()
    set_budget(runner, item, 1)
    started = time.monotonic()
    result = runner.process(enqueue(runner, item))
    assert result is not None
    assert result.error == "timeout" and time.monotonic() - started < 3


@pytest.mark.component
def test_record_etl_refusal_fails_without_loading(runtime: tuple[ProcessorRunner, WorkItem], world_db: str) -> None:
    import yaml

    from docproc.sdk.models import InputSelector, RunVisibility, payload_text
    from tests.kinds.test_record_etl import prepared_etl
    from tests.pack import context

    runner, item = runtime
    etl, ctx, wh = prepared_etl(world_db)
    etl.run(ctx)
    with psycopg.connect(world_db) as conn:
        before = conn.execute("SELECT * FROM review ORDER BY row_id").fetchall()
    original = runner.manager.processor(item.family, item.version_hash)
    manifest = context("review-001.en.json", "records-review").manifest
    manifest = {**manifest, "family": item.family}
    processor = original.model_copy(update={"kind": "record-etl", "manifest_yaml": yaml.safe_dump(manifest)})
    assert isinstance(runner.manager, PackManager)
    runner.manager.processors = {(item.family, item.version_hash): processor}
    runner.warehouse = wh
    runner.kinds.register("record-etl", etl)
    a = ctx.annotations[0]
    p = a.payload()
    p["attributes"]["customer"] = {"qname": "unknown"}
    a.payload_json = payload_text(p)
    wh.annotations = [a if other.id == a.id else other for other in wh.annotations]
    item = item.model_copy(
        update={
            "kind": "record-etl",
            "visibility": RunVisibility.ACTIVE,
            "unit_ids": ctx.item.unit_ids,
            "consume": InputSelector(types=["Record.review"], visibility="active"),
        }
    )
    try:
        result = runner.process(enqueue(runner, item))
        assert result is not None
        assert result.status == CompletionStatus.FAILED and result.error == "record-etl/rows-rejected: 1 records"
        assert wh.loads == 0
        assert section(runner, item, "errors")["diagnostics"] == [
            {"annotationId": a.id, "rule": "record-etl/unresolved-ref"}
        ]
        assert len(completions(runner, item)) == 1
        with psycopg.connect(world_db) as conn:
            assert conn.execute("SELECT * FROM review ORDER BY row_id").fetchall() == before
        # The same rejected record in CANDIDATE emits a Verdict and succeeds without world writes.
        item = item.model_copy(
            update={
                "step_id": item.step_id + "-candidate",
                "run_id": item.step_id + "-candidate/1",
                "visibility": RunVisibility.CANDIDATE,
            }
        )

        def crash() -> None:
            raise RuntimeError("crash after candidate load")

        runner.after_load = crash
        message = enqueue(runner, item)
        with pytest.raises(RuntimeError, match="crash after candidate load"):
            runner.process(message)
        runner.bus.extend(message, 0)
        result = runner.process(runner.bus.fetch(message.queue, 30)[0])
        assert section(runner, item, "errors")["recovery"] == "sections-lost"
        assert result is not None
        assert result.status == CompletionStatus.SUCCEEDED and wh.loads == 1 and result.verdict_ids
        with psycopg.connect(world_db) as conn:
            assert conn.execute("SELECT * FROM review ORDER BY row_id").fetchall() == before
    finally:
        with psycopg.connect(world_db) as conn:
            conn.execute("DELETE FROM review")


def test_unknown_plan_registry_is_permanent(
    runtime: tuple[ProcessorRunner, WorkItem], monkeypatch: pytest.MonkeyPatch
) -> None:
    from docproc.sdk.store import StoreError

    runner, item = runtime

    def missing(version: str) -> dict[str, Any]:
        raise StoreError(404, "registry/not-found", "SECRET")

    monkeypatch.setattr(runner.manager, "registry", missing)
    result = runner.process(enqueue(runner, item))
    assert result and result.status == CompletionStatus.FAILED and result.error == "store/registry/not-found"
    assert len(completions(runner, item)) == 1


@pytest.mark.parametrize(
    ("annotations", "expected"),
    [
        ([("Decision", {"decider": "link/rule"})], CompletionStatus.ABSTAINED),
        ([("Abstention", {})], CompletionStatus.ABSTAINED),
        ([("Verdict", {}), ("Decision", {"decider": "normalize/conflicts"})], CompletionStatus.SUCCEEDED),
        ([("Mention", {}), ("Decision", {"decider": "link/rule", "chosen": "item"})], CompletionStatus.SUCCEEDED),
    ],
)
def test_recovered_status_deciders(annotations: list[tuple[str, dict[str, Any]]], expected: CompletionStatus) -> None:
    from docproc.runtime.runner import recovered_status

    existing = [Annotation(type=kind, payload_json=json.dumps(payload)) for kind, payload in annotations]
    assert recovered_status(existing) == expected
