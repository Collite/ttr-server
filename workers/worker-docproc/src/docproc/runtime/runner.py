# SPDX-License-Identifier: Apache-2.0
"""Synchronous consumer: leased work, atomic artefact/done/ack, load-before-ack recovery.

Kinds run on the main thread. A POSIX interval timer interrupts synchronous Python
and blocking I/O on timeout; no abandoned executor thread can write after FAILED.
"""

from __future__ import annotations

import logging
import signal
import threading
import time
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from typing import Any

from opentelemetry import trace
from psycopg.types.json import Jsonb

from docproc.bus.pgmq import DONE, Message, PgmqBus, work_queue
from docproc.config import Config
from docproc.probes import duration, queue_age, queue_depth, runs, verdicts
from docproc.runtime.failures import diagnostic, transient
from docproc.runtime.registry import KindRefused, KindRegistry, RunContext, TypeRegistryClient
from docproc.runtime.validators import Validators
from docproc.sdk.models import (
    Annotation,
    Completion,
    CompletionStatus,
    ListAnnotationsRequest,
    LoadAnnotationsRequest,
    Processor,
    WorkItem,
    unspecified_fields,
)
from docproc.sdk.pipeline import Manager
from docproc.sdk.protocol import ProtocolWriter
from docproc.sdk.store import StoreError, Warehouse

log = logging.getLogger(__name__)


class RunTimeout(BaseException):
    """A control-flow signal; ordinary kind error handlers must not swallow it."""


@contextmanager
def time_budget(seconds: float) -> Iterator[None]:
    if threading.current_thread() is not threading.main_thread():
        raise RuntimeError("processor kinds must run on the main thread")

    def expired(signum: int, frame: Any) -> None:
        raise RunTimeout()

    previous = signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, seconds, 1.0)
    try:
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


class ProcessorRunner:
    def __init__(
        self,
        config: Config,
        bus: PgmqBus,
        warehouse: Warehouse,
        manager: Manager,
        kinds: KindRegistry,
        registry: TypeRegistryClient,
        *,
        after_load: Callable[[], None] = lambda: None,
    ) -> None:
        self.config, self.bus, self.warehouse, self.manager = config, bus, warehouse, manager
        self.kinds, self.registry = kinds, registry
        self.validators = Validators(registry)
        self.writer = ProtocolWriter()
        self.after_load = after_load
        self.stopping = threading.Event()
        self.in_flight: set[str] = set()
        self.processors: list[Processor] = []
        self.bootstrapped = False

    def bootstrap(self) -> None:
        hosted = self.manager.hosted_processors()
        self.processors = [p for p in hosted if not self.config.families or p.family in self.config.families]
        missing = set(self.config.families) - {p.family for p in self.processors}
        if missing:
            raise ValueError(f"hosted families not found: {sorted(missing)}")
        self.registry.verify_pack()
        for processor in self.processors:
            self.kinds.manifest(processor)
        self.bootstrapped = True

    def ready(self) -> bool:
        return not self.stopping.is_set() and self.bootstrapped and self.bus.ready()

    def status(self) -> dict[str, Any]:
        return {
            "pool": self.config.pool,
            "families": [p.family for p in self.processors],
            "queues": self.queues(),
            "inFlight": sorted(self.in_flight),
            "kinds": sorted(self.kinds.kinds),
        }

    def queues(self) -> list[str]:
        return [work_queue(p.family, hi=hi) for p in self.processors for hi in (True, False)]

    def refresh_metrics(self) -> None:
        for queue in self.queues():
            try:
                m = self.bus.metrics(queue)
                queue_depth.labels(queue).set(m.queue_length)
                queue_age.labels(queue).set(m.oldest_msg_age_sec or 0)
            except Exception as exc:
                log.warning("queue metrics interrupted queue=%s %s", queue, diagnostic(exc))

    @contextmanager
    def heartbeat(self, msg: Message, vt: int) -> Iterator[None]:
        stop = threading.Event()
        lost: list[BaseException] = []

        def beat() -> None:
            while not stop.wait(vt / 3):
                try:
                    if not self.bus.extend(msg, vt):
                        raise RuntimeError("work lease disappeared")
                except Exception as exc:
                    lost.append(exc)
                    return

        thread = threading.Thread(target=beat, name="docproc-heartbeat", daemon=True)
        thread.start()
        try:
            yield
            if lost:
                raise RuntimeError("work lease heartbeat failed") from lost[0]
        finally:
            stop.set()
            thread.join()

    def poll_once(self) -> int:
        count = 0
        for processor in self.processors:
            try:
                manifest = self.kinds.manifest(processor)
                vt = int(manifest["budget"]["timeout_s"]) + self.config.vt_slack_s
                # Lease one at a time: the batch is the family's per-poll quota.
                for _ in range(self.config.batch):
                    if self.stopping.is_set():
                        return count
                    messages = self.bus.fetch_prefer_hi(processor.family, max(vt, 1), qty=1)
                    if not messages:
                        break
                    self.process(messages[0])
                    count += 1
            except Exception as exc:
                log.warning("family poll interrupted family=%s %s", processor.family, diagnostic(exc))
        return count

    def run(self) -> None:
        while not self.stopping.is_set():
            try:
                if self.poll_once() == 0:
                    self.stopping.wait(self.config.idle_ms / 1000)
            except Exception as exc:
                # Infra failures leave leases unacked; pgmq redelivers. Never log bodies/credentials.
                log.warning("consumer attempt interrupted; work remains leased %s", diagnostic(exc))
                self.stopping.wait(self.config.idle_ms / 1000)

    def process(self, msg: Message) -> Completion | None:
        try:
            item = WorkItem.model_validate(msg.body)
            expected_queues = {work_queue(item.family), work_queue(item.family, True)}
        except ValueError:
            log.warning("invalid work item on %s", msg.queue)
            self.bus.fail(msg, retries=0)
            return None
        unknown = unspecified_fields(item)
        if unknown:
            log.warning("unsupported work fields: %s", ", ".join(unknown))
            self.bus.fail(msg, retries=0)
            return None
        # Never dispatch a payload to a different family's queue/host.
        if msg.queue not in expected_queues:
            self.bus.fail(msg, retries=0)
            return None
        self.in_flight.add(item.run_id)
        started = time.monotonic()
        try:
            with trace.get_tracer("docproc").start_as_current_span(
                "docproc.run",
                attributes={
                    "family": item.family,
                    "version_hash": item.version_hash,
                    "run_id": item.run_id,
                },
            ):
                return self._process(msg, item, started)
        finally:
            duration.labels(item.family).observe(time.monotonic() - started)
            self.in_flight.discard(item.run_id)

    def _process(self, msg: Message, item: WorkItem, started: float) -> Completion:
        completion = Completion(run_id=item.run_id, step_id=item.step_id)
        sections: dict[str, Any] = {}
        ctx = RunContext(item, None, {}, {}, [])
        if msg.read_ct > self.config.max_deliveries:
            completion.status, completion.error = CompletionStatus.FAILED, "deliveries exhausted"
            self._complete(msg, ctx, completion, archive=True)
            return completion
        try:
            processor = self.manager.processor(item.family, item.version_hash)
        except Exception as exc:
            if transient(exc):
                raise
            detail = f"{exc.status} {exc.code}" if isinstance(exc, StoreError) else type(exc).__name__
            completion.status, completion.error = CompletionStatus.FAILED, f"processor/unavailable: {detail}"
            self._complete(msg, ctx, completion, archive=True)
            return completion
        ctx = RunContext(item, processor, {}, {}, [])
        try:
            manifest = self.kinds.manifest(processor)
            graph = self.manager.graph(item.plan_id)
            version = graph.get("registryVersion")
            if not isinstance(version, str) or not version:
                raise ValueError("registry/graph-version-required")
            self.registry.load(version)
            if item.kind != processor.kind:
                raise ValueError("kind/unsupported: work kind differs from processor")
        except Exception as exc:
            if transient(exc):
                raise
            error = f"store/{exc.code}" if isinstance(exc, StoreError) else str(exc)
            completion.status, completion.error = CompletionStatus.FAILED, error
            self._complete(msg, ctx, completion)
            return completion
        vt = max(1, int(manifest["budget"]["timeout_s"]) + self.config.vt_slack_s)
        self.bus.extend(msg, vt)
        with self.heartbeat(msg, vt):
            existing = list(
                self.warehouse.iter_annotations(ListAnnotationsRequest(run_id=item.run_id, active_only=False))
            )
            if existing:
                completion.status = (
                    CompletionStatus.ABSTAINED
                    if all(a.type in ("Abstention", "Decision", "Verdict") for a in existing)
                    and any(
                        a.type == "Abstention" or a.type == "Decision" and "chosen" not in a.payload() for a in existing
                    )
                    else CompletionStatus.SUCCEEDED
                )
                sections = {"errors": {"recovery": "sections-lost"}}
                completion.annotation_ids = [a.id for a in existing]
                completion.verdict_ids = [str(a.id) for a in existing if a.type == "Verdict"]
            elif msg.read_ct > (int(manifest["budget"]["retries"]) + 1):
                completion.status, completion.error = CompletionStatus.FAILED, "retries exhausted"
            else:
                units = {uid: self.warehouse.get_unit(uid) for uid in item.unit_ids}
                if item.kind in ("import", "diff") or manifest["graph"]["unit"] in ("document", "subject"):
                    for source_id in {u.source_id for u in list(units.values())}:
                        units.update((u.id, u) for u in self.warehouse.units_of_source(source_id))
                annotations: list[Annotation] = []
                if item.consume.visibility not in ("active", "active+generation"):
                    completion.status, completion.error = CompletionStatus.FAILED, "unsupported consume visibility"
                    self._complete(msg, ctx, completion)
                    return completion
                selectors = [self.registry.selector(key) for key in item.consume.types]
                for uid in units:
                    candidates = self.warehouse.iter_annotations(
                        ListAnnotationsRequest(
                            unit_id=uid,
                            types=list(dict.fromkeys(key for key, _ in selectors)),
                            generation=item.generation if item.consume.visibility == "active+generation" else None,
                        )
                    )
                    annotations.extend(
                        a
                        for a in candidates
                        if not selectors
                        or any(
                            a.type == key and (kind is None or a.payload().get("kind") == kind)
                            for key, kind in selectors
                        )
                    )
                ctx = RunContext(item, processor, manifest, units, annotations)
                try:
                    with time_budget(float(manifest["budget"]["timeout_s"])):
                        result = self.kinds.kinds[item.kind].run(ctx)
                    sections = result.sections
                    annotations_to_validate = result.annotations + result.verdicts
                    conditions = manifest.get("post_conditions", [])
                    chunk_diagnostics: list[Annotation] = []
                    if item.kind == "chunk":
                        annotations_to_validate, chunk_diagnostics = self.validators.validate_chunks(
                            annotations_to_validate, units, manifest["engine"], result.chunk_params
                        )
                        conditions = [
                            c for c in conditions if c not in ("coverage", "overlap_within_declared", "max_tokens")
                        ]
                    output, diagnostics = self.validators.validate(annotations_to_validate, units, conditions)
                    diagnostics += chunk_diagnostics
                    output += diagnostics
                    for a in output:
                        a.run_id = item.run_id
                        if item.kind != "import" or not a.producer.startswith("import:"):
                            a.producer, a.producer_version = item.family, item.version_hash
                    only_abstentions = bool(output) and all(a.type == "Abstention" for a in output)
                    completion.status = (
                        CompletionStatus.ABSTAINED
                        if only_abstentions
                        or (result.abstained and not any(a.type not in ("Decision", "Verdict") for a in output))
                        else CompletionStatus.SUCCEEDED
                    )
                    completion.usage = result.usage
                    completion.child_unit_ids = result.child_unit_ids
                except KindRefused as exc:
                    output = []
                    completion.status, completion.error = CompletionStatus.FAILED, str(exc)
                    sections = {"errors": {"diagnostics": exc.diagnostics}}
                except RunTimeout:
                    output = []
                    completion.status, completion.error = CompletionStatus.FAILED, "timeout"
                except Exception as exc:
                    output = []
                    completion.status = CompletionStatus.FAILED
                    completion.error = f"{type(exc).__name__}: {exc}"
                if output:
                    try:
                        loaded = self.warehouse.load_annotations(
                            LoadAnnotationsRequest(
                                run_id=item.run_id,
                                annotations=output,
                                visibility=item.visibility,
                                generation=item.generation or None,
                            )
                        )
                    except Exception as exc:
                        if transient(exc):
                            raise
                        error = f"store/{exc.code}" if isinstance(exc, StoreError) else type(exc).__name__
                        completion.status, completion.error = CompletionStatus.FAILED, error
                        self._complete(msg, ctx, completion, sections=sections)
                        return completion
                    completion.annotation_ids = loaded.ids
                    completion.verdict_ids = [
                        str(uid) for uid, a in zip(loaded.ids, output, strict=True) if a.type == "Verdict"
                    ]
                    for a in output:
                        if a.type == "Verdict" and not a.payload().get("ok", True):
                            verdicts.labels(item.family, str(a.payload().get("rule", ""))).inc()
                    self.after_load()  # fault injection: outside kind error handling, leaves message unacked
        completion.usage.latency_ms = int((time.monotonic() - started) * 1000)
        self._complete(
            msg,
            ctx,
            completion,
            archive=msg.read_ct > (int(manifest["budget"]["retries"]) + 1) and not existing,
            sections=sections,
        )
        return completion

    def _complete(
        self,
        msg: Message,
        ctx: RunContext,
        completion: Completion,
        archive: bool = False,
        sections: dict[str, Any] | None = None,
    ) -> None:
        completion.artefact_ref = f"pipeline://run_artefacts/{ctx.item.run_id}"
        document = self.writer.document(ctx, completion, sections)
        with self.bus.transaction() as conn:
            inserted = conn.execute(
                "INSERT INTO pipeline.run_artefacts(run_id, protocol) VALUES (%s, %s) "
                "ON CONFLICT (run_id) DO NOTHING RETURNING run_id",
                (ctx.item.run_id, Jsonb(document)),
            ).fetchone()
            if inserted:
                self.bus.send(DONE, completion.dump(), conn=conn)
            if archive:
                self.bus.fail(msg, retries=0, conn=conn)
            elif not self.bus.ack(msg, conn=conn):
                raise RuntimeError("work message vanished before completion")
        runs.labels(ctx.item.family, completion.status.value).inc()
