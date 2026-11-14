// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import org.tatrman.automation.writer.applyContext

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tatrman.automation.bundle.ApprovalReader
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.config.AutomationConfig
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.RunQueue
import org.tatrman.automation.ledger.StagingRetention
import org.tatrman.automation.staging.StagingSweeper
import org.tatrman.automation.protocol.ProtocolRecorder
import org.tatrman.automation.queue.RunWorkQueue
import org.tatrman.automation.staging.Staging
import org.tatrman.automation.staging.StagingStore
import java.time.Clock
import java.util.UUID

/**
 * The assembled P1 evaluation runtime. The application owns the ledger, Query channel and staging store;
 * it must keep them alive until [shutdown] returns. Supply the SAME clock used to construct the ledger
 * (including configured clock.skew). No credentials or executors are recovered after a process restart.
 *
 * Application installs this runtime and its authenticated routes. The deployment must keep exactly one
 * replica and use Recreate updates: pinned credentials and contexts are never routed to another process.
 */
class RunRuntime(
    config: AutomationConfig,
    served: () -> Bundle?,
    approvals: ApprovalReader,
    tokens: ServiceTokens,
    ledger: Ledger,
    parent: CoroutineScope,
    clock: Clock,
    query: QueryRun,
    store: StagingStore,
    queue: RunWorkQueue = RunQueue(clock = clock, defaultBudget = config.budgets.batchDefault),
    retention: StagingRetention = StagingRetention(config.retention, clock),
    /** D-184: the approvals writer behind the dev seeding route; the ledger's reader is also its book. */
    book: org.tatrman.automation.bundle.ApprovalBook? = approvals as? org.tatrman.automation.bundle.ApprovalBook,
    /** S3.1: the Golem lane of `POST /automation/query` (iris-bff); none configured = that lane refuses. */
    golem: org.tatrman.automation.query.Golem = org.tatrman.automation.query.NoGolem,
) {
    private val shutdownLock = Mutex()
    private var shutdownComplete = false
    private val workers: RunWorkers
    val protocol =
        org.tatrman.automation.protocol
            .ProtocolRecorder(ledger)
    val sessions: RunSessions
    val status = RunStatusReader(ledger)
    private val door = QueryDoor(query, config.door.routes.toMap(), tokens, config.door.writeRoutes)
    val closer =
        RunCloser(
            ledger,
            org.tatrman.automation.writer
                .Apply(door, clock),
            { id, caller -> sessions.context(id, caller) },
        ) { sessions.release(it) }

    private val deferred =
        org.tatrman.automation.writer
            .BundleDeferredContexts(served, tokens) { sessions.pinned(it) }

    /** S2.3: held groups (commit/reject after the close) and their dev routes (`AUTOMATION_DEV_ROUTES`). */
    val held =
        org.tatrman.automation.writer.Held(
            ledger,
            org.tatrman.automation.writer
                .Apply(door, clock),
            deferred,
            served,
            clock,
        )

    /** S2.3: `perform`/`outcome` (under the close's per-run gate) and `-rev`. */
    val perform =
        org.tatrman.automation.writer.Perform(
            ledger,
            org.tatrman.automation.writer
                .Apply(door, clock),
            { id, caller ->
                sessions.context(id, caller)?.applyContext()
            },
            { id, block -> closer.exclusive(id, block) },
        )
    val revert =
        org.tatrman.automation.writer.Revert(
            ledger,
            org.tatrman.automation.writer
                .Apply(door, clock),
            deferred,
            held,
            clock,
        )
    val devRoutes = config.devRoutes

    /** S3.1 T2: the governed query step over the same door, under the caller (or a deployment's granted run_as). */
    val query =
        org.tatrman.automation.query.QueryStep(
            served,
            door,
            golem,
            ledger,
            tokens,
            config.door.routes.keys
                .toSet(),
            { config.paused },
            org.tatrman.automation.query.QueryLimits(
                config.query.defaultLimit,
                config.query.maxLimit,
                config.query.maxTableRows,
                config.budgets.syncDefault,
            ),
            clock,
        )
    val ledger: Ledger = ledger

    /** S3.1 T6: the digest renderer (D-148) and the typed-action perform (D-168) through the same writer. */
    val digests =
        org.tatrman.automation.protocol
            .Digests(ledger, served)
    val typedPerform =
        org.tatrman.automation.writer.TypedPerform(
            served,
            org.tatrman.automation.writer
                .Apply(door, clock),
            ledger,
            tokens,
            config.budgets.syncDefault,
            clock,
        )
    val served: () -> Bundle? = served
    val approver =
        book?.let {
            org.tatrman.automation.bundle
                .Approver(served, it, clock::instant)
        }
    val sweeper = StagingSweeper(retention, store) { sessions.release(it) }
    val displayRows = config.budgets.displayRows
    val owner: UUID get() = workers.owner

    init {
        require(displayRows > 0 && config.budgets.detailLimit > 0) { "runner row limits must be positive" }
        val staging = Staging(store, protocol)
        val planner =
            org.tatrman.automation.apply
                .ApplyPlanner(clock)
        val policy =
            Open(
                served,
                approvals,
                tokens,
                ledger::claimRun,
                { ledger.findRun(it)?.protocolRef },
                { config.paused },
                config.budgets.syncDefault,
                config.budgets.batchDefault,
                clock,
                config.budgets.detailLimit,
                config.door.routes.keys
                    .toSet(),
            )
        // The registry exists before a consumer or its maintenance job can start. The dispatcher is only
        // called by a session created after construction returns, when workers has been assigned.
        sessions =
            RunSessions(
                policy,
                parent,
                { context, scope ->
                    PartRunner(
                        context,
                        door,
                        staging,
                        scope,
                        ledger::completeEvaluation,
                        ::dispatch,
                        protocol::part,
                        { runner -> planner.plan(planner.preview(runner)) },
                        ledger::completeEvaluation,
                    )
                },
                { ledger.findRun(it)?.status },
                ledger::failExecution,
                ledger::startEvaluation,
                clock,
                closer::authorizeExecution,
            )
        workers =
            RunWorkers(
                queue,
                parent,
                config.runner.workers,
                maintenance = {
                    withContext(Dispatchers.IO) { ledger.abandonExpiredRuns() }
                    try {
                        sessions.reapAbandoned()
                    } finally {
                        sweeper.sweep()
                    }
                },
            )
    }

    private suspend fun dispatch(
        id: UUID,
        scope: CoroutineScope,
        block: suspend () -> EvaluationResult,
    ) = workers.evaluate(id, scope, block)

    /** Idempotent, cancellation-safe and serialized: fence requests → join workers → release executors. */
    suspend fun shutdown() {
        withContext(NonCancellable) {
            shutdownLock.withLock {
                if (shutdownComplete) return@withLock
                sessions.stopAccepting()
                var failure: Exception? = null
                try {
                    workers.shutdown()
                } catch (e: Exception) {
                    failure = e
                }
                try {
                    sessions.shutdown()
                } catch (e: Exception) {
                    if (failure == null) failure = e else failure.addSuppressed(e)
                }
                failure?.let { throw it }
                shutdownComplete = true
            }
        }
    }
}
