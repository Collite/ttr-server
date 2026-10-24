// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.MutableClock
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.queue.Pgmq
import org.tatrman.automation.queue.LedgerQueue
import org.tatrman.automation.runner.EvaluationResult
import org.tatrman.automation.runner.VerdictSummary
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RunBudgetSpec :
    StringSpec({
        LedgerTestDb.ready()
        val t0 = Instant.parse("2026-10-23T12:00:00Z")
        val clock = MutableClock(t0)
        val budget = Duration.ofSeconds(10)
        val ledger = Ledger(budget, clock)
        val queue = RunQueue(Pgmq(), clock, budget)
        val owner = UUID.randomUUID()
        val result = EvaluationResult(VerdictSummary(emptyMap(), 0), emptyList())
        beforeEach {
            LedgerTestDb.reset()
            clock.now = t0
        }

        fun open(
            batch: Duration? = null,
            sync: Duration? = null,
        ) = ledger
            .claimRun(
                RunOpen("release_order", asOf = t0.minusSeconds(86400), budgetBatch = batch, budgetSync = sync),
            ).runId

        fun output(
            id: UUID,
            port: String = "rows",
        ) = RunOutput(id, "placed", port, 1, 12, "runs/$id/$port.arrow", "sha256:abc")

        fun status(id: UUID) = ledger.findRun(id)!!.status

        fun budgetRefusal(block: () -> Unit) {
            shouldThrow<AutomationException>(block).code shouldBe ErrorCode.BUDGET
        }

        fun outputs() = transaction { RunOutputs.selectAll().count() }

        fun decisions() =
            transaction {
                RunProtocol.selectAll().where { RunProtocol.section eq ProtocolSection.DECISION.wire }.count()
            }

        "a claim returns exactly the persisted microsecond timestamp, independently of user asOf" {
            clock.now = t0.plusNanos(123456789)
            val claimed = ledger.claimRun(RunOpen("release_order", asOf = t0.minusSeconds(100000))) as Claim.Claimed
            claimed.startedAt shouldBe t0.plusNanos(123456000)
            transaction { Runs.selectAll().single()[Runs.startedAt].toInstant() } shouldBe claimed.startedAt
        }

        "begin, output and new decision commit refuse at the exact deadline while earlier evidence stays immutable" {
            val first = open()
            val late = open()
            clock.now = t0 + budget - Duration.ofNanos(1000)
            ledger.startEvaluation(first)
            ledger.putOutput(output(first))
            ledger.completeEvaluation(first, result)
            clock.now = t0 + budget
            budgetRefusal { ledger.startEvaluation(late) }
            budgetRefusal { ledger.putOutput(output(late)) }
            budgetRefusal { ledger.putOutput(output(first, "late")) }
            budgetRefusal { ledger.completeEvaluation(late, result) }
            outputs() shouldBe 1L
            decisions() shouldBe 1L
            status(late) shouldBe RunStatus.OPEN
            status(first) shouldBe RunStatus.EVALUATED
            // An already committed identical decision is acknowledgement, not a second execution.
            ledger.startEvaluation(first)
            ledger.completeEvaluation(first, result)
            decisions() shouldBe 1L
        }

        "pinned batch then sync then configured default determine the execution deadline without overflow" {
            val batch = open(batch = Duration.ofSeconds(5), sync = Duration.ofMillis(1))
            val sync = open(sync = Duration.ofSeconds(2))
            val default = open()
            val huge = open(batch = Duration.ofMillis(Long.MAX_VALUE))
            clock.now = t0.plusSeconds(2)
            budgetRefusal { ledger.startEvaluation(sync) }
            ledger.startEvaluation(batch)
            ledger.startEvaluation(default)
            clock.now = t0.plusSeconds(5)
            budgetRefusal { ledger.completeEvaluation(batch, result) }
            ledger.putOutput(output(default))
            clock.now = t0 + budget
            budgetRefusal { ledger.putOutput(output(default, "late")) }
            clock.now = t0 + Duration.ofDays(365)
            ledger.startEvaluation(huge)
            ledger.completeEvaluation(huge, result)
            status(huge) shouldBe RunStatus.EVALUATED
        }

        "expired dispatch cannot enqueue or acquire execution authority and forged handles reveal no budget" {
            val queued = open()
            val notQueued = open()
            queue.enqueue(queued, owner)
            val delivery = queue.read(owner, 30)!!
            clock.now = t0 + budget
            queue.claim(delivery.copy(owner = UUID.randomUUID())) shouldBe false
            queue.claim(delivery.copy(messageId = delivery.messageId + 1)) shouldBe false
            budgetRefusal { queue.claim(delivery) }
            budgetRefusal { queue.enqueue(queued, owner) }
            shouldThrow<AutomationException> { queue.enqueue(queued, UUID.randomUUID()) }.code shouldBe
                ErrorCode.RUN_ABANDONED
            budgetRefusal { queue.enqueue(notQueued, owner) }
            transaction { RunEvaluations.selectAll().single()[RunEvaluations.claimedAt] } shouldBe null
            status(queued) shouldBe RunStatus.OPEN
            Pgmq().metrics(LedgerQueue.RUNS).queueLength shouldBe 1L
        }

        "a blocked publication or dispatch claim checks the clock after acquiring its run lock" {
            for (operation in listOf("begin", "output", "decision", "claim")) {
                clock.now = t0
                val id = open()
                queue.enqueue(id, owner)
                val delivery = queue.read(owner, 30)!!
                val locked = CountDownLatch(1)
                val finish = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val holder =
                        pool.submit {
                            transaction {
                                Runs
                                    .selectAll()
                                    .where { Runs.id eq id }
                                    .forUpdate()
                                    .single()
                                locked.countDown()
                                check(finish.await(10, TimeUnit.SECONDS))
                            }
                        }
                    check(locked.await(10, TimeUnit.SECONDS))
                    val waiting =
                        pool.submit<ErrorCode> {
                            shouldThrow<AutomationException> {
                                when (operation) {
                                    "begin" -> ledger.startEvaluation(id)
                                    "output" -> ledger.putOutput(output(id))
                                    "decision" -> ledger.completeEvaluation(id, result)
                                    "claim" -> queue.claim(delivery)
                                }
                            }.code
                        }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    var blocked = false
                    while (!blocked && System.nanoTime() < deadline) {
                        blocked =
                            transaction {
                                exec(
                                    """SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                                   WHERE datname = current_database() AND wait_event_type = 'Lock'
                                     AND pid <> pg_backend_pid() AND query LIKE '%automation.runs%')""",
                                ) {
                                    it.next()
                                    it.getBoolean(1)
                                } == true
                            }
                        if (!blocked) Thread.sleep(5)
                    }
                    check(blocked) { "publication did not block on the held run lock" }
                    clock.now = t0 + budget
                    finish.countDown()
                    holder.get(10, TimeUnit.SECONDS)
                    waiting.get(10, TimeUnit.SECONDS) shouldBe ErrorCode.BUDGET
                    status(id) shouldBe RunStatus.OPEN
                } finally {
                    finish.countDown()
                    pool.shutdownNow()
                }
            }
            outputs() shouldBe 0L
            decisions() shouldBe 0L
        }

        "typed execution failure records only its stable code and cannot overwrite a terminal run" {
            val id = open()
            ledger.failExecution(id, AutomationException(ErrorCode.BUDGET, "secret in exception text"))
            status(id) shouldBe RunStatus.FAILED
            transaction { Runs.selectAll().where { Runs.id eq id }.single()[Runs.error] }.toString() shouldBe
                "{\"code\":\"budget\"}"
            ledger.failExecution(id, IllegalStateException("later failure"))
            transaction { Runs.selectAll().where { Runs.id eq id }.single()[Runs.error] }.toString() shouldBe
                "{\"code\":\"budget\"}"
            val closed = open()
            ledger.markStatus(closed, RunStatus.CLOSED)
            ledger.failExecution(closed, AutomationException(ErrorCode.BUDGET, "late expiry"))
            status(closed) shouldBe RunStatus.CLOSED
        }

        "lost-owner recovery waits for budget plus grace without reassigning execution" {
            val original = RunOpen("release_order", idempotencyKey = "lost-owner")
            val id = ledger.claimRun(original).runId
            queue.enqueue(id, owner)
            clock.now = t0 + budget
            budgetRefusal { ledger.completeEvaluation(id, result) }
            ledger.claimRun(original) shouldBe Claim.Running(id, RunStatus.OPEN)
            ledger.abandonExpiredRuns() shouldBe emptyList()
            clock.now = t0 + budget + IdempotencyPolicy.GRACE + Duration.ofNanos(1000)
            val successor = ledger.claimRun(original) as Claim.Claimed
            successor.abandoned shouldBe id
            status(id) shouldBe RunStatus.ABANDONED
            queue.archiveFinished() shouldBe 1
        }
    })
