// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.MutableClock
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.apply.PreviewPlan
import org.tatrman.automation.config.RetentionConfig
import org.tatrman.automation.ledger.*
import org.tatrman.automation.protocol.IslandExecution
import org.tatrman.automation.staging.*
import java.nio.file.Files
import java.time.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StagingRetentionSpec :
    StringSpec({
        LedgerTestDb.ready()
        val t0 = Instant.parse("2026-10-23T12:00:00Z")
        val clock = MutableClock(t0)
        val config = RetentionConfig(24, 1, 400)
        val ledger = Ledger(Duration.ofDays(3), clock)
        val retention = StagingRetention(config, clock)
        val decision = EvaluationResult(VerdictSummary(emptyMap(), 0), emptyList())
        beforeEach {
            LedgerTestDb.reset()
            clock.now = t0
        }

        fun open() = ledger.claimRun(RunOpen("release_order", dryRun = true, callerSubject = "owner")).runId

        fun output(id: UUID) = RunOutput(id, "i", "rows", 1, 1, "runs/$id/i.rows.arrow", "sha256:x")

        fun released(id: UUID) =
            transaction {
                RunOutputs.selectAll().where { RunOutputs.run eq id }.single()[RunOutputs.releasedAt]
            }

        "closed staging survives the grace boundary then releases without changing durable evidence" {
            val root = Files.createTempDirectory("au-retention-close")
            val store = LocalStagingStore(root)
            val id = open()
            ledger.putOutput(output(id))
            store.putIfAbsent(output(id).path!!, byteArrayOf(1))
            store.putIfAbsent("runs/$id/orphan.arrow", byteArrayOf(2))
            val plan = PreviewPlan(UUID.randomUUID().toString(), id.toString(), emptyList(), emptyList())
            ledger.completeEvaluation(id, decision, plan)
            val closed = ledger.closePreview(id, "owner")
            val protocol = ledger.readProtocol(id, "owner")
            val batch = ledger.readRunBatch(id, "owner")
            val progress = ledger.readRunProgress(id, "owner")
            clock.now = t0.plusSeconds(3600).minusNanos(1000)
            retention.candidates() shouldBe emptyList()
            retention.fence(id) shouldBe false
            clock.now = t0.plusSeconds(3600)
            retention.candidates() shouldBe listOf(id)
            val cleanups = mutableListOf<UUID>()
            StagingSweeper(retention, store) { cleanups.add(it) }.sweep()
            cleanups shouldBe listOf(id)
            Files.exists(root.resolve("runs/$id")) shouldBe false
            released(id)!!.toInstant() shouldBe clock.now
            clock.now = t0.plusSeconds(90000)
            val restarted = Ledger(Duration.ofDays(3), clock)
            restarted.closePreview(id, "owner") shouldBe closed
            restarted.readProtocol(id, "owner") shouldBe protocol
            restarted.readRunBatch(id, "owner") shouldBe batch
            restarted.readRunProgress(id, "owner") shouldBe progress
            StagingSweeper(StagingRetention(config, clock), store) {}.sweep()
            released(id)!!.toInstant() shouldBe t0.plusSeconds(3600)
        }

        "abandoned runs release immediately, while fresh failed and waiting runs retain staging" {
            val abandoned = open()
            val failed = open()
            val waiting = open()
            ledger.markStatus(abandoned, RunStatus.ABANDONED)
            ledger.markStatus(failed, RunStatus.FAILED)
            ledger.markStatus(waiting, RunStatus.WAITING)
            retention.candidates() shouldBe listOf(abandoned)
            retention.fence(abandoned) shouldBe true
            retention.fence(failed) shouldBe false
            retention.fence(waiting) shouldBe false
            // Retention does not abandon WAITING or mutate its task lifecycle; the global blob lifetime still applies.
            clock.now = t0.plusSeconds(86400).minusNanos(1000)
            retention.candidates().toSet() shouldBe setOf(abandoned)
            clock.now = t0.plusSeconds(86400)
            retention.candidates().toSet() shouldBe setOf(abandoned, failed, waiting)
            retention.fence(waiting) shouldBe true
            ledger.findRun(waiting)!!.status shouldBe RunStatus.WAITING
        }

        "global expiry fences every execution publication and hides released state from other subjects" {
            val id = open()
            ledger.putOutput(output(id))
            clock.now = t0.plusSeconds(86400)
            retention.fence(id) shouldBe true

            fun gone(block: () -> Unit) {
                shouldThrow<AutomationException>(block).code shouldBe ErrorCode.STAGING_RELEASED
            }
            gone { ledger.checkRunExecution(id, "owner") }
            gone { ledger.startEvaluation(id) }
            gone { ledger.putOutput(output(id)) }
            gone { ledger.completeEvaluation(id, decision) }
            gone {
                ledger.recordExecution(
                    id,
                    IslandExecution("part", "p", "parts/p.sql", "postgres", emptyMap(), 1, 1, emptyList()),
                )
            }
            shouldThrow<NoSuchRun> { ledger.checkRunExecution(id, "other") }
            shouldThrow<NoSuchRun> { ledger.checkRunExecution(UUID.randomUUID(), "owner") }
            released(id) shouldBe null // The tombstone is not a claim that deletion succeeded.
            retention.sweep(id) { it shouldBe id }
            released(id)!!.toInstant() shouldBe clock.now
        }

        "store failure after partial deletion preserves the fence and retries the unreleased index after restart" {
            val root = Files.createTempDirectory("au-retention-retry")
            val store = LocalStagingStore(root)
            val id = open()
            ledger.putOutput(output(id))
            store.putIfAbsent(output(id).path!!, byteArrayOf(1))
            ledger.markStatus(id, RunStatus.ABANDONED)
            retention.fence(id) shouldBe true
            shouldThrow<IllegalStateException> {
                retention.sweep(id) {
                    store.deleteRun(it)
                    error("store acknowledgement lost")
                }
            }
            released(id) shouldBe null
            shouldThrow<AutomationException> { ledger.checkRunExecution(id, "owner") }.code shouldBe
                ErrorCode.RUN_ABANDONED
            transaction {
                Runs
                    .selectAll()
                    .where { Runs.id eq id }
                    .single()[Runs.stagingReleasedAt]!!
                    .toInstant()
            } shouldBe
                t0
            // Simulate a delayed blob put that began before the fence and never published an output row.
            store.putIfAbsent("runs/$id/late.arrow", byteArrayOf(2))
            val restarted = StagingRetention(config, clock)
            restarted.fence(id) shouldBe true
            restarted.sweep(id, store::deleteRun)
            Files.exists(root.resolve("runs/$id")) shouldBe false
            released(id)!!.toInstant() shouldBe t0
            store.putIfAbsent("runs/$id/even-later.arrow", byteArrayOf(3))
            StagingSweeper(restarted, store) {}.sweep()
            Files.exists(root.resolve("runs/$id")) shouldBe false
        }

        "UUID prefixes ignore malicious ledger paths and never remove another run's blobs" {
            val root = Files.createTempDirectory("au-retention-paths")
            val store = LocalStagingStore(root)
            val id = open()
            val other = open()
            ledger.putOutput(output(id).copy(path = "runs/$other/keep.arrow"))
            store.putIfAbsent("runs/$other/keep.arrow", byteArrayOf(4))
            store.putIfAbsent("runs/$id/unindexed.arrow", byteArrayOf(5))
            ledger.markStatus(id, RunStatus.ABANDONED)
            StagingSweeper(retention, store) {}.sweep()
            Files.exists(root.resolve("runs/$id")) shouldBe false
            store.get("runs/$other/keep.arrow").toList() shouldBe listOf<Byte>(4)
            released(id)!!.toInstant() shouldBe t0
            retention.fence(other) shouldBe false
        }

        "bounded scans prioritize unattempted runs even when an earlier cleanup keeps failing" {
            val ids = (1..4).map { open().also { ledger.markStatus(it, RunStatus.ABANDONED) } }
            val first = retention.candidates(1).single()
            retention.fence(first) shouldBe true
            shouldThrow<IllegalStateException> { retention.sweep(first) { error("unavailable") } }
            val second = retention.candidates(1).single()
            (second != first) shouldBe true
            retention.fence(second) shouldBe true
            clock.now = t0.plusSeconds(1)
            val next = retention.candidates(2)
            next.toSet() shouldBe ids.toSet() - setOf(first, second)
            shouldThrow<IllegalArgumentException> { retention.candidates(0) }
            shouldThrow<IllegalStateException> { transaction { retention.fence(first) } }
            shouldThrow<IllegalStateException> { transaction { retention.sweep(first) {} } }
            shouldThrow<IllegalStateException> { transaction { retention.candidates() } }
        }

        "publication and concurrent sweepers serialize on the run row without resurrecting released outputs" {
            val id = open()
            ledger.putOutput(output(id))
            ledger.markStatus(id, RunStatus.ABANDONED)
            retention.fence(id) shouldBe true
            val entered = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(3)
            try {
                val first =
                    pool.submit {
                        retention.sweep(id) {
                            entered.countDown()
                            check(finish.await(10, TimeUnit.SECONDS))
                        }
                    }
                check(entered.await(5, TimeUnit.SECONDS))
                val second = pool.submit { retention.sweep(id) {} }
                val late = pool.submit { shouldThrow<IllegalRunTransition> { ledger.putOutput(output(id)) } }
                second.isDone shouldBe false
                finish.countDown()
                first.get(10, TimeUnit.SECONDS)
                second.get(10, TimeUnit.SECONDS)
                late.get(10, TimeUnit.SECONDS)
                released(id)!!.toInstant() shouldBe t0
            } finally {
                finish.countDown()
                pool.shutdownNow()
            }
        }
    })
