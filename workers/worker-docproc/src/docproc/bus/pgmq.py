# SPDX-License-Identifier: Apache-2.0
"""The worker side of the pgmq work-item protocol (psycopg 3).

pgmq is installed SQL-only (the D-130 install vendored by ``services/automation``), so
every verb is a function call into schema ``pgmq``. The protocol:

* ``fetch(queue, vt)`` reads and hides a message for ``vt`` seconds — the visibility
  timeout is the step's budget: ``vt = budget.timeout_s + 30``;
* ``extend(msg, vt)`` is the heartbeat — pgmq sets the message's vt to *now* + ``vt``
  (it does not add to the current one); call it every ``vt / 3``;
* ``ack(msg)`` deletes a handled message — only after the step's writes committed;
* ``fail(msg, retries)`` archives a message read more than ``retries`` times (the archive
  table ``pgmq.a_<queue>`` is the dead-letter queue) and otherwise releases it for a retry.

A worker that dies between its commit and its ack lets the message come back after vt;
the second attempt finds its own writes (idempotent by ``run_id``) and only acks — at
least once, effectively once.

Every verb takes an optional ``conn``: pass an open transaction to make a ``send``
commit with the writes it announces; without one, the bus's own autocommit
connection is used.
"""

from __future__ import annotations

import json
import re
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime
from typing import Any

import psycopg

DONE = "done"
_NAME = re.compile(r"[a-z][a-z0-9_]*")


def work_queue(family: str, hi: bool = False) -> str:
    """``work_<family>[_hi]`` — pgmq names must be identifiers, so the family's dashes become underscores."""
    return _checked("work_" + family.lower().replace("-", "_").replace(".", "_") + ("_hi" if hi else ""))


def _checked(queue: str) -> str:
    if not _NAME.fullmatch(queue):
        raise ValueError(f"invalid pgmq queue name {queue!r}")
    if len(queue) > 47:
        raise ValueError(f"pgmq queue name {queue!r} is longer than 47 characters")
    return queue


@dataclass(frozen=True)
class Message:
    queue: str
    msg_id: int
    read_ct: int
    enqueued_at: datetime
    vt: datetime
    body: dict[str, Any]


@dataclass(frozen=True)
class Metrics:
    queue_name: str
    queue_length: int
    visible_length: int
    total_messages: int
    oldest_msg_age_sec: int | None


class PgmqBus:
    def __init__(self, conninfo: str) -> None:
        self._conninfo = conninfo
        self._conn: psycopg.Connection[Any] | None = None

    @contextmanager
    def transaction(self) -> Iterator[psycopg.Connection[Any]]:
        """Separate connection: heartbeat/probe activity must never join completion's transaction."""
        with psycopg.connect(self._conninfo) as conn:
            yield conn

    def ready(self) -> bool:
        try:
            return self._c(None).execute("SELECT 1").fetchone() == (1,)
        except psycopg.Error:
            return False

    def close(self) -> None:
        if self._conn is not None:
            self._conn.close()
            self._conn = None

    def _c(self, conn: psycopg.Connection[Any] | None) -> psycopg.Connection[Any]:
        if conn is not None:
            return conn
        if self._conn is None or self._conn.closed:
            self._conn = psycopg.connect(self._conninfo, autocommit=True)
        return self._conn

    def send(
        self, queue: str, body: dict[str, Any] | str, delay_s: int = 0, conn: psycopg.Connection[Any] | None = None
    ) -> int:
        text = body if isinstance(body, str) else json.dumps(body, ensure_ascii=False)
        row = self._c(conn).execute("SELECT pgmq.send(%s, %s::jsonb, %s)", (_checked(queue), text, delay_s)).fetchone()
        assert row is not None
        return int(row[0])

    def fetch(self, queue: str, vt_s: int, qty: int = 1, conn: psycopg.Connection[Any] | None = None) -> list[Message]:
        rows = (
            self._c(conn)
            .execute(
                "SELECT msg_id, read_ct, enqueued_at, vt, message FROM pgmq.read(%s, %s, %s)",
                (_checked(queue), vt_s, qty),
            )
            .fetchall()
        )
        return [Message(queue, int(r[0]), int(r[1]), r[2], r[3], r[4]) for r in rows]

    def fetch_prefer_hi(
        self, family: str, vt_s: int, qty: int = 1, conn: psycopg.Connection[Any] | None = None
    ) -> list[Message]:
        """The ``_hi`` lane first; ``work_<family>`` only when it yields nothing."""
        return self.fetch(work_queue(family, hi=True), vt_s, qty, conn) or self.fetch(
            work_queue(family), vt_s, qty, conn
        )

    def extend(self, msg: Message, vt_s: int, conn: psycopg.Connection[Any] | None = None) -> bool:
        """The heartbeat: ``msg`` stays invisible until now + ``vt_s``. False when it is gone."""
        rows = (
            self._c(conn)
            .execute("SELECT msg_id FROM pgmq.set_vt(%s, %s::bigint, %s)", (msg.queue, msg.msg_id, vt_s))
            .fetchall()
        )
        return len(rows) == 1

    def ack(self, msg: Message, conn: psycopg.Connection[Any] | None = None) -> bool:
        row = self._c(conn).execute("SELECT pgmq.delete(%s, %s::bigint)", (msg.queue, msg.msg_id)).fetchone()
        return bool(row and row[0])

    def fail(self, msg: Message, retries: int, backoff_s: int = 0, conn: psycopg.Connection[Any] | None = None) -> bool:
        """Dead-letter after ``retries`` re-reads (archive, answers True); otherwise visible again in ``backoff_s``."""
        if msg.read_ct > retries:
            row = self._c(conn).execute("SELECT pgmq.archive(%s, %s::bigint)", (msg.queue, msg.msg_id)).fetchone()
            return bool(row and row[0])
        self.extend(msg, backoff_s, conn)
        return False

    def metrics(self, queue: str, conn: psycopg.Connection[Any] | None = None) -> Metrics:
        row = (
            self._c(conn)
            .execute(
                "SELECT queue_name, queue_length, queue_visible_length, total_messages, oldest_msg_age_sec "
                "FROM pgmq.metrics(%s)",
                (_checked(queue),),
            )
            .fetchone()
        )
        assert row is not None
        return Metrics(str(row[0]), int(row[1]), int(row[2]), int(row[3]), None if row[4] is None else int(row[4]))

    def ensure_queue(self, queue: str, conn: psycopg.Connection[Any] | None = None) -> bool:
        """Create ``queue`` unless it exists; answers whether it had to."""
        c = self._c(conn)
        known = {r[0] for r in c.execute("SELECT queue_name FROM pgmq.list_queues()").fetchall()}
        if _checked(queue) in known:
            return False
        c.execute("SELECT pgmq.create(%s)", (queue,))
        return True
