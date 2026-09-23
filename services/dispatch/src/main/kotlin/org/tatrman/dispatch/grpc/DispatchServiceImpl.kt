// SPDX-License-Identifier: Apache-2.0
package org.tatrman.dispatch.grpc

import com.google.protobuf.kotlin.toByteString
import org.tatrman.dispatch.v1.DispatchRequest
import org.tatrman.dispatch.v1.DispatchServiceGrpcKt
import org.tatrman.dispatch.v1.GetStatusRequest
import org.tatrman.dispatch.v1.GetStatusResponse
import org.tatrman.dispatch.v1.ListWorkersRequest
import org.tatrman.dispatch.v1.ListWorkersResponse
import org.tatrman.dispatch.v1.WorkerHealthStatus
import org.tatrman.dispatch.v1.WorkerInfo
import org.tatrman.meta.v1.OverallStatus
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.Warning
import org.tatrman.worker.receipt.ExecutionReceiptBuilder
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.ResultBatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.slf4j.LoggerFactory
import org.tatrman.dispatch.registry.WorkerEntry
import org.tatrman.dispatch.registry.WorkerRegistry
import org.tatrman.dispatch.routing.ConsistentHashRing
import org.tatrman.dispatch.routing.LoadTracker
import org.tatrman.dispatch.sticky.StickyRegistry
import org.tatrman.dispatch.world.WorldConfig
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * gRPC entrypoint for the Dispatcher.
 *
 * `Dispatch` implements the routing algorithm from Round 6.D §6.D.2:
 *
 *   1. Walk the plan for TableScan qnames.
 *   2. Map each via [WorldConfig] to a connection_id.
 *   3. Reconcile derived set with the optional explicit
 *      `request.connection_id`.
 *   4. Reject cross-database plans with `cross_database_not_supported`.
 *   5. Resolve to a single Worker (sticky-routing if applicable; first
 *      healthy candidate otherwise).
 *   6. Stream Worker batches upstream, prepending routing-decision
 *      warnings to the first batch.
 *
 * Errors travel as a single-element error stream (a ResultBatch with
 * `is_first = is_last = true`, empty `arrow_ipc`, and a populated
 * `messages` field) so callers can treat the stream uniformly.
 */
class DispatchServiceImpl(
    private val registry: WorkerRegistry,
    private val sticky: StickyRegistry,
    private val world: WorldConfig,
    private val allowStickyFailover: Boolean = false,
    internal val loadTracker: LoadTracker = LoadTracker(),
) : DispatchServiceGrpcKt.DispatchServiceCoroutineImplBase() {
    private val activeDispatches = AtomicInteger(0)

    override fun dispatch(request: DispatchRequest): Flow<ResultBatch> {
        val routing =
            try {
                resolveRouting(request)
            } catch (ex: RoutingError) {
                return flowOf(ex.toErrorBatch())
            }
        val worker = routing.worker
        val warnings = routing.warnings.toMutableList()

        if (request.context.sessionId.isNotEmpty() && worker.supportsStateful) {
            sticky.recordSticky(request.context.sessionId, worker.endpoint)
        }

        // Issue #57 Phase C — concretize the plan's DB-scope qnames against the chosen
        // worker's advertised default_schema before forwarding. No-op when the worker
        // hasn't advertised one (legacy / Phase-B-only workers).
        val info = worker.connectionInfo(routing.connectionId)
        val rewriteResult = PlanQnameRewriter.applyDefaultSchema(request.plan, info?.defaultSchema.orEmpty())
        rewriteResult.mismatches.forEach { m ->
            warnings.add(
                warning(
                    "schema_mismatch",
                    "Plan TableScan ${m.table.namespace}.${m.table.name} names a schema " +
                        "that differs from worker ${worker.endpoint}'s default_schema " +
                        "(${m.workerDefaultSchema}); plan namespace left untouched.",
                ),
            )
        }

        val executeReq =
            ExecuteRequest
                .newBuilder()
                .setPlan(rewriteResult.plan)
                .setContext(request.context)
                .setConnectionId(routing.connectionId)
                .setOptions(request.options)
                .build()

        return flow {
            activeDispatches.incrementAndGet()
            loadTracker.incrementAndGet(worker.endpoint)
            try {
                var firstSeen = false
                worker.client
                    .execute(executeReq)
                    .collect { batch ->
                        if (!firstSeen && batch.isFirst) {
                            firstSeen = true
                            emit(stampTarget(annotateFirst(batch, warnings), worker.endpoint))
                        } else {
                            emit(batch)
                        }
                    }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                log.warn("Worker stream failed: {}", t.message)
                emit(errorBatch("worker_stream_failed", t.message ?: "Worker stream failed."))
            } finally {
                activeDispatches.decrementAndGet()
                loadTracker.decrement(worker.endpoint)
            }
        }
    }

    override suspend fun listWorkers(request: ListWorkersRequest): ListWorkersResponse {
        val builder = ListWorkersResponse.newBuilder()
        for (entry in registry.all()) {
            val info =
                WorkerInfo
                    .newBuilder()
                    .setEndpoint(entry.endpoint)
                    .setRoleHint(entry.roleHint)
                    .setHealth(entry.health)
                    .setLastPolled(entry.lastPolled?.toString().orEmpty())
                    .setConsecutiveCapabilityFailures(entry.consecutiveFailures)
                    .setActiveStickySessions(sticky.activeSessionsForEndpoint(entry.endpoint))
            entry.capabilities?.let { info.setCapabilities(it) }
            builder.addWorkers(info)
        }
        return builder.build()
    }

    override suspend fun getStatus(request: GetStatusRequest): GetStatusResponse {
        val workers = registry.all()
        val healthy = workers.count { it.health == WorkerHealthStatus.HEALTHY }
        val degraded = workers.count { it.health == WorkerHealthStatus.DEGRADED }
        val unreachable = workers.count { it.health == WorkerHealthStatus.UNREACHABLE }
        val overall =
            when {
                healthy == 0 && workers.isNotEmpty() -> OverallStatus.DOWN
                degraded > 0 || unreachable > 0 -> OverallStatus.DEGRADED
                else -> OverallStatus.OK
            }
        return GetStatusResponse
            .newBuilder()
            .setReady(healthy > 0)
            .setActiveDispatches(activeDispatches.get())
            .setKnownWorkers(workers.size)
            .setHealthyWorkers(healthy)
            .setDegradedWorkers(degraded)
            .setUnreachableWorkers(unreachable)
            .setActiveStickySessions(sticky.size())
            .setOverallStatus(overall)
            .setDefaultConnection(world.defaultConnection)
            .build()
    }

    private fun resolveRouting(request: DispatchRequest): Routing {
        val tables = PlanScanCollector.collect(request.plan)
        val derived = tables.map { world.resolveOrDefault(it) }.toSet()
        val explicit = request.connectionId
        val warnings = mutableListOf<Warning>()

        // Phase 2.4 — routing-by-content for workspace-rooted plans.
        val planNeedsWorkspace = WorkspaceRefDetector.hasWorkspaceRef(request.plan)
        if (planNeedsWorkspace && request.context.sessionId.isEmpty()) {
            throw RoutingError(
                "workspace_requires_session",
                "Plan references a session-scoped workspace but session_id is empty.",
            )
        }

        val connectionId =
            when {
                derived.size > 1 -> {
                    throw RoutingError(
                        "cross_database_not_supported",
                        "Plan touches multiple connections: ${derived.joinToString()}",
                    )
                }
                explicit.isEmpty() && derived.isEmpty() -> world.defaultConnection
                explicit.isEmpty() -> derived.single()
                derived.isEmpty() -> explicit
                derived.single() == explicit -> explicit
                else -> {
                    warnings.add(
                        warning(
                            "connection_id_overrides_derived",
                            "Caller supplied connection_id=$explicit; derived=${derived.single()}.",
                        ),
                    )
                    explicit
                }
            }

        val candidates =
            if (planNeedsWorkspace) {
                // Workspace plans must land on a stateful Worker. The Worker's
                // connection list is irrelevant here — Polars / DuckDB don't
                // model `connection_id` against ERP sources. Pick from any
                // healthy Worker advertising stateful sessions.
                registry.all().filter {
                    it.health == WorkerHealthStatus.HEALTHY && it.supportsStateful
                }
            } else {
                registry.lookupForConnection(connectionId)
            }
        if (candidates.isEmpty()) {
            val code = if (planNeedsWorkspace) "no_stateful_worker_available" else "no_worker_for_connection"
            val msg =
                if (planNeedsWorkspace) {
                    "No HEALTHY worker advertises supports_stateful_sessions = true."
                } else {
                    "No HEALTHY worker advertises connection_id=$connectionId."
                }
            throw RoutingError(code, msg)
        }

        // Sticky lookup
        val sessionId = request.context.sessionId
        if (sessionId.isNotEmpty()) {
            val pinnedEndpoint = sticky.findSticky(sessionId)
            if (pinnedEndpoint != null) {
                val pinned = candidates.firstOrNull { it.endpoint == pinnedEndpoint }
                if (pinned != null) {
                    warnings.add(
                        warning(
                            "sticky_session_match",
                            "Routed to pinned worker $pinnedEndpoint${databaseTag(pinned, connectionId)}",
                        ),
                    )
                    return Routing(pinned, connectionId, warnings)
                }
                // DF-D02 / G7 — `sticky_failover`: the pinned worker is no longer in the healthy
                // candidate set. Two postures, gated by `dispatch.sticky.allow-failover`:
                //   - **strict** (default, today's behaviour): throw `session_lost` — sessions are
                //     stateful, silently moving to a fresh pod loses workspace state.
                //   - **allow-failover** (opt-in, will become the common posture once multi-pod
                //     sticky routing lands in DF-D03 Phase 06): pick a replacement via the
                //     consistent-hash ring over the *surviving* candidates so the same key fails
                //     over to the same pod across retries, and emit a `sticky_failover` warning.
                if (allowStickyFailover && candidates.isNotEmpty()) {
                    val fallback = chooseByHashRing(sessionId, candidates) ?: candidates.first()
                    sticky.evictSession(sessionId)
                    warnings.add(
                        warning(
                            "sticky_failover",
                            "Pinned worker $pinnedEndpoint unavailable for session $sessionId; " +
                                "failing over to ${fallback.endpoint}${databaseTag(fallback, connectionId)} " +
                                "(session state may be reset).",
                        ),
                    )
                    return Routing(fallback, connectionId, warnings)
                }
                throw RoutingError(
                    "session_lost",
                    "Sticky worker $pinnedEndpoint for session $sessionId is no longer available.",
                )
            }
            // DF-D03 — no existing pin: pick deterministically via consistent hashing so the same
            // `session_id` always lands on the same worker across cold starts of the dispatcher
            // (the `StickyRegistry` then records the choice for fast subsequent lookups). Without
            // this, a fresh dispatcher would route the first call to `candidates.first()` —
            // arbitrary, history-free.
            if (candidates.size > 1) {
                chooseByHashRing(sessionId, candidates)?.let { chosen ->
                    warnings.add(
                        warning(
                            "routing_decision",
                            "Routed to ${chosen.endpoint} via consistent-hash " +
                                "(connection=$connectionId, session=$sessionId" +
                                "${databaseTag(chosen, connectionId)})",
                        ),
                    )
                    return Routing(chosen, connectionId, warnings)
                }
            }
        }

        // DF-D01 / Phase 06 C3 — for non-sticky / no-session traffic with multiple candidates,
        // pick the least-loaded worker (lowest current in-flight dispatch count). Ties broken by
        // endpoint string for deterministic test behaviour. Single-candidate fast path keeps the
        // pre-Phase-06 behaviour byte-identical.
        val chosen = chooseLeastLoaded(candidates)
        warnings.add(
            warning(
                "routing_decision",
                if (candidates.size > 1) {
                    "Routed to ${chosen.endpoint} via least-loaded " +
                        "(connection=$connectionId${databaseTag(chosen, connectionId)})"
                } else {
                    "Routed to ${chosen.endpoint} (connection=$connectionId${databaseTag(chosen, connectionId)})"
                },
            ),
        )
        return Routing(chosen, connectionId, warnings)
    }

    /**
     * Issue #57 Phase C — append the worker's advertised engine-side `database`/`default_schema`
     * for the chosen [connectionId] to a routing-decision warning. Returns an empty string when
     * the worker hasn't been polled yet OR was deployed before Phase B (no `connections[]` info
     * in its `GetCapabilities` response). The dispatcher doesn't rewrite the PlanNode itself —
     * the worker's JDBC URL (`databaseName=…`) handles the physical concretization. This tag
     * exists so the routing decision is observable end-to-end.
     */
    private fun databaseTag(
        worker: WorkerEntry,
        connectionId: String,
    ): String {
        val info = worker.connectionInfo(connectionId) ?: return ""
        if (info.database.isEmpty() && info.defaultSchema.isEmpty()) return ""
        val parts =
            buildList {
                if (info.database.isNotEmpty()) add("database=${info.database}")
                if (info.defaultSchema.isNotEmpty()) add("schema=${info.defaultSchema}")
            }
        return ", " + parts.joinToString(", ")
    }

    /**
     * DF-D01 — pick the candidate with the lowest current in-flight count. Ties broken by
     * endpoint string for determinism in tests + predictable behaviour at cold start (zero
     * load, so endpoint-string ordering wins consistently).
     */
    private fun chooseLeastLoaded(candidates: List<WorkerEntry>): WorkerEntry {
        require(candidates.isNotEmpty())
        if (candidates.size == 1) return candidates.first()
        return candidates.minWithOrNull(
            compareBy<WorkerEntry> { loadTracker.load(it.endpoint) }.thenBy { it.endpoint },
        )!!
    }

    /**
     * DF-D03 — pick a worker by consistent-hash over the live [candidates]. The ring is rebuilt
     * per call (cheap: ~10 entries × 128 virtual nodes); endpoint is the node id, so a pod
     * joining/leaving moves only the keys in its hash range.
     */
    private fun chooseByHashRing(
        key: String,
        candidates: List<WorkerEntry>,
    ): WorkerEntry? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        val ring = ConsistentHashRing(candidates.map { it.endpoint to it })
        return ring.nodeFor(key)
    }

    /**
     * ES-P0·S0.4 — names the routed worker on the receipt (contracts §1.3 (b)).
     *
     * Which endpoint served the query is the dispatcher's fact and nobody else's: the plan half
     * the query service fills cannot know it, and the statement half the worker fills has no reason to
     * repeat its own address. The receipt is created when the worker sent none — an older worker
     * still yields a receipt that names where its rows came from — and every field a worker DID
     * send is left exactly as it was.
     *
     * Best-effort like every other receipt write: a failure here returns the batch untouched
     * rather than failing a stream that is carrying real rows.
     */
    private fun stampTarget(
        batch: ResultBatch,
        endpoint: String,
    ): ResultBatch {
        val stamped =
            ExecutionReceiptBuilder.guarded {
                batch.receipt
                    .toBuilder()
                    .setDispatchTarget(endpoint)
                    .build()
            } ?: return batch
        return batch.toBuilder().setReceipt(stamped).build()
    }

    private fun annotateFirst(
        batch: ResultBatch,
        warnings: List<Warning>,
    ): ResultBatch {
        if (warnings.isEmpty()) return batch
        val ctxBuilder = batch.context.toBuilder()
        warnings.forEach { ctxBuilder.addWarnings(it) }
        return batch.toBuilder().setContext(ctxBuilder).build()
    }

    private fun warning(
        code: String,
        message: String,
    ): Warning =
        Warning
            .newBuilder()
            .setCode(code)
            .setMessage(message)
            .setSourceStage("dispatch")
            .setSourceService("dispatch")
            .build()

    private fun errorBatch(
        code: String,
        message: String,
        context: PipelineContext = PipelineContext.getDefaultInstance(),
    ): ResultBatch =
        ResultBatch
            .newBuilder()
            .setIsFirst(true)
            .setIsLast(true)
            .setArrowIpc(ByteArray(0).toByteString())
            .setContext(context)
            .addMessages(
                ResponseMessage
                    .newBuilder()
                    .setSeverity(Severity.ERROR)
                    .setCode(code)
                    .setHumanMessage(message),
            ).build()

    private data class Routing(
        val worker: WorkerEntry,
        val connectionId: String,
        val warnings: List<Warning>,
    )

    private inner class RoutingError(
        val code: String,
        val humanMessage: String,
    ) : RuntimeException(humanMessage) {
        fun toErrorBatch(): ResultBatch = errorBatch(code, humanMessage)
    }

    @Suppress("unused")
    private fun nowIso(): String = Instant.now().toString()

    companion object {
        private val log = LoggerFactory.getLogger(DispatchServiceImpl::class.java)
    }
}
