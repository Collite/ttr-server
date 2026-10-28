# SPDX-License-Identifier: Apache-2.0
"""DX-P0 S0.2 T5 — the at-least-once proof: a worker that dies between its commit and
its ack is redelivered after vt; the second attempt finds its own write (idempotent by
``run_id``), posts exactly one Completion on ``done`` and acks. Python ↔ Python here;
kantheon's ``WorkItemRoundTripSpec`` runs the same peer across the language boundary.
"""

from __future__ import annotations

import json
import time

import psycopg
import pytest

from docproc.bus import DONE, PgmqBus
from docproc.sdk.models import Completion, CompletionStatus
from tests import roundtrip_peer as peer
from tests.conftest import CONTRACTS

pytestmark = pytest.mark.component

VT = 5  # outlasts a consumer start between "crashed" and the "still hidden" read (review-158 F13)


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


def test_a_work_item_with_an_unknown_value_is_failed_once_not_crashed(pg_url: str) -> None:
    """review-158 F5 — a value a newer producer added reads as *_UNSPECIFIED; the consumer fails the message (released
    for a retry, dead-lettered after its retries) instead of acting on a guess or crashing on the decode."""
    peer.setup(pg_url, "work_noop_unknown")
    with psycopg.connect(pg_url) as conn:  # the session's Postgres: the other round trip wrote this run_id already
        conn.execute("DELETE FROM fake_silver WHERE run_id = 'step-1/1'")
    text = (CONTRACTS / "workItem.json").read_text(encoding="utf-8").replace('"CANDIDATE"', '"ARCHIVED"')
    bus = PgmqBus(pg_url)
    try:
        bus.send("work_noop_unknown", json.loads(text))
    finally:
        bus.close()
    assert peer.consume(pg_url, "work_noop_unknown", VT) == {
        "status": "failed",
        "readCt": 1,
        "unspecified": ["visibility"],
    }
    with psycopg.connect(pg_url) as conn:
        assert conn.execute("SELECT count(*) FROM fake_silver WHERE run_id = 'step-1/1'").fetchone() == (0,)
