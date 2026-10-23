// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.apply.*
import org.tatrman.automation.bundle.*
import org.tatrman.automation.staging.*
import org.tatrman.ttr.metadata.model.LocalizedText
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class DryRunSpec :
    StringSpec({
        "calling the planner before staging refuses without starting Query or evaluation" {
            supervisorScope {
                val f = PreviewFixture(this)
                f.runner.use { runner ->
                    shouldThrow<IllegalStateException> {
                        ApplyPlanner(Clock.fixed(PREVIEW_NOW, ZoneOffset.UTC)).preview(runner)
                    }.message shouldBe "preview requires completed staged outputs"
                    f.reads.get() shouldBe 0
                    f.journals.get() shouldBe 0
                }
            }
        }

        "evaluation shares one preview with verdict/detail inserts, native effects and authored would-texts" {
            supervisorScope {
                val f = PreviewFixture(this)
                f.runner.use { runner ->
                    val results = listOf(async { runner.evaluate() }, async { runner.evaluate() }).awaitAll()
                    results.distinct().size shouldBe 1
                    f.journals.get() shouldBe 1
                    f.legacy.get() shouldBe 0
                    f.reads.get() shouldBe 5
                    val plan = f.plan!!
                    plan.run shouldBe runner.context.id.toString()
                    UUID.fromString(plan.batch).toString() shouldBe plan.batch
                    plan.mode shouldBe "preview"
                    plan.rows.size shouldBe 8
                    plan.rows.map { it.seq } shouldBe (1..8).toList()
                    plan.rows.groupingBy { it.entity }.eachCount() shouldBe
                        mapOf(
                            "er.entity.release_order_verdict" to 2,
                            "er.entity.release_order_verdict_detail" to 3,
                            "er.entity.order" to 3,
                        )
                    val verdict = plan.rows.first().row!!
                    verdict["verdict_reason"] shouldBe JsonPrimitive("released by SQL")
                    verdict["run"] shouldBe JsonPrimitive(runner.context.id.toString())
                    verdict["as_of"] shouldBe JsonPrimitive(PREVIEW_NOW.toString())
                    verdict["written_at"] shouldBe JsonPrimitive(PREVIEW_NOW.plusSeconds(1).toString())
                    verdict["parameters_fp"] shouldBe JsonPrimitive("values:original")
                    verdict["tenant"] shouldBe JsonPrimitive("a")
                    val detail = plan.rows.filter { it.entity.endsWith("_detail") }.map { it.row!! }
                    detail.map { it["row_no"] } shouldBe listOf(JsonPrimitive(1), JsonPrimitive(2), JsonPrimitive(3))
                    detail.last()["truncated"] shouldBe JsonPrimitive(true)
                    detail.last()["amount"] shouldBe JsonNull
                    detail.first()["amount"] shouldBe JsonPrimitive("1.00") // Generated detail columns are text.
                    val effects = plan.rows.filter { it.entity == "er.entity.order" }
                    effects.map { it.op } shouldBe listOf("transition", "update", "transition")
                    effects[1].set shouldBe buildJsonObject { put("held_for", "Sales") }
                    effects[1].key shouldBe buildJsonObject { put("order_number", "4712") }
                    effects[2].from shouldBe listOf("placed")
                    effects[2].to shouldBe JsonPrimitive("held")
                    effects[2].history shouldBe true
                    plan.actions.map { it.action } shouldBe listOf("release", "hold", "notify")
                    plan.actions.map { it.would } shouldBe
                        listOf(
                            "would release order 4711",
                            "would hold order 4712 for Sales",
                            "would notify role.sales with 2 rows",
                        )
                    plan.actions.map { it.batchRows } shouldBe listOf(listOf(2), listOf(7, 8), emptyList())
                    val notification = plan.actions.last()
                    notification.key shouldBe
                        buildJsonObject {
                            put("order_number", "4712")
                            put("tenant", "a")
                        }
                    val refs =
                        notification.arguments
                            .getValue("detail")
                            .jsonObject
                            .getValue("refs")
                            .jsonArray
                    refs.single().jsonObject["truncated"] shouldBe JsonPrimitive(true)
                    refs.single().jsonObject["ref"] shouldBe
                        JsonPrimitive("/automation/runs/${runner.context.id}/routes/hold/details/lines?row=0")
                    Json.encodeToString(plan).contains("must-not-forward") shouldBe false
                    Json.encodeToString(plan).contains("not-logged") shouldBe false
                    Json.encodeToJsonElement(notification).jsonObject["dryRun"] shouldBe JsonPrimitive(true)
                }
            }
        }

        "unsupported writer sinks and complex conditions fail once before any decision or preview journal" {
            for (problem in listOf("writer", "condition", "key-lookup", "expression", "text", "types", "collation")) {
                supervisorScope {
                    val original = PreviewMetadata.from(BundleFixture.load())
                    var metadata = original
                    var recipe = previewRecipe()
                    when (problem) {
                        "writer" ->
                            metadata =
                                original.copy(
                                    actions =
                                        original.actions +
                                            (
                                                "release" to
                                                    original.actions.getValue("release").copy(
                                                        sinks =
                                                            mapOf(
                                                                "native" to "writer",
                                                            ),
                                                    )
                                            ),
                                )
                        "condition" ->
                            recipe =
                                recipe.withWiring(
                                    recipe.recipe.wiring.map { it.copy(whenCondition = "amount > 0 and amount < 9") },
                                )
                        "types" ->
                            recipe =
                                recipe.withWiring(
                                    recipe.recipe.wiring.map { it.copy(whenCondition = "amount = \"4.56\"") },
                                )
                        "collation" ->
                            recipe =
                                recipe.withWiring(
                                    recipe.recipe.wiring.map { it.copy(whenCondition = "department > \"AR\"") },
                                )
                        "key-lookup" ->
                            metadata =
                                original.copy(entities = original.entities.filter { it.qname.name != "order" })
                        "expression" ->
                            recipe =
                                recipe.withWiring(
                                    recipe.recipe.wiring.map {
                                        it.copy(
                                            columns =
                                                it.columns + ("order" to "missing_key"),
                                        )
                                    },
                                )
                        "text" ->
                            metadata =
                                original.copy(
                                    actions =
                                        original.actions +
                                            (
                                                "release" to
                                                    original.actions
                                                        .getValue(
                                                            "release",
                                                        ).copy(dryRun = LocalizedText(mapOf("en" to "{missing}")))
                                            ),
                                )
                    }
                    val f = PreviewFixture(this, recipe, metadata)
                    f.runner.use { runner ->
                        repeat(2) {
                            shouldThrow<AutomationException> { runner.evaluate() }.code shouldBe
                                ErrorCode.PREVIEW_UNSUPPORTED
                        }
                        f.journals.get() shouldBe 0
                        f.legacy.get() shouldBe 0
                        f.reads.get() shouldBe 5
                    }
                }
            }
        }

        "each action describes only its own detail limit while the batch keeps the shared snapshot" {
            supervisorScope {
                val original = PreviewMetadata.from(BundleFixture.load())
                val notify = original.actions.getValue("notify")
                val limited =
                    notify.copy(
                        properties =
                            notify.properties + (
                                "detail_limit" to
                                    org.tatrman.ttr.parser.model.AutoValue.Num(
                                        1.0,
                                        org.tatrman.ttr.parser.model.SourceLocation.UNKNOWN,
                                        "1",
                                    )
                            ),
                    )
                val f =
                    PreviewFixture(this, metadata = original.copy(actions = original.actions + ("notify" to limited)))
                f.runner.use { runner ->
                    runner.evaluate()
                    f.plan!!.rows.count { it.entity.endsWith("_detail") } shouldBe 3
                    val action = f.plan!!.actions.single { it.action == "notify" }
                    action.would shouldBe "would notify role.sales with 1 rows"
                    val detail = action.arguments.getValue("detail").jsonObject
                    detail["count"] shouldBe JsonPrimitive(1)
                    detail["truncated"] shouldBe JsonPrimitive(true)
                    detail
                        .getValue("refs")
                        .jsonArray
                        .single()
                        .jsonObject["limit"] shouldBe JsonPrimitive(1)
                    detail
                        .getValue("refs")
                        .jsonArray
                        .single()
                        .jsonObject["ref"] shouldBe
                        JsonPrimitive("/automation/runs/${runner.context.id}/routes/hold/details/lines?row=0")
                }
            }
        }

        "reads-only evaluation leaves an empty preview without manufacturing verdict or action rows" {
            supervisorScope {
                val original = previewRecipe()
                val metadata =
                    original.recipe.copy(
                        roles = ManifestRoles(candidates = "candidates"),
                        wiring = emptyList(),
                    )
                val recipe = original.copy(recipe = metadata, manifest = original.manifest.copy(recipe = metadata))
                val f = PreviewFixture(this, recipe)
                f.runner.use { runner ->
                    runner.evaluate().verdict shouldBe VerdictSummary(emptyMap(), 0)
                    f.plan!!.rows shouldBe emptyList()
                    f.plan!!.actions shouldBe emptyList()
                    f.journals.get() shouldBe 1
                    f.legacy.get() shouldBe 0
                }
            }
        }

        "a refused atomic preview journal stays cached and cannot fall back to decision-only completion" {
            supervisorScope {
                val f = PreviewFixture(this, refuseJournal = true)
                f.runner.use { runner ->
                    repeat(
                        2,
                    ) { shouldThrow<IllegalStateException> { runner.evaluate() }.message shouldBe "journal refused" }
                    f.journals.get() shouldBe 1
                    f.legacy.get() shouldBe 0
                    runner.evaluationRecorded shouldBe false
                    f.reads.get() shouldBe 5
                }
            }
        }

        "the planner rereads verified immutable Arrow before synthesizing rows" {
            supervisorScope {
                val delegate = LocalStagingStore(Files.createTempDirectory("au-preview-corrupt"))
                var corrupt = false
                val store =
                    object : StagingStore by delegate {
                        override fun get(path: String): ByteArray =
                            delegate.get(path).also {
                                if (corrupt) {
                                    it[0] =
                                        (it[0].toInt() xor 1).toByte()
                                }
                            }
                    }
                val f = PreviewFixture(this, store = store)
                f.runner.use { runner ->
                    runner.island("way")
                    corrupt = true
                    shouldThrow<IllegalStateException> { runner.evaluate() }
                    f.journals.get() shouldBe 0
                    f.legacy.get() shouldBe 0
                }
            }
        }

        "model metadata and action texts come from the pinned context, and numeric conditions preserve decimals" {
            supervisorScope {
                val original = PreviewMetadata.from(BundleFixture.load())
                val release =
                    original.actions.getValue("release").copy(
                        dryRun =
                            LocalizedText(
                                mapOf(
                                    "en" to "original {order}",
                                ),
                            ),
                    )
                val recipe =
                    previewRecipe().withWiring(
                        listOf(
                            ManifestWiring(
                                listOf("way.hold"),
                                "release",
                                mapOf(
                                    "order" to "order_number",
                                ),
                                "amount >= :threshold",
                            ),
                        ),
                    )
                val f = PreviewFixture(this, recipe, original.copy(actions = original.actions + ("release" to release)))
                f.runner.use { runner ->
                    runner.evaluate()
                    f.plan!!
                        .actions
                        .single()
                        .would shouldBe "original 4712"
                    f.plan!!
                        .actions
                        .single()
                        .key["tenant"] shouldBe JsonPrimitive("a")
                    f.plan!!.rows.count { it.entity == "er.entity.order" } shouldBe 1
                }
            }
        }

        "malformed and unqualified route wiring refuses before completion even when no rows select it" {
            supervisorScope {
                for (route in listOf("other.release", "release")) {
                    val recipe =
                        previewRecipe().withWiring(
                            listOf(
                                ManifestWiring(
                                    listOf(route),
                                    "release",
                                    mapOf(
                                        "order" to "order_number",
                                    ),
                                ),
                            ),
                        )
                    val f = PreviewFixture(this, recipe)
                    f.runner.use { runner ->
                        shouldThrow<IllegalArgumentException> { runner.evaluate() }
                        f.journals.get() shouldBe 0
                    }
                }
            }
        }

        "qualified manifest wiring applies the action's authored detail limit before previewing" {
            val recipe = previewRecipe().recipe
            val actions = PreviewMetadata.from(BundleFixture.load()).actions
            val notify = actions.getValue("notify")
            val source =
                org.tatrman.ttr.parser.model
                    .SourceLocation.UNKNOWN
            val changed =
                actions + (
                    "notify" to
                        notify.copy(
                            properties =
                                notify.properties +
                                    (
                                        "detail_limit" to
                                            org.tatrman.ttr.parser.model.AutoValue
                                                .Num(7.0, source, "7")
                                    ),
                        )
                )
            detailLimits(recipe, changed, 2).getValue("hold") shouldBe 7
        }
    })

private val PREVIEW_NOW = Instant.parse("2026-10-23T12:00:00Z")
private val PREVIEW_KEYS = listOf(ManifestColumn("order_number", "text"), ManifestColumn("tenant", "text"))
private val PREVIEW_COLUMNS =
    PREVIEW_KEYS +
        listOf(
            ManifestColumn("verdict_reason", "text"),
            ManifestColumn("department", "text"),
            ManifestColumn("amount", "decimal"),
        )

private fun LoadedRecipe.withWiring(wiring: List<ManifestWiring>): LoadedRecipe {
    val recipe = recipe.copy(wiring = wiring)
    return copy(recipe = recipe, manifest = manifest.copy(recipe = recipe))
}

private fun previewRecipe(): LoadedRecipe {
    val original = BundleFixture.load().recipes.getValue("release_order")
    val recipe =
        original.recipe.copy(
            key = listOf("order.order_number", "scope.tenant"),
            parts = emptyList(),
            roles =
                ManifestRoles(
                    "candidates",
                    listOf(ManifestContext("lines", "per-candidate")),
                    "way",
                    listOf(
                        ManifestRoute("release"),
                        ManifestRoute("hold", detail = listOf("lines")),
                        ManifestRoute("empty"),
                    ),
                ),
            wiring =
                listOf(
                    ManifestWiring(listOf("way.release"), "release", mapOf("order" to "order_number")),
                    ManifestWiring(
                        listOf("way.hold"),
                        "hold",
                        mapOf(
                            "order" to "order_number",
                            "department" to "department",
                        ),
                    ),
                    ManifestWiring(listOf("way.hold"), "notify", mapOf("who" to "role.sales", "detail" to "lines")),
                    ManifestWiring(
                        listOf("way.hold"),
                        "notify",
                        mapOf("who" to "role.ar", "detail" to "lines"),
                        "department = \"AR\"",
                    ),
                    ManifestWiring(listOf("way.empty"), "release", mapOf("order" to "order_number")),
                ),
            fingerprints = ManifestFingerprints("text:original", "values:original"),
            verdictSchema =
                original.recipe.verdictSchema!!.copy(
                    columns =
                        original.recipe.verdictSchema!!.columns +
                            buildJsonObject {
                                put("name", "tenant")
                                put("type", "text")
                            },
                ),
        )

    fun output(
        port: String,
        columns: List<ManifestColumn>,
    ) = ManifestOutput(port, port, columns = columns, keyed = true, storage = "erp_db")
    val islands =
        listOf(
            ManifestIsland("candidates", outputs = listOf(output("rows", PREVIEW_KEYS))),
            ManifestIsland(
                "way",
                outputs =
                    listOf("release", "hold", "empty").map { output(it, PREVIEW_COLUMNS) } +
                        output(
                            "hold.lines",
                            PREVIEW_KEYS +
                                listOf(
                                    ManifestColumn("amount", "decimal"),
                                    ManifestColumn("route", "text"),
                                    ManifestColumn("context", "text"),
                                ),
                        ),
            ),
        )
    return LoadedRecipe(
        "release_order",
        RecipeManifest(3, islands = islands, waves = listOf(listOf("candidates"), listOf("way")), recipe = recipe),
        recipe,
        listOf("rows", "release", "hold", "empty", "hold.lines").associateWith { it },
    )
}

private class PreviewFixture(
    scope: CoroutineScope,
    recipe: LoadedRecipe = previewRecipe(),
    metadata: PreviewMetadata = PreviewMetadata.from(BundleFixture.load()),
    refuseJournal: Boolean = false,
    store: StagingStore = LocalStagingStore(Files.createTempDirectory("au-preview")),
) {
    val reads = AtomicInteger()
    val journals = AtomicInteger()
    val legacy = AtomicInteger()
    var plan: PreviewPlan? = null
    val runner =
        PartRunner(
            runnerContext(recipe).copy(
                keys = null,
                detailLimit = 2,
                previewMetadata = metadata,
                bindings =
                    mapOf(
                        "as_of" to BoundValue("datetime", PREVIEW_NOW),
                        "threshold" to BoundValue("decimal", BigDecimal("4.560")),
                        "unused_secret" to BoundValue("text", "must-not-forward"),
                    ),
            ),
            Door { read ->
                reads.incrementAndGet()
                val rows =
                    when (read.sql) {
                        "rows" -> listOf(listOf("4711", "a"), listOf("4712", "a"), listOf("4713", "b"))
                        "release" -> listOf(listOf("4711", "a", "released by SQL", "Sales", BigDecimal("1.23")))
                        "hold" -> listOf(listOf("4712", "a", "held by SQL", "Sales", BigDecimal("4.56")))
                        "empty" -> emptyList()
                        "hold.lines" ->
                            listOf(
                                "1.00",
                                "2.00",
                                "3.00",
                            ).map { listOf("4712", "a", BigDecimal(it), "hold", "lines") }
                        else -> error("unknown read")
                    }
                ReadResult(Table(read.columns, rows))
            },
            Staging(store) {},
            scope,
            { _, _ -> legacy.incrementAndGet() },
            preview = ApplyPlanner(Clock.fixed(PREVIEW_NOW.plusSeconds(1), ZoneOffset.UTC))::preview,
            recordPreview = { _, _, value ->
                journals.incrementAndGet()
                if (refuseJournal) error("journal refused")
                plan =
                    value
            },
        )
}
