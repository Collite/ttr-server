// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.MutableClock
import org.tatrman.automation.ledger.*
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RunStatusSpec :
    StringSpec({
        LedgerTestDb.ready()
        val now = Instant.parse("2026-10-23T12:00:00Z")
        val clock = MutableClock(now)
        val ledger = Ledger(Duration.ofMinutes(30), clock)
        val result =
            EvaluationResult(
                VerdictSummary(mapOf("hold" to RouteSummary(3, "arrow:held")), 1),
                listOf(GuardResult("restricted-view", "orders", "passed")),
                "exclusive",
            )
        beforeEach {
            LedgerTestDb.reset()
            clock.now = now
        }

        fun open(
            subject: String? = "subject-1",
            replayOf: UUID? = null,
        ) = ledger
            .claimRun(
                RunOpen(
                    recipe = "release_order",
                    program = "morning_release",
                    deployment = "native",
                    bundle = "bundle:original",
                    fpText = "text",
                    fpValues = "values",
                    fpProgram = "program",
                    fpDeployment = "deployment",
                    modelFp = "model",
                    asOf = now.minusSeconds(60),
                    author = "po",
                    runAs = "automation_decider",
                    caller = "display-name",
                    callerSubject = subject,
                    dryRun = true,
                    replayOf = replayOf,
                ),
            ).runId

        "status reconstructs the pinned metadata and exact decision after restart and expiry" {
            val original = ledger.claimRun(RunOpen("original")).runId
            val id = open(replayOf = original)
            val opened = ledger.readRunProgress(id, "subject-1")
            opened.status shouldBe "open"
            opened.recipe shouldBe "release_order"
            opened.program shouldBe "morning_release"
            opened.deployment shouldBe "native"
            opened.bundle shouldBe "bundle:original"
            opened.runAs shouldBe "automation_decider"
            opened.author shouldBe "po"
            opened.fingerprints shouldBe RunFingerprints("text", "values", "program", "deployment", "model")
            opened.asOf shouldBe now.minusSeconds(60).toString()
            opened.startedAt shouldBe now.toString()
            opened.endedAt shouldBe null
            opened.dryRun shouldBe true
            opened.replayOf shouldBe
                ledger
                    .findRun(id)!!
                    .open.replayOf
                    .toString()
            opened.protocolRef shouldBe "/automation/runs/$id/protocol"
            opened.verdict shouldBe null
            ledger.completeEvaluation(id, result)
            val evaluated = ledger.readRunProgress(id, "subject-1")
            evaluated.status shouldBe "evaluated"
            evaluated.verdict shouldBe result.verdict
            evaluated.guards shouldBe result.guards
            evaluated.mode shouldBe result.mode
            ledger.sealProtocol(id)
            ledger.readRunProgress(id, "subject-1") shouldBe evaluated
            clock.now = now.plusSeconds(90000)
            Ledger(Duration.ofSeconds(1), clock).readRunProgress(id, "subject-1") shouldBe evaluated
            ledger.readRunProgress(id, "subject-1") shouldBe
                opened.copy(
                    status = "evaluated",
                    verdict = result.verdict,
                    guards = result.guards,
                    mode = result.mode,
                )
        }

        "waiting and terminal states retain committed verdicts, timestamps and public failure evidence" {
            for (state in listOf(RunStatus.WAITING, RunStatus.CLOSED, RunStatus.FAILED, RunStatus.ABANDONED)) {
                val id = open()
                ledger.completeEvaluation(id, result)
                clock.now = now.plusSeconds(1)
                val error = if (state == RunStatus.FAILED) buildJsonObject { put("code", "door-refused") } else null
                ledger.markStatus(id, state, error)
                val view = ledger.readRunProgress(id, "subject-1")
                view.status shouldBe state.wire
                view.verdict shouldBe result.verdict
                view.error shouldBe error
                view.endedAt shouldBe if (state.terminal) clock.instant().toString() else null
                clock.now = now
            }
        }

        "unknown handles, other subjects, reused principals and legacy rows refuse the same way" {
            val id = open()
            for (subject in listOf("subject-2", "display-name")) {
                shouldThrow<NoSuchRun> { ledger.readRunProgress(id, subject) }
            }
            shouldThrow<NoSuchRun> { ledger.readRunProgress(UUID.randomUUID(), "subject-1") }
            shouldThrow<NoSuchRun> { ledger.readRunProgress(open(null), "display-name") }
            shouldThrow<IllegalArgumentException> { ledger.readRunProgress(id, " ") }
            transaction {
                shouldThrow<IllegalStateException> { ledger.readRunProgress(id, "subject-1") }
            }
        }

        "polling an expired open run does not start, abandon, journal or otherwise mutate it" {
            val id = open()
            clock.now = now.plusSeconds(90000)
            repeat(3) { ledger.readRunProgress(id, "subject-1").status shouldBe "open" }
            ledger.findRun(id)!!.status shouldBe RunStatus.OPEN
            transaction {
                RunProtocol.selectAll().count() shouldBe 0L
                RunEvaluations.selectAll().count() shouldBe 0L
                RunOutputs.selectAll().count() shouldBe 0L
                Batches.selectAll().count() shouldBe 0L
            }
        }

        "a linked batch is projected from the same durable run snapshot" {
            val id = open()
            val batchId = UUID.randomUUID()
            transaction {
                Batches.insert {
                    it[Batches.id] = batchId
                    it[run] = id
                    it[state] = "preview"
                    it[rows] = 6
                }
                Runs.update({ Runs.id eq id }) { it[Runs.batchId] = batchId }
            }
            ledger.readRunProgress(id, "subject-1").batch shouldBe RunBatch(batchId.toString(), "preview", 6)
            transaction { Batches.update({ Batches.id eq batchId }) { it[state] = "held" } }
            Ledger(Duration.ofMinutes(30), clock).readRunProgress(id, "subject-1").batch shouldBe
                RunBatch(batchId.toString(), "held", 6)
        }

        "a decision committed between the run and protocol SELECTs cannot mix two snapshots" {
            val id = open()
            val pool = Executors.newSingleThreadExecutor()
            try {
                LedgerTestDb.dataSource("automation", "automation", "automation").connection.use { writer ->
                    writer.autoCommit = false
                    writer.createStatement().use {
                        it.execute(
                            "LOCK TABLE automation.run_protocol IN ACCESS EXCLUSIVE MODE",
                        )
                    }
                    val reading = pool.submit<RunProgress> { ledger.readRunProgress(id, "subject-1") }
                    // The reader has selected the OPEN run and is blocked at the protocol SELECT. Commit the
                    // decision now; READ COMMITTED would return OPEN with an already evaluated verdict.
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    var blocked = false
                    while (!blocked && System.nanoTime() < deadline) {
                        writer.createStatement().use { statement ->
                            statement
                                .executeQuery(
                                    "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE relation = " +
                                        "'automation.run_protocol'::regclass AND NOT granted)",
                                ).use { rs ->
                                    rs.next()
                                    blocked = rs.getBoolean(1)
                                }
                        }
                        if (!blocked) Thread.sleep(10)
                    }
                    blocked shouldBe true
                    writer
                        .prepareStatement(
                            "INSERT INTO automation.run_protocol (run, section, seq, payload, at) " +
                                "VALUES (?, 'decision', 1, ?::jsonb, ?)",
                        ).use { statement ->
                            statement.setObject(1, id)
                            statement.setString(2, Json.encodeToString(result))
                            statement.setObject(3, now.atOffset(java.time.ZoneOffset.UTC))
                            statement.executeUpdate()
                        }
                    writer.prepareStatement("UPDATE automation.runs SET status = 'evaluated' WHERE id = ?").use {
                        it.setObject(1, id)
                        it.executeUpdate()
                    }
                    writer.commit()
                    val beforeCommit = reading.get(5, TimeUnit.SECONDS)
                    beforeCommit.status shouldBe "open"
                    beforeCommit.verdict shouldBe null
                }
                val afterCommit = ledger.readRunProgress(id, "subject-1")
                afterCommit.status shouldBe "evaluated"
                afterCommit.verdict shouldBe result.verdict
            } finally {
                pool.shutdownNow()
            }
        }
    })
