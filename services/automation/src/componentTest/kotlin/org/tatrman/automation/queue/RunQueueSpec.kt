// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.queue

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.MutableClock
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.ledger.*
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RunQueueSpec :
    StringSpec({
        LedgerTestDb.ready()
        val clock = MutableClock(Instant.parse("2026-10-23T10:00:00Z"))
        val ledger = Ledger(Duration.ofMinutes(30), clock)
        val pgmq = Pgmq()
        val queue = RunQueue(pgmq, clock)
        val owner = UUID.randomUUID()
        beforeEach { LedgerTestDb.reset() }

        fun open() = ledger.claimRun(RunOpen("release_order")).runId

        fun redeliver() {
            transaction { exec("UPDATE pgmq.q_automation_runs SET vt = clock_timestamp() - interval '1 second'") }
        }

        "concurrent enqueue is atomic and idempotent, with only run and owner handles in the delivery" {
            val run = open()
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(4)
            try {
                val jobs =
                    (1..8).map {
                        pool.submit {
                            start.await()
                            queue.enqueue(run, owner)
                        }
                    }
                start.countDown()
                jobs.forEach { it.get(10, TimeUnit.SECONDS) }
                pgmq.metrics(LedgerQueue.RUNS).queueLength shouldBe 1L
                transaction { RunEvaluations.selectAll().count() } shouldBe 1L
                val message = pgmq.read(LedgerQueue.RUNS, 30).single()
                message.message shouldBe
                    buildJsonObject {
                        put("run", run.toString())
                        put("owner", owner.toString())
                    }
                queue.enqueue(run, owner)
                shouldThrow<AutomationException> { queue.enqueue(run, UUID.randomUUID()) }
                pgmq.metrics(LedgerQueue.RUNS).queueLength shouldBe 1L
            } finally {
                pool.shutdownNow()
            }
        }

        "a failed dispatch insert rolls back its pgmq send in the same transaction" {
            val run = open()
            transaction {
                exec(
                    "ALTER TABLE automation.run_evaluations ADD CONSTRAINT queue_reject CHECK (false) NOT VALID",
                )
            }
            try {
                shouldThrow<Exception> { queue.enqueue(run, owner) }
                pgmq.metrics(LedgerQueue.RUNS).queueLength shouldBe 0L
                transaction { RunEvaluations.selectAll().count() } shouldBe 0L
            } finally {
                transaction { exec("ALTER TABLE automation.run_evaluations DROP CONSTRAINT queue_reject") }
            }
            queue.enqueue(run, owner)
            pgmq.metrics(LedgerQueue.RUNS).queueLength shouldBe 1L
        }

        "owner filtering and a permanent execution claim protect against visibility expiry and forged deliveries" {
            val run = open()
            queue.enqueue(run, owner)
            queue.read(UUID.randomUUID(), 30) shouldBe null
            val delivery = queue.read(owner, 30)!!
            queue.claim(delivery.copy(owner = UUID.randomUUID())) shouldBe false
            queue.claim(delivery.copy(messageId = delivery.messageId + 1)) shouldBe false
            queue.claim(delivery.copy(run = UUID.randomUUID())) shouldBe false
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(4)
            try {
                val jobs =
                    (1..8).map {
                        pool.submit<Boolean> {
                            start.await()
                            queue.claim(delivery)
                        }
                    }
                start.countDown()
                jobs.count { it.get(10, TimeUnit.SECONDS) } shouldBe 1
            } finally {
                pool.shutdownNow()
            }
            ledger.findRun(run)!!.status shouldBe RunStatus.RUNNING
            redeliver()
            val retry = queue.read(owner, 30)!!
            retry shouldBe delivery
            queue.claim(retry) shouldBe false
            transaction { RunEvaluations.selectAll().single()[RunEvaluations.claimedAt]!!.toInstant() } shouldBe
                clock.instant()
        }

        "terminal, evaluated, waiting and sealed rows cannot enqueue or acquire evaluation authority" {
            for (state in listOf(
                RunStatus.CLOSED,
                RunStatus.FAILED,
                RunStatus.ABANDONED,
                RunStatus.WAITING,
                RunStatus.EVALUATED,
            )) {
                val run = open()
                queue.enqueue(run, owner)
                val delivery = queue.read(owner, 30)!!
                ledger.markStatus(run, state)
                shouldThrow<AutomationException> { queue.enqueue(run, owner) }
                queue.claim(delivery) shouldBe false
                ledger.findRun(run)!!.status shouldBe state
                queue.archive(delivery)
            }
            val sealed = open()
            queue.enqueue(sealed, owner)
            val delivery = queue.read(owner, 30)!!
            ledger.sealProtocol(sealed)
            shouldThrow<AutomationException> { queue.enqueue(sealed, owner) }
            queue.claim(delivery) shouldBe false
        }

        "a restarted owner never adopts old work; abandonment frees the key and terminal cleanup archives its trigger" {
            val run = ledger.claimRun(RunOpen("release_order", idempotencyKey = "engine:lost")).runId
            queue.enqueue(run, owner)
            queue.claim(queue.read(owner, 30)!!) shouldBe true
            redeliver()
            val restarted = UUID.randomUUID()
            queue.read(restarted, 30) shouldBe null
            queue.archiveFinished() shouldBe 0
            clock.now = clock.now + Duration.ofHours(1)
            ledger.abandonExpiredRuns() shouldBe listOf(run)
            queue.archiveFinished() shouldBe 1
            queue.archiveFinished() shouldBe 0
            pgmq.metrics(LedgerQueue.RUNS).queueLength shouldBe 0L
            ledger.findRun(run)!!.status shouldBe RunStatus.ABANDONED
            (ledger.claimRun(RunOpen("release_order", idempotencyKey = "engine:lost")) is Claim.Claimed) shouldBe true
        }

        "archive and its dispatch stamp roll back together, and wrong handles cannot archive another trigger" {
            val run = open()
            queue.enqueue(run, owner)
            val delivery = queue.read(owner, 30)!!
            queue.archive(delivery.copy(owner = UUID.randomUUID()))
            pgmq.metrics(LedgerQueue.RUNS).queueLength shouldBe 1L
            transaction {
                exec(
                    "ALTER TABLE automation.run_evaluations ADD CONSTRAINT ack_reject CHECK (archived_at IS NULL) NOT VALID",
                )
            }
            try {
                shouldThrow<Exception> { queue.archive(delivery) }
                pgmq.metrics(LedgerQueue.RUNS).queueLength shouldBe 1L
                transaction { RunEvaluations.selectAll().single()[RunEvaluations.archivedAt] } shouldBe null
            } finally {
                transaction { exec("ALTER TABLE automation.run_evaluations DROP CONSTRAINT ack_reject") }
            }
            queue.archive(delivery)
            queue.archive(delivery)
            pgmq.metrics(LedgerQueue.RUNS).queueLength shouldBe 0L
        }

        "bounded concurrent terminal cleanup skips live runs and does not repeatedly select already archived tickets" {
            val live = open()
            queue.enqueue(live, owner)
            repeat(7) {
                val run = open()
                queue.enqueue(run, owner)
                ledger.markStatus(run, RunStatus.EVALUATED)
            }
            val pool = Executors.newFixedThreadPool(4)
            try {
                val jobs = (1..4).map { pool.submit<Int> { queue.archiveFinished(2) } }
                val total = jobs.sumOf { it.get(10, TimeUnit.SECONDS) } + queue.archiveFinished(2)
                total shouldBe 7
                queue.archiveFinished() shouldBe 0
                pgmq.metrics(LedgerQueue.RUNS).queueLength shouldBe 1L
                ledger.findRun(live)!!.status shouldBe RunStatus.OPEN
            } finally {
                pool.shutdownNow()
            }
        }

        "poison owner deliveries are archived; mutations refuse nested transactions" {
            pgmq.send(LedgerQueue.RUNS, buildJsonObject { put("owner", owner.toString()) })
            shouldThrow<IllegalArgumentException> { queue.read(owner, 30) }
            queue.read(owner, 30) shouldBe null
            val run = open()
            shouldThrow<IllegalStateException> { transaction { queue.enqueue(run, owner) } }
            queue.enqueue(run, owner)
            val delivery = queue.read(owner, 30)!!
            shouldThrow<IllegalStateException> { transaction { queue.claim(delivery) } }
            shouldThrow<IllegalStateException> { transaction { queue.archive(delivery) } }
            shouldThrow<IllegalStateException> { transaction { queue.archiveFinished() } }
            shouldThrow<IllegalArgumentException> { queue.archiveFinished(0) }
            shouldThrow<IllegalArgumentException> { queue.read(owner, 0) }
        }
    })
