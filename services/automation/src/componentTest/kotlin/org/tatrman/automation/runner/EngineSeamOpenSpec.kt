// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
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
import org.tatrman.ttr.metadata.model.RunAsRef as ModelRunAs

/**
 * S3.2 T1 on a real ledger: D-143's watermark (a windowed program's first open runs over `[epoch, as_of)`, the next over
 * `[previous as_of, as_of)`; a dry run and an explicit window never advance it; count/gap windows are gated) and the
 * engine seam (D-150/D-159: the six columns and the protocol; an answer the engine's history does not back is refused
 * before any claim; a backed one goes on to the D-99 fingerprint check; the engine cannot run as its caller).
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
        ) = Open(
            { bundle },
            ApprovalReader { _, _, _ -> approved },
            ServiceTokens(emptyMap(), { error("no token exchange") }),
            ledger::claimRun,
            { null },
            { false },
            Duration.ofSeconds(60),
            Duration.ofMinutes(30),
            clock,
            watermark = Watermark::lastTo,
        )

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

        "a dry run and an explicit window never advance the watermark; a later close never rewinds it" {
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

            // Two windows opened before either closed: the later one closes first; the earlier close cannot rewind it.
            val early =
                UUID.fromString(
                    open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya).response.runId,
                )
            clock.now = t0 + Duration.ofMinutes(10) // inside the early run's 30 min batch budget
            val late =
                UUID.fromString(
                    open(windowed).open(recipe, OpenRequest(deployment, windowPort()), maya).response.runId,
                )
            closeWet(late)
            closeWet(early)
            lastTo() shouldBe t0 + Duration.ofMinutes(10)
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
            val verified = runBlocking { EngineRuns(history).verify(ref, maya) }
            val opened = open(plain).open(recipe, OpenRequest(deployment, keyPort(), engine = ref), maya, verified)
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
            val protocol = ledger.readProtocol(id, maya.sub).sections
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
                        runBlocking { EngineRuns { _, _ -> history }.verify(ref, maya) }
                    }
                refused.code shouldBe ErrorCode.IDENTITY
                refused.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("unverified-answer")
            }
            // Open itself never records an unverified answer (fail closed), and an answer needs its task id.
            shouldThrow<AutomationException> {
                open(plain).open(recipe, OpenRequest(deployment, keyPort(), engine = ref), maya)
            }.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("unverified-answer")
            shouldThrow<AutomationException> {
                runBlocking { EngineRuns { _, _ -> error("not read") }.verify(ref.copy(taskId = null), maya) }
            }.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("unverified-answer")
            transaction { Runs.selectAll().count() } shouldBe 0L
        }

        "a matching answer proceeds to the D-99 fingerprint check; the engine's bearer is the caller's" {
            val ref = EngineRef("pi-1", "4711", "dep-7", taskId = "task-3", answeredBy = "maya")
            var bearer: String? = null
            val verified =
                runBlocking {
                    EngineRuns { _, token ->
                        bearer = token
                        listOf(HistoricTask("task-3", "pi-1", "maya", "2026-11-14T06:00:00Z", "completed"))
                    }.verify(ref, maya)
                }
            bearer shouldBe "maya-token"
            shouldThrow<AutomationException> {
                open(
                    plain,
                    approved = false,
                ).open(recipe, OpenRequest(deployment, keyPort(), engine = ref), maya, verified)
            }.code shouldBe ErrorCode.RECIPE_STALE
            transaction { Runs.selectAll().count() } shouldBe 0L
        }

        "the engine client cannot run a deployment as its caller (403 token-exchange)" {
            val engine = Caller("pe-id", "process-engine", emptySet(), TokenKind.SERVICE, "engine-token")
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
