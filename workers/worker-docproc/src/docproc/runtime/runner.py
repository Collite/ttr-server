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
from docproc.runtime.registry import KindRegistry, RunContext, TypeRegistryClient
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
from docproc.sdk.store import Warehouse

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
    signal.setitimer(signal.ITIMER_REAL, seconds)
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

    def bootstrap(self) -> None:
        hosted = self.manager.hosted_processors()
        self.processors = [p for p in hosted if not self.config.families or p.family in self.config.families]
        missing = set(self.config.families) - {p.family for p in self.processors}
        if missing:
            raise ValueError(f"hosted families not found: {sorted(missing)}")
        # A single loaded registry version is the frozen v1 seam; pack version negotiation is P2's responsibility.
        self.registry.load("1")
        for processor in self.processors:
            self.kinds.manifest(processor)

    def ready(self) -> bool:
        return not self.stopping.is_set() and self.registry.loaded and self.bus.ready()

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
            m = self.bus.metrics(queue)
            queue_depth.labels(queue).set(m.queue_length)
            queue_age.labels(queue).set(m.oldest_msg_age_sec or 0)

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
            manifest = self.kinds.manifest(processor)
            vt = int(manifest["budget"]["timeout_s"]) + self.config.vt_slack_s
            # Batch is the per-family poll quota. Lease one at a time so later items
            # never spend their timeout waiting behind earlier items in this process.
            for _ in range(self.config.batch):
                if self.stopping.is_set():
                    return count
                messages = self.bus.fetch_prefer_hi(processor.family, max(vt, 1), qty=1)
                if not messages:
                    break
                self.process(messages[0])
                count += 1
        return count

    def run(self) -> None:
        while not self.stopping.is_set():
            try:
                if self.poll_once() == 0:
                    self.stopping.wait(self.config.idle_ms / 1000)
            except Exception:
                # Infra failures leave leases unacked; pgmq redelivers. Do not log request bodies/credentials.
                log.warning("consumer attempt interrupted; work remains leased", exc_info=False)
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
        processor = self.manager.processor(item.family, item.version_hash)
        completion = Completion(run_id=item.run_id, step_id=item.step_id)
        ctx = RunContext(item, processor, {}, {}, [])
        try:
            manifest = self.kinds.manifest(processor)
            if item.kind != processor.kind:
                raise ValueError("kind/unsupported: work kind differs from processor")
        except Exception as exc:
            completion.status, completion.error = CompletionStatus.FAILED, str(exc)
            self._complete(msg, ctx, completion)
            return completion
        vt = max(1, int(manifest["budget"]["timeout_s"]) + self.config.vt_slack_s)
        self.bus.extend(msg, vt)
        with self.heartbeat(msg, vt):
            existing = list(
                self.warehouse.iter_annotations(ListAnnotationsRequest(run_id=item.run_id, active_only=False))
            )
            if existing:
                completion.status = CompletionStatus.SUCCEEDED
                completion.annotation_ids = [a.id for a in existing]
                completion.verdict_ids = [str(a.id) for a in existing if a.type == "Verdict"]
            elif msg.read_ct > int(manifest["budget"]["retries"]):
                completion.status, completion.error = CompletionStatus.FAILED, "retries exhausted"
            else:
                units = {uid: self.warehouse.get_unit(uid) for uid in item.unit_ids}
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
                    output, diagnostics = self.validators.validate(
                        result.annotations + result.verdicts, units, manifest.get("post_conditions", [])
                    )
                    output += diagnostics
                    for a in output:
                        a.run_id, a.producer, a.producer_version = item.run_id, item.family, item.version_hash
                    only_abstentions = bool(output) and all(a.type == "Abstention" for a in output)
                    completion.status = (
                        CompletionStatus.ABSTAINED
                        if only_abstentions or (result.abstained and not output)
                        else CompletionStatus.SUCCEEDED
                    )
                    completion.usage = result.usage
                    completion.child_unit_ids = result.child_unit_ids
                except RunTimeout:
                    output = []
                    completion.status, completion.error = CompletionStatus.FAILED, "timeout"
                except Exception as exc:
                    output = []
                    completion.status = CompletionStatus.FAILED
                    completion.error = f"{type(exc).__name__}: {exc}"
                if output:
                    loaded = self.warehouse.load_annotations(
                        LoadAnnotationsRequest(
                            run_id=item.run_id,
                            annotations=output,
                            visibility=item.visibility,
                            generation=item.generation or None,
                        )
                    )
                    completion.annotation_ids = loaded.ids
                    completion.verdict_ids = [
                        str(uid) for uid, a in zip(loaded.ids, output, strict=True) if a.type == "Verdict"
                    ]
                    for a in output:
                        if a.type == "Verdict" and not a.payload().get("ok", True):
                            verdicts.labels(item.family, str(a.payload().get("rule", ""))).inc()
                    self.after_load()  # fault injection: outside kind error handling, leaves message unacked
        completion.usage.latency_ms = int((time.monotonic() - started) * 1000)
        self._complete(msg, ctx, completion, archive=msg.read_ct > int(manifest["budget"]["retries"]) and not existing)
        return completion

    def _complete(self, msg: Message, ctx: RunContext, completion: Completion, archive: bool = False) -> None:
        completion.artefact_ref = f"pipeline://run_artefacts/{ctx.item.run_id}"
        document = self.writer.document(ctx, completion)
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
