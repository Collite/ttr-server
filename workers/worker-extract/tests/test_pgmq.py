# SPDX-License-Identifier: Apache-2.0
"""DX-P0 S0.2 T4 — the five worker verbs against a real Postgres with the SQL-only pgmq."""

from __future__ import annotations

import hashlib
import time
from collections.abc import Iterator

import pytest

from aristarchos.bus import DONE, PgmqBus, work_queue
from tests.conftest import PGMQ_SQL, kantheon_dir

pytestmark = pytest.mark.component


@pytest.fixture
def bus(pg_url: str) -> Iterator[PgmqBus]:
    b = PgmqBus(pg_url)
    yield b
    b.close()


def test_ensure_queue_is_idempotent(bus: PgmqBus) -> None:
    assert bus.ensure_queue("work_split") is True
    assert bus.ensure_queue("work_split") is False


def test_fetch_extend_ack(bus: PgmqBus) -> None:
    bus.ensure_queue("work_py_vt")
    sent = bus.send("work_py_vt", {"n": 1})
    [m] = bus.fetch("work_py_vt", vt_s=1)
    assert (m.msg_id, m.read_ct, m.body) == (sent, 1, {"n": 1})
    assert bus.fetch("work_py_vt", vt_s=1) == []
    assert bus.extend(m, vt_s=60) is True  # the heartbeat outlives the first vt
    time.sleep(1.5)
    assert bus.fetch("work_py_vt", vt_s=1) == []
    assert bus.ack(m) is True
    assert bus.ack(m) is False


def test_redelivery_after_vt_counts_reads(bus: PgmqBus) -> None:
    bus.ensure_queue("work_py_redeliver")
    bus.send("work_py_redeliver", {"n": 2})
    [first] = bus.fetch("work_py_redeliver", vt_s=1)
    time.sleep(1.5)
    [again] = bus.fetch("work_py_redeliver", vt_s=30)
    assert again.msg_id == first.msg_id and again.read_ct == 2


def test_fail_releases_then_dead_letters_after_retries(bus: PgmqBus, pg_url: str) -> None:
    import psycopg

    bus.ensure_queue("work_py_fail")
    bus.send("work_py_fail", {"n": 3})
    [m1] = bus.fetch("work_py_fail", vt_s=30)
    assert bus.fail(m1, retries=1) is False  # read once: released for a retry (backoff 0)
    [m2] = bus.fetch("work_py_fail", vt_s=30)
    assert m2.read_ct == 2
    assert bus.fail(m2, retries=1) is True  # read twice > retries: archived — the DLQ
    assert bus.fetch("work_py_fail", vt_s=30) == []
    with psycopg.connect(pg_url) as c:
        row = c.execute("SELECT count(*) FROM pgmq.a_work_py_fail WHERE msg_id = %s", (m2.msg_id,)).fetchone()
        assert row is not None and row[0] == 1


def test_metrics(bus: PgmqBus) -> None:
    bus.ensure_queue("work_py_metrics")
    assert bus.metrics("work_py_metrics").queue_length == 0
    for i in range(3):
        bus.send("work_py_metrics", {"n": i})
    time.sleep(1.1)
    m = bus.metrics("work_py_metrics")
    assert m.queue_length == 3 and m.oldest_msg_age_sec is not None and m.oldest_msg_age_sec >= 1


def test_hi_lane_first(bus: PgmqBus) -> None:
    bus.ensure_queue(work_queue("review-extract"))
    bus.ensure_queue(work_queue("review-extract", hi=True))
    bus.send(work_queue("review-extract"), {"lane": "lo"})
    bus.send(work_queue("review-extract", hi=True), {"lane": "hi"})
    assert [m.body["lane"] for m in bus.fetch_prefer_hi("review-extract", 30)] == ["hi"]
    assert [m.body["lane"] for m in bus.fetch_prefer_hi("review-extract", 30)] == ["lo"]
    assert bus.fetch_prefer_hi("review-extract", 30) == []


def test_queue_names() -> None:
    assert work_queue("review-extract") == "work_review_extract"
    assert work_queue("review-extract", hi=True) == "work_review_extract_hi"
    assert DONE == "done"
    with pytest.raises(ValueError):
        work_queue("x" * 45)


def test_vendored_sql_body_identical_to_kantheon_side_by_side() -> None:
    """Both repos install the same pgmq body (headers differ; the sha256 of the body below the header must not)."""
    k = kantheon_dir()
    if k is None:
        pytest.skip("no kantheon checkout beside this one (set KANTHEON_DIR)")

    def body_sha(text: str) -> str:
        return hashlib.sha256(text[text.index("\n\n") + 2 :].encode("utf-8")).hexdigest()

    theirs = (
        k / "shared" / "libs" / "kotlin" / "pgmq-client" / "src" / "main" / "resources" / "db" / "pgmq" / PGMQ_SQL.name
    )
    assert body_sha(theirs.read_text(encoding="utf-8")) == body_sha(PGMQ_SQL.read_text(encoding="utf-8"))
