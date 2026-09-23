# SPDX-License-Identifier: Apache-2.0
"""ES-P0·S0.2 — the Polars worker's half of the execution receipt.

Polars executes the **plan**, so its receipt is the honest short version: ``PLAN_EXECUTED``, no
statement, no parameters, and the rows and duration it actually cost. The point of asserting the
absence of a statement is that it must be an absence with a *name* — a reader that finds
``statement = ""`` next to ``PLAN_EXECUTED`` knows there never was one, which is what stops the
document from rendering nothing as "executed".
"""

from __future__ import annotations

import polars as pl
import pytest
from org.tatrman.plan.v1 import context_pb2, plan_pb2
from org.tatrman.worker.v1 import worker_pb2

from workers_polars.grpc_service import WorkerService
from workers_polars.workspace import WorkspaceStore

from .test_execute_rpc import _cfg_full, _drain, _workspace_ref


@pytest.mark.asyncio
async def test_last_batch_carries_a_plan_executed_receipt():
    cfg = _cfg_full()
    store = WorkspaceStore(cfg.workspace)
    await store.put("s1", "q1", pl.DataFrame({"id": [1, 2, 3], "amount": [10.0, 20.0, 30.0]}))
    service = WorkerService(cfg, store)
    request = worker_pb2.ExecuteRequest(
        plan=_workspace_ref("q1"),
        context=context_pb2.PipelineContext(session_id="s1", correlation_id="corr-es-1"),
        connection_id="polars-local",
    )

    batches = await _drain(service.execute(request, context=None))

    tail = batches[-1]
    assert tail.is_last is True
    assert tail.HasField("receipt")
    receipt = tail.receipt
    assert receipt.statement_kind == worker_pb2.PLAN_EXECUTED
    assert receipt.engine_label == "worker-polars@polars-local"
    assert receipt.connection_id == "polars-local"
    assert receipt.rows_total == 3
    assert receipt.duration_ms >= 0
    assert receipt.rls == worker_pb2.RLS_NOT_REQUIRED
    # It ran a plan, not a statement — and says so rather than leaving the reader to guess.
    assert receipt.statement == ""
    assert receipt.statement_ref == ""
    assert list(receipt.parameters) == []
    # The plan half belongs to the query service (⚑ES-1); the worker leaves it unset.
    assert receipt.HasField("dispatched_plan") is False


@pytest.mark.asyncio
async def test_an_empty_result_still_reports_a_run():
    cfg = _cfg_full()
    store = WorkspaceStore(cfg.workspace)
    await store.put("s1", "q1", pl.DataFrame({"id": [], "amount": []}))
    service = WorkerService(cfg, store)
    request = worker_pb2.ExecuteRequest(
        plan=_workspace_ref("q1"),
        context=context_pb2.PipelineContext(session_id="s1"),
        connection_id="polars-local",
    )

    batches = await _drain(service.execute(request, context=None))

    tail = batches[-1]
    assert tail.is_last is True
    assert tail.receipt.statement_kind == worker_pb2.PLAN_EXECUTED
    assert tail.receipt.rows_total == 0


@pytest.mark.asyncio
async def test_a_failed_run_carries_a_receipt_that_says_nothing_ran():
    cfg = _cfg_full()
    store = WorkspaceStore(cfg.workspace)
    service = WorkerService(cfg, store)
    request = worker_pb2.ExecuteRequest(
        # A workspace that was never staged — the converter rejects before anything executes.
        plan=_workspace_ref("never-staged"),
        context=context_pb2.PipelineContext(session_id="s1"),
        connection_id="polars-local",
    )

    batches = await _drain(service.execute(request, context=None))

    only = batches[0]
    assert only.is_last is True
    assert only.messages[0].severity == 3  # ERROR
    assert only.receipt.statement_kind == worker_pb2.NONE
    assert only.receipt.engine_label == "worker-polars@polars-local"


@pytest.mark.asyncio
async def test_a_missing_session_id_also_carries_a_nothing_ran_receipt():
    cfg = _cfg_full()
    service = WorkerService(cfg, WorkspaceStore(cfg.workspace))
    request = worker_pb2.ExecuteRequest(
        plan=plan_pb2.PlanNode(),
        context=context_pb2.PipelineContext(),
        connection_id="polars-local",
    )

    batches = await _drain(service.execute(request, context=None))

    assert batches[0].messages[0].code == "workspace_requires_session"
    assert batches[0].receipt.statement_kind == worker_pb2.NONE


def test_a_throwing_builder_yields_an_absent_receipt_not_a_failed_run():
    """The rule every worker shares: a receipt must never fail a run (ES architecture §7)."""
    from workers_polars.receipt import guarded

    def boom():
        raise RuntimeError("the receipt blew up")

    assert guarded(boom) is None
