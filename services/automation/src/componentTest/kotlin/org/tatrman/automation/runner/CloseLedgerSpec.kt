// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.MutableClock
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.apply.*
import org.tatrman.automation.ledger.*
import java.time.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CloseLedgerSpec :
    StringSpec({
        LedgerTestDb.ready()
        val now = Instant.parse("2026-10-23T12:00:00Z")
        val clock = MutableClock(now)
        val ledger = Ledger(Duration.ofMinutes(30), clock)
        val decision = EvaluationResult(VerdictSummary(emptyMap(), 0), emptyList())
        beforeEach {
            LedgerTestDb.reset()
            clock.now = now
        }

        fun open(
            subject: String? = "owner",
            dry: Boolean = true,
        ) = ledger
            .claimRun(
                RunOpen(
                    "release_order",
                    callerSubject = subject,
                    dryRun = dry,
                    openingProtocol = buildJsonObject { put("event", "opened") },
                ),
            ).runId

        fun evaluate(
            id: UUID,
            empty: Boolean = false,
        ): PreviewPlan {
            val plan =
                PreviewPlan(
                    UUID.randomUUID().toString(),
                    id.toString(),
                    if (empty) {
                        emptyList()
                    } else {
                        listOf(
                            ApplyRow(
                                1,
                                "er.entity.order",
                                "update",
                                key = buildJsonObject { put("order_number", 4711) },
                                set = buildJsonObject { put("state", "held") },
                            ),
                        )
                    },
                    emptyList(),
                )
            ledger.completeEvaluation(id, decision, plan)
            return plan
        }

        "close atomically preserves preview rows, adds final evidence and seals once with a stable ended_at" {
            val id = open()
            val plan = evaluate(id)
            val before = ledger.readRunBatch(id, "owner")
            clock.now = now.plusSeconds(5)
            val result = ledger.closePreview(id, "owner")
            result shouldBe CloseResult("closed", RunBatch(plan.batch, "preview", 1), "/automation/runs/$id/protocol")
            ledger.readRunBatch(id, "owner") shouldBe before
            val progress = ledger.readRunProgress(id, "owner")
            progress.status shouldBe "closed"
            progress.endedAt shouldBe clock.now.toString()
            progress.verdict shouldBe decision.verdict
            val protocol = ledger.readProtocol(id, "owner")
            protocol.sections.getValue("automation").map { it.seq } shouldBe listOf(1, 2, 3)
            protocol.sections
                .getValue("automation")
                .last()
                .payload shouldBe buildJsonObject { put("sealed", true) }
            protocol.sections.getValue("writes").map { it.seq } shouldBe listOf(1, 2)
            protocol.sections
                .getValue("writes")
                .last()
                .payload.jsonObject["applied"] shouldBe JsonPrimitive(0)
            clock.now = now.plusSeconds(7200)
            val restarted = Ledger(Duration.ofMinutes(30), clock)
            restarted.closePreview(id, "owner") shouldBe result
            restarted.readRunProgress(id, "owner") shouldBe progress
            restarted.readProtocol(id, "owner") shouldBe protocol
            transaction {
                BatchRows.selectAll().single()[BatchRows.status] shouldBe "staged"
                Batches.selectAll().single()[Batches.committedAt] shouldBe null
                Tasks.selectAll().count() shouldBe 0L
                Deliveries.selectAll().count() shouldBe 0L
            }
        }

        "sixteen concurrent closes return one result and never duplicate final evidence" {
            val id = open()
            evaluate(id, empty = true)
            val pool = Executors.newFixedThreadPool(8)
            try {
                val futures = (1..16).map { pool.submit<CloseResult> { ledger.closePreview(id, "owner") } }
                futures.map { it.get(15, TimeUnit.SECONDS) }.distinct().size shouldBe 1
                ledger
                    .readProtocol(id, "owner")
                    .sections
                    .getValue("automation")
                    .size shouldBe 3
                ledger
                    .readProtocol(id, "owner")
                    .sections
                    .getValue("writes")
                    .size shouldBe 2
                ledger.readRunBatch(id, "owner").rows shouldBe emptyList()
            } finally {
                pool.shutdownNow()
            }
        }

        "owner filtering precedes status and seal checks for unknown, other and legacy handles" {
            val id = open()
            evaluate(id)
            ledger.closePreview(id, "owner")
            val legacy = open(subject = null)
            listOf(id, legacy, UUID.randomUUID()).forEach {
                shouldThrow<NoSuchRun> { ledger.closePreview(it, "other") }
                shouldThrow<NoSuchRun> { ledger.checkRunExecution(it, "other") }
            }
            shouldThrow<IllegalStateException> { transaction { ledger.closePreview(id, "owner") } }
            shouldThrow<IllegalStateException> { transaction { ledger.checkRunExecution(id, "owner") } }
        }

        "pending, failed, abandoned and waiting runs refuse close without changing evidence" {
            listOf(
                RunStatus.OPEN,
                RunStatus.RUNNING,
                RunStatus.FAILED,
                RunStatus.ABANDONED,
                RunStatus.WAITING,
            ).forEach { state ->
                val id = open()
                if (state != RunStatus.OPEN) ledger.markStatus(id, state)
                val protocol = ledger.readProtocol(id, "owner")
                shouldThrow<IllegalRunTransition> { ledger.closePreview(id, "owner") }.from shouldBe state
                ledger.readProtocol(id, "owner") shouldBe protocol
                ledger.findRun(id)!!.status shouldBe state
            }
            val noBatch = open()
            ledger.completeEvaluation(noBatch, decision)
            shouldThrow<NoRunBatch> { ledger.closePreview(noBatch, "owner") }
            val wet = open(dry = false)
            ledger.completeEvaluation(wet, decision)
            shouldThrow<IllegalStateException> { ledger.closePreview(wet, "owner") }
        }

        "seal fences every execution publication, while durable reads and repeated close remain available" {
            val id = open()
            val plan = evaluate(id)
            ledger.closePreview(id, "owner")
            val protocol = ledger.readProtocol(id, "owner")
            shouldThrow<ProtocolSealed> { ledger.checkRunExecution(id, "owner") }
            shouldThrow<ProtocolSealed> {
                ledger.appendProtocol(id, ProtocolSection.ACTIONS, buildJsonObject { put("late", true) })
            }
            shouldThrow<IllegalRunTransition> { ledger.startEvaluation(id) }
            shouldThrow<IllegalRunTransition> { ledger.completeEvaluation(id, decision, plan) }
            shouldThrow<IllegalRunTransition> { ledger.putOutput(RunOutput(id, "late", "out", 1, 1, "bad", "bad")) }
            ledger.failActiveRun(id) shouldBe false
            ledger.readProtocol(id, "owner") shouldBe protocol
            val prematurelySealed = open()
            evaluate(prematurelySealed)
            ledger.sealProtocol(prematurelySealed)
            shouldThrow<ProtocolSealed> { ledger.closePreview(prematurelySealed, "owner") }
            shouldThrow<ProtocolSealed> { ledger.checkRunExecution(prematurelySealed, "owner") }
        }

        "expiry during final close journalling rolls back writes, closure, seal and ended_at together" {
            val id = open()
            evaluate(id)
            val protocol = ledger.readProtocol(id, "owner")
            val calls = AtomicInteger()
            val ticking =
                object : Clock() {
                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = this

                    override fun instant(): Instant = if (calls.incrementAndGet() >= 3) now.plusSeconds(1800) else now
                }
            shouldThrow<AutomationException> {
                Ledger(
                    Duration.ofMinutes(30),
                    ticking,
                ).closePreview(id, "owner")
            }.code shouldBe
                ErrorCode.BUDGET
            calls.get() shouldBe 3
            ledger.readProtocol(id, "owner") shouldBe protocol
            ledger.readRunProgress(id, "owner").endedAt shouldBe null
            ledger.findRun(id)!!.status shouldBe RunStatus.EVALUATED
            ledger.closePreview(id, "owner").status shouldBe "closed"
        }
    })
