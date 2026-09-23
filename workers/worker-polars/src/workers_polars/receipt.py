# SPDX-License-Identifier: Apache-2.0
"""The Polars worker's half of the ``worker.v1.ExecutionReceipt`` (ES-P0·S0.2).

A deliberate twin of the JVM ``ExecutionReceiptBuilder``
(``shared/libs/kotlin/execution-receipt``) rather than a shared implementation: this worker is
Python, and the two have nothing to share but the contract.

What differs from the SQL workers, and why it is a fact rather than a gap: Polars **executes the
plan**. There is no unparse, so there is no statement — ``statement_kind = PLAN_EXECUTED`` with
``statement = ""`` and no parameters. A reader that finds an empty statement next to
``PLAN_EXECUTED`` knows there never was one; that is the point of the enum (ES architecture §7).

What is identical: the receipt is **best-effort**. A run that streamed its rows correctly must not
fail because the bookkeeping about it did, so every builder call is guarded and returns ``None``
with a WARN.
"""

from __future__ import annotations

import logging
from collections.abc import Callable

from org.tatrman.worker.v1 import worker_pb2

logger = logging.getLogger("workers_polars.receipt")

#: How this worker names itself in ``ExecutionReceipt.engine_label`` (``<id>@<connection>``).
ENGINE_ID = "worker-polars"


def guarded(build: Callable[[], worker_pb2.ExecutionReceipt | None]) -> worker_pb2.ExecutionReceipt | None:
    """Run ``build`` and swallow anything it raises, WARN-logged, as an absent receipt."""
    try:
        return build()
    except Exception as e:  # noqa: BLE001 — a receipt must never fail a run.
        logger.warning("Execution receipt could not be built — continuing without one: %s", e)
        return None


def plan_executed(
    *,
    connection_id: str,
    rows_total: int,
    duration_ms: int,
) -> worker_pb2.ExecutionReceipt | None:
    """The receipt for a completed Polars run: the plan ran, and here is what it cost."""
    return guarded(
        lambda: worker_pb2.ExecutionReceipt(
            statement_kind=worker_pb2.PLAN_EXECUTED,
            connection_id=connection_id,
            engine_label=f"{ENGINE_ID}@{connection_id}",
            rows_total=rows_total,
            duration_ms=duration_ms,
            # Polars has no tenant envelope — the same true statement the MSSQL worker makes.
            rls=worker_pb2.RLS_NOT_REQUIRED,
        )
    )


def nothing_ran(*, connection_id: str) -> worker_pb2.ExecutionReceipt | None:
    """The receipt for a run that failed before it produced anything: ``statement_kind = NONE``."""
    return guarded(
        lambda: worker_pb2.ExecutionReceipt(
            statement_kind=worker_pb2.NONE,
            connection_id=connection_id,
            engine_label=f"{ENGINE_ID}@{connection_id}",
            rls=worker_pb2.RLS_NOT_REQUIRED,
        )
    )
