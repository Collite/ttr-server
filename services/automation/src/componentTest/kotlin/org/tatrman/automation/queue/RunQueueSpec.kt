// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.queue

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
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

/** D-142 (S3.2 T4): the run queue is a row claim on `run_evaluations` — no pgmq. */
class RunQueueSpec :
    StringSpec({
        LedgerTestDb.ready()
        val clock = MutableClock(Instant.parse("2026-10-23T10:00:00Z"))
        val ledger = Ledger(Duration.ofMinutes(30), clock)
        val queue = RunQueue(clock, Duration.ofMinutes(30))
        val owner = UUID.randomUUID()
        beforeEach {
            LedgerTestDb.reset()
            clock.now = Instant.parse("2026-10-23T10:00:00Z")
        }

        fun open() = ledger.claimRun(RunOpen("release_order")).runId

        "concurrent enqueue is atomic and idempotent; the delivery carries only the run and owner handles" {
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
                transaction { RunEvaluations.selectAll().count() } shouldBe 1L
                queue.read(owner, 30) shouldBe RunDelivery(run, owner)
                queue.enqueue(run, owner)
                shouldThrow<AutomationException> { queue.enqueue(run, UUID.randomUUID()) }
                transaction { RunEvaluations.selectAll().count() } shouldBe 1L
            } finally {
                pool.shutdownNow()
            }
        }

        "a failed dispatch insert leaves no ticket" {
            val run = open()
            transaction {
                exec("ALTER TABLE automation.run_evaluations ADD CONSTRAINT queue_reject CHECK (false) NOT VALID")
            }
            try {
                shouldThrow<Exception> { queue.enqueue(run, owner) }
                transaction { RunEvaluations.selectAll().count() } shouldBe 0L
            } finally {
                transaction { exec("ALTER TABLE automation.run_evaluations DROP CONSTRAINT queue_reject") }
            }
            queue.enqueue(run, owner)
            queue.read(owner, 30) shouldBe RunDelivery(run, owner)
        }

        "T1: two concurrent readers take distinct rows (FOR UPDATE SKIP LOCKED), oldest first, each hidden while read" {
            val runs =
                (1..6).map {
                    val run = open()
                    queue.enqueue(run, owner)
                    clock.now = clock.now.plusMillis(1)
                    run
                }
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(6)
            try {
                val jobs =
                    (1..6).map {
                        pool.submit<RunDelivery?> {
                            start.await()
                            queue.read(owner, 30)
                        }
                    }
                start.countDown()
                val read = jobs.map { it.get(10, TimeUnit.SECONDS) }
                read.map { it!!.run } shouldContainExactlyInAnyOrder runs
            } finally {
                pool.shutdownNow()
            }
            queue.read(owner, 30) shouldBe null
            val claims = runs.map { queue.claim(RunDelivery(it, owner)) }
            claims shouldBe runs.map { true }
        }

        "owner filtering, visibility and a permanent claim: an unclaimed ticket shows again, a claimed one never" {
            val run = open()
            queue.enqueue(run, owner)
            queue.read(UUID.randomUUID(), 30) shouldBe null
            val delivery = queue.read(owner, 30)!!
            queue.read(owner, 30) shouldBe null
            clock.now = clock.now + Duration.ofSeconds(31)
            queue.read(owner, 30) shouldBe delivery
            queue.claim(delivery.copy(owner = UUID.randomUUID())) shouldBe false
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
            clock.now = clock.now + Duration.ofMinutes(1)
            queue.read(owner, 30) shouldBe null
            queue.claim(delivery) shouldBe false
            transaction { RunEvaluations.selectAll().single()[RunEvaluations.claimedAt]!!.toInstant() } shouldBe
                clock.now - Duration.ofMinutes(1)
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

        "a restarted owner never adopts old work; abandonment frees the key and terminal cleanup archives its ticket" {
            val run = ledger.claimRun(RunOpen("release_order", idempotencyKey = "engine:lost")).runId
            queue.enqueue(run, owner)
            queue.claim(queue.read(owner, 30)!!) shouldBe true
            val restarted = UUID.randomUUID()
            queue.read(restarted, 30) shouldBe null
            queue.archiveFinished() shouldBe 0
            clock.now = clock.now + Duration.ofHours(1)
            ledger.abandonExpiredRuns() shouldBe listOf(run)
            queue.archiveFinished() shouldBe 1
            queue.archiveFinished() shouldBe 0
            ledger.findRun(run)!!.status shouldBe RunStatus.ABANDONED
            (ledger.claimRun(RunOpen("release_order", idempotencyKey = "engine:lost")) is Claim.Claimed) shouldBe true
        }

        "an archive rolls back whole; a wrong owner cannot archive a ticket; an archived one is never read" {
            val run = open()
            queue.enqueue(run, owner)
            val delivery = queue.read(owner, 30)!!
            queue.archive(delivery.copy(owner = UUID.randomUUID()))
            transaction { RunEvaluations.selectAll().single()[RunEvaluations.archivedAt] } shouldBe null
            transaction {
                exec(
                    "ALTER TABLE automation.run_evaluations ADD CONSTRAINT ack_reject CHECK (archived_at IS NULL) NOT VALID",
                )
            }
            try {
                shouldThrow<Exception> { queue.archive(delivery) }
                transaction { RunEvaluations.selectAll().single()[RunEvaluations.archivedAt] } shouldBe null
            } finally {
                transaction { exec("ALTER TABLE automation.run_evaluations DROP CONSTRAINT ack_reject") }
            }
            queue.archive(delivery)
            queue.archive(delivery)
            clock.now = clock.now + Duration.ofMinutes(1)
            queue.read(owner, 30) shouldBe null
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
                queue.read(owner, 30) shouldBe RunDelivery(live, owner)
                ledger.findRun(live)!!.status shouldBe RunStatus.OPEN
            } finally {
                pool.shutdownNow()
            }
        }

        "mutations refuse nested transactions; limits and visibility are bounded" {
            val run = open()
            shouldThrow<IllegalStateException> { transaction { queue.enqueue(run, owner) } }
            queue.enqueue(run, owner)
            shouldThrow<IllegalStateException> { transaction { queue.read(owner, 30) } }
            val delivery = queue.read(owner, 30)!!
            shouldThrow<IllegalStateException> { transaction { queue.claim(delivery) } }
            shouldThrow<IllegalStateException> { transaction { queue.archive(delivery) } }
            shouldThrow<IllegalStateException> { transaction { queue.archiveFinished() } }
            shouldThrow<IllegalArgumentException> { queue.archiveFinished(0) }
            shouldThrow<IllegalArgumentException> { queue.read(owner, 0) }
        }
    })
