# SPDX-License-Identifier: Apache-2.0
"""Phase 2.4 §H — Execute RPC server-streaming Arrow IPC + assign_to_workspace."""

from __future__ import annotations

import io

import polars as pl
import pyarrow.ipc as ipc
import pytest
from org.tatrman.plan.v1 import context_pb2, plan_pb2
from org.tatrman.worker.v1 import worker_pb2

from workers_polars.config import WorkspaceConfig
from workers_polars.grpc_service import WorkerService
from workers_polars.workspace import WorkspaceStore


def _cfg_full():
    """Minimal full WorkersPolarsConfig with realistic limits."""
    from workers_polars.config import (
        CapabilityConfig,
        GrpcConfig,
        HttpConfig,
        LimitsConfig,
        MetadataConfig,
        TelemetryConfig,
        WorkersPolarsConfig,
    )

    return WorkersPolarsConfig(
        grpc=GrpcConfig(host="0", port=7501, max_message_bytes=33554432),
        http=HttpConfig(host="0", port=7502),
        workspace=WorkspaceConfig(
            max_sessions=10,
            max_dfs_per_session=10,
            max_bytes_per_df=10_000_000,
            max_total_bytes=100_000_000,
            idle_ttl_seconds=60,
            sweeper_interval_seconds=10,
        ),
        capability=CapabilityConfig(
            engine_name="polars",
            engine_version="polars 1.13",
            supports_stateful_sessions=True,
            max_concurrent_sessions=1000,
            session_idle_timeout_seconds=3600,
            max_session_memory_mb=8192,
        ),
        limits=LimitsConfig(
            max_rows_per_result=1000000,
            execute_timeout_seconds=300,
            default_batch_size_rows=10000,
            max_batch_size_rows=100000,
            max_blob_bytes_per_cell=8388608,
        ),
        metadata=MetadataConfig(host="localhost", port=7204, poll_interval_seconds=60),
        telemetry=TelemetryConfig(enabled=False, otlp_endpoint=""),
    )


async def _drain(stream):
    """Helper: collect a server-streaming generator into a list."""
    out = []
    async for batch in stream:
        out.append(batch)
    return out


def _workspace_ref(name: str) -> plan_pb2.PlanNode:
    return plan_pb2.PlanNode(workspace_ref=plan_pb2.WorkspaceRef(workspace_name=name))


def _read_arrow_stream(payload: bytes) -> pl.DataFrame:
    reader = ipc.RecordBatchStreamReader(io.BytesIO(payload))
    return pl.from_arrow(reader.read_all())


@pytest.mark.asyncio
async def test_execute_workspace_ref_streams_arrow_back():
    cfg = _cfg_full()
    store = WorkspaceStore(cfg.workspace)
    await store.put(
        "s1",
        "q1",
        pl.DataFrame({"id": [1, 2, 3], "amount": [10.0, 20.0, 30.0]}),
    )
    service = WorkerService(cfg, store)
    request = worker_pb2.ExecuteRequest(
        plan=_workspace_ref("q1"),
        context=context_pb2.PipelineContext(session_id="s1"),
    )
    batches = await _drain(service.execute(request, context=None))
    assert len(batches) == 1
    batch = batches[0]
    assert batch.is_first is True
    assert batch.is_last is True
    assert batch.batch_row_count == 3
    assert batch.schema_fingerprint != ""
    df = _read_arrow_stream(batch.arrow_ipc)
    assert df.shape == (3, 2)


@pytest.mark.asyncio
async def test_execute_row_limit_is_exact():
    # BI (C-10) — 2 of 3 rows, however the batches fall; the receipt counts what was streamed.
    cfg = _cfg_full()
    store = WorkspaceStore(cfg.workspace)
    await store.put("s1", "q1", pl.DataFrame({"id": [1, 2, 3]}))
    service = WorkerService(cfg, store)
    request = worker_pb2.ExecuteRequest(
        plan=_workspace_ref("q1"),
        context=context_pb2.PipelineContext(session_id="s1"),
        options=worker_pb2.ExecutionOptions(row_limit=2),
    )
    batches = await _drain(service.execute(request, context=None))
    assert sum(b.batch_row_count for b in batches) == 2
    assert _read_arrow_stream(batches[0].arrow_ipc)["id"].to_list() == [1, 2]
    assert batches[-1].receipt.rows_total == 2


@pytest.mark.asyncio
async def test_execute_with_assign_to_workspace_persists_result():
    cfg = _cfg_full()
    store = WorkspaceStore(cfg.workspace)
    await store.put("s1", "q1", pl.DataFrame({"id": [1, 2, 3], "amount": [10.0, 20.0, 30.0]}))
    service = WorkerService(cfg, store)
    request = worker_pb2.ExecuteRequest(
        plan=plan_pb2.PlanNode(
            filter=plan_pb2.FilterNode(
                input=_workspace_ref("q1"),
                condition=plan_pb2.Expression(
                    function=plan_pb2.FunctionCall(
                        operation="gt",
                        operands=[
                            plan_pb2.Expression(column_ref=plan_pb2.ColumnRef(name="amount")),
                            plan_pb2.Expression(literal=plan_pb2.Literal(float_value=15.0)),
                        ],
                    )
                ),
            )
        ),
        context=context_pb2.PipelineContext(session_id="s1"),
        assign_to_workspace="q2",
    )
    batches = await _drain(service.execute(request, context=None))
    assert batches[-1].is_last is True

    # The new workspace q2 contains the filter result.
    entry = await store.get("s1", "q2")
    assert entry is not None
    assert entry.df.shape[0] == 2


@pytest.mark.asyncio
async def test_execute_missing_session_id_returns_workspace_requires_session():
    cfg = _cfg_full()
    store = WorkspaceStore(cfg.workspace)
    service = WorkerService(cfg, store)
    request = worker_pb2.ExecuteRequest(
        plan=_workspace_ref("q1"),
        # session_id intentionally omitted
    )
    batches = await _drain(service.execute(request, context=None))
    assert len(batches) == 1
    assert batches[0].messages[0].code == "workspace_requires_session"


@pytest.mark.asyncio
async def test_execute_missing_workspace_returns_workspace_not_found():
    cfg = _cfg_full()
    store = WorkspaceStore(cfg.workspace)
    service = WorkerService(cfg, store)
    request = worker_pb2.ExecuteRequest(
        plan=_workspace_ref("nope"),
        context=context_pb2.PipelineContext(session_id="s1"),
    )
    batches = await _drain(service.execute(request, context=None))
    assert batches[0].messages[0].code == "workspace_not_found"


@pytest.mark.asyncio
async def test_execute_table_scan_returns_unsupported_table_scan():
    cfg = _cfg_full()
    store = WorkspaceStore(cfg.workspace)
    service = WorkerService(cfg, store)
    request = worker_pb2.ExecuteRequest(
        plan=plan_pb2.PlanNode(table_scan=plan_pb2.TableScanNode()),
        context=context_pb2.PipelineContext(session_id="s1"),
    )
    batches = await _drain(service.execute(request, context=None))
    assert batches[0].messages[0].code == "unsupported_table_scan"


@pytest.mark.asyncio
async def test_execute_assign_workspace_cap_exceeded_returns_code():
    cfg = _cfg_full()
    cfg = cfg.__class__(
        grpc=cfg.grpc,
        http=cfg.http,
        workspace=WorkspaceConfig(
            max_sessions=10,
            max_dfs_per_session=1,  # only the source workspace fits.
            max_bytes_per_df=cfg.workspace.max_bytes_per_df,
            max_total_bytes=cfg.workspace.max_total_bytes,
            idle_ttl_seconds=cfg.workspace.idle_ttl_seconds,
            sweeper_interval_seconds=cfg.workspace.sweeper_interval_seconds,
        ),
        capability=cfg.capability,
        limits=cfg.limits,
        metadata=cfg.metadata,
        telemetry=cfg.telemetry,
    )
    store = WorkspaceStore(cfg.workspace)
    await store.put("s1", "q1", pl.DataFrame({"id": [1, 2]}))
    service = WorkerService(cfg, store)
    request = worker_pb2.ExecuteRequest(
        plan=_workspace_ref("q1"),
        context=context_pb2.PipelineContext(session_id="s1"),
        assign_to_workspace="q2",
    )
    batches = await _drain(service.execute(request, context=None))
    assert batches[0].messages[0].code == "workspace_cap_exceeded"
