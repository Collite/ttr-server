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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DryRunLedgerSpec :
    StringSpec({
        LedgerTestDb.ready()
        val now = Instant.parse("2026-10-23T12:00:00Z")
        val clock = MutableClock(now)
        val ledger = Ledger(Duration.ofMinutes(30), clock)
        val result = EvaluationResult(VerdictSummary(mapOf("hold" to RouteSummary(1, "arrow:held")), 0), emptyList())
        beforeSpec {
            transaction {
                exec("CREATE SCHEMA IF NOT EXISTS au_preview_probe")
                exec(
                    "CREATE TABLE au_preview_probe.sales_order (order_number integer PRIMARY KEY, state text NOT NULL)",
                )
            }
        }
        afterSpec { transaction { exec("DROP SCHEMA au_preview_probe CASCADE") } }
        beforeEach {
            LedgerTestDb.reset()
            clock.now = now
            transaction {
                exec("TRUNCATE au_preview_probe.sales_order")
                exec("INSERT INTO au_preview_probe.sales_order VALUES (4711, 'placed')")
            }
        }

        fun open(
            dry: Boolean = true,
            subject: String? = "subject-1",
        ) = ledger
            .claimRun(
                RunOpen(
                    "release_order",
                    dryRun = dry,
                    callerSubject = subject,
                    openingProtocol = buildJsonObject { put("event", "opened") },
                ),
            ).runId

        fun plan(
            id: UUID,
            batch: UUID = UUID.randomUUID(),
        ): PreviewPlan {
            val key = buildJsonObject { put("order_number", 4711) }
            return PreviewPlan(
                batch.toString(),
                id.toString(),
                listOf(
                    ApplyRow(
                        1,
                        "er.entity.release_order_verdict",
                        "insert",
                        key,
                        row =
                            buildJsonObject {
                                put("run", id.toString())
                                put("order_number", 4711)
                                put("reason", "from SQL")
                                put("optional", JsonNull)
                                put(
                                    "amount",
                                    Json.parseToJsonElement("12345678901234567890.123456789"),
                                )
                            },
                    ),
                    ApplyRow(2, "er.entity.order", "update", key, set = buildJsonObject { put("held_for", "Sales") }),
                    ApplyRow(
                        3,
                        "er.entity.order",
                        "transition",
                        key,
                        attribute = "state",
                        from = listOf("placed"),
                        to = JsonPrimitive("held"),
                        history = true,
                    ),
                ),
                listOf(
                    DryRunAction(
                        key,
                        "hold",
                        "hold",
                        "transition",
                        buildJsonObject {
                            put("department", "Sales")
                        },
                        listOf(2, 3),
                        "would hold order 4711 for Sales",
                    ),
                    DryRunAction(
                        key,
                        "hold",
                        "notify",
                        "deliver",
                        buildJsonObject {
                            put("who", "role.sales")
                        },
                        emptyList(),
                        "would notify Sales",
                    ),
                ),
            )
        }

        fun unchanged() =
            transaction {
                exec("SELECT state FROM au_preview_probe.sales_order WHERE order_number = 4711") { rs ->
                    rs.next()
                    rs.getString(1)
                } shouldBe "placed"
                Tasks.selectAll().count() shouldBe 0L
                Deliveries.selectAll().count() shouldBe 0L
            }

        "a durable preview commits with decision/action/write evidence and never touches model or connector rows" {
            val id = open()
            val preview = plan(id)
            ledger.completeEvaluation(id, result, preview)
            val batch = ledger.readRunBatch(id, "subject-1")
            batch shouldBe RunBatchPlan(preview.batch, preview.run, preview.rows, "preview")
            Json.encodeToString(batch).contains("\"amount\":12345678901234567890.123456789") shouldBe true
            transaction {
                exec("SELECT row->>'amount' FROM automation.batch_rows WHERE seq = 1") { rs ->
                    rs.next()
                    rs.getString(1)
                } shouldBe "12345678901234567890.123456789"
            }
            val progress = ledger.readRunProgress(id, "subject-1")
            progress.status shouldBe "evaluated"
            progress.batch shouldBe RunBatch(preview.batch, "preview", 3)
            progress.actions shouldBe preview.actions
            val document = ledger.readProtocol(id, "subject-1")
            document.sections.getValue("actions").map { it.seq } shouldBe listOf(1, 2)
            document.sections
                .getValue("writes")
                .single()
                .payload shouldBe Json.encodeToJsonElement(preview.evidence())
            transaction { BatchRows.selectAll().forEach { it[BatchRows.status] shouldBe "staged" } }
            ledger.sealProtocol(id)
            clock.now = now.plusSeconds(90000)
            Ledger(Duration.ofSeconds(1), clock).readRunBatch(id, "subject-1") shouldBe batch
            ledger.readRunProgress(id, "subject-1").actions shouldBe preview.actions
            unchanged()
        }

        "JSONB exponent normalization preserves exact values in batch and action HTTP JSON and identical retries" {
            val id = open()
            val original = plan(id)
            val amount = Json.parseToJsonElement("1.2345678901234567890123456789e20")
            val preview =
                original.copy(
                    rows =
                        original.rows.map {
                            if (it.seq ==
                                1
                            ) {
                                it.copy(row = JsonObject(it.row!! + ("amount" to amount)))
                            } else {
                                it
                            }
                        },
                    actions =
                        original.actions.map {
                            it.copy(
                                arguments = JsonObject(it.arguments + ("amount" to amount)),
                            )
                        },
                )
            ledger.completeEvaluation(id, result, preview)
            ledger.completeEvaluation(id, result, preview)
            val batchJson = Json.encodeToString(ledger.readRunBatch(id, "subject-1"))
            val statusJson = Json.encodeToString(ledger.readRunProgress(id, "subject-1"))
            batchJson.contains("123456789012345678901.23456789") shouldBe true
            statusJson.contains("123456789012345678901.23456789") shouldBe true
            val changed =
                preview.copy(
                    rows =
                        preview.rows.map {
                            if (it.seq == 1) {
                                it.copy(
                                    row =
                                        JsonObject(
                                            it.row!! +
                                                ("amount" to Json.parseToJsonElement("123456789012345678901.23456788")),
                                        ),
                                )
                            } else {
                                it
                            }
                        },
                )
            shouldThrow<IllegalStateException> { ledger.completeEvaluation(id, result, changed) }
            unchanged()
        }

        "an empty reads-only preview is a real batch with no action or model rows" {
            val id = open()
            val preview = PreviewPlan(UUID.randomUUID().toString(), id.toString(), emptyList(), emptyList())
            ledger.completeEvaluation(id, EvaluationResult(VerdictSummary(emptyMap(), 0), emptyList()), preview)
            ledger.readRunBatch(id, "subject-1").rows shouldBe emptyList()
            ledger.readRunProgress(id, "subject-1").batch shouldBe RunBatch(preview.batch, "preview", 0)
            ledger.readProtocol(id, "subject-1").sections.getValue("actions") shouldBe emptyList()
            unchanged()
        }

        "concurrent identical completions publish one batch and gapless immutable evidence" {
            val id = open()
            val preview = plan(id)
            val pool = Executors.newFixedThreadPool(8)
            try {
                val jobs = (1..16).map { pool.submit { ledger.completeEvaluation(id, result, preview) } }
                jobs.forEach { it.get(20, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            transaction {
                Batches.selectAll().count() shouldBe 1L
                BatchRows.selectAll().count() shouldBe 3L
                RunProtocol.selectAll().count() shouldBe 5L // Opening, decision, two actions, writes.
            }
            clock.now = now.plusSeconds(90000)
            ledger.completeEvaluation(id, result, preview) // Identical committed work remains a no-op after expiry.
            ledger.readRunBatch(id, "subject-1").rows shouldBe preview.rows
            unchanged()
        }

        "changed rows, descriptions or decision cannot replace an existing preview" {
            val id = open()
            val preview = plan(id)
            ledger.completeEvaluation(id, result, preview)
            val before = ledger.readProtocol(id, "subject-1")
            val changed =
                preview.copy(
                    rows =
                        preview.rows.map {
                            if (it.seq ==
                                2
                            ) {
                                it.copy(set = buildJsonObject { put("held_for", "AR") })
                            } else {
                                it
                            }
                        },
                )
            shouldThrow<IllegalStateException> { ledger.completeEvaluation(id, result, changed) }
            shouldThrow<IllegalStateException> {
                ledger.completeEvaluation(
                    id,
                    result,
                    preview.copy(actions = preview.actions.map { it.copy(would = "changed") }),
                )
            }
            shouldThrow<IllegalStateException> {
                ledger.completeEvaluation(
                    id,
                    result.copy(mode = "exclusive"),
                    preview,
                )
            }
            ledger.readProtocol(id, "subject-1") shouldBe before
            ledger.readRunBatch(id, "subject-1").rows shouldBe preview.rows
            unchanged()
        }

        "a batch collision rolls back the losing run's whole completion" {
            val id = open()
            val preview = plan(id)
            ledger.completeEvaluation(id, result, preview)
            val other = open()
            val collision = plan(other, UUID.fromString(preview.batch))
            shouldThrow<org.jetbrains.exposed.v1.exceptions.ExposedSQLException> {
                ledger.completeEvaluation(other, result, collision)
            }
            ledger.findRun(other)!!.status shouldBe RunStatus.OPEN
            shouldThrow<NoRunBatch> { ledger.readRunBatch(other, "subject-1") }
            ledger
                .readProtocol(
                    other,
                    "subject-1",
                ).sections
                .filterKeys { it != "automation" }
                .values
                .all { it.isEmpty() } shouldBe
                true
            transaction {
                Batches.selectAll().count() shouldBe 1L
                BatchRows.selectAll().count() shouldBe 3L
            }
        }

        "expiry after rows and action evidence have been inserted rolls back the batch, evidence and evaluated status" {
            val calls = AtomicInteger()
            var armed = false
            val advancing =
                object : Clock() {
                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = this

                    override fun instant(): Instant =
                        if (!armed ||
                            calls.incrementAndGet() <= 2
                        ) {
                            now
                        } else {
                            now.plusSeconds(1800)
                        }
                }
            val l = Ledger(Duration.ofMinutes(30), advancing)
            val id = l.claimRun(RunOpen("release_order", dryRun = true, callerSubject = "subject-1")).runId
            armed = true
            shouldThrow<AutomationException> { l.completeEvaluation(id, result, plan(id)) }.code shouldBe
                ErrorCode.BUDGET
            calls.get() shouldBe 3
            l.findRun(id)!!.status shouldBe RunStatus.OPEN
            shouldThrow<NoRunBatch> { l.readRunBatch(id, "subject-1") }
            transaction {
                Batches.selectAll().count() shouldBe 0L
                BatchRows.selectAll().count() shouldBe 0L
                RunProtocol.selectAll().count() shouldBe
                    0L
            }
            unchanged()
        }

        "seals, terminal states, wet runs, wrong run ids and outer transactions refuse new previews" {
            val sealed = open()
            ledger.sealProtocol(sealed)
            shouldThrow<ProtocolSealed> { ledger.completeEvaluation(sealed, result, plan(sealed)) }
            for (state in listOf(RunStatus.CLOSED, RunStatus.FAILED, RunStatus.ABANDONED, RunStatus.WAITING)) {
                val id = open()
                ledger.markStatus(id, state)
                shouldThrow<IllegalRunTransition> { ledger.completeEvaluation(id, result, plan(id)) }
            }
            val wet = open(dry = false)
            shouldThrow<IllegalStateException> { ledger.completeEvaluation(wet, result, plan(wet)) }
            val id = open()
            shouldThrow<IllegalArgumentException> { ledger.completeEvaluation(id, result, plan(UUID.randomUUID())) }
            transaction { shouldThrow<IllegalStateException> { ledger.completeEvaluation(id, result, plan(id)) } }
            transaction {
                Batches.selectAll().count() shouldBe 0L
                BatchRows.selectAll().count() shouldBe 0L
            }
            unchanged()
        }

        "batch ownership is checked before existence and legacy display labels never authorize reads" {
            val id = open()
            ledger.completeEvaluation(id, result, plan(id))
            shouldThrow<NoSuchRun> { ledger.readRunBatch(id, "other") }
            shouldThrow<NoSuchRun> { ledger.readRunBatch(UUID.randomUUID(), "subject-1") }
            val legacy = open(subject = null)
            ledger.completeEvaluation(legacy, result, plan(legacy))
            shouldThrow<NoSuchRun> { ledger.readRunBatch(legacy, "subject-1") }
            shouldThrow<NoRunBatch> { ledger.readRunBatch(open(), "subject-1") }
            shouldThrow<IllegalArgumentException> { ledger.readRunBatch(id, " ") }
            transaction { shouldThrow<IllegalStateException> { ledger.readRunBatch(id, "subject-1") } }
        }
    })
