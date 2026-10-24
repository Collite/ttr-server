// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.MutableClock
import org.tatrman.automation.runner.EvaluationResult
import org.tatrman.automation.runner.VerdictSummary
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AbandonmentSpec :
    StringSpec({
        LedgerTestDb.ready()
        val t0 = Instant.parse("2026-10-23T10:00:00Z")
        val clock = MutableClock(t0)
        val budget = Duration.ofMinutes(30)
        val counted = Collections.synchronizedList(mutableListOf<String>())
        val ledger = Ledger(budget, clock) { counted += it }
        val result = EvaluationResult(VerdictSummary(emptyMap(), 0), emptyList())

        beforeEach {
            LedgerTestDb.reset()
            clock.now = t0
            counted.clear()
        }

        fun open(
            key: String? = null,
            batch: Duration? = null,
            sync: Duration? = null,
        ) = ledger
            .claimRun(
                RunOpen("release_order", idempotencyKey = key, budgetBatch = batch, budgetSync = sync),
            ).runId

        fun status(id: UUID) = ledger.findRun(id)!!.status

        fun outputs(id: UUID) = transaction { RunOutputs.selectAll().where { RunOutputs.run eq id }.count() }

        fun output(
            id: UUID,
            port: String = "rows",
        ) = RunOutput(id, "placed", port, 1, 12, "runs/$id/$port.arrow", "sha256:abc")

        "clock abandonment covers every executing status and unkeyed runs at the strict grace boundary" {
            val ids =
                listOf(RunStatus.OPEN, RunStatus.RUNNING, RunStatus.EVALUATED).map { state ->
                    open().also { id -> if (state != RunStatus.OPEN) ledger.markStatus(id, state) }
                }
            ledger.putOutput(output(ids.first()))
            clock.now = t0 + budget + IdempotencyPolicy.GRACE
            ledger.abandonExpiredRuns() shouldBe emptyList()
            // PostgreSQL timestamps have microsecond precision; no floating-point conversion may lose this edge.
            clock.now = clock.now.plusNanos(1000)
            ledger.abandonExpiredRuns().toSet() shouldBe ids.toSet()
            ids.forEach { id ->
                status(id) shouldBe RunStatus.ABANDONED
                transaction {
                    val row = Runs.selectAll().where { Runs.id eq id }.single()
                    row[Runs.endedAt]!!.toInstant() shouldBe clock.now
                    row[Runs.error].toString() shouldBe "{\"code\":\"run-abandoned\"}"
                }
            }
            outputs(ids.first()) shouldBe 1L // Existing immutable evidence survives; blob retention is separate.
            ledger.abandonExpiredRuns() shouldBe emptyList()
            counted.size shouldBe 3
        }

        "the sweep uses batch then sync then configured budget, without expiring waiting or terminal runs" {
            val batch = open(batch = Duration.ofMinutes(5), sync = Duration.ofSeconds(1))
            val sync = open(sync = Duration.ofMinutes(3))
            val default = open()
            val long = open(batch = Duration.ofMillis(Long.MAX_VALUE))
            val protected =
                listOf(RunStatus.WAITING, RunStatus.CLOSED, RunStatus.FAILED, RunStatus.ABANDONED).associateWith {
                    open().also { id -> ledger.markStatus(id, it) }
                }
            clock.now = t0 + Duration.ofMinutes(6)
            ledger.abandonExpiredRuns() shouldBe listOf(sync)
            status(batch) shouldBe RunStatus.OPEN
            status(default) shouldBe RunStatus.OPEN
            clock.now = t0 + Duration.ofMinutes(8)
            ledger.abandonExpiredRuns() shouldBe listOf(batch)
            clock.now = t0 + Duration.ofDays(365)
            ledger.abandonExpiredRuns() shouldBe listOf(default)
            status(long) shouldBe RunStatus.OPEN
            protected.forEach { (state, id) -> status(id) shouldBe state }
            counted.size shouldBe 3
        }

        "bounded concurrent sweepers partition expired rows, skip unexpired rows and count each commit once" {
            // An older, long-budget run must not consume a sweep's limit and starve the expired ones.
            val live = open(batch = Duration.ofDays(10))
            clock.now = t0 + Duration.ofSeconds(1)
            val ids = (1..7).map { open() }.toSet()
            clock.now = t0 + Duration.ofHours(1)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(4)
            try {
                val jobs =
                    (1..4).map {
                        pool.submit<List<UUID>> {
                            start.await()
                            ledger.abandonExpiredRuns(2)
                        }
                    }
                start.countDown()
                val batches = jobs.map { it.get(10, TimeUnit.SECONDS) }
                batches.all { it.size <= 2 } shouldBe true
                val swept = batches.flatten() + ledger.abandonExpiredRuns(2)
                swept.size shouldBe 7
                swept.toSet() shouldBe ids
                counted.size shouldBe 7
                status(live) shouldBe RunStatus.OPEN
            } finally {
                pool.shutdownNow()
            }
        }

        "a locked close or waiting transition is skipped and never overwritten after it commits" {
            for (state in listOf(RunStatus.CLOSED, RunStatus.WAITING)) {
                val id = open()
                clock.now = clock.now + Duration.ofHours(1)
                val locked = CountDownLatch(1)
                val finish = CountDownLatch(1)
                val pool = Executors.newSingleThreadExecutor()
                try {
                    val changing =
                        pool.submit {
                            transaction {
                                // Hold the status update uncommitted while another pod sweeps its older snapshot.
                                ledger.markStatus(id, state)
                                locked.countDown()
                                check(finish.await(10, TimeUnit.SECONDS))
                            }
                        }
                    check(locked.await(10, TimeUnit.SECONDS))
                    ledger.abandonExpiredRuns() shouldBe emptyList()
                    finish.countDown()
                    changing.get(10, TimeUnit.SECONDS)
                    ledger.abandonExpiredRuns() shouldBe emptyList()
                    status(id) shouldBe state
                } finally {
                    finish.countDown()
                    pool.shutdownNow()
                }
            }
            counted shouldBe emptyList()
        }

        "a competing keyed reopen and sweep abandon the old run once and retain exactly one new holder" {
            val old = open("engine:1")
            clock.now = t0 + Duration.ofHours(1)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val claim =
                    pool.submit<Claim> {
                        start.await()
                        ledger.claimRun(RunOpen("release_order", idempotencyKey = "engine:1"))
                    }
                val sweep =
                    pool.submit<List<UUID>> {
                        start.await()
                        ledger.abandonExpiredRuns()
                    }
                start.countDown()
                val fresh = claim.get(10, TimeUnit.SECONDS).runId
                sweep.get(10, TimeUnit.SECONDS).all { it == old } shouldBe true
                fresh shouldNotBe old
                status(old) shouldBe RunStatus.ABANDONED
                status(fresh) shouldBe RunStatus.OPEN
                ledger.claimRun(RunOpen("release_order", idempotencyKey = "engine:1")) shouldBe
                    Claim.Running(fresh, RunStatus.OPEN)
                counted.size shouldBe 1
            } finally {
                pool.shutdownNow()
            }
        }

        "a swept run frees its key and refuses late begin, output, decision and failure writes" {
            val old = open("engine:2")
            ledger.putOutput(output(old))
            clock.now = t0 + Duration.ofHours(1)
            ledger.abandonExpiredRuns() shouldBe listOf(old)
            val fresh = open("engine:2")
            fresh shouldNotBe old
            shouldThrow<IllegalRunTransition> { ledger.startEvaluation(old) }
            shouldThrow<IllegalRunTransition> { ledger.putOutput(output(old)) }
            shouldThrow<IllegalRunTransition> { ledger.putOutput(output(old, "late")) }
            shouldThrow<IllegalRunTransition> { ledger.completeEvaluation(old, result) }
            ledger.failActiveRun(old) shouldBe false
            status(old) shouldBe RunStatus.ABANDONED
            status(fresh) shouldBe RunStatus.OPEN
            outputs(old) shouldBe 1L
            transaction { RunProtocol.selectAll().count() } shouldBe 0L
        }

        "outputs refuse terminal, waiting, sealed and nested writes, retaining the existing immutable row" {
            for (state in listOf(RunStatus.CLOSED, RunStatus.FAILED, RunStatus.ABANDONED, RunStatus.WAITING)) {
                val id = open()
                ledger.putOutput(output(id))
                ledger.markStatus(id, state)
                shouldThrow<IllegalRunTransition> { ledger.putOutput(output(id)) }
                shouldThrow<IllegalRunTransition> { ledger.putOutput(output(id, "late")) }
                outputs(id) shouldBe 1L
            }
            val sealed = open()
            ledger.sealProtocol(sealed)
            shouldThrow<ProtocolSealed> { ledger.putOutput(output(sealed)) }
            outputs(sealed) shouldBe 0L
            val nested = open()
            shouldThrow<IllegalStateException> { transaction { ledger.putOutput(output(nested)) } }
            outputs(nested) shouldBe 0L
            shouldThrow<IllegalStateException> { transaction { ledger.abandonExpiredRuns() } }
            shouldThrow<IllegalArgumentException> { ledger.abandonExpiredRuns(0) }
            shouldThrow<IllegalArgumentException> { ledger.abandonExpiredRuns(1001) }
        }

        "concurrent identical output publishers share one immutable row through READ COMMITTED locking" {
            for (state in listOf(RunStatus.OPEN, RunStatus.RUNNING, RunStatus.EVALUATED)) {
                val id = open()
                if (state != RunStatus.OPEN) ledger.markStatus(id, state)
                val start = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(4)
                try {
                    val jobs =
                        (1..8).map {
                            pool.submit {
                                start.await()
                                ledger.putOutput(output(id))
                            }
                        }
                    start.countDown()
                    jobs.forEach { it.get(10, TimeUnit.SECONDS) }
                    outputs(id) shouldBe 1L
                } finally {
                    pool.shutdownNow()
                }
            }
        }
    })
