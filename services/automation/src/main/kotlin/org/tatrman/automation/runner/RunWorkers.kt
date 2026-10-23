// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.queue.RunDelivery
import org.tatrman.automation.queue.RunWorkQueue
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded evaluation consumers. Each runScope must belong to ONE pinned run, never the application scope.
 * Owner is an incarnation UUID: a new process cannot acquire an old process's jobs or credentials. Delivery
 * redelivery can recover an unclaimed trigger; after a durable claim, lost execution waits for abandonment.
 * Shutdown cancels/joins the affected run scopes; RunSessions.shutdown then closes their executors.
 */
class RunWorkers(
    private val queue: RunWorkQueue,
    parent: CoroutineScope,
    workers: Int,
    val owner: UUID = UUID.randomUUID(),
    private val visibilitySeconds: Int = 30,
    private val poll: Duration = Duration.ofMillis(250),
    private val maintenanceEvery: Duration = Duration.ofSeconds(15),
    /** Runtime supplies ledger abandonment followed by local session reaping. */
    private val maintenance: suspend () -> Unit,
    private val report: (Exception) -> Unit = { log.warn("automation evaluation worker failed", it) },
) {
    init {
        require(workers in 1..256) { "runner.workers must be in 1..256" }
        require(visibilitySeconds > 0 && !poll.isNegative && poll.toMillis() > 0)
        require(!maintenanceEvery.isNegative && maintenanceEvery.toMillis() > 0)
    }

    private class Pending(
        val scope: CoroutineScope,
        val block: suspend () -> EvaluationResult,
    ) {
        val started = AtomicBoolean()
        val result = CompletableDeferred<EvaluationResult>(scope.coroutineContext[Job])
    }

    private val lock = Mutex()
    private val pending = mutableMapOf<UUID, Pending>()
    private val parentJob = parent.coroutineContext[Job]
    private val job = SupervisorJob(parentJob)
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private var stopped = false

    init {
        repeat(workers) { scope.launch { consume() } }
        scope.launch {
            while (isActive) {
                try {
                    maintenance()
                    withContext(Dispatchers.IO) { queue.archiveFinished() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    runCatching { report(e) }
                }
                delay(maintenanceEvery.toMillis())
            }
        }
    }

    /** Register before enqueue; cancellation cannot split enqueue from its durable dispatch journal. */
    suspend fun evaluate(
        id: UUID,
        runScope: CoroutineScope,
        block: suspend () -> EvaluationResult,
    ): EvaluationResult {
        require(
            runScope.coroutineContext[Job] != null &&
                runScope.coroutineContext[Job] != job &&
                runScope.coroutineContext[Job] != parentJob,
        )
        val work =
            lock.withLock {
                if (stopped || !job.isActive) unavailable()
                check(id !in pending) { "one dispatcher promise per pinned evaluation" }
                Pending(runScope, block).also { pending[id] = it }
            }
        try {
            withContext(NonCancellable + Dispatchers.IO) { queue.enqueue(id, owner) }
            return work.result.await()
        } finally {
            withContext(NonCancellable) {
                lock.withLock { if (pending[id] === work) pending.remove(id) }
                work.result.cancel()
            }
        }
    }

    private suspend fun consume() {
        while (currentCoroutineContext().isActive) {
            try {
                val delivery = withContext(Dispatchers.IO) { queue.read(owner, visibilitySeconds) }
                if (delivery == null) {
                    delay(poll.toMillis())
                    continue
                }
                val work = lock.withLock { pending[delivery.run] }
                if (work == null) {
                    // The original context may have been released. Never reconstruct it or steal its job.
                    delay(poll.toMillis())
                    continue
                }
                if (!work.started.compareAndSet(false, true)) {
                    // Visibility may expire while the first claim is still committing. Do not archive
                    // its trigger early; only that worker or terminal maintenance may acknowledge it.
                    delay(poll.toMillis())
                    continue
                }
                execute(delivery, work)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { report(e) }
                delay(poll.toMillis()) // An unavailable queue cannot spin or permanently kill a worker.
            }
        }
    }

    private suspend fun execute(
        delivery: RunDelivery,
        work: Pending,
    ) {
        try {
            val claimed = withContext(Dispatchers.IO) { queue.claim(delivery) }
            if (!claimed) unavailable()
            // The worker waits on a child of the PINNED run scope. HTTP cancellation is not its parent.
            val executing = work.scope.async { work.block() }
            try {
                work.result.complete(executing.await())
            } finally {
                if (!currentCoroutineContext().isActive) {
                    withContext(NonCancellable) { work.scope.coroutineContext[Job]!!.cancelAndJoin() }
                }
            }
        } catch (e: CancellationException) {
            work.result.cancel(e)
            if (!currentCoroutineContext().isActive) throw e
        } catch (e: Exception) {
            work.result.completeExceptionally(e)
        }
        // On archive failure, periodic terminal maintenance retries the ack without repeating execution.
        withContext(Dispatchers.IO) { queue.archive(delivery) }
    }

    suspend fun shutdown() {
        withContext(NonCancellable) {
            val active =
                lock.withLock {
                    stopped = true
                    pending.values.map { it.scope.coroutineContext[Job]!! }.distinct()
                }
            job.cancel()
            active.forEach { it.cancel() }
            active.forEach { it.join() }
            job.join()
        }
    }

    private fun unavailable(): Nothing =
        throw AutomationException(ErrorCode.RUN_ABANDONED, "the pinned evaluation context is unavailable")

    companion object {
        private val log = LoggerFactory.getLogger(RunWorkers::class.java)
    }
}
