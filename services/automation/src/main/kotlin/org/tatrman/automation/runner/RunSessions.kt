// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.ledger.RunStatus
import java.util.UUID
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory

/**
 * Owns the pinned executors, not a cache of reconstructible runs. A refresh never replaces a context; a
 * restart never recreates one from today's bundle or caller. Missing contexts refuse until durable recovery
 * lands. Only the verified subject that opened a run may read its staged data, even when runAs is a service.
 *
 * [open] serializes claim + registration, including competing idempotent opens. The short handoff is not
 * cancelled by a disconnected HTTP caller. Reads live in a supervised run scope, so cancellation of one HTTP
 * waiter does not cancel shared work. [release] cancels and joins that scope BEFORE closing DuckDB.
 * [failed] must atomically fail only an active ledger row, never overwrite a terminal/waiting run. A failed
 * journal attempt can be retried; successful recording is serialized once per session.
 * The committed claim timestamp bounds idle/queued/running execution. Deadline monitors belong to the
 * application scope so they can cancel the run scope and journal/replay its refusal independently. Supply
 * the same configured clock as Open/Ledger and Ledger.failExecution as [failed]. A successful durable
 * evaluation ends this timer; production mounting, preview/close/task handoff and retention remain separate.
 */
class RunSessions(
    private val policy: Open,
    private val parent: CoroutineScope,
    private val create: (RunContext, CoroutineScope) -> PartRunner,
    private val status: (UUID) -> RunStatus?,
    private val failed: (UUID, Exception) -> Unit,
    private val running: (UUID) -> Unit = { error("running journal unavailable") },
    private val clock: Clock,
) {
    private class Session(
        val runner: PartRunner,
        val job: Job,
        val scope: CoroutineScope,
    ) {
        val evaluationLock = Mutex()
        var evaluation: Deferred<EvaluationResult>? = null
        val failureLock = Mutex()

        @Volatile var failure: Exception? = null

        @Volatile var budgetExpired = false

        @Volatile var failureRecorded = false
        var closing = false // Guarded by the registry lock.
        val released = CompletableDeferred<Unit>()
        var deadline: Job? = null
    }

    private val lock = Mutex()
    private val sessions = mutableMapOf<UUID, Session>()
    private var stopped = false

    suspend fun open(
        recipe: String,
        request: OpenRequest,
        caller: Caller,
    ): OpenResult =
        lock.withLock {
            parent.ensureActive()
            if (stopped) unavailable()
            withContext(NonCancellable) {
                val result = withContext(Dispatchers.IO) { policy.open(recipe, request, caller) }
                val context = result.context ?: return@withContext result
                val job = SupervisorJob(parent.coroutineContext[Job])
                val scope = CoroutineScope(parent.coroutineContext + job)
                try {
                    parent.ensureActive()
                    check(context.id !in sessions) { "duplicate executor for a claimed run" }
                    val session = Session(create(context, scope), job, scope)
                    sessions[context.id] = session
                    session.deadline = parent.launch { watchDeadline(session, context.id) }
                } catch (e: Exception) {
                    job.cancel()
                    withContext(Dispatchers.IO) { failed(context.id, e) }
                    throw e
                }
                result
            }
        }

    /** No lookup or door read is allowed until the initiating subject matches. */
    suspend fun <S, T> read(
        id: UUID,
        caller: Caller,
        select: (RunContext) -> S,
        block: suspend (PartRunner, S) -> T,
    ): T {
        val session = owned(id, caller)
        val current = withContext(Dispatchers.IO) { status(id) }
        if (current !in setOf(RunStatus.OPEN, RunStatus.RUNNING, RunStatus.EVALUATED, RunStatus.FAILED)) unavailable()
        if (current == RunStatus.FAILED) {
            if ((session.failure as? AutomationException)?.code == ErrorCode.BUDGET) throwFailure(session, id)
            unavailable()
        }
        checkDeadline(session, id)
        throwFailure(session, id)
        val selection = select(session.runner.context) // Client mistakes never fail the run.
        return await(session, id, session.scope.async { guarded(session, id) { block(session.runner, selection) } })
    }

    /** A timed-out/disconnected HTTP waiter never cancels the shared evaluation. Null means still running. */
    suspend fun evaluate(
        id: UUID,
        caller: Caller,
    ): EvaluationResult? {
        val session = owned(id, caller)
        val current = withContext(Dispatchers.IO) { status(id) }
        if (current !in setOf(RunStatus.OPEN, RunStatus.RUNNING, RunStatus.EVALUATED, RunStatus.FAILED)) unavailable()
        checkDeadline(session, id)
        throwFailure(session, id) // Preserve a cached refusal, never restart it.
        if (current !in setOf(RunStatus.OPEN, RunStatus.RUNNING, RunStatus.EVALUATED)) unavailable()
        val work =
            session.evaluationLock.withLock {
                session.evaluation ?: run {
                    session.scope
                        .async {
                            guarded(session, id) {
                                withContext(Dispatchers.IO) { running(id) }
                                session.runner.evaluate()
                            }
                        }.also { session.evaluation = it }
                }
            }
        val result =
            withTimeoutOrNull(
                session.runner.context.syncBudget
                    .toMillis(),
            ) { await(session, id, work) }
        checkDeadline(session, id)
        throwFailure(session, id)
        return result
    }

    /** Owner-checked observation only: polling never starts a read, evaluation or decision journal. */
    suspend fun progress(
        id: UUID,
        caller: Caller,
    ): RunProgress {
        val session = owned(id, caller)
        val current = withContext(Dispatchers.IO) { status(id) }
        if (current !in setOf(RunStatus.OPEN, RunStatus.RUNNING, RunStatus.EVALUATED, RunStatus.FAILED)) unavailable()
        checkDeadline(session, id)
        throwFailure(session, id) // Replay failures even after the active-run failure was journalled.
        if (current == RunStatus.FAILED) unavailable()
        val result =
            if (current == RunStatus.EVALUATED) {
                guarded(session, id) { session.runner.completedEvaluation() }
            } else {
                null
            }
        // The journal can commit just before the executor publishes its completed promise.
        val visibleStatus =
            if (current == RunStatus.EVALUATED &&
                result == null
            ) {
                "running"
            } else {
                requireNotNull(current).wire
            }
        return RunProgress(
            id.toString(),
            visibleStatus,
            result?.verdict,
            result?.guards.orEmpty(),
            result?.mode,
            "/automation/runs/$id/protocol",
        )
    }

    private suspend fun throwFailure(
        session: Session,
        id: UUID,
    ) {
        val failure = session.failure ?: return
        guarded(session, id) { throw failure }
    }

    private suspend fun owned(
        id: UUID,
        caller: Caller,
    ): Session {
        val session = lock.withLock { if (stopped) null else sessions[id]?.takeUnless { it.closing } } ?: unavailable()
        if (session.runner.context.identity.caller.sub != caller.sub) unavailable()
        return session
    }

    private suspend fun <T> guarded(
        session: Session,
        id: UUID,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(session, id, e)
            throw requireNotNull(session.failure)
        }

    /** HTTP cancellation remains cancellation; a cancelled shared executor replays its stable refusal. */
    private suspend fun <T> await(
        session: Session,
        id: UUID,
        work: Deferred<T>,
    ): T =
        try {
            work.await().also {
                checkDeadline(session, id)
                throwFailure(session, id)
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throwFailure(session, id)
            throw e
        }

    private suspend fun recordFailure(
        session: Session,
        id: UUID,
        error: Exception,
    ) {
        withContext(NonCancellable) {
            session.failureLock.withLock {
                if (session.failure == null) session.failure = error
                if (!session.failureRecorded) {
                    withContext(Dispatchers.IO) { failed(id, requireNotNull(session.failure)) }
                    session.failureRecorded = true
                }
            }
        }
    }

    private fun expired(session: Session): Boolean =
        session.budgetExpired ||
            !session.runner.evaluationRecorded &&
            Duration.between(session.runner.context.startedAt, clock.instant()) >= session.runner.context.timeout

    private suspend fun checkDeadline(
        session: Session,
        id: UUID,
    ) {
        if (!expired(session)) return
        withContext(NonCancellable) {
            session.failureLock.withLock {
                if (session.runner.evaluationRecorded) return@withContext
                if (session.failure == null) {
                    session.failure = AutomationException(ErrorCode.BUDGET, "the run exceeded its batch budget")
                }
                // Latch before cancelling every shared read/combine/dispatch child. Keep the session itself
                // available for refusal replay; release still joins blocking IO before closing the executor.
                session.budgetExpired = true
                session.job.cancel(CancellationException("automation batch budget expired"))
            }
            recordFailure(session, id, requireNotNull(session.failure))
        }
    }

    private suspend fun watchDeadline(
        session: Session,
        id: UUID,
    ) {
        while (currentCoroutineContext().isActive && !session.runner.evaluationRecorded) {
            try {
                checkDeadline(session, id)
                if (session.budgetExpired && session.failureRecorded) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("automation batch deadline journal failed; will retry", e)
            }
            // Re-read the same clock as Open/Ledger, including clock.skew and forward/backward adjustments.
            // Never reset from a delivery, a new HTTP waiter, user asOf, or today's bundle.
            val remaining =
                session.runner.context.timeout.minus(
                    Duration.between(session.runner.context.startedAt, clock.instant()),
                )
            delay(
                if (remaining.isNegative || remaining.isZero || remaining >= Duration.ofMillis(250)) {
                    250
                } else {
                    remaining.toMillis().coerceAtLeast(1)
                },
            )
        }
    }

    suspend fun release(id: UUID) {
        val (session, owner) =
            lock.withLock {
                val session = sessions[id] ?: return
                val owner = !session.closing
                session.closing = true
                session to owner
            }
        withContext(NonCancellable) {
            if (!owner) {
                session.released.await()
                return@withContext
            }
            try {
                session.deadline?.cancelAndJoin()
                session.job.cancelAndJoin()
                withContext(Dispatchers.IO) { session.runner.close() }
                session.released.complete(Unit)
            } catch (e: Exception) {
                session.released.completeExceptionally(e)
                throw e
            } finally {
                lock.withLock { sessions.remove(id) }
            }
        }
    }

    /**
     * Reconcile local resources against durable abandonment, regardless of which pod/claim swept the row.
     * Run after each clock sweep, even when that sweep returns no IDs. Status failures leave that session
     * intact for retry while other sessions are still cleaned. Failed runs retain their cached refusal;
     * waiting runs and their tasks are never clock-abandoned. Blob retention is a separate lifecycle task.
     */
    suspend fun reapAbandoned() {
        val active = lock.withLock { sessions.keys.toList() }
        var failure: Exception? = null
        active.forEach { id ->
            try {
                if (withContext(Dispatchers.IO) { status(id) } == RunStatus.ABANDONED) release(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failure == null) failure = e else failure!!.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }

    /** Fence new HTTP work before the runtime joins workers and then closes the pinned executors. */
    suspend fun stopAccepting() {
        lock.withLock { stopped = true }
    }

    suspend fun shutdown() {
        withContext(NonCancellable) {
            val active =
                lock.withLock {
                    stopped = true
                    sessions.keys.toList()
                }
            // Finish all cleanup even if one resource refuses to close, then report that failure.
            var failure: Exception? = null
            active.forEach {
                try {
                    release(it)
                } catch (e: Exception) {
                    if (failure == null) failure = e else failure!!.addSuppressed(e)
                }
            }
            failure?.let { throw it }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RunSessions::class.java)
    }

    private fun unavailable(): Nothing =
        throw AutomationException(ErrorCode.RUN_ABANDONED, "the pinned run execution context is unavailable")
}
