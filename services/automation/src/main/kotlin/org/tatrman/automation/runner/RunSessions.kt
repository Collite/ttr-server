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
 * Production mounting, deadlines/retention and restart recovery are separate integration tasks.
 */
class RunSessions(
    private val policy: Open,
    private val parent: CoroutineScope,
    private val create: (RunContext, CoroutineScope) -> PartRunner,
    private val status: (UUID) -> RunStatus?,
    private val failed: (UUID) -> Unit,
    private val running: (UUID) -> Unit = { error("running journal unavailable") },
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
        var failureRecorded = false
        var closing = false // Guarded by the registry lock.
        val released = CompletableDeferred<Unit>()
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
                    sessions[context.id] = Session(create(context, scope), job, scope)
                } catch (e: Exception) {
                    job.cancel()
                    withContext(Dispatchers.IO) { failed(context.id) }
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
        if (current !in setOf(RunStatus.OPEN, RunStatus.RUNNING, RunStatus.EVALUATED)) unavailable()
        val selection = select(session.runner.context) // Client mistakes never fail the run.
        return session.scope.async { guarded(session, id) { block(session.runner, selection) } }.await()
    }

    /** A timed-out/disconnected HTTP waiter never cancels the shared evaluation. Null means still running. */
    suspend fun evaluate(
        id: UUID,
        caller: Caller,
    ): EvaluationResult? {
        val session = owned(id, caller)
        val current = withContext(Dispatchers.IO) { status(id) }
        if (current !in setOf(RunStatus.OPEN, RunStatus.RUNNING, RunStatus.EVALUATED, RunStatus.FAILED)) unavailable()
        throwFailure(session, id) // Preserve a cached refusal, never restart it.
        if (current !in setOf(RunStatus.OPEN, RunStatus.RUNNING, RunStatus.EVALUATED)) unavailable()
        val work =
            session.evaluationLock.withLock {
                session.evaluation ?: run {
                    session.scope.ensureActive()
                    session.scope
                        .async {
                            guarded(session, id) {
                                withContext(Dispatchers.IO) { running(id) }
                                session.runner.evaluate()
                            }
                        }.also { session.evaluation = it }
                }
            }
        return withTimeoutOrNull(
            session.runner.context.syncBudget
                .toMillis(),
        ) { work.await() }
    }

    /** Owner-checked observation only: polling never starts a read, evaluation or decision journal. */
    suspend fun progress(
        id: UUID,
        caller: Caller,
    ): RunProgress {
        val session = owned(id, caller)
        val current = withContext(Dispatchers.IO) { status(id) }
        if (current !in setOf(RunStatus.OPEN, RunStatus.RUNNING, RunStatus.EVALUATED, RunStatus.FAILED)) unavailable()
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
        val session = lock.withLock { sessions[id]?.takeUnless { it.closing } } ?: unavailable()
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
            withContext(NonCancellable) {
                session.failureLock.withLock {
                    if (session.failure == null) session.failure = e
                    if (!session.failureRecorded) {
                        withContext(Dispatchers.IO) { failed(id) }
                        session.failureRecorded = true
                    }
                }
            }
            throw e
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

    private fun unavailable(): Nothing =
        throw AutomationException(ErrorCode.RUN_ABANDONED, "the pinned run execution context is unavailable")
}
