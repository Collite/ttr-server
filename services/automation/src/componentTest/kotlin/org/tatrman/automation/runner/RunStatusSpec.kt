// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.MutableClock
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.LoadedRecipe
import org.tatrman.automation.bundle.ManifestIsland
import org.tatrman.automation.bundle.ManifestRecipe
import org.tatrman.automation.bundle.RecipeManifest
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.RunAsRef
import org.tatrman.automation.identity.RunIdentity
import org.tatrman.automation.identity.TokenKind
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

        fun located(
            id: UUID,
            island: String = "orders",
            truncated: Boolean = false,
        ): AutomationException {
            val recipe =
                LoadedRecipe(
                    "release_order",
                    RecipeManifest(3, islands = listOf(ManifestIsland(island))),
                    ManifestRecipe("release_order"),
                    emptyMap(),
                )
            val caller = Caller("subject-1", "display-name", emptySet(), TokenKind.PERSON, "fixture")
            val identity = RunIdentity(caller, RunAsRef.Caller, caller.principal, null)
            val guards = Guards(RunContext(id, recipe, "demo", "model", identity, emptyMap()))
            val table = Table(emptyList(), emptyList())
            return shouldThrow<AutomationException> {
                if (truncated) {
                    guards.ceiling(island, table, validatorCap = true)
                } else {
                    guards.read(island, ReadResult(table, setOf("security_predicate_applied")), requireComplete = true)
                }
            }
        }

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

        "evaluation warnings survive the protocol and a fresh status reader" {
            val id = open()
            val warning = Json.parseToJsonElement("""[{"code":"route_padded","port":"to_customer_care","rows":2}]""")
            val payload = JsonObject(Json.encodeToJsonElement(result).jsonObject + ("warnings" to warning))
            ledger.completeEvaluation(id, Json.decodeFromJsonElement<EvaluationResult>(payload))
            transaction {
                RunProtocol.selectAll().single()[RunProtocol.payload].jsonObject["warnings"] shouldBe warning
            }
            val status = Ledger(Duration.ofMinutes(30), clock).readRunProgress(id, "subject-1")
            Json.encodeToJsonElement(status).jsonObject["warnings"] shouldBe warning
        }

        "an older evaluation entry without warnings decodes to an empty warning list" {
            val id = open()
            val legacy = JsonObject(Json.encodeToJsonElement(result).jsonObject - "warnings")
            transaction {
                RunProtocol.insert {
                    it[run] = id
                    it[section] = ProtocolSection.DECISION.wire
                    it[seq] = 1
                    it[payload] = legacy
                    it[at] = now.atOffset(java.time.ZoneOffset.UTC)
                }
                Runs.update({ Runs.id eq id }) { it[status] = RunStatus.EVALUATED.wire }
            }
            val status = Ledger(Duration.ofMinutes(30), clock).readRunProgress(id, "subject-1")
            Json { encodeDefaults = true }.encodeToJsonElement(status).jsonObject["warnings"] shouldBe
                JsonArray(emptyList())
        }

        "stored decisions ignore future additive fields without changing their payload" {
            val id = open()
            val payload = JsonObject(Json.encodeToJsonElement(result).jsonObject + ("future" to JsonPrimitive(1)))
            transaction {
                RunProtocol.insert {
                    it[run] = id
                    it[section] = ProtocolSection.DECISION.wire
                    it[seq] = 1
                    it[RunProtocol.payload] = payload
                    it[at] = now.atOffset(java.time.ZoneOffset.UTC)
                }
                Runs.update({ Runs.id eq id }) { it[status] = RunStatus.EVALUATED.wire }
            }
            val status = Ledger(Duration.ofMinutes(30), clock).readRunProgress(id, "subject-1")
            status.verdict shouldBe result.verdict
            status.guards shouldBe result.guards
            status.warnings shouldBe emptyList()
            transaction {
                RunProtocol.selectAll().single()[RunProtocol.payload] shouldBe payload
            }
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

        "unlocated and legacy refusals retain only their run-level error code" {
            val current = open()
            ledger.failExecution(
                current,
                AutomationException(
                    ErrorCode.RESTRICTED_VIEW,
                    "unscoped failure",
                    buildJsonObject { put("island", "orders") },
                ),
            )
            val legacy = open()
            val error = buildJsonObject { put("code", "restricted-view") }
            ledger.markStatus(legacy, RunStatus.FAILED, error)

            val reader = Ledger(Duration.ofMinutes(30), clock)
            for (id in listOf(current, legacy)) {
                val run = reader.readRunProgress(id, "subject-1")
                run.status shouldBe "failed"
                run.error shouldBe error
                run.guards shouldBe emptyList()
            }
        }

        "the first committed failure fixes both its public code and its refusing island" {
            val id = open()
            val first = located(id)
            val retry = located(id, "balances", truncated = true)
            ledger.failExecution(id, first)
            val reader = Ledger(Duration.ofMinutes(30), clock)
            val failed = reader.readRunProgress(id, "subject-1")
            failed.error shouldBe buildJsonObject { put("code", "restricted-view") }
            failed.guards shouldBe listOf(GuardResult("restricted-view", "orders", "refused"))
            clock.now = now.plusSeconds(10)
            ledger.failExecution(id, retry)
            reader.readRunProgress(id, "subject-1") shouldBe failed

            val unrelated = open()
            ledger.failExecution(unrelated, first)
            reader.readRunProgress(unrelated, "subject-1").guards shouldBe emptyList()
        }

        "refusal location expires at the configured horizon from terminal time and is removed by maintenance" {
            val duration = Duration.ofDays(7)
            val history = Ledger(Duration.ofMinutes(30), clock, protocolHistory = duration)
            val id = open()
            clock.now = now.plusSeconds(30)
            val terminal = clock.now
            history.failExecution(id, located(id))
            clock.now = terminal.plus(duration).minusSeconds(1)
            history.purgeExpiredRefusalLocations()
            history.readRunProgress(id, "subject-1").guards shouldBe
                listOf(GuardResult("restricted-view", "orders", "refused"))
            clock.now = terminal.plus(duration)
            val expired = history.readRunProgress(id, "subject-1")
            expired.guards shouldBe emptyList()
            expired.error shouldBe buildJsonObject { put("code", "restricted-view") }
            transaction { Runs.selectAll().single()[Runs.refusedIsland] shouldBe "orders" }
            history.purgeExpiredRefusalLocations()
            transaction { Runs.selectAll().single()[Runs.refusedIsland] shouldBe null }
            history.readRunProgress(id, "subject-1") shouldBe expired
        }

        "on_route suppresses the refusal location only with a recorded silent verdict" {
            val silentRoutes = mapOf("none" to RouteSummary(1, "arrow:none"), "hold" to RouteSummary(0, "arrow:held"))
            for (routes in listOf(null, silentRoutes, result.verdict.routes)) {
                val id = open()
                ledger.appendProtocol(id, ProtocolSection.AUTOMATION, buildJsonObject { put("protocol", "on_route") })
                if (routes != null) ledger.completeEvaluation(id, result.copy(verdict = VerdictSummary(routes, 0)))
                ledger.failExecution(id, located(id))
                val refused = ledger.readRunProgress(id, "subject-1").guards.filter { it.status == "refused" }
                val silent = routes != null && routes.getValue("hold").rowCount == 0L
                refused shouldBe
                    if (silent) emptyList() else listOf(GuardResult("restricted-view", "orders", "refused"))
                transaction {
                    Runs.selectAll().where { Runs.id eq id }.single()[Runs.refusedIsland] shouldBe
                        if (silent) null else "orders"
                }
            }
        }

        "deleting the parent run removes its refusal location" {
            val id = open()
            ledger.failExecution(id, located(id))
            ledger.readRunProgress(id, "subject-1").guards shouldBe
                listOf(GuardResult("restricted-view", "orders", "refused"))
            transaction { Runs.deleteWhere { Runs.id eq id } shouldBe 1 }
            shouldThrow<NoSuchRun> { ledger.readRunProgress(id, "subject-1") }
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
