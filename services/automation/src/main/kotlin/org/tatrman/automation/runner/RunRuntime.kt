// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

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
) {
    private val shutdownLock = Mutex()
    private var shutdownComplete = false
    private val workers: RunWorkers
    val protocol =
        org.tatrman.automation.protocol
            .ProtocolRecorder(ledger)
    val sessions: RunSessions
    val status = RunStatusReader(ledger)
    val closer = RunCloser(ledger) { sessions.release(it) }
    val sweeper = StagingSweeper(retention, store) { sessions.release(it) }
    val displayRows = config.budgets.displayRows
    val owner: UUID get() = workers.owner

    init {
        require(displayRows > 0 && config.budgets.detailLimit > 0) { "runner row limits must be positive" }
        val door = QueryDoor(query, config.door.routes.toMap(), tokens)
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
                config.approvalsOff,
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
                        planner::preview,
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
