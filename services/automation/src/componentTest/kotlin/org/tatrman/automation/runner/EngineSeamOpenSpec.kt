// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import org.tatrman.automation.writer.RowOutcome
import org.tatrman.automation.apply.ApplyRow
import io.kotest.matchers.shouldNotBe
import io.kotest.assertions.withClue
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.MutableClock
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.apply.PreviewPlan
import org.tatrman.automation.bundle.ApprovalDependencies
import org.tatrman.automation.bundle.ApprovalFixture
import org.tatrman.automation.bundle.ApprovalReader
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.bundle.BundleKind
import org.tatrman.automation.bundle.BundleLoader
import org.tatrman.automation.bundle.Checkout
import org.tatrman.automation.bundle.ManifestParameter
import org.tatrman.automation.engine.EngineHistory
import org.tatrman.automation.engine.HistoricTask
import org.tatrman.automation.identity.AuthorMap
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.automation.identity.TokenKind
import org.tatrman.automation.ledger.*
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.tatrman.ttr.metadata.model.RunAsRef as ModelRunAs

/**
 * S3.2 T1 on a real ledger: D-143's watermark (a windowed program's first open runs over `[epoch, as_of)`, the next over
 * `[previous as_of, as_of)`; a dry run and an explicit window never advance it; count/gap windows are gated) and the
 * engine seam (D-150/D-159: the six columns and the protocol; an answer the engine's history does not back is refused
 * before any claim; a backed one goes on to the D-99 fingerprint check; the engine cannot run as its caller).
 * review-280: one unsealed wet watermark run per program (✅R280-A `409 window-open`), answers from the engine client
 * only (✅R280-B), only a clock `asOf` keeps the watermark (F3), the cheap refusals before the engine call (F4), the
 * reserved window bindings (F5) and the engine predicate's token kind (F6).
 */
class EngineSeamOpenSpec :
    StringSpec({
        LedgerTestDb.ready()
        val recipe = "release_order"
        val program = "release_on_placement"
        val deployment = "release_native_event"
        val t0 = Instant.parse("2026-11-14T06:00:00Z")
        val clock = MutableClock(t0)
        val ledger = Ledger(Duration.ofMinutes(30), clock)
        val maya = Caller("maya-id", "maya", setOf("hartland-sales-managers"), TokenKind.PERSON, "maya-token")
        // The engine's client-credentials bearer; the fixture world grants its role the deployment's named identity.
        val engine =
            Caller("pe-id", "process-engine", setOf("hartland-sales-managers"), TokenKind.SERVICE, "engine-token")
        lateinit var named: Bundle
        lateinit var plain: Bundle
        lateinit var windowed: Bundle

        fun withWindows(
            base: Bundle,
            over: String,
        ): Bundle {
            val r = base.recipes.getValue(recipe)
            val windows =
                listOf(
                    buildJsonObject {
                        put("context", "placed_orders")
                        put("by", JsonArray(listOf(JsonPrimitive("order_number"))))
                        put("over", over)
                    },
                )
            return base.copy(
                programs = base.programs + (program to base.programs.getValue(program).copy(mode = WINDOWED_MODE)),
                recipes = base.recipes + (recipe to r.copy(recipe = r.recipe.copy(windows = windows))),
            )
        }

        beforeSpec {
            val root = ApprovalFixture.copyTo(tempdir().toPath().resolve("engine-seam"))
            val loaded =
                ApprovalDependencies.prepare(
                    BundleLoader(AuthorMap(emptyMap())).load(
                        Checkout(root, "master", "0123456789abcdef", "po", "po@example.org"),
                        BundleKind.SERVED,
                    ),
                )
            named = loaded
            plain =
                loaded.copy(
                    deployments =
                        loaded.deployments +
                            (deployment to loaded.deployments.getValue(deployment).copy(runAs = ModelRunAs.Caller)),
                )
            windowed = withWindows(plain, "day")
        }
        beforeEach {
            LedgerTestDb.reset()
            clock.now = t0
        }

        fun open(
            bundle: Bundle,
            approved: Boolean = true,
            paused: Boolean = false,
            watermark: (String, List<String>) -> Instant = Watermark::lastTo,
        ) = Open(
            { bundle },
            ApprovalReader { _, _, _ -> approved },
            ServiceTokens(mapOf("AUTOMATION_DECIDER" to "svc-hartland-automation:unused"), {
                error("no token is fetched at Open")
            }),
            ledger::claimRun,
            { null },
            { paused },
            Duration.ofSeconds(60),
            Duration.ofMinutes(30),
            clock,
            watermark = watermark,
            engineClient = "process-engine",
        )

        fun runId(result: OpenResult): UUID = UUID.fromString(result.response.runId)

        fun runs(): Long = transaction { Runs.selectAll().count() }

        fun windowPort(from: String? = null) =
            buildJsonObject { putJsonObject("window") { from?.let { put("from", it) } } }

        fun keyPort() = buildJsonObject { put("key", "4711") }

        fun window(id: UUID): JsonObject =
            ledger
                .findRun(id)!!
                .open.port.jsonObject
                .getValue("window")
                .jsonObject

        fun closeWet(id: UUID) {
            ledger.completeEvaluation(
                id,
                EvaluationResult(VerdictSummary(emptyMap(), 0), emptyList()),
                PreviewPlan(UUID.randomUUID().toString(), id.toString(), emptyList(), emptyList()),
            )
            ledger.closeCommitted(id, maya.sub)
        }

        /** One batch row of [op] on order 47(10 + [i]): the verdict's `insert`, the order's `update` or `transition`. */
        fun row(
            i: Int,
            op: String,
            group: JsonObject? = null,
        ): ApplyRow {
            val key = buildJsonObject { put("order_number", "47${10 + i}") }
            return when (op) {
                "insert" -> ApplyRow(i + 1, "er.entity.release_order_verdict", op, key, row = key, group = group)
                "update" ->
                    ApplyRow(
                        i + 1,
                        "er.entity.order",
                        op,
                        key,
                        set = buildJsonObject { put("held_for", "Sales") },
                        group = group,
                    )
                "transition" ->
                    ApplyRow(
                        i + 1,
                        "er.entity.order",
                        op,
                        key,
                        attribute = "status",
                        from = listOf("placed"),
                        to = JsonPrimitive("released"),
                        history = true,
                        group = group,
                    )
                else -> error("no fixture row for $op")
            }
        }

        /**
         * A wet close whose rows the door answered [outcomes] (status to error; `staged` + error = an unknown row), every
         * row of [op] — the writer answers `stale-state` only for a transition's compare-and-set (review-285 N2). An
         * applied row carries [history] as its `state_history` sub-outcome; [held] more rows wait undecided in one
         * `approval: required` group, so the close leaves the run WAITING.
         */
        fun closeWith(
            id: UUID,
            vararg outcomes: Pair<String, String?>,
            op: String = "insert",
            history: String? = null,
            held: Int = 0,
        ) {
            val group = buildJsonObject { put("action", "release") }
            val rows =
                outcomes.indices.map { row(it, op) } +
                    (outcomes.size until outcomes.size + held).map { row(it, op, group) }
            ledger.completeEvaluation(
                id,
                EvaluationResult(VerdictSummary(emptyMap(), 0), emptyList()),
                PreviewPlan(UUID.randomUUID().toString(), id.toString(), rows, emptyList()),
            )
            val batch = ledger.applyState(id, maya.sub).batch
            ledger.recordApply(
                batch,
                outcomes.mapIndexed { i, (status, error) ->
                    RowOutcome(i + 1, status, error, history = history.takeIf { status == "applied" })
                },
            )
            ledger.closeCommitted(id, maya.sub)
        }

        fun lastTo(): Instant? =
            transaction {
                ProgramWindows
                    .selectAll()
                    .where { ProgramWindows.program eq program }
                    .singleOrNull()
                    ?.get(ProgramWindows.lastTo)
                    ?.toInstant()
            }

        "a windowed program's first open runs over [epoch, as_of), the next over [previous as_of, as_of)" {
            val first = open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya)
            first.httpStatus shouldBe 201
            val id1 = UUID.fromString(first.response.runId)
            window(id1)["from"] shouldBe JsonPrimitive(Watermark.EPOCH.toString())
            window(id1)["to"] shouldBe JsonPrimitive(t0.toString())
            window(id1)["watermark"] shouldBe JsonPrimitive(true)
            first.context!!.bindings[WINDOW_FROM] shouldBe BoundValue("datetime", Watermark.EPOCH)
            first.context!!.bindings[WINDOW_TO] shouldBe BoundValue("datetime", t0)
            ledger
                .readProtocol(id1, maya.sub)
                .sections
                .getValue("automation")
                .first()
                .payload.jsonObject["window"]!!
                .jsonObject["from"] shouldBe JsonPrimitive(Watermark.EPOCH.toString())
            lastTo() shouldBe null // advanced at the seal, not at Open
            closeWet(id1)
            lastTo() shouldBe t0
            transaction { ProgramWindows.selectAll().single()[ProgramWindows.run] } shouldBe id1

            val t1 = t0 + Duration.ofHours(24)
            clock.now = t1
            val second = open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya)
            val id2 = UUID.fromString(second.response.runId)
            window(id2)["from"] shouldBe JsonPrimitive(t0.toString())
            window(id2)["to"] shouldBe JsonPrimitive(t1.toString())
            closeWet(id2)
            lastTo() shouldBe t1
        }

        "a dry run and an explicit window never advance the watermark" {
            val dry = open(windowed).open(recipe, OpenRequest(deployment, windowPort(), dryRun = true), maya)
            val dryId = UUID.fromString(dry.response.runId)
            dry.response.dryRun shouldBe true
            ledger.completeEvaluation(
                dryId,
                EvaluationResult(VerdictSummary(emptyMap(), 0), emptyList()),
                PreviewPlan(UUID.randomUUID().toString(), dryId.toString(), emptyList(), emptyList()),
            )
            ledger.closePreview(dryId, maya.sub)
            lastTo() shouldBe null

            val explicit =
                open(windowed).open(recipe, OpenRequest(deployment, windowPort("2026-11-13T06:00:00Z")), maya)
            val explicitId = UUID.fromString(explicit.response.runId)
            window(explicitId)["from"] shouldBe JsonPrimitive("2026-11-13T06:00:00Z")
            window(explicitId)["watermark"] shouldBe JsonPrimitive(false)
            closeWet(explicitId)
            lastTo() shouldBe null
        }

        "✅R280-A: a 2nd wet watermark open while the 1st is unsealed is 409 window-open; after the close it opens" {
            val first = runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya))
            val t1 = t0 + Duration.ofMinutes(10) // inside the first run's 30 min batch budget
            clock.now = t1
            val refused =
                shouldThrow<AutomationException> {
                    open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya)
                }
            refused.code shouldBe ErrorCode.WINDOW_OPEN
            refused.detail!!.jsonObject.let {
                it["program"] shouldBe JsonPrimitive(program)
                it["run"] shouldBe JsonPrimitive(first.toString())
                it["from"] shouldBe JsonPrimitive(Watermark.EPOCH.toString())
                it["to"] shouldBe JsonPrimitive(t1.toString())
            }
            runs() shouldBe 1L

            // Neither a dry watermark open nor an explicit window is blocked (and neither blocks).
            open(windowed).open(recipe, OpenRequest(deployment, windowPort(), dryRun = true), maya).httpStatus shouldBe
                201
            open(windowed)
                .open(recipe, OpenRequest(deployment, windowPort("2026-11-13T06:00:00Z")), maya)
                .httpStatus shouldBe 201

            closeWet(first)
            val second = open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya)
            second.httpStatus shouldBe 201
            window(runId(second))["from"] shouldBe JsonPrimitive(t0.toString())
            window(runId(second))["to"] shouldBe JsonPrimitive(t1.toString())
        }

        "✅R280-A: a same-key retry of the unsealed run is 202, and an abandoned unsealed run does not block" {
            val keyed = OpenRequest(deployment, windowPort(), idempotencyKey = "timer-1")
            val first = open(windowed).open(recipe, keyed, maya)
            first.httpStatus shouldBe 201
            val retry = open(windowed).open(recipe, keyed, maya)
            retry.httpStatus shouldBe 202
            retry.response.runId shouldBe first.response.runId

            ledger.markStatus(runId(first), RunStatus.ABANDONED)
            clock.now = t0 + Duration.ofMinutes(5)
            open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya).httpStatus shouldBe 201
        }

        "✅R280-A: a watermark that moved after Open resolved the window refuses inside the claim (stale from)" {
            closeWet(runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya)))
            lastTo() shouldBe t0
            clock.now = t0 + Duration.ofMinutes(5)
            // An Open that read the watermark before that seal committed resolves from the epoch again.
            val refused =
                shouldThrow<AutomationException> {
                    open(windowed, watermark = { _, _ -> Watermark.EPOCH })
                        .open(recipe, OpenRequest(deployment, windowPort()), maya)
                }
            refused.code shouldBe ErrorCode.WINDOW_OPEN
            refused.detail!!.jsonObject["watermark"] shouldBe JsonPrimitive(t0.toString())
            runs() shouldBe 1L
        }

        "✅R280-A: two replicas opening one program's watermark window at once (two keys) — one 201, one 409" {
            repeat(5) { round ->
                LedgerTestDb.reset()
                clock.now = t0 + Duration.ofMinutes(round.toLong())
                val barrier = CyclicBarrier(2)
                val outcomes =
                    runBlocking(Dispatchers.IO) {
                        List(2) { replica ->
                            // Two different opens (keys): identical ones derive one key, and that race is N1's 202.
                            val opener = open(windowed)
                            async {
                                barrier.await()
                                try {
                                    opener
                                        .open(
                                            recipe,
                                            OpenRequest(deployment, windowPort(), idempotencyKey = "replica-$replica"),
                                            maya,
                                        ).httpStatus
                                } catch (e: AutomationException) {
                                    e.code.status.value
                                }
                            }
                        }.awaitAll()
                    }
                outcomes.sorted() shouldBe listOf(201, 409)
                runs() shouldBe 1L
            }
        }

        "✅R280-F: a watermark window whose asOf is not the clock's is 400 as-of; a replay names window.from" {
            val debug =
                windowed.copy(
                    deployments =
                        windowed.deployments +
                            (deployment to windowed.deployments.getValue(deployment).copy(debug = true)),
                )
            val future = t0 + Duration.ofDays(1)
            val replay =
                shouldThrow<AutomationException> {
                    open(debug).open(recipe, OpenRequest(deployment, windowPort(), asOf = future.toString()), maya)
                }
            replay.code shouldBe ErrorCode.AS_OF
            replay.detail!!.jsonObject["asOf"] shouldBe JsonPrimitive(future.toString())
            replay.detail!!.jsonObject["policy"] shouldBe JsonPrimitive(future.toString())
            runs() shouldBe 0L

            // The replay with its range named: explicit, never keeps the watermark.
            val from = t0 - Duration.ofHours(1)
            val named =
                open(debug).open(
                    recipe,
                    OpenRequest(deployment, windowPort(from.toString()), asOf = future.toString()),
                    maya,
                )
            named.httpStatus shouldBe 201
            window(runId(named))["watermark"] shouldBe JsonPrimitive(false)
            window(runId(named))["to"] shouldBe JsonPrimitive(future.toString())
            closeWet(runId(named))
            lastTo() shouldBe null

            val fixedAt = t0 - Duration.ofHours(1)
            val fixed =
                windowed.copy(
                    programs =
                        windowed.programs +
                            (program to windowed.programs.getValue(program).copy(asOf = fixedAt.toString())),
                )
            val pinned =
                shouldThrow<AutomationException> {
                    open(fixed).open(recipe, OpenRequest(deployment, windowPort()), maya)
                }
            pinned.code shouldBe ErrorCode.AS_OF
            pinned.detail!!.jsonObject["policy"] shouldBe JsonPrimitive(fixedAt.toString())
            runs() shouldBe 1L

            // asOf "now" is the clock's: the normal open runs from the (untouched) watermark and keeps it.
            val now = open(windowed).open(recipe, OpenRequest(deployment, windowPort(), asOf = "now"), maya)
            now.httpStatus shouldBe 201
            window(runId(now))["from"] shouldBe JsonPrimitive(Watermark.EPOCH.toString())
            window(runId(now))["watermark"] shouldBe JsonPrimitive(true)
        }

        "N4: a seal never rewinds the watermark — a later close of an earlier window leaves the later one" {
            val first =
                runId(
                    open(
                        windowed,
                    ).open(recipe, OpenRequest(deployment, windowPort(t0.minusSeconds(60).toString())), maya),
                )
            val second =
                runId(
                    open(
                        windowed,
                    ).open(recipe, OpenRequest(deployment, windowPort(t0.minusSeconds(30).toString())), maya),
                )
            val t1 = t0 + Duration.ofHours(1)
            val names = listOf("placed_orders")
            transaction {
                val now = t0.atOffset(java.time.ZoneOffset.UTC)
                Watermark.advanceAtSeal(first, program, Watermark.port(Watermark.EPOCH, t1, names, true), now)
                Watermark.advanceAtSeal(second, program, Watermark.port(Watermark.EPOCH, t0, names, true), now)
            }
            lastTo() shouldBe t1
            transaction { ProgramWindows.selectAll().single()[ProgramWindows.run] } shouldBe first
        }

        "N1: a same-key retry racing its own first claim on another replica is 202, never 409 (fresh and stale key)" {
            val pg = LedgerTestDb.dataSource("automation", "automation", "automation")

            fun waiters(): Long =
                pg.connection.use { c ->
                    c.createStatement().use { st ->
                        st
                            .executeQuery(
                                "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted",
                            ).use {
                                it.next()
                                it.getLong(1)
                            }
                    }
                }

            // Both claims block on the program lock held here, then race for it once it is released.
            fun race(key: String): List<OpenResult> =
                pg.connection.use { holder ->
                    holder.autoCommit = true

                    fun lock(sql: String) =
                        holder.prepareStatement(sql).use {
                            it.setInt(1, Watermark.LOCK_NAMESPACE)
                            it.setString(2, program)
                            it.execute()
                        }
                    lock("SELECT pg_advisory_lock(?, hashtext(?))")
                    runBlocking(Dispatchers.IO) {
                        val claims =
                            List(2) {
                                val replica = open(windowed)
                                async {
                                    replica.open(
                                        recipe,
                                        OpenRequest(deployment, windowPort(), idempotencyKey = key),
                                        maya,
                                    )
                                }
                            }
                        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
                        while (waiters() < 2L) {
                            check(
                                System.nanoTime() < deadline,
                            ) { "the two claims never both waited on the program lock" }
                            Thread.sleep(20)
                        }
                        lock("SELECT pg_advisory_unlock(?, hashtext(?))")
                        claims.awaitAll()
                    }
                }

            val fresh = race("timer-7").sortedBy { it.httpStatus }
            fresh.map { it.httpStatus } shouldBe listOf(201, 202)
            fresh[1].response.runId shouldBe fresh[0].response.runId
            runs() shouldBe 1L

            // A stale holder of the key: one AbandonThenOpen 201, the other waits on the new run (202).
            LedgerTestDb.reset()
            val stale =
                runId(
                    open(
                        windowed,
                    ).open(recipe, OpenRequest(deployment, windowPort(), idempotencyKey = "timer-8"), maya),
                )
            clock.now = t0 + Duration.ofHours(2) // past the 30 min batch budget + grace
            val retried = race("timer-8").sortedBy { it.httpStatus }
            retried.map { it.httpStatus } shouldBe listOf(201, 202)
            retried[1].response.runId shouldBe retried[0].response.runId
            ledger.findRun(stale)!!.status shouldBe RunStatus.ABANDONED
            runs() shouldBe 2L
        }

        "F5: a recipe parameter named window_from is 400 port with the reserved names; a non-window open still works" {
            fun withParameter(base: Bundle): Bundle {
                val r = base.recipes.getValue(recipe)
                val parameters = r.recipe.parameters + ManifestParameter(WINDOW_FROM, "int", "5")
                val withParameters = r.copy(recipe = r.recipe.copy(parameters = parameters))
                return base.copy(recipes = base.recipes + (recipe to withParameters))
            }
            val refused =
                shouldThrow<AutomationException> {
                    open(withParameter(windowed)).open(recipe, OpenRequest(deployment, windowPort()), maya)
                }
            refused.code shouldBe ErrorCode.PORT
            refused.detail!!.jsonObject["reserved"] shouldBe JsonArray(listOf(JsonPrimitive(WINDOW_FROM)))
            open(withParameter(plain)).open(recipe, OpenRequest(deployment, keyPort()), maya).httpStatus shouldBe 201
        }

        "count/gap windows are gated (422); a window port needs a windowed program; an empty window refuses" {
            for (over in listOf("rows 50", "gap 30 minutes")) {
                shouldThrow<AutomationException> {
                    open(withWindows(plain, over)).open(recipe, OpenRequest(deployment, windowPort()), maya)
                }.code shouldBe ErrorCode.WINDOW_KIND_GATED
            }
            shouldThrow<AutomationException> {
                open(plain).open(recipe, OpenRequest(deployment, windowPort()), maya)
            }.code shouldBe ErrorCode.PORT
            shouldThrow<AutomationException> {
                open(windowed).open(recipe, OpenRequest(deployment, keyPort()), maya)
            }.code shouldBe ErrorCode.PORT
            shouldThrow<AutomationException> {
                open(windowed).open(recipe, OpenRequest(deployment, windowPort(t0.toString())), maya)
            }.code shouldBe ErrorCode.PORT
            transaction { Runs.selectAll().count() } shouldBe 0L
        }

        "an engine open stores the six columns and names them in the protocol" {
            val ref = EngineRef("pi-1", "4711", "dep-7", taskId = "task-3", answeredBy = "maya")
            val history = EngineHistory { _, _ -> listOf(HistoricTask("task-3", "pi-1", "maya", null, null)) }
            val verified = runBlocking { engineSeam(history).verify(ref, engine) }
            val opened = open(named).open(recipe, OpenRequest(deployment, keyPort(), engine = ref), engine, verified)
            val id = UUID.fromString(opened.response.runId)
            transaction {
                val row = Runs.selectAll().where { Runs.id eq id }.single()
                listOf(
                    row[Runs.engineInstanceId],
                    row[Runs.businessKey],
                    row[Runs.engineDeploymentId],
                    row[Runs.engineTaskId],
                    row[Runs.assertedPerson],
                ) shouldBe listOf("pi-1", "4711", "dep-7", "task-3", "maya")
                row[Runs.personVerified] shouldBe true
            }
            ledger.findRun(id)!!.open.engine shouldBe EngineColumns("pi-1", "4711", "dep-7", "task-3", "maya", true)
            val protocol = ledger.readProtocol(id, engine.sub).sections
            protocol
                .getValue("automation")
                .first()
                .payload.jsonObject["engine"] shouldBe
                buildJsonObject {
                    put("instanceId", "pi-1")
                    put("businessKey", "4711")
                    put("deploymentId", "dep-7")
                    put("taskId", "task-3")
                    put("answeredBy", "maya")
                    put("personVerified", true)
                }
            protocol.getValue("people").single().payload.jsonObject.let {
                it["event"] shouldBe JsonPrimitive("identity")
                it["asserted"] shouldBe JsonPrimitive("maya")
                it["verified"] shouldBe JsonPrimitive(true)
            }
            // A run the engine starts without an answer records the instance and nothing asserted.
            val started =
                open(plain).open(
                    recipe,
                    OpenRequest(
                        deployment,
                        buildJsonObject { put("key", "4712") },
                        engine = EngineRef("pi-2", "4712", "dep-7"),
                    ),
                    maya,
                )
            ledger.findRun(UUID.fromString(started.response.runId))!!.open.engine shouldBe
                EngineColumns("pi-2", "4712", "dep-7", null, null, null)
            ledger.readProtocol(UUID.fromString(started.response.runId), maya.sub).sections["people"] shouldBe
                emptyList()
        }

        "an answer whose task history names another assignee is 403 unverified-answer and nothing is claimed" {
            val ref = EngineRef("pi-1", "4711", "dep-7", taskId = "task-3", answeredBy = "maya")
            for (history in listOf(
                listOf(HistoricTask("task-3", "pi-1", "petr", null, null)),
                listOf(HistoricTask("task-3", "pi-OTHER", "maya", null, null)),
                emptyList(),
            )) {
                val refused =
                    shouldThrow<AutomationException> {
                        runBlocking { engineSeam({ _, _ -> history }).verify(ref, engine) }
                    }
                refused.code shouldBe ErrorCode.IDENTITY
                refused.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("unverified-answer")
            }
            // Open itself never records an unverified answer (fail closed), and an answer needs its task id.
            shouldThrow<AutomationException> {
                open(plain).open(recipe, OpenRequest(deployment, keyPort(), engine = ref), maya)
            }.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("unverified-answer")
            shouldThrow<AutomationException> {
                runBlocking { engineSeam({ _, _ -> error("not read") }).verify(ref.copy(taskId = null), engine) }
            }.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("unverified-answer")
            transaction { Runs.selectAll().count() } shouldBe 0L
        }

        "a matching answer proceeds to the D-99 fingerprint check; the engine's bearer is the caller's" {
            val ref = EngineRef("pi-1", "4711", "dep-7", taskId = "task-3", answeredBy = "maya")
            var bearer: String? = null
            val verified =
                runBlocking {
                    engineSeam({ _, token ->
                        bearer = token
                        listOf(HistoricTask("task-3", "pi-1", "maya", "2026-11-14T06:00:00Z", "completed"))
                    }).verify(ref, engine)
                }
            bearer shouldBe "engine-token"
            shouldThrow<AutomationException> {
                open(
                    named,
                    approved = false,
                ).open(recipe, OpenRequest(deployment, keyPort(), engine = ref), engine, verified)
            }.code shouldBe ErrorCode.RECIPE_STALE
            transaction { Runs.selectAll().count() } shouldBe 0L
        }

        "the engine client cannot run a deployment as its caller (403 token-exchange)" {
            val refused =
                shouldThrow<AutomationException> {
                    open(
                        plain,
                    ).open(recipe, OpenRequest(deployment, keyPort()), engine)
                }
            refused.code shouldBe ErrorCode.IDENTITY
            refused.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("token-exchange")
            transaction { Runs.selectAll().count() } shouldBe 0L
        }

        "F6: a person whose username is the engine's client id is not the engine (runs as caller, 201)" {
            val person = Caller("p-id", "process-engine", emptySet(), TokenKind.PERSON, "person-token")
            open(plain).open(recipe, OpenRequest(deployment, keyPort()), person).httpStatus shouldBe 201
        }

        "✅R280-B: an answer is accepted from the engine client only — refused before the history call" {
            val ref = EngineRef("pi-1", "4711", "dep-7", taskId = "task-3", answeredBy = "maya")
            val calls = AtomicInteger()
            val engineRuns =
                engineSeam({ _, _ ->
                    calls.incrementAndGet()
                    listOf(HistoricTask("task-3", "pi-1", "maya", null, null))
                })
            for (caller in listOf(
                // A person, even one whose username is the engine's client id; a service of another client.
                Caller("p-id", "process-engine", emptySet(), TokenKind.PERSON, "person-token"),
                maya,
                Caller("svc-id", "n8n-hartland", emptySet(), TokenKind.SERVICE, "svc-token"),
            )) {
                val refused = shouldThrow<AutomationException> { runBlocking { engineRuns.verify(ref, caller) } }
                refused.code shouldBe ErrorCode.IDENTITY
                refused.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("unverified-answer")
            }
            calls.get() shouldBe 0
            runBlocking { engineRuns.verify(ref, engine) }!!.personVerified shouldBe true
            calls.get() shouldBe 1
            // An engine block that asserts nobody is open to any caller with the grant.
            runBlocking { engineRuns.verify(EngineRef("pi-2", "4712", "dep-7"), maya) }!!.personVerified shouldBe null
            calls.get() shouldBe 1
        }

        "F4: a paused service refuses an answer open 503 paused without an engine round-trip" {
            val calls = AtomicInteger()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sessions =
                RunSessions(
                    open(named, paused = true),
                    scope,
                    { _, _ -> error("nothing is claimed") },
                    { null },
                    { _, _ -> },
                    clock = clock,
                    engineRuns =
                        engineSeam({ _, _ ->
                            calls.incrementAndGet()
                            listOf(HistoricTask("task-3", "pi-1", "maya", null, null))
                        }),
                )
            val ref = EngineRef("pi-1", "4711", "dep-7", taskId = "task-3", answeredBy = "maya")
            val refused =
                shouldThrow<AutomationException> {
                    runBlocking { sessions.open(recipe, OpenRequest(deployment, keyPort(), engine = ref), engine) }
                }
            refused.code shouldBe ErrorCode.PAUSED
            calls.get() shouldBe 0
            runs() shouldBe 0L
            scope.cancel()
        }

        "N3: the stopping pre-check takes no lock — an answer open reaches the engine while another open holds it" {
            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val reached = CountDownLatch(1)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // The first open blocks inside policy.open (its watermark read), so it holds the registry lock.
            val sessions =
                RunSessions(
                    open(windowed, watermark = { _, _ ->
                        inside.countDown()
                        release.await()
                        error("released")
                    }),
                    scope,
                    { _, _ -> error("nothing is claimed") },
                    { null },
                    { _, _ -> },
                    clock = clock,
                    engineRuns =
                        engineSeam({ _, _ ->
                            reached.countDown()
                            listOf(HistoricTask("task-3", "pi-1", "maya", null, null))
                        }),
                )
            val ref = EngineRef("pi-1", "4711", "dep-7", taskId = "task-3", answeredBy = "maya")
            val held =
                scope.async {
                    runCatching {
                        sessions.open(
                            recipe,
                            OpenRequest(deployment, windowPort()),
                            maya,
                        )
                    }
                }
            inside.await(10, TimeUnit.SECONDS) shouldBe true
            val answer =
                scope.async {
                    runCatching {
                        sessions.open(
                            recipe,
                            OpenRequest(deployment, keyPort(), engine = ref),
                            engine,
                        )
                    }
                }
            try {
                reached.await(5, TimeUnit.SECONDS) shouldBe true
            } finally {
                release.countDown()
                runBlocking { listOf(held, answer).awaitAll() }
                scope.cancel()
            }
        }

        "D-195 (C): a transient failed or an unknown row seals but holds the watermark; the next window re-reads" {
            closeWet(runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya)))
            lastTo() shouldBe t0
            for ((case, outcomes) in listOf(
                "failed" to arrayOf("applied" to null, "failed" to "worker_execution_failed"),
                "unknown" to arrayOf("applied" to null, "staged" to RowOutcome.UNKNOWN),
            )) {
                clock.now += Duration.ofHours(1)
                val held = runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya))
                window(held)["from"] shouldBe JsonPrimitive(t0.toString())
                closeWith(held, *outcomes)
                ledger.findRun(held)!!.status shouldBe RunStatus.CLOSED
                withClue(case) { lastTo() shouldBe t0 }
                transaction { ProgramWindows.selectAll().single()[ProgramWindows.run] } shouldNotBe held
            }
            // Applied and skipped rows only: the window advances, from the held one's old last_to.
            clock.now += Duration.ofHours(1)
            val clean = runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya))
            window(clean)["from"] shouldBe JsonPrimitive(t0.toString())
            closeWith(clean, "applied" to null, "skipped" to "if_absent")
            lastTo() shouldBe clock.now
            transaction { ProgramWindows.selectAll().single()[ProgramWindows.run] } shouldBe clean
        }

        "D-201: a close whose only failure is stale-state advances; a transient failed or an unknown row still holds" {
            closeWet(runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya)))
            lastTo() shouldBe t0
            for ((case, outcomes) in listOf(
                "stale-state + failed" to
                    arrayOf("failed" to RowOutcome.STALE_STATE, "failed" to "worker_execution_failed"),
                "stale-state + unknown" to arrayOf("failed" to RowOutcome.STALE_STATE, "staged" to RowOutcome.UNKNOWN),
            )) {
                clock.now += Duration.ofHours(1)
                val held = runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya))
                window(held)["from"] shouldBe JsonPrimitive(t0.toString())
                closeWith(held, *outcomes, op = "transition")
                ledger.findRun(held)!!.status shouldBe RunStatus.CLOSED
                withClue(case) { lastTo() shouldBe t0 }
                transaction { ProgramWindows.selectAll().single()[ProgramWindows.run] } shouldNotBe held
            }
            // The re-read window: its already-applied transitions come back stale-state — settled, so it advances.
            clock.now += Duration.ofHours(1)
            val reread = runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya))
            window(reread)["from"] shouldBe JsonPrimitive(t0.toString())
            closeWith(
                reread,
                "failed" to RowOutcome.STALE_STATE,
                "applied" to null,
                "failed" to RowOutcome.STALE_STATE,
                op = "transition",
            )
            ledger.findRun(reread)!!.status shouldBe RunStatus.CLOSED
            lastTo() shouldBe clock.now
            transaction { ProgramWindows.selectAll().single()[ProgramWindows.run] } shouldBe reread
        }

        "D-202 (F5): a close whose failures are all settled advances; one transient outcome holds" {
            closeWet(runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya)))
            lastTo() shouldBe t0
            // Each settled code on the op its producer answers it for (the fixture matches its producer).
            for ((op, code) in listOf(
                "transition" to RowOutcome.STALE_STATE,
                "transition" to "bad-transition",
                "update" to "no-row",
                "insert" to "rows-affected-0",
                "insert" to "write_under_row_policy",
                "insert" to "not-writable",
                "insert" to "bad-value",
            )) {
                clock.now += Duration.ofHours(1)
                val id = runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya))
                closeWith(id, "applied" to null, "failed" to code, op = op)
                withClue("settled $code") { lastTo() shouldBe clock.now }
            }
            val settledTo = clock.now
            for ((op, outcome) in listOf(
                "insert" to ("staged" to RowOutcome.UNKNOWN),
                "insert" to ("failed" to "worker_execution_failed"),
                "transition" to ("failed" to "connection_closed"),
                "insert" to ("failed" to "deadline-exceeded"),
                "insert" to ("failed" to "identity"),
            )) {
                clock.now += Duration.ofHours(1)
                val id = runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya))
                window(id)["from"] shouldBe JsonPrimitive(settledTo.toString())
                closeWith(id, "applied" to null, outcome, op = op)
                withClue("transient ${outcome.second}") { lastTo() shouldBe settledTo }
                transaction { ProgramWindows.selectAll().single()[ProgramWindows.run] } shouldNotBe id
            }
        }

        "review-293 F1: a close whose only failure is a door outage (translator_unavailable) holds the watermark" {
            closeWet(runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya)))
            lastTo() shouldBe t0
            clock.now += Duration.ofHours(1)
            val id = runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya))
            closeWith(id, "failed" to "translator_unavailable", "failed" to "translator_unavailable")
            lastTo() shouldBe t0
            transaction { ProgramWindows.selectAll().single()[ProgramWindows.run] } shouldNotBe id
        }

        "D-202 (F3): a WAITING run whose seal did not advance blocks the next window; one that advanced does not" {
            closeWet(runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya)))
            lastTo() shouldBe t0
            // Clean auto rows + an undecided group: WAITING, and the seal advanced — the next window opens.
            clock.now += Duration.ofHours(1)
            val advanced = runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya))
            closeWith(advanced, "applied" to null, held = 1)
            ledger.findRun(advanced)!!.status shouldBe RunStatus.WAITING
            lastTo() shouldBe clock.now
            val advancedTo = clock.now
            clock.now += Duration.ofHours(1)
            val held = runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya))
            window(held)["from"] shouldBe JsonPrimitive(advancedTo.toString())
            // A transient row + an undecided group: WAITING, and the seal did not advance.
            closeWith(held, "failed" to "worker_execution_failed", held = 1)
            ledger.findRun(held)!!.status shouldBe RunStatus.WAITING
            lastTo() shouldBe advancedTo
            clock.now += Duration.ofHours(1)
            val refused =
                shouldThrow<AutomationException> {
                    open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya)
                }
            refused.code shouldBe ErrorCode.WINDOW_OPEN
            refused.detail!!.jsonObject.let {
                it["run"] shouldBe JsonPrimitive(held.toString())
                it["reason"] shouldBe JsonPrimitive("waiting")
                it["watermark"] shouldBe JsonPrimitive(advancedTo.toString())
            }
            val before = runs()
            // A dry open and an explicit window are never blocked.
            open(windowed).open(recipe, OpenRequest(deployment, windowPort(), dryRun = true), maya).httpStatus shouldBe
                201
            open(windowed)
                .open(recipe, OpenRequest(deployment, windowPort(from = advancedTo.toString())), maya)
                .httpStatus shouldBe 201
            runs() shouldBe before + 2
        }

        "D-202 (F4, accepted): a held group's failure after the seal advanced is inspected, not re-read" {
            closeWet(runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya)))
            clock.now += Duration.ofHours(1)
            val id = runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya))
            closeWith(id, "applied" to null, held = 1)
            ledger.findRun(id)!!.status shouldBe RunStatus.WAITING
            val advancedTo = clock.now
            lastTo() shouldBe advancedTo
            // The operator commits the group later; its row fails at the door (a transient code, even).
            clock.now += Duration.ofMinutes(10)
            ledger.recordApply(
                ledger.applyState(id, maya.sub).batch,
                listOf(RowOutcome(2, "failed", "worker_execution_failed")),
            )
            ledger.decideHeld(id, buildJsonObject { put("action", "release") }, "commit", "ops-person")
            ledger.findRun(id)!!.status shouldBe RunStatus.CLOSED
            lastTo() shouldBe advancedTo
            transaction { ProgramWindows.selectAll().single()[ProgramWindows.run] } shouldBe id
            ledger
                .readProtocol(id, maya.sub)
                .sections
                .getValue("writes")
                .last()
                .payload.jsonObject
                .let {
                    it["event"] shouldBe JsonPrimitive("held-commit")
                    it["failed"] shouldBe JsonPrimitive(1)
                }
            // The next window starts after the advanced range: the failed row is not re-read.
            clock.now += Duration.ofHours(1)
            val next = runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya))
            window(next)["from"] shouldBe JsonPrimitive(advancedTo.toString())
        }

        "review-285 N3: a history sub-outcome and an undecided held group do not hold the watermark" {
            closeWet(runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya)))
            lastTo() shouldBe t0
            // (i) a transition applied, its state_history request failed: the row status is `applied`.
            for (history in listOf("failed", "unknown")) {
                clock.now += Duration.ofHours(1)
                val id = runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya))
                closeWith(id, "applied" to null, op = "transition", history = history)
                ledger.findRun(id)!!.status shouldBe RunStatus.CLOSED
                withClue("history: $history") { lastTo() shouldBe clock.now }
                transaction { ProgramWindows.selectAll().single()[ProgramWindows.run] } shouldBe id
            }
            // (ii) clean auto rows and one undecided held group: WAITING, and the seal advanced.
            clock.now += Duration.ofHours(1)
            val waiting = runId(open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya))
            closeWith(waiting, "applied" to null, held = 2)
            ledger.findRun(waiting)!!.status shouldBe RunStatus.WAITING
            lastTo() shouldBe clock.now
            transaction { ProgramWindows.selectAll().single()[ProgramWindows.run] } shouldBe waiting
        }

        "D-195 (D): a same-key 202 and 200 carry the stored dryRun — true for a forced dry run, false for a wet one" {
            val forced =
                plain.copy(
                    deployments =
                        plain.deployments + (deployment to plain.deployments.getValue(deployment).copy(dryRun = true)),
                )
            val keyed = OpenRequest(deployment, keyPort(), dryRun = false, idempotencyKey = "engine-1")
            val first = open(forced).open(recipe, keyed, maya)
            first.response.dryRun shouldBe true
            val running = open(forced).open(recipe, keyed, maya)
            running.httpStatus shouldBe 202
            running.response.dryRun shouldBe true
            val id = runId(first)
            ledger.completeEvaluation(
                id,
                EvaluationResult(VerdictSummary(emptyMap(), 0), emptyList()),
                PreviewPlan(UUID.randomUUID().toString(), id.toString(), emptyList(), emptyList()),
            )
            ledger.closePreview(id, maya.sub)
            val replay = open(forced).open(recipe, keyed, maya)
            replay.httpStatus shouldBe 200
            replay.response.dryRun shouldBe true

            val wet = OpenRequest(deployment, keyPort(), idempotencyKey = "engine-2")
            open(plain).open(recipe, wet, maya).httpStatus shouldBe 201
            open(plain).open(recipe, wet, maya).let {
                it.httpStatus shouldBe 202
                it.response.dryRun shouldBe false
            }
        }

        "⚑AU-EXP-5: a deployment-forced dry run answers dryRun true to a dryRun false request" {
            val forced =
                plain.copy(
                    deployments =
                        plain.deployments + (deployment to plain.deployments.getValue(deployment).copy(dryRun = true)),
                )
            val opened = open(forced).open(recipe, OpenRequest(deployment, keyPort(), dryRun = false), maya)
            opened.httpStatus shouldBe 201
            opened.response.dryRun shouldBe true
            ledger.findRun(UUID.fromString(opened.response.runId))!!.open.dryRun shouldBe true
        }
    })

/** The engine check as `RunRuntime` wires it (review-280 N6: the client id is required). */
private fun engineSeam(history: EngineHistory) = EngineRuns(history, "process-engine")
