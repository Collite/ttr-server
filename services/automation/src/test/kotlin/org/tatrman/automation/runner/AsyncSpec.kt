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
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class AsyncSpec :
    StringSpec({
        "polling an open run is observation only and checks subject ownership before the ledger" {
            val fixture = asyncFixture()
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                repeat(3) { fixture.runs.progress(id, fixture.caller).status shouldBe "open" }
                fixture.reads.get() shouldBe 0
                fixture.outputs.size shouldBe 0
                fixture.evaluations.size shouldBe 0
                val statuses = fixture.statusReads.get()
                for (operation in listOf<suspend () -> Any?>(
                    { fixture.runs.progress(id, fixture.caller.copy(sub = "other")) },
                    { fixture.runs.evaluate(id, fixture.caller.copy(sub = "other")) },
                )) {
                    shouldThrow<AutomationException> { operation() }.code shouldBe ErrorCode.RUN_ABANDONED
                }
                fixture.statusReads.get() shouldBe statuses
            } finally {
                fixture.close()
            }
        }

        "a disconnected evaluation waiter leaves one shared decision running for all later callers" {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val reads = AtomicInteger()
            val starts = AtomicInteger()
            val fixture =
                asyncFixture(
                    beforeRunning = { starts.incrementAndGet() },
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
                    val disconnected = launch { fixture.runs.evaluate(id, fixture.caller) }
                    withTimeout(5000) { entered.await() }
                    disconnected.cancelAndJoin()
                    fixture.runs.progress(id, fixture.caller).status shouldBe "running"
                    val retry = fixture.open()
                    retry.httpStatus shouldBe 202
                    retry.response.runId shouldBe id.toString()
                    fixture.created.get() shouldBe 1
                    finish.complete(Unit)
                    val results = (1..5).map { async { fixture.runs.evaluate(id, fixture.caller) } }.awaitAll()
                    results.distinct().size shouldBe 1
                    results
                        .first()!!
                        .verdict.routes
                        .getValue("release")
                        .rowCount shouldBe 1L
                    fixture.runs.progress(id, fixture.caller).verdict shouldBe results.first()!!.verdict
                }
                starts.get() shouldBe 1
                reads.get() shouldBe 4
                fixture.evaluations.size shouldBe 1
            } finally {
                finish.complete(Unit)
                fixture.close()
            }
        }

        "polling observes evaluations started by another read path without starting another evaluation" {
            val starts = AtomicInteger()
            val fixture = asyncFixture(beforeRunning = { starts.incrementAndGet() })
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                val result = fixture.runs.read(id, fixture.caller, { Unit }) { runner, _ -> runner.evaluate() }
                val progress = fixture.runs.progress(id, fixture.caller)
                progress.status shouldBe "evaluated"
                progress.verdict shouldBe result.verdict
                progress.guards shouldBe result.guards
                starts.get() shouldBe 0
                fixture.evaluations.size shouldBe 1
            } finally {
                fixture.close()
            }
        }

        "polling waits for the completed summary after EVALUATED commits without blocking or returning a refusal" {
            val committed = java.util.concurrent.CountDownLatch(1)
            val finish = java.util.concurrent.CountDownLatch(1)
            val fixture =
                RunSessionFixture(
                    door = Door(::asyncRows),
                    columns = listOf(ManifestColumn("order_number", "text"), ManifestColumn("verdict_reason", "text")),
                    afterEvaluation = {
                        committed.countDown()
                        check(finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    },
                )
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                coroutineScope {
                    val request = async { fixture.runs.evaluate(id, fixture.caller) }
                    withContext(Dispatchers.IO) { check(committed.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                    fixture.states[id] shouldBe RunStatus.EVALUATED
                    withTimeout(1000) { fixture.runs.progress(id, fixture.caller) }.status shouldBe "running"
                    fixture.evaluations.size shouldBe 1
                    finish.countDown()
                    request
                        .await()!!
                        .verdict.routes
                        .getValue("release")
                        .rowCount shouldBe 1L
                    fixture.runs.progress(id, fixture.caller).status shouldBe "evaluated"
                }
            } finally {
                finish.countDown()
                fixture.close()
            }
        }

        "release joins a blocked running journal before closing the runner and never starts Query afterward" {
            val entered = java.util.concurrent.CountDownLatch(1)
            val finish = java.util.concurrent.CountDownLatch(1)
            val reads = AtomicInteger()
            val fixture =
                asyncFixture(
                    beforeRunning = {
                        entered.countDown()
                        check(finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    },
                    door =
                        Door {
                            reads.incrementAndGet()
                            asyncRows(it)
                        },
                )
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                coroutineScope {
                    val request = launch { fixture.runs.evaluate(id, fixture.caller) }
                    withContext(Dispatchers.IO) { check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                    val release = async { fixture.runs.release(id) }
                    withTimeoutOrNull(50) {
                        release.await()
                        true
                    } shouldBe null
                    finish.countDown()
                    release.await()
                    request.join()
                    reads.get() shouldBe 0
                    fixture.evaluations.size shouldBe 0
                    shouldThrow<AutomationException> { fixture.runs.progress(id, fixture.caller) }.code shouldBe
                        ErrorCode.RUN_ABANDONED
                }
            } finally {
                finish.countDown()
                fixture.close()
            }
        }

        "a missing running journal fails closed before Query and is not retried by polls or evaluate" {
            val starts = AtomicInteger()
            val failures = AtomicInteger()
            val reads = AtomicInteger()
            val fixture =
                asyncFixture(
                    beforeRunning = {
                        starts.incrementAndGet()
                        error("journal offline")
                    },
                    beforeFailure = { failures.incrementAndGet() },
                    door =
                        Door {
                            reads.incrementAndGet()
                            asyncRows(it)
                        },
                )
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                shouldThrow<IllegalStateException> { fixture.runs.evaluate(id, fixture.caller) }.message shouldBe
                    "journal offline"
                repeat(2) {
                    shouldThrow<IllegalStateException> { fixture.runs.progress(id, fixture.caller) }.message shouldBe
                        "journal offline"
                    shouldThrow<IllegalStateException> { fixture.runs.evaluate(id, fixture.caller) }.message shouldBe
                        "journal offline"
                }
                starts.get() shouldBe 1
                failures.get() shouldBe 1
                fixture.states[id] shouldBe RunStatus.FAILED
                reads.get() shouldBe 0
            } finally {
                fixture.close()
            }
        }

        "polling retries a failed failure journal and returns the original refusal without another Query read" {
            val attempts = AtomicInteger()
            val reads = AtomicInteger()
            val fixture =
                asyncFixture(
                    beforeFailure = {
                        if (attempts.incrementAndGet() ==
                            1
                        ) {
                            error("failure journal offline")
                        }
                    },
                    door =
                        Door {
                            reads.incrementAndGet()
                            throw AutomationException(ErrorCode.OUT_OF_SCOPE_READ, "scan refused")
                        },
                )
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                shouldThrow<IllegalStateException> { fixture.runs.evaluate(id, fixture.caller) }.message shouldBe
                    "failure journal offline"
                fixture.states[id] shouldBe RunStatus.RUNNING
                shouldThrow<AutomationException> { fixture.runs.progress(id, fixture.caller) }.code shouldBe
                    ErrorCode.OUT_OF_SCOPE_READ
                fixture.states[id] shouldBe RunStatus.FAILED
                shouldThrow<AutomationException> { fixture.runs.evaluate(id, fixture.caller) }.code shouldBe
                    ErrorCode.OUT_OF_SCOPE_READ
                attempts.get() shouldBe 2
                reads.get() shouldBe 1
            } finally {
                fixture.close()
            }
        }

        "polls do not return cached success for abandoned, closed, waiting, failed or released contexts" {
            val fixture = asyncFixture()
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                fixture.runs
                    .evaluate(id, fixture.caller)!!
                    .verdict.routes
                    .getValue("release")
                    .rowCount shouldBe 1L
                for (state in listOf(RunStatus.CLOSED, RunStatus.ABANDONED, RunStatus.WAITING, RunStatus.FAILED)) {
                    fixture.states[id] = state
                    shouldThrow<AutomationException> { fixture.runs.progress(id, fixture.caller) }.code shouldBe
                        ErrorCode.RUN_ABANDONED
                }
                fixture.runs.release(id)
                shouldThrow<AutomationException> { fixture.runs.progress(id, fixture.caller) }.code shouldBe
                    ErrorCode.RUN_ABANDONED
            } finally {
                fixture.close()
            }
        }
    })

internal fun asyncRows(read: DoorRead): ReadResult =
    ReadResult(
        Table(
            read.columns,
            if (read.sql == "hold") emptyList() else listOf(listOf("4711", "SQL reason")),
        ),
    )

internal fun asyncFixture(
    sync: Duration = Duration.ofSeconds(60),
    beforeRunning: () -> Unit = {},
    beforeFailure: () -> Unit = {},
    door: Door? = null,
): RunSessionFixture =
    RunSessionFixture(
        syncDefault = sync,
        beforeRunning = beforeRunning,
        beforeFailure = beforeFailure,
        door = door ?: Door(::asyncRows),
        columns = listOf(ManifestColumn("order_number", "text"), ManifestColumn("verdict_reason", "text")),
    )
