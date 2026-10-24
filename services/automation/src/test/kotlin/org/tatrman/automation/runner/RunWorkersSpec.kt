// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.queue.RunDelivery
import org.tatrman.automation.queue.RunWorkQueue
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RunWorkersSpec :
    StringSpec({
        val result = EvaluationResult(VerdictSummary(emptyMap(), 0), emptyList())

        "only the configured number of evaluations execute while additional durable jobs wait" {
            val f = WorkerFixture(2)
            val entered = Channel<Unit>(Channel.UNLIMITED)
            val gate = CompletableDeferred<Unit>()
            val active = AtomicInteger()
            val peak = AtomicInteger()
            try {
                val jobs =
                    (1..7).map {
                        val run = f.runScope()
                        run.async {
                            f.workers.evaluate(UUID.randomUUID(), run) {
                                val n = active.incrementAndGet()
                                peak.accumulateAndGet(n, ::maxOf)
                                entered.send(Unit)
                                try {
                                    gate.await()
                                    result
                                } finally {
                                    active.decrementAndGet()
                                }
                            }
                        }
                    }
                withTimeout(5000) { repeat(2) { entered.receive() } }
                eventually { f.queue.enqueues.get() == 7 }
                active.get() shouldBe 2
                gate.complete(Unit)
                jobs.awaitAll() shouldBe List(7) { result }
                peak.get() shouldBe 2
                f.queue.claims.get() shouldBe 7
            } finally {
                gate.complete(Unit)
                f.close()
            }
        }

        "visibility redelivery during active work never starts another evaluation or acknowledges its claim early" {
            val f = WorkerFixture(2)
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val id = UUID.randomUUID()
            val calls = AtomicInteger()
            try {
                val scope = f.runScope()
                val task =
                    scope.async {
                        f.workers.evaluate(id, scope) {
                            calls.incrementAndGet()
                            entered.complete(Unit)
                            gate.await()
                            result
                        }
                    }
                withTimeout(5000) { entered.await() }
                repeat(3) { f.queue.redeliver(id) }
                eventually { f.queue.reads.get() >= 4 }
                f.queue.claims.get() shouldBe 1
                calls.get() shouldBe 1
                f.queue.archives.get() shouldBe 0
                gate.complete(Unit)
                task.await() shouldBe result
                eventually { f.queue.archives.get() == 1 }
            } finally {
                gate.complete(Unit)
                f.close()
            }
        }

        "queue read errors retry, failed claims execute nothing and do not kill a worker" {
            val f = WorkerFixture(1)
            try {
                f.queue.beforeEnqueue = { error("enqueue unavailable") }
                val rejected = f.runScope()
                shouldThrow<IllegalStateException> {
                    rejected
                        .async {
                            f.workers.evaluate(
                                UUID.randomUUID(),
                                rejected,
                            ) { error("must not execute") }
                        }.await()
                }.message shouldBe "enqueue unavailable"
                f.queue.beforeEnqueue = {}
                f.queue.claims.get() shouldBe 0
                f.queue.failReads.set(1)
                f.queue.failClaim = true
                val first = f.runScope()
                shouldThrow<IllegalStateException> {
                    first.async { f.workers.evaluate(UUID.randomUUID(), first) { error("must not execute") } }.await()
                }.message shouldBe "claim unavailable"
                f.queue.failClaim = false
                val second = f.runScope()
                second.async { f.workers.evaluate(UUID.randomUUID(), second) { result } }.await() shouldBe result
                eventually { f.reports.isNotEmpty() }
            } finally {
                f.close()
            }
        }

        "shutdown waits for an enqueue already in flight and cancels queued work without executing it" {
            val entered = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val f = WorkerFixture(1)
            f.queue.beforeEnqueue = {
                entered.countDown()
                check(finish.await(10, TimeUnit.SECONDS))
            }
            try {
                val scope = f.runScope()
                val task = scope.async { f.workers.evaluate(UUID.randomUUID(), scope) { error("must not execute") } }
                withContext(Dispatchers.IO) { check(entered.await(10, TimeUnit.SECONDS)) }
                coroutineScope {
                    val stop = async { f.workers.shutdown() }
                    eventually { !scope.isActive }
                    stop.isCompleted shouldBe false
                    finish.countDown()
                    withTimeout(5000) { stop.await() }
                }
                task.isCancelled shouldBe true
                f.queue.claims.get() shouldBe 0
                shouldThrow<AutomationException> {
                    f.workers.evaluate(UUID.randomUUID(), f.runScope()) { result }
                }.code shouldBe ErrorCode.RUN_ABANDONED
            } finally {
                finish.countDown()
                f.close()
            }
        }

        "shutdown joins active evaluation cancellation before returning" {
            val f = WorkerFixture(1)
            val entered = CompletableDeferred<Unit>()
            val cancelling = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            try {
                val scope = f.runScope()
                val task =
                    scope.async {
                        f.workers.evaluate(UUID.randomUUID(), scope) {
                            entered.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                withContext(NonCancellable) {
                                    cancelling.complete(Unit)
                                    finish.await()
                                }
                            }
                        }
                    }
                withTimeout(5000) { entered.await() }
                coroutineScope {
                    val stop = async { f.workers.shutdown() }
                    withTimeout(5000) { cancelling.await() }
                    stop.isCompleted shouldBe false
                    finish.complete(Unit)
                    withTimeout(5000) { stop.await() }
                }
                task.isCancelled shouldBe true
            } finally {
                finish.complete(Unit)
                f.close()
            }
        }

        "all PartRunner evaluation paths share durable dispatch across HTTP disconnects and repeated requests" {
            val f = WorkerFixture(1)
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val reads = AtomicInteger()
            val fixture =
                RunSessionFixture(
                    columns =
                        listOf(
                            org.tatrman.automation.bundle
                                .ManifestColumn("order_number", "text"),
                            org.tatrman.automation.bundle
                                .ManifestColumn("verdict_reason", "text"),
                        ),
                    workers = f.workers,
                    door =
                        Door { read ->
                            reads.incrementAndGet()
                            if (read.island == "placed") {
                                entered.complete(Unit)
                                finish.await()
                            }
                            asyncRows(read)
                        },
                )
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                coroutineScope {
                    // Route/output readers call PartRunner.evaluate directly; they must use the same queue hook.
                    val disconnected =
                        launch { fixture.runs.read(id, fixture.caller, { Unit }) { r, _ -> r.evaluate() } }
                    withTimeout(5000) { entered.await() }
                    disconnected.cancelAndJoin()
                    val retry = async { fixture.runs.evaluate(id, fixture.caller) }
                    fixture.open().httpStatus shouldBe 202
                    finish.complete(Unit)
                    retry
                        .await()!!
                        .verdict.routes
                        .getValue("release")
                        .rowCount shouldBe 1L
                }
                fixture.runs
                    .evaluate(id, fixture.caller)!!
                    .verdict.none shouldBe 0L
                f.queue.enqueues.get() shouldBe 1
                f.queue.claims.get() shouldBe 1
                reads.get() shouldBe 4
                fixture.created.get() shouldBe 1
            } finally {
                finish.complete(Unit)
                fixture.close()
                f.close()
            }
        }

        "a dispatched refusal remains the original cached refusal and never repeats Query or dispatch" {
            val f = WorkerFixture(1)
            val reads = AtomicInteger()
            val fixture =
                RunSessionFixture(
                    workers = f.workers,
                    door =
                        Door {
                            reads.incrementAndGet()
                            throw AutomationException(ErrorCode.OUT_OF_SCOPE_READ, "refused")
                        },
                )
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                repeat(2) {
                    shouldThrow<AutomationException> { fixture.runs.evaluate(id, fixture.caller) }.code shouldBe
                        ErrorCode.OUT_OF_SCOPE_READ
                }
                shouldThrow<AutomationException> { fixture.runs.progress(id, fixture.caller) }.code shouldBe
                    ErrorCode.OUT_OF_SCOPE_READ
                reads.get() shouldBe 1
                f.queue.enqueues.get() shouldBe 1
                f.queue.claims.get() shouldBe 1
            } finally {
                fixture.close()
                f.close()
            }
        }

        "periodic maintenance retries failures, then archives terminal triggers without starting evaluations" {
            val calls = AtomicInteger()
            val f = WorkerFixture(1, maintenance = { if (calls.incrementAndGet() == 1) error("sweep unavailable") })
            try {
                f.queue.failCleanup.set(1)
                eventually { calls.get() >= 3 && f.queue.cleanups.get() >= 2 }
                f.reports.isNotEmpty() shouldBe true
                f.queue.enqueues.get() shouldBe 0
                f.queue.claims.get() shouldBe 0
            } finally {
                f.close()
            }
        }

        "a delivery without its original context cannot acquire a claim" {
            val f = WorkerFixture(1)
            try {
                val id = UUID.randomUUID()
                f.queue.enqueue(id, f.workers.owner)
                eventually { f.queue.reads.get() > 0 }
                f.queue.claims.get() shouldBe 0
                // A still-live original context can submit later; visibility redelivery supplies its trigger.
                val scope = f.runScope()
                val task = scope.async { f.workers.evaluate(id, scope) { result } }
                eventually { f.queue.enqueues.get() == 2 }
                f.queue.redeliver(id)
                withTimeout(5000) { task.await() } shouldBe result
                f.queue.claims.get() shouldBe 1
            } finally {
                f.close()
            }
        }
    })

private suspend fun eventually(predicate: () -> Boolean) {
    withTimeout(5000) { while (!predicate()) delay(5) }
}

private class WorkerFixture(
    workers: Int,
    maintenance: suspend () -> Unit = {},
) {
    private val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val queue = FakeRunQueue()
    val reports = ConcurrentLinkedQueue<Exception>()
    val workers =
        RunWorkers(
            queue,
            parent,
            workers,
            poll = Duration.ofMillis(5),
            maintenanceEvery = Duration.ofMillis(10),
            maintenance = maintenance,
            report = { reports.add(it) },
        )

    fun runScope() = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    suspend fun close() {
        workers.shutdown()
        parent.cancel()
    }
}

private class FakeRunQueue : RunWorkQueue {
    private val tickets = ConcurrentHashMap<UUID, RunDelivery>()
    private val deliveries = ConcurrentHashMap<UUID, ConcurrentLinkedQueue<RunDelivery>>()
    private val claimed = ConcurrentHashMap.newKeySet<UUID>()
    private val sequence = AtomicLong()
    val enqueues = AtomicInteger()
    val claims = AtomicInteger()
    val archives = AtomicInteger()
    val reads = AtomicInteger()
    val cleanups = AtomicInteger()
    val failReads = AtomicInteger()
    val failCleanup = AtomicInteger()

    @Volatile var failClaim = false
    var beforeEnqueue: () -> Unit = {}

    override fun enqueue(
        run: UUID,
        owner: UUID,
    ) {
        beforeEnqueue()
        enqueues.incrementAndGet()
        tickets.computeIfAbsent(run) {
            RunDelivery(sequence.incrementAndGet(), run, owner).also { d ->
                deliveries.computeIfAbsent(owner) { ConcurrentLinkedQueue() }.add(d)
            }
        }
    }

    fun redeliver(run: UUID) {
        val d = tickets.getValue(run)
        deliveries.getValue(d.owner).add(d)
    }

    override fun read(
        owner: UUID,
        visibilitySeconds: Int,
    ): RunDelivery? {
        if (failReads.getAndUpdate { maxOf(0, it - 1) } > 0) error("read unavailable")
        return deliveries[owner]?.poll()?.also { reads.incrementAndGet() }
    }

    override fun claim(delivery: RunDelivery): Boolean {
        if (failClaim) error("claim unavailable")
        claims.incrementAndGet()
        return claimed.add(delivery.run)
    }

    override fun archive(delivery: RunDelivery) {
        archives.incrementAndGet()
    }

    override fun archiveFinished(limit: Int): Int {
        cleanups.incrementAndGet()
        if (failCleanup.getAndUpdate { maxOf(0, it - 1) } > 0) error("cleanup unavailable")
        return 0
    }
}
