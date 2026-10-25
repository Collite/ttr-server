# SPDX-License-Identifier: Apache-2.0
"""The Python half of the at-least-once round trip (DX-P0 S0.2 T5) — imported by
``test_roundtrip.py`` and run as a script by kantheon's ``WorkItemRoundTripSpec`` for the
cross-language legs::

    python tests/roundtrip_peer.py setup    --dsn URL
    python tests/roundtrip_peer.py produce  --dsn URL --queue work_noop --file tests/fixtures/contracts/workItem.json
    python tests/roundtrip_peer.py consume  --dsn URL --queue work_noop --vt 5 [--crash-before-ack]

``consume`` is a ``noop`` step: it "writes annotations" (one ``fake_silver`` row keyed by
``run_id``, ``ON CONFLICT DO NOTHING`` — the idempotency the real writes get from
``run_id``) and commits; then, unless told to crash, it posts the ``Completion`` on
``done`` and acks the work item IN ONE TRANSACTION, so a crash there leaves neither. A work
item holding a value this release does not know (an ``*_UNSPECIFIED`` after the tolerant
read) is failed — released for a retry, dead-lettered after ``RETRIES`` — and writes nothing.
Each command prints one JSON line.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import psycopg

from aristarchos.bus import DONE, PgmqBus
from aristarchos.sdk.models import Completion, CompletionStatus, Usage, WorkItem, unspecified_fields

RETRIES = 3  # a failed work item is released this many times, then dead-lettered (PgmqBus.fail)

FAKE_SILVER = (
    "CREATE TABLE IF NOT EXISTS fake_silver ("
    " run_id TEXT PRIMARY KEY, writer TEXT NOT NULL, read_ct INT NOT NULL, written_at TIMESTAMPTZ NOT NULL DEFAULT now())"
)


def setup(dsn: str, queue: str = "work_noop") -> None:
    bus = PgmqBus(dsn)
    try:
        bus.ensure_queue(queue)
        bus.ensure_queue(DONE)
        with psycopg.connect(dsn, autocommit=True) as c:
            c.execute(FAKE_SILVER)
    finally:
        bus.close()


def produce(dsn: str, queue: str, item_json: str) -> dict[str, Any]:
    item = WorkItem.strict(item_json)  # a fixture: the strict twin
    bus = PgmqBus(dsn)
    try:
        return {"status": "sent", "msgId": bus.send(queue, item.dump())}
    finally:
        bus.close()


def consume(dsn: str, queue: str, vt_s: int, crash_before_ack: bool = False, writer: str = "python") -> dict[str, Any]:
    bus = PgmqBus(dsn)
    try:
        msgs = bus.fetch(queue, vt_s)
        if not msgs:
            return {"status": "empty"}
        [msg] = msgs
        item = WorkItem.model_validate(msg.body)  # the tolerant reader (review-158 ⚑R158-B)
        if unknown := unspecified_fields(item):
            # a value this release does not know: fail the message once, never act on a guess
            print(f"work item {item.run_id}: unknown value in {unknown}; failing msg {msg.msg_id}", file=sys.stderr)
            bus.fail(msg, retries=RETRIES)
            return {"status": "failed", "readCt": msg.read_ct, "unspecified": unknown}
        with psycopg.connect(dsn) as c:  # the step's writes, committed on their own
            c.execute(
                "INSERT INTO fake_silver (run_id, writer, read_ct) VALUES (%s, %s, %s) ON CONFLICT (run_id) DO NOTHING",
                (item.run_id, writer, msg.read_ct),
            )
        if crash_before_ack:
            return {"status": "crashed", "readCt": msg.read_ct, "runId": item.run_id}
        done = Completion(
            run_id=item.run_id,
            step_id=item.step_id,
            status=CompletionStatus.SUCCEEDED,
            artefact_ref=f"pipeline://run_artefacts/{item.run_id}",
            usage=Usage(latency_ms=1),
        )
        with psycopg.connect(dsn) as c:  # completion + ack: one transaction
            bus.send(DONE, done.dump(), conn=c)
            if not bus.ack(msg, conn=c):
                raise RuntimeError(f"ack lost for msg {msg.msg_id}")
        return {"status": "completed", "readCt": msg.read_ct, "runId": item.run_id}
    finally:
        bus.close()


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("command", choices=["setup", "produce", "consume"])
    p.add_argument("--dsn", required=True)
    p.add_argument("--queue", default="work_noop")
    p.add_argument("--file")
    p.add_argument("--vt", type=int, default=2)
    p.add_argument("--crash-before-ack", action="store_true")
    a = p.parse_args(argv)
    if a.command == "setup":
        setup(a.dsn, a.queue)
        out: dict[str, Any] = {"status": "ready"}
    elif a.command == "produce":
        out = produce(a.dsn, a.queue, Path(a.file).read_text(encoding="utf-8"))
    else:
        out = consume(a.dsn, a.queue, a.vt, a.crash_before_ack)
    print(json.dumps(out))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
