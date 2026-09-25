# SPDX-License-Identifier: Apache-2.0
"""WorkerService gRPC handlers for the Polars Worker.

Phase 2.4 §C+§D scaffolding + §H Execute RPC. Registers via
``grpc.method_handlers_generic_handler`` so we don't depend on
auto-generated ``*_pb2_grpc.py`` stubs — keeps the build self-contained.
"""

from __future__ import annotations

import io
import logging
import time
from collections.abc import AsyncIterator

import grpc
import polars as pl
import pyarrow as pa
import pyarrow.ipc as ipc
from opentelemetry import trace
from org.tatrman.common.v1 import response_message_pb2
from org.tatrman.meta.v1 import meta_pb2
from org.tatrman.worker.v1 import worker_pb2

from workers_polars.config import WorkersPolarsConfig
from workers_polars.converter import (
    PlanToPolars,
    UnsupportedExpression,
    UnsupportedNode,
    UnsupportedTableScan,
    WorkspaceNotFound,
)
from workers_polars.fingerprint import schema_fingerprint
from workers_polars.probes import execute_duration_seconds, executes_total
from workers_polars.receipt import nothing_ran, plan_executed
from workers_polars.workspace import WorkspaceCapExceeded, WorkspaceStore

logger = logging.getLogger("workers_polars.grpc_service")

# DF-P01 — tracer for the WorkerService.Execute span hierarchy. When OTEL is not initialised
# (tests / local dev with telemetry.enabled=false) this is a NoOp tracer and spans are free.
_tracer = trace.get_tracer("workers-polars")


class WorkerService:
    """Stateful holder for the WorkerService handlers.

    Owns the workspace store and converter factory; the gRPC generic
    handlers below close over a single instance.
    """

    def __init__(self, cfg: WorkersPolarsConfig, store: WorkspaceStore) -> None:
        self._cfg = cfg
        self._store = store

    # ----- Execute -----

    async def execute(
        self,
        request: worker_pb2.ExecuteRequest,
        context: grpc.aio.ServicerContext,
    ) -> AsyncIterator[worker_pb2.ResultBatch]:
        started = time.monotonic()
        session_id = request.context.session_id
        with _tracer.start_as_current_span("workers-polars.Execute") as root_span:
            # Attributes mirror the mssql worker's span attrs for cross-engine comparability.
            root_span.set_attribute("workspace.session_id", session_id)
            root_span.set_attribute("correlation_id", request.context.correlation_id)
            root_span.set_attribute("engine", "polars")
            root_span.set_attribute("connection_id", request.connection_id)
            if request.assign_to_workspace:
                root_span.set_attribute("workspace.assign_to", request.assign_to_workspace)

            if not session_id:
                executes_total.labels(result="error").inc()
                root_span.set_attribute("error.code", "workspace_requires_session")
                yield _error_batch(
                    "workspace_requires_session",
                    "Polars Worker requires a non-empty session_id.",
                    receipt=nothing_ran(connection_id=request.connection_id),
                )
                return

            parameters = _bindings_to_python(request.context.parameters)
            converter = PlanToPolars(self._store, session_id=session_id, parameters=parameters)
            try:
                with _tracer.start_as_current_span("workers-polars.convert"):
                    lf = await converter.convert(request.plan)
                with _tracer.start_as_current_span("workers-polars.collect"):
                    df = lf.collect()
            except (WorkspaceNotFound, UnsupportedTableScan, UnsupportedExpression, UnsupportedNode) as e:
                logger.info("Execute rejected: %s", e)
                executes_total.labels(result="error").inc()
                code = getattr(e, "code", "polars_execution_failed")
                root_span.set_attribute("error.code", code)
                yield _error_batch(code, str(e), receipt=nothing_ran(connection_id=request.connection_id))
                return
            except Exception as e:  # noqa: BLE001 — Polars-side failures funnel here.
                logger.warning("Polars execution failed: %s", e)
                executes_total.labels(result="error").inc()
                root_span.set_attribute("error.code", "polars_execution_failed")
                yield _error_batch(
                    "polars_execution_failed",
                    str(e),
                    receipt=nothing_ran(connection_id=request.connection_id),
                )
                return

            # Optional: assign result to workspace.
            if request.assign_to_workspace:
                try:
                    with _tracer.start_as_current_span("workers-polars.workspace_put") as ws_span:
                        ws_span.set_attribute("workspace.df_name", request.assign_to_workspace)
                        await self._store.put(session_id, request.assign_to_workspace, df)
                except WorkspaceCapExceeded as e:
                    logger.info("Workspace cap exceeded: %s", e)
                    executes_total.labels(result="error").inc()
                    root_span.set_attribute("error.code", "workspace_cap_exceeded")
                    yield _error_batch(
                        "workspace_cap_exceeded",
                        str(e),
                        receipt=nothing_ran(connection_id=request.connection_id),
                    )
                    return
                except Exception as e:  # noqa: BLE001
                    logger.warning("Workspace put failed: %s", e)
                    executes_total.labels(result="error").inc()
                    root_span.set_attribute("error.code", "workspace_put_failed")
                    yield _error_batch(
                        "workspace_put_failed",
                        str(e),
                        receipt=nothing_ran(connection_id=request.connection_id),
                    )
                    return

            # Stream Arrow IPC. v1 emits one batch per record-batch the Arrow
            # table produces (Polars' default chunking ≈ ~64 MiB target). We
            # also clamp by the configured max_batch_size_rows.
            with _tracer.start_as_current_span("workers-polars.serialize") as ser_span:
                table = df.to_arrow()
                batch_size_rows = (
                    int(request.options.batch_size_rows) if request.options.batch_size_rows > 0
                    else self._cfg.limits.default_batch_size_rows
                )
                batch_size_rows = min(batch_size_rows, self._cfg.limits.max_batch_size_rows)
                record_batches = list(_split_table(table, max_rows=batch_size_rows))
                schema_fp = _schema_fingerprint(table.schema)
                ser_span.set_attribute("result.row_count", table.num_rows)
                ser_span.set_attribute("result.column_count", table.num_columns)
                ser_span.set_attribute("result.batch_count", len(record_batches))

            root_span.set_attribute("result.row_count", table.num_rows)

            if not record_batches:
                # Empty result — emit a single batch carrying just the schema.
                empty_batch = _serialize_schema_only(table.schema)
                yield worker_pb2.ResultBatch(
                    arrow_ipc=empty_batch,
                    batch_index=0,
                    batch_row_count=0,
                    is_first=True,
                    is_last=True,
                    context=request.context,
                    schema_fingerprint=schema_fp,
                    # ES — an empty result still ran: the receipt says so, with zero rows.
                    receipt=plan_executed(
                        connection_id=request.connection_id,
                        rows_total=0,
                        duration_ms=int((time.monotonic() - started) * 1000),
                    ),
                )
                executes_total.labels(result="ok").inc()
                execute_duration_seconds.observe(time.monotonic() - started)
                return

            for i, rb in enumerate(record_batches):
                is_first = i == 0
                is_last = i == len(record_batches) - 1
                payload = _serialize_record_batch(table.schema if is_first else None, rb)
                yield worker_pb2.ResultBatch(
                    arrow_ipc=payload,
                    batch_index=i,
                    batch_row_count=rb.num_rows,
                    is_first=is_first,
                    is_last=is_last,
                    context=request.context if is_last else worker_pb2.ResultBatch().context,
                    schema_fingerprint=schema_fp if is_first else "",
                    # ES (⚑ES-1) — the statement half rides the LAST batch: rows and time are
                    # only known there. Polars ran a plan, not a statement.
                    receipt=(
                        plan_executed(
                            connection_id=request.connection_id,
                            rows_total=table.num_rows,
                            duration_ms=int((time.monotonic() - started) * 1000),
                        )
                        if is_last
                        else None
                    ),
                )
            executes_total.labels(result="ok").inc()
            execute_duration_seconds.observe(time.monotonic() - started)

    # ----- ImportDataFrame / DropWorkspaceEntry (session-workspace ingest) -----

    async def import_data_frame(
        self,
        request_iterator: AsyncIterator[worker_pb2.ImportChunk],
        context: grpc.aio.ServicerContext,
    ) -> worker_pb2.ImportDataFrameResult:
        """Client-stream an external Arrow DataFrame INTO a session workspace.

        The first chunk carries the ImportHeader (session_id, df_name); every
        chunk's ipc_payload is a self-contained Arrow IPC stream. Charon's
        WorkerEndpoint (POLARS) drives this — the symmetric counterpart to the
        WorkspaceRef read-out path.
        """
        with _tracer.start_as_current_span("workers-polars.ImportDataFrame") as span:
            header: worker_pb2.ImportHeader | None = None
            tables: list[pa.Table] = []
            async for chunk in request_iterator:
                if chunk.HasField("header"):
                    header = chunk.header
                if chunk.ipc_payload:
                    reader = ipc.RecordBatchStreamReader(io.BytesIO(chunk.ipc_payload))
                    tables.append(reader.read_all())

            if header is None or not header.session_id or not header.df_name:
                await context.abort(
                    grpc.StatusCode.INVALID_ARGUMENT,
                    "ImportDataFrame requires a first chunk with header.session_id + header.df_name",
                )
                # abort() raises in grpc.aio; this guard keeps the rest provably
                # unreachable with header narrowed to non-None even if a context
                # impl ever returns instead of raising.
                return worker_pb2.ImportDataFrameResult()
            span.set_attribute("workspace.session_id", header.session_id)
            span.set_attribute("workspace.df_name", header.df_name)

            if not tables:
                await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "ImportDataFrame received no Arrow payload")
                return worker_pb2.ImportDataFrameResult()
            table = tables[0] if len(tables) == 1 else pa.concat_tables(tables)
            fp = _schema_fingerprint(table.schema)
            if header.expected_schema_fingerprint and header.expected_schema_fingerprint != fp:
                await context.abort(
                    grpc.StatusCode.FAILED_PRECONDITION,
                    f"schema fingerprint mismatch: expected {header.expected_schema_fingerprint}, got {fp}",
                )
                return worker_pb2.ImportDataFrameResult()

            df = pl.from_arrow(table)
            try:
                await self._store.put(header.session_id, header.df_name, df)
            except WorkspaceCapExceeded as e:
                logger.info("ImportDataFrame cap exceeded: %s", e)
                span.set_attribute("error.code", "workspace_cap_exceeded")
                await context.abort(grpc.StatusCode.RESOURCE_EXHAUSTED, str(e))
                return worker_pb2.ImportDataFrameResult()

            span.set_attribute("result.row_count", table.num_rows)
            return worker_pb2.ImportDataFrameResult(
                df_name=header.df_name,
                schema_fingerprint=fp,
                rows=table.num_rows,
            )

    async def drop_workspace_entry(
        self,
        request: worker_pb2.DropWorkspaceRequest,
        _context: grpc.aio.ServicerContext,
    ) -> worker_pb2.DropWorkspaceResult:
        existed = await self._store.drop(request.session_id, request.name)
        return worker_pb2.DropWorkspaceResult(existed=existed)

    # ----- Capabilities / Status -----

    async def get_capabilities(
        self,
        _request: worker_pb2.GetCapabilitiesRequest,
        _context: grpc.aio.ServicerContext,
    ) -> worker_pb2.GetCapabilitiesResponse:
        return make_capabilities(self._cfg)

    async def get_status(
        self,
        _request: worker_pb2.GetStatusRequest,
        _context: grpc.aio.ServicerContext,
    ) -> worker_pb2.GetStatusResponse:
        sessions = self._store.stats()["sessions"]
        return make_status(active_sessions=sessions, ready=True)


# ----- Capability / status builders (kept module-level for easy testing) -----


def make_capabilities(cfg: WorkersPolarsConfig) -> worker_pb2.GetCapabilitiesResponse:
    return worker_pb2.GetCapabilitiesResponse(
        engine_name=cfg.capability.engine_name,
        engine_version=cfg.capability.engine_version,
        supported_languages=[],
        supported_dialects=[],
        supported_connections=[],
        limits=worker_pb2.ExecutionLimits(
            default_timeout_seconds=cfg.limits.execute_timeout_seconds,
            max_timeout_seconds=cfg.limits.execute_timeout_seconds,
            default_batch_size_rows=cfg.limits.default_batch_size_rows,
            max_batch_size_rows=cfg.limits.max_batch_size_rows,
            max_row_limit=cfg.limits.max_rows_per_result,
            max_blob_bytes_per_cell=cfg.limits.max_blob_bytes_per_cell,
        ),
        supports_stateful_sessions=cfg.capability.supports_stateful_sessions,
        max_concurrent_sessions=cfg.capability.max_concurrent_sessions,
        session_idle_timeout_seconds=cfg.capability.session_idle_timeout_seconds,
        max_session_memory_mb=cfg.capability.max_session_memory_mb,
    )


def make_status(active_sessions: int = 0, ready: bool = True) -> worker_pb2.GetStatusResponse:
    return worker_pb2.GetStatusResponse(
        ready=ready,
        active_queries=0,
        active_sessions=active_sessions,
        overall_status=meta_pb2.OK,
    )


# ----- Generic-handler registration -----


def build_worker_service_handlers(service: WorkerService) -> grpc.GenericRpcHandler:
    rpc_method_handlers = {
        "Execute": grpc.unary_stream_rpc_method_handler(
            service.execute,
            request_deserializer=worker_pb2.ExecuteRequest.FromString,
            response_serializer=worker_pb2.ResultBatch.SerializeToString,
        ),
        "GetCapabilities": grpc.unary_unary_rpc_method_handler(
            service.get_capabilities,
            request_deserializer=worker_pb2.GetCapabilitiesRequest.FromString,
            response_serializer=worker_pb2.GetCapabilitiesResponse.SerializeToString,
        ),
        "GetStatus": grpc.unary_unary_rpc_method_handler(
            service.get_status,
            request_deserializer=worker_pb2.GetStatusRequest.FromString,
            response_serializer=worker_pb2.GetStatusResponse.SerializeToString,
        ),
        "ImportDataFrame": grpc.stream_unary_rpc_method_handler(
            service.import_data_frame,
            request_deserializer=worker_pb2.ImportChunk.FromString,
            response_serializer=worker_pb2.ImportDataFrameResult.SerializeToString,
        ),
        "DropWorkspaceEntry": grpc.unary_unary_rpc_method_handler(
            service.drop_workspace_entry,
            request_deserializer=worker_pb2.DropWorkspaceRequest.FromString,
            response_serializer=worker_pb2.DropWorkspaceResult.SerializeToString,
        ),
    }
    return grpc.method_handlers_generic_handler(
        "org.tatrman.worker.v1.WorkerService",
        rpc_method_handlers,
    )


# ----- Internal helpers -----


def _error_batch(
    code: str,
    message: str,
    receipt: worker_pb2.ExecutionReceipt | None = None,
) -> worker_pb2.ResultBatch:
    return worker_pb2.ResultBatch(
        is_first=True,
        is_last=True,
        arrow_ipc=b"",
        # ES — the error stream carries a tail marker too, so it carries a receipt too.
        receipt=receipt,
        messages=[
            response_message_pb2.ResponseMessage(
                severity=response_message_pb2.ERROR,
                code=code,
                human_message=message,
            )
        ],
    )


def _bindings_to_python(bindings) -> dict[str, object]:
    """Convert PipelineContext.parameters (repeated Binding) → dict.

    The `parameters` field is a repeated message; the per-element shape
    depends on the proto. For Phase 2.4 we accept anything with a `.name`
    and `.value` (Literal) and map literals to Python via the converter's
    `_literal_to_python`.
    """
    from workers_polars.converter import _literal_to_python

    out: dict[str, object] = {}
    for b in bindings:
        # Defensive — bindings shape is platform-specific; field names known
        # from `org.tatrman.plan.v1.parameters.proto`. Best-effort here.
        name = getattr(b, "name", "")
        if not name:
            continue
        if hasattr(b, "value") and hasattr(b.value, "WhichOneof"):
            out[name] = _literal_to_python(b.value)
    return out


def _split_table(table: pa.Table, max_rows: int) -> list[pa.RecordBatch]:
    """Split a PyArrow table into RecordBatches of at most `max_rows` rows."""
    if max_rows <= 0:
        return list(table.to_batches())
    return list(table.to_batches(max_chunksize=max_rows))


def _serialize_record_batch(schema: pa.Schema | None, batch: pa.RecordBatch) -> bytes:
    """Serialize a RecordBatch to Arrow IPC stream bytes.

    When `schema` is provided, emits a complete stream (schema + batch +
    end-of-stream marker) so the consumer can decode standalone. When None
    (subsequent batches), emits a stream that reuses the recipient's
    already-known schema — but for v1 streaming-isolation, every batch is
    a fully self-contained IPC stream so callers can decode batches
    independently.
    """
    sink = io.BytesIO()
    use_schema = schema if schema is not None else batch.schema
    with ipc.new_stream(sink, use_schema) as writer:
        writer.write_batch(batch)
    return sink.getvalue()


def _serialize_schema_only(schema: pa.Schema) -> bytes:
    sink = io.BytesIO()
    with ipc.new_stream(sink, schema):
        pass
    return sink.getvalue()


def _schema_fingerprint(schema: pa.Schema) -> str:
    """Cross-engine schema fingerprint (canonical logical-schema digest).

    Delegates to the shared canonical algorithm (fork Stage 3.4 T2) so the
    ResultBatch.schema_fingerprint Polars stamps is byte-identical to what
    Mssql (Kotlin) and Charon (Integrity.kt) compute for the same schema —
    NOT the old raw-IPC-bytes hash, which was not stable across Arrow impls.
    """
    return schema_fingerprint(schema)
