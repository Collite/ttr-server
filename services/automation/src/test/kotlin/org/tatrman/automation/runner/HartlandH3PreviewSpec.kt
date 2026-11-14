// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
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

/** P3E H3 @ e54fab20, grammar 0.22.3; real verifier/planner, only Query result tables are faked. */
class HartlandH3PreviewSpec :
    StringSpec({
        val root = Path.of(requireNotNull(HartlandH3PreviewSpec::class.java.getResource("/hartland-h3")).toURI())
        val bundle by lazy {
            BundleLoader(AuthorMap(emptyMap())).load(
                Checkout(
                    root,
                    "fixture",
                    "9f6fff1aa4bc0aadb1452f469ebe417bc732c933",
                    "fixture",
                    "fixture@example.invalid",
                ),
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

        "20-shape H3 previews its handoff as the engine's: an action with no batch rows (⚑AU-D3-1)" {
            supervisorScope {
                val plan = preview(this, false)
                val handoff = plan.actions.filter { it.action == "handoff" }
                handoff.isNotEmpty() shouldBe true
                handoff.forEach {
                    it.sink shouldBe ApplyPlanner.ENGINE_SINK
                    it.batchRows shouldBe emptyList()
                    it.would!! shouldContain "hand the proposal"
                }
                // The engine's action never becomes a §4 row the Server would write.
                plan.rows.none { row -> handoff.any { row.seq in it.batchRows } } shouldBe true
            }
        }

        "empty 20-shape H3 has no proposal or handoff and does not allocate a deadline" {
            supervisorScope {
                val plan = preview(this, true)
                plan.actions shouldBe emptyList()
                plan.rows shouldBe emptyList()
            }
        }
    })
