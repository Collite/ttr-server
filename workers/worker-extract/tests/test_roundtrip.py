# SPDX-License-Identifier: Apache-2.0
"""DX-P0 S0.2 T5 — the at-least-once proof: a worker that dies between its commit and
its ack is redelivered after vt; the second attempt finds its own write (idempotent by
``run_id``), posts exactly one Completion on ``done`` and acks. Python ↔ Python here;
kantheon's ``WorkItemRoundTripSpec`` runs the same peer across the language boundary.
"""

from __future__ import annotations

import time

import psycopg
import pytest

from aristarchos.bus import DONE, PgmqBus
from aristarchos.sdk.models import Completion, CompletionStatus
from tests import roundtrip_peer as peer
from tests.conftest import CONTRACTS

pytestmark = pytest.mark.component

VT = 2


def test_crash_before_ack_is_redelivered_and_completes_once(pg_url: str) -> None:
    peer.setup(pg_url, "work_noop")
    peer.produce(pg_url, "work_noop", (CONTRACTS / "workItem.json").read_text(encoding="utf-8"))

    first = peer.consume(pg_url, "work_noop", VT, crash_before_ack=True)
    assert first == {"status": "crashed", "readCt": 1, "runId": "step-1/1"}
    assert peer.consume(pg_url, "work_noop", VT)["status"] == "empty"  # hidden until vt

    time.sleep(VT + 0.5)
    second = peer.consume(pg_url, "work_noop", VT)
    assert second == {"status": "completed", "readCt": 2, "runId": "step-1/1"}

    bus = PgmqBus(pg_url)
    try:
        done = bus.fetch(DONE, 30, qty=10)
        assert len(done) == 1
        c = Completion.model_validate(done[0].body)
        assert (c.run_id, c.status) == ("step-1/1", CompletionStatus.SUCCEEDED)
        assert bus.metrics("work_noop").queue_length == 0
        bus.ack(done[0])
    finally:
        bus.close()

    with psycopg.connect(pg_url) as conn:
        rows = conn.execute("SELECT writer, read_ct FROM fake_silver WHERE run_id = 'step-1/1'").fetchall()
    assert rows == [("python", 1)]  # written once, by the first (crashed) attempt
