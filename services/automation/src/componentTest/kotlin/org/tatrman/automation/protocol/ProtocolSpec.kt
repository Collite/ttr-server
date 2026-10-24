// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.MutableClock
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.ledger.*
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProtocolSpec :
    StringSpec({
        LedgerTestDb.ready()
        val now = Instant.parse("2026-10-23T12:00:00Z")
        val clock = MutableClock(now)
        val ledger = Ledger(Duration.ofMinutes(30), clock)
        beforeEach {
            LedgerTestDb.reset()
            clock.now = now
        }

        fun open(subject: String? = "subject-1") =
            ledger
                .claimRun(
                    RunOpen(
                        "recipe",
                        caller = "display-name",
                        callerSubject = subject,
                        openingProtocol = buildJsonObject { put("event", "opened") },
                    ),
                ).runId

        fun part(name: String = "erp") =
            IslandExecution(
                "part",
                name,
                "parts/erp.sql",
                "query",
                emptyMap(),
                3,
                4,
                listOf("security_predicate_applied"),
                storage = "erp_db",
            )

        fun output(
            id: UUID,
            port: String = "main",
        ) = RunOutput(id, "decision", port, 2, 33, "runs/$id/$port.arrow", "sha256:abc")

        fun step(output: RunOutput) =
            IslandExecution(
                "output",
                output.island,
                "combine.sql",
                "duckdb",
                emptyMap(),
                output.rows,
                7,
                emptyList(),
                port = output.port,
                bytes = output.bytes,
            )

        fun entries(id: UUID) = ledger.readProtocol(id, "subject-1").sections.getValue("islands")

        fun outputs(id: UUID) = transaction { RunOutputs.selectAll().where { RunOutputs.run eq id }.count() }

        "verified subject persists independently of the principal, and a new ledger assembles all sections in order" {
            val id = open()
            ledger.recordExecution(id, part())
            val out = output(id)
            ledger.putOutput(out, step(out))
            ledger.appendProtocol(id, ProtocolSection.SIGNALS, buildJsonObject { put("ref", "signal:1") })
            ledger.sealProtocol(id)
            ledger.findRun(id)!!.open.callerSubject shouldBe "subject-1"
            ledger.findRun(id)!!.open.caller shouldBe "display-name"
            val document = Ledger(Duration.ofMinutes(30), clock).readProtocol(id, "subject-1")
            document.run shouldBe id.toString()
            document.schema shouldBe "protocol/v1"
            document.sections.keys.toList() shouldBe ProtocolSection.entries.map { it.wire }
            document.sections.getValue("automation").map { it.seq } shouldBe listOf(1, 2)
            document.sections
                .getValue("automation")
                .last()
                .payload.jsonObject["sealed"] shouldBe JsonPrimitive(true)
            document.sections.getValue("islands").map { it.seq } shouldBe listOf(1, 2)
            document.sections.values
                .flatten()
                .forEach { it.at shouldBe now.toString() }
            document.sections.getValue("receipts") shouldBe emptyList()
            clock.now = now.plusSeconds(90000)
            ledger.readProtocol(id, "subject-1") shouldBe document // Observations are not subject to execution budgets.
        }

        "raw decimals, integers, nulls and strings survive storage and HTTP encoding exactly" {
            val id = open()
            val payload =
                Json.parseToJsonElement(
                    """
                    {"amount":12345678901234567890.123456789,
                     "nested":[123456789012345678901234567890,null,true,"1.230"]}
                    """.trimIndent(),
                )
            ledger.appendProtocol(id, ProtocolSection.SIGNALS, payload)
            val document = ledger.readProtocol(id, "subject-1")
            document.sections
                .getValue("signals")
                .single()
                .payload shouldBe payload
            val wire = Json.encodeToString(document)
            wire.contains("12345678901234567890.123456789") shouldBe true
            wire.contains("123456789012345678901234567890") shouldBe true
            wire.contains("\"1.230\"") shouldBe true
        }

        "other subjects, reused display names, unknown handles and legacy rows all refuse durable reads" {
            val id = open()
            shouldThrow<NoSuchRun> { ledger.readProtocol(id, "display-name") }
            shouldThrow<NoSuchRun> { ledger.readProtocol(id, "subject-2") }
            shouldThrow<NoSuchRun> { ledger.readProtocol(UUID.randomUUID(), "subject-1") }
            shouldThrow<NoSuchRun> { ledger.readProtocol(open(null), "display-name") }
            shouldThrow<IllegalArgumentException> { ledger.readProtocol(id, " ") }
        }

        "concurrent retries record shared parts and output evidence once, with gapless section sequences" {
            val id = open()
            val out = output(id)
            val pool = Executors.newFixedThreadPool(8)
            try {
                val jobs =
                    (1..16).map { index ->
                        pool.submit {
                            if (index % 2 == 0) ledger.recordExecution(id, part()) else ledger.putOutput(out, step(out))
                        }
                    }
                jobs.forEach { it.get(20, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            entries(id).map { it.seq } shouldBe listOf(1, 2)
            outputs(id) shouldBe 1L
            ledger.putOutput(output(id, "detail"), step(output(id, "detail")))
            entries(id).size shouldBe 3 // Separate ports of one island retain separate evidence.
        }

        "changed evidence and changed output metadata roll back both sides of publication" {
            val id = open()
            val out = output(id)
            ledger.putOutput(out) // An earlier unjournalled fixture output.
            shouldThrow<IllegalStateException> { ledger.putOutput(out.copy(sha256 = "sha256:changed"), step(out)) }
            entries(id).size shouldBe 0 // The newly appended evidence rolled back with the metadata conflict.
            ledger.putOutput(out, step(out))
            shouldThrow<IllegalStateException> { ledger.putOutput(out, step(out).copy(durationMs = 999)) }
            ledger.recordExecution(id, part())
            shouldThrow<IllegalStateException> { ledger.recordExecution(id, part().copy(statement = "changed.sql")) }
            entries(id).size shouldBe 2
            outputs(id) shouldBe 1L
        }

        "a conflicting protocol entry prevents a new output row from being published" {
            val id = open()
            val out = output(id)
            ledger.appendProtocol(id, ProtocolSection.ISLANDS, Json.encodeToJsonElement(step(out).copy(rows = 99)))
            shouldThrow<IllegalStateException> { ledger.putOutput(out, step(out)) }
            outputs(id) shouldBe 0L
            entries(id).size shouldBe 1
        }

        "sealed and failed runs reject late source and output evidence without changing their durable document" {
            for (sealed in listOf(true, false)) {
                val id = open()
                if (sealed) ledger.sealProtocol(id) else ledger.markStatus(id, RunStatus.FAILED)
                val before = ledger.readProtocol(id, "subject-1")
                if (sealed) {
                    shouldThrow<ProtocolSealed> { ledger.recordExecution(id, part()) }
                    shouldThrow<ProtocolSealed> { ledger.putOutput(output(id), step(output(id))) }
                } else {
                    shouldThrow<IllegalRunTransition> { ledger.recordExecution(id, part()) }
                    shouldThrow<IllegalRunTransition> { ledger.putOutput(output(id), step(output(id))) }
                }
                ledger.readProtocol(id, "subject-1") shouldBe before
                outputs(id) shouldBe 0L
            }
        }

        "the exact execution deadline fences source and output evidence, including an identical retry" {
            val id = open()
            ledger.recordExecution(id, part())
            val out = output(id)
            ledger.putOutput(out, step(out))
            clock.now = now.plusSeconds(1800)
            shouldThrow<AutomationException> { ledger.recordExecution(id, part()) }.code shouldBe ErrorCode.BUDGET
            shouldThrow<AutomationException> { ledger.putOutput(out, step(out)) }.code shouldBe ErrorCode.BUDGET
            entries(id).size shouldBe 2
        }

        "protocol operations refuse nested transactions and mismatched output evidence" {
            val id = open()
            val out = output(id)
            shouldThrow<IllegalStateException> { transaction { ledger.readProtocol(id, "subject-1") } }
            shouldThrow<IllegalStateException> { transaction { ledger.recordExecution(id, part()) } }
            shouldThrow<IllegalStateException> { transaction { ledger.putOutput(out, step(out)) } }
            shouldThrow<IllegalArgumentException> { ledger.putOutput(out, step(out).copy(port = "other")) }
            shouldThrow<IllegalArgumentException> { ledger.putOutput(out, step(out).copy(bytes = 1)) }
            shouldThrow<IllegalArgumentException> { ledger.recordExecution(id, step(out)) }
            entries(id).size shouldBe 0
            outputs(id) shouldBe 0L
        }
    })
