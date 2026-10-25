// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.*
import org.tatrman.automation.apply.ApplyPlanner
import org.tatrman.automation.apply.PreviewMetadata
import org.tatrman.automation.apply.PreviewPlan
import org.tatrman.automation.bundle.BundleKind
import org.tatrman.automation.bundle.BundleLoader
import org.tatrman.automation.bundle.Checkout
import org.tatrman.automation.identity.AuthorMap
import org.tatrman.automation.staging.LocalStagingStore
import org.tatrman.automation.staging.Staging
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Frozen accepted Hartland H3 source/bundle, real verifier/planner; only Query result tables are faked. */
class HartlandH3PreviewSpec :
    StringSpec({
        val root = Path.of(requireNotNull(HartlandH3PreviewSpec::class.java.getResource("/hartland-h3")).toURI())
        val bundle by lazy {
            BundleLoader(AuthorMap(emptyMap())).load(
                Checkout(root, "fixture", "9f6fff1aa4bc0aadb1452f469ebe417bc732c933", "bora", "bora@post.cz"),
                BundleKind.SERVED,
            )
        }
        val run = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")

        suspend fun preview(
            scope: CoroutineScope,
            empty: Boolean,
        ): PreviewPlan {
            val loaded = bundle.recipes.getValue("reorder_proposals")
            loaded.coverage.refusal shouldBe null
            val context =
                runnerContext(loaded).copy(
                    id = run,
                    startedAt = Instant.parse("2031-08-15T12:00:00Z"),
                    previewMetadata =
                        PreviewMetadata(
                            loaded.coverage.actions,
                            bundle.entities,
                            loaded.coverage.targets,
                        ),
                    bindings = RecipeBindings.resolve(loaded.recipe, Instant.parse("2026-04-01T00:00:00Z")),
                )
            val temp = Files.createTempDirectory("full-h3-preview-")
            val reads = AtomicInteger()
            val journals = AtomicInteger()
            var plan: PreviewPlan? = null
            val runner =
                PartRunner(
                    context,
                    Door { read ->
                        reads.incrementAndGet()
                        val row =
                            read.columns.map { column ->
                                when (column.name) {
                                    "item" -> 17
                                    "warehouse" -> 3
                                    "on_hand" -> 2
                                    "weekly_demand" -> BigDecimal("3.125")
                                    "proposed_qty" -> BigDecimal("17")
                                    "sold" -> 25.0
                                    "route" -> "propose"
                                    "verdict_reason" -> "full H3 fixture"
                                    else -> 4
                                }
                            }
                        ReadResult(Table(read.columns, if (empty) emptyList() else listOf(row)))
                    },
                    Staging(LocalStagingStore(temp)) {},
                    scope,
                    preview = ApplyPlanner(Clock.fixed(context.startedAt, ZoneOffset.UTC))::preview,
                    recordPreview = { _, _, value ->
                        journals.incrementAndGet()
                        plan = value
                    },
                )
            try {
                runner.evaluate()
                val firstReads = reads.get()
                runner.evaluate()
                val replay =
                    ApplyPlanner(
                        Clock.fixed(Instant.parse("2040-01-01T00:00:00Z"), ZoneOffset.UTC),
                    ).preview(runner)
                replay.actions shouldBe plan!!.actions
                journals.get() shouldBe 1
                reads.get() shouldBe firstReads
                return plan!!
            } finally {
                runner.close()
                temp.toFile().deleteRecursively()
            }
        }

        "complete governed H3 evaluates both the proposal and the deferred buyer ask" {
            supervisorScope {
                val plan = preview(this, false)
                val proposal = plan.rows.single { it.entity == "er.entity.reorder_proposal" }
                proposal.key!!
                    .getValue("proposal_id")
                    .jsonPrimitive.long
                    .let { it < 0 } shouldBe true
                proposal.row!!.keys shouldBe
                    setOf(
                        "proposal_id",
                        "run",
                        "state",
                        "item",
                        "warehouse",
                        "on_hand",
                        "weekly_demand",
                        "proposed_qty",
                    )
                proposal.row!!["proposal_id"] shouldBe proposal.key!!["proposal_id"]
                proposal.row!!["run"] shouldBe JsonPrimitive(run.toString())
                proposal.row!!["weekly_demand"]!!
                    .jsonPrimitive.content
                    .toBigDecimal() shouldBe BigDecimal("3.13")
                proposal.row!!["proposed_qty"]!!.jsonPrimitive.int shouldBe 17
                plan.actions.map { it.action } shouldBe listOf("propose", "ask")
                val ask = plan.actions.single { it.action == "ask" }
                ask.arguments["action"] shouldBe
                    buildJsonObject {
                        put("action", "start")
                        put("arguments", buildJsonObject { put("process", "purchasing") })
                        put("deferred", true)
                    }
                ask.arguments["due"] shouldBe
                    buildJsonObject {
                        put("amount", 2)
                        put("unit", "working_days")
                    }
                ask.arguments["group_by"] shouldBe JsonPrimitive(3)
                ask.would shouldBe "would ask role.buyer to approve start { process: \"purchasing\" } for 3"
                ask.batchRows shouldBe emptyList()
                plan.rows.none { it.entity == "automation.task" || it.entity == "automation.obligation" } shouldBe true
            }
        }

        "empty complete H3 has no proposal or ask and does not allocate a deadline" {
            supervisorScope {
                val plan = preview(this, true)
                plan.actions shouldBe emptyList()
                plan.rows shouldBe emptyList()
            }
        }
    })
