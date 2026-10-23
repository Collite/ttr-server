// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.ledger.RunStatus
import org.tatrman.automation.queue.RunDelivery
import org.tatrman.automation.queue.RunWorkQueue
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class BatchDeadlineSpec :
    StringSpec({
        val t0 = Instant.parse("2026-10-23T12:00:00Z")
        val budget = Duration.ofSeconds(10)

        "idle runs expire automatically and clock rollback cannot reset the original refusal or a new same-key run" {
            val clock = BatchClock(t0)
            val f = RunSessionFixture(batchDefault = budget, clock = clock)
            try {
                val id = UUID.fromString(f.open().response.runId)
                clock.now = t0 + budget
                waitUntil { f.states[id] == RunStatus.FAILED }
                (f.failures.getValue(id) as AutomationException).code shouldBe ErrorCode.BUDGET
                val statusReads = f.statusReads.get()
                shouldThrow<AutomationException> {
                    f.runs.progress(id, f.caller.copy(sub = "other"))
                }.code shouldBe ErrorCode.RUN_ABANDONED
                f.statusReads.get() shouldBe statusReads
                clock.now = t0.minusSeconds(100)
                repeat(2) {
                    shouldThrow<AutomationException> { f.runs.evaluate(id, f.caller) }.code shouldBe ErrorCode.BUDGET
                    shouldThrow<AutomationException> { f.runs.progress(id, f.caller) }.code shouldBe ErrorCode.BUDGET
                    shouldThrow<AutomationException> {
                        f.runs.read(id, f.caller, { "placed" }) { r, name -> r.island(name) }
                    }.code shouldBe ErrorCode.BUDGET
                }
                val next = UUID.fromString(f.open().response.runId)
                (next != id) shouldBe true
                f.runs.progress(next, f.caller).status shouldBe "open"
                f.reads.get() shouldBe 0
                f.outputs.size shouldBe 0
            } finally {
                f.close()
            }
        }

        "queue wait consumes the batch budget without acquiring a claim or reading Query" {
            val clock = BatchClock(t0)
            val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val queue = HoldingQueue()
            val workers = RunWorkers(queue, parent, 1, poll = Duration.ofMillis(5), maintenance = {})
            val f = RunSessionFixture(batchDefault = budget, clock = clock, workers = workers)
            try {
                val id = UUID.fromString(f.open().response.runId)
                coroutineScope {
                    val request = async { runCatching { f.runs.evaluate(id, f.caller) } }
                    waitUntil { queue.enqueues.get() == 1 }
                    clock.now = t0 + budget
                    val error = withTimeout(5000) { request.await().exceptionOrNull() } as AutomationException
                    error.code shouldBe ErrorCode.BUDGET
                    shouldThrow<AutomationException> { f.runs.evaluate(id, f.caller) }.code shouldBe ErrorCode.BUDGET
                }
                f.states[id] shouldBe RunStatus.FAILED
                queue.claims.get() shouldBe 0
                f.reads.get() shouldBe 0
                f.evaluations.size shouldBe 0
            } finally {
                workers.shutdown()
                f.close()
                parent.cancel()
            }
        }

        "completed waves do not reset the deadline and an active later wave is cancelled before staging" {
            val clock = BatchClock(t0)
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val reads = AtomicInteger()
            val f =
                RunSessionFixture(
                    batchDefault = budget,
                    clock = clock,
                    columns = listOf(ManifestColumn("order_number", "text"), ManifestColumn("verdict_reason", "text")),
                    door =
                        Door { read ->
                            reads.incrementAndGet()
                            if (read.island == "shortfall") {
                                clock.now = t0 + Duration.ofSeconds(6)
                                entered.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    cancelled.complete(Unit)
                                }
                            }
                            clock.now = t0 + Duration.ofSeconds(4)
                            asyncRows(read)
                        },
                )
            try {
                val id = UUID.fromString(f.open().response.runId)
                coroutineScope {
                    val request = async { runCatching { f.runs.evaluate(id, f.caller) } }
                    withTimeout(5000) { entered.await() }
                    f.outputs.size shouldBe 1
                    f.outputs.single().island shouldBe "placed"
                    clock.now = t0 + budget
                    (withTimeout(5000) { request.await().exceptionOrNull() } as AutomationException).code shouldBe
                        ErrorCode.BUDGET
                    withTimeout(5000) { cancelled.await() }
                }
                reads.get() shouldBe 2
                f.outputs.size shouldBe 1
                f.evaluations.size shouldBe 0
            } finally {
                f.close()
            }
        }

        "release joins non-interruptible cancellation cleanup before executor close and no late output escapes" {
            val clock = BatchClock(t0)
            val entered = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val f =
                RunSessionFixture(
                    batchDefault = budget,
                    clock = clock,
                    door =
                        Door { read ->
                            entered.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                withContext(NonCancellable) {
                                    cleanup.complete(Unit)
                                    finish.await()
                                }
                            }
                            @Suppress("UNREACHABLE_CODE")
                            asyncRows(read)
                        },
                )
            try {
                val id = UUID.fromString(f.open().response.runId)
                coroutineScope {
                    val request =
                        async { runCatching { f.runs.read(id, f.caller, { "placed" }) { r, n -> r.island(n) } } }
                    withTimeout(5000) { entered.await() }
                    clock.now = t0 + budget
                    withTimeout(5000) { cleanup.await() }
                    shouldThrow<AutomationException> { f.runs.progress(id, f.caller) }.code shouldBe ErrorCode.BUDGET
                    val release = async { f.runs.release(id) }
                    withTimeoutOrNull(50) {
                        release.await()
                        true
                    } shouldBe null
                    finish.complete(Unit)
                    withTimeout(5000) { release.await() }
                    (request.await().exceptionOrNull() as AutomationException).code shouldBe ErrorCode.BUDGET
                }
                f.outputs.size shouldBe 0
            } finally {
                finish.complete(Unit)
                f.close()
            }
        }

        "a blocking blob publication cannot journal or return late output after its run is cancelled" {
            val clock = BatchClock(t0)
            val entered = java.util.concurrent.CountDownLatch(1)
            val finish = java.util.concurrent.CountDownLatch(1)
            val store =
                org.tatrman.automation.staging.LocalStagingStore(
                    java.nio.file.Files
                        .createTempDirectory("au-deadline-staging"),
                )
            val blocked =
                object : org.tatrman.automation.staging.StagingStore by store {
                    override fun putIfAbsent(
                        path: String,
                        bytes: ByteArray,
                    ): Boolean {
                        val created = store.putIfAbsent(path, bytes)
                        entered.countDown()
                        check(finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        return created
                    }
                }
            val f = RunSessionFixture(batchDefault = budget, clock = clock, stagingStore = blocked)
            try {
                val id = UUID.fromString(f.open().response.runId)
                coroutineScope {
                    val request =
                        async {
                            runCatching {
                                f.runs.read(id, f.caller, { "placed" }) { r, name -> r.island(name) }
                            }
                        }
                    withContext(Dispatchers.IO) { check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                    clock.now = t0 + budget
                    waitUntil { f.states[id] == RunStatus.FAILED }
                    val release = async { f.runs.release(id) }
                    withTimeoutOrNull(50) {
                        release.await()
                        true
                    } shouldBe null
                    finish.countDown()
                    withTimeout(5000) { release.await() }
                    (request.await().exceptionOrNull() as AutomationException).code shouldBe ErrorCode.BUDGET
                }
                f.outputs.size shouldBe 0
                // The unreferenced immutable blob is left for retention; no output ref was published.
                f.evaluations.size shouldBe 0
            } finally {
                finish.countDown()
                f.close()
            }
        }

        "a completed durable evaluation stays readable when the batch clock later passes its deadline" {
            val clock = BatchClock(t0)
            val f =
                RunSessionFixture(
                    batchDefault = budget,
                    clock = clock,
                    door = Door(::asyncRows),
                    columns = listOf(ManifestColumn("order_number", "text"), ManifestColumn("verdict_reason", "text")),
                )
            try {
                val id = UUID.fromString(f.open().response.runId)
                val result = f.runs.evaluate(id, f.caller)
                clock.now = t0 + Duration.ofHours(2)
                f.runs.evaluate(id, f.caller) shouldBe result
                f.runs.progress(id, f.caller).status shouldBe "evaluated"
                f.failures.size shouldBe 0
                f.outputs.size shouldBe 4
            } finally {
                f.close()
            }
        }

        "a guard refusal remains the original failure after the batch clock expires" {
            val clock = BatchClock(t0)
            val f =
                RunSessionFixture(
                    batchDefault = budget,
                    clock = clock,
                    door = Door { throw AutomationException(ErrorCode.OUT_OF_SCOPE_READ, "original refusal") },
                )
            try {
                val id = UUID.fromString(f.open().response.runId)
                shouldThrow<AutomationException> { f.runs.evaluate(id, f.caller) }.code shouldBe
                    ErrorCode.OUT_OF_SCOPE_READ
                clock.now = t0 + budget
                shouldThrow<AutomationException> { f.runs.progress(id, f.caller) }.code shouldBe
                    ErrorCode.OUT_OF_SCOPE_READ
                (f.failures.getValue(id) as AutomationException).code shouldBe ErrorCode.OUT_OF_SCOPE_READ
                f.outputs.size shouldBe 0
            } finally {
                f.close()
            }
        }

        "a recorded refusal keeps the deadline monitor alive to cancel a sibling read without another HTTP call" {
            val clock = BatchClock(t0)
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val f =
                RunSessionFixture(
                    batchDefault = budget,
                    clock = clock,
                    columns = listOf(ManifestColumn("order_number", "text"), ManifestColumn("verdict_reason", "text")),
                    door =
                        Door { read ->
                            when (read.sql) {
                                "hold" -> {
                                    entered.complete(Unit)
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        cancelled.complete(Unit)
                                    }
                                }
                                "release" -> {
                                    entered.await()
                                    throw AutomationException(ErrorCode.OUT_OF_SCOPE_READ, "original refusal")
                                }
                                else -> asyncRows(read)
                            }
                        },
                )
            try {
                val id = UUID.fromString(f.open().response.runId)
                shouldThrow<AutomationException> { f.runs.evaluate(id, f.caller) }.code shouldBe
                    ErrorCode.OUT_OF_SCOPE_READ
                f.states[id] shouldBe RunStatus.FAILED
                cancelled.isCompleted shouldBe false
                clock.now = t0 + budget
                withTimeout(5000) { cancelled.await() }
                (f.failures.getValue(id) as AutomationException).code shouldBe ErrorCode.OUT_OF_SCOPE_READ
                f.evaluations.size shouldBe 0
            } finally {
                f.close()
            }
        }

        "a failed deadline journal retries automatically even after clock rollback without resuming execution" {
            val clock = BatchClock(t0)
            val attempts = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val f =
                RunSessionFixture(batchDefault = budget, clock = clock, beforeFailure = {
                    if (attempts.incrementAndGet() == 1) {
                        clock.now = t0.minusSeconds(100)
                        entered.complete(Unit)
                        error("journal unavailable")
                    }
                })
            try {
                val id = UUID.fromString(f.open().response.runId)
                clock.now = t0 + budget
                withTimeout(5000) { entered.await() }
                waitUntil { f.states[id] == RunStatus.FAILED }
                attempts.get() shouldBe 2
                shouldThrow<AutomationException> { f.runs.progress(id, f.caller) }.code shouldBe ErrorCode.BUDGET
                f.reads.get() shouldBe 0
            } finally {
                f.close()
            }
        }
    })

private suspend fun waitUntil(predicate: () -> Boolean) {
    withTimeout(5000) { while (!predicate()) delay(5) }
}

internal class BatchClock(
    @Volatile var now: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}

private class HoldingQueue : RunWorkQueue {
    val enqueues = AtomicInteger()
    val claims = AtomicInteger()

    override fun enqueue(
        run: UUID,
        owner: UUID,
    ) {
        enqueues.incrementAndGet()
    }

    override fun read(
        owner: UUID,
        visibilitySeconds: Int,
    ): RunDelivery? = null

    override fun claim(delivery: RunDelivery): Boolean {
        claims.incrementAndGet()
        return true
    }

    override fun archive(delivery: RunDelivery) = Unit

    override fun archiveFinished(limit: Int): Int = 0
}
