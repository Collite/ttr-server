// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.apply.*
import org.tatrman.automation.bundle.*
import org.tatrman.automation.bundle.BundleFixture.edit
import org.tatrman.automation.staging.*
import org.tatrman.ttr.metadata.model.LocalizedText
import org.tatrman.ttr.metadata.model.AutomationColumn
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class DryRunSpec :
    StringSpec({
        fun writerMetadata(amountType: String = "decimal"): PreviewMetadata {
            val original = legacyPreviewMetadata(BundleFixture.load())
            val order = original.entities.single { it.qname.name == "order" }
            val key = order.attributes.single { it.isKey }
            val target =
                order.copy(
                    attributes =
                        listOf(
                            key.copy(nullable = false),
                            key.copy(
                                internalId = "tenant",
                                qname = key.qname.copy(name = "order.tenant"),
                                nullable = false,
                            ),
                            order.attributes
                                .single {
                                    it.qname.name.endsWith(
                                        ".value",
                                    )
                                }.copy(type = amountType, nullable = false),
                            order.attributes.single { it.qname.name.endsWith(".held_for") }.copy(nullable = true),
                            order.attributes.single { it.qname.name.endsWith(".state") }.copy(nullable = true),
                        ),
                )
            val action =
                original.actions.getValue("hold").copy(
                    columns =
                        listOf(
                            AutomationColumn("id", "order.order_number"),
                            AutomationColumn("tenant", "order.tenant"),
                            AutomationColumn("total", "order.value"),
                            AutomationColumn("label", "order.held_for", optional = true),
                        ),
                    sinks = mapOf("native" to "writer"),
                    transition = null,
                    set = null,
                    dryRun = LocalizedText(mapOf("en" to "would insert order {id} for {tenant}")),
                )
            return original.copy(actions = original.actions + ("hold" to action), entities = listOf(target))
        }

        fun writerWiring(
            routes: List<String> = listOf("way.hold"),
            total: String = "amount + 1.5",
        ) = ManifestWiring(routes, "hold", mapOf("id" to "order_number", "tenant" to "tenant", "total" to total))

        fun defaultMetadata(defaults: String): PreviewMetadata {
            val root = BundleFixture.copy()
            try {
                root.edit("model/er/automation-entities.ttrm") {
                    it.replace(
                        "def attribute order_number",
                        """
                        def attribute tenant { type: text },
                        def attribute run { type: text, optional: true },
                        def attribute opened { type: datetime, optional: true },
                        def attribute order_number
                        """.trimIndent(),
                    )
                }
                root.edit("automation/automation.ttrm") {
                    it +
                        """

                        def action default_writer {
                            columns: [
                                def column id { type: order.order_number },
                                def column tenant { type: order.tenant },
                                def column total { type: order.value, optional: true },
                                def column label { type: order.held_for, optional: true },
                                def column state { type: order.state, optional: true },
                                def column run { type: order.run, optional: true },
                                def column opened { type: order.opened, optional: true }
                            ],
                            sinks: { native: writer },
                            $defaults
                            dry_run: { en: "would insert order {id} for {tenant}" }
                        }
                        """.trimIndent()
                }
                val loaded = legacyPreviewMetadata(BundleFixture.load(root)).actions.getValue("default_writer")
                val original = writerMetadata()
                val target = original.entities.single()
                val field = target.attributes.first()
                return original.copy(
                    actions = original.actions + ("hold" to loaded),
                    entities =
                        listOf(
                            target.copy(
                                attributes =
                                    target.attributes +
                                        listOf(
                                            field.copy(
                                                qname = field.qname.copy(name = "order.run"),
                                                type = "text",
                                                isKey = false,
                                                nullable = true,
                                            ),
                                            field.copy(
                                                qname = field.qname.copy(name = "order.opened"),
                                                type = "datetime",
                                                isKey = false,
                                                nullable = true,
                                            ),
                                        ),
                            ),
                        ),
                )
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        fun generatedMetadata(
            type: String = "bigint",
            source: String = "bigint_id_v1",
        ): PreviewMetadata {
            val original = defaultMetadata("writer_defaults: { id: { generated: $source } },")
            return original.copy(
                actions =
                    original.actions.mapValues { (_, action) ->
                        action.copy(
                            columns =
                                action.columns.map {
                                    if (it.name ==
                                        "id"
                                    ) {
                                        it.copy(optional = true)
                                    } else {
                                        it
                                    }
                                },
                        )
                    },
                entities =
                    original.entities.map { entity ->
                        entity.copy(
                            attributes =
                                entity.attributes.map {
                                    if (it.qname.name.endsWith(".order_number")) it.copy(type = type) else it
                                },
                        )
                    },
            )
        }

        "generated bigint defaults distinguish repeated wiring and routes and replay the same run" {
            supervisorScope {
                val metadata = generatedMetadata()
                val wiring =
                    writerWiring(listOf("way.release", "way.hold")).let {
                        it.copy(columns = it.columns - "id")
                    }
                val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring, wiring)), metadata)
                f.runner.use { runner ->
                    runner.evaluate()
                    val plan = f.plan!!
                    val ids =
                        plan.actions.map {
                            it.arguments
                                .getValue("id")
                                .jsonPrimitive.long
                        }
                    ids.distinct().size shouldBe 4
                    ids.all { it < 0 } shouldBe true
                    plan.rows.filter { it.entity == "er.entity.order" }.map {
                        it.key!!
                            .getValue("order_number")
                            .jsonPrimitive.long
                    } shouldBe ids
                    ApplyPlanner(
                        Clock.fixed(PREVIEW_NOW.plusSeconds(100), ZoneOffset.UTC),
                    ).preview(runner).actions shouldBe
                        plan.actions
                    runner.evaluate()
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                }
            }
        }

        "generated defaults validate on empty and unselected routes even with an explicit override" {
            for ((type, source) in listOf(
                "text" to "bigint_id_v1",
                "bigint" to "bigint_id_v2",
                "bigint" to "\"bigint_id_v1\"",
            )) {
                supervisorScope {
                    val metadata = generatedMetadata(type, source)
                    val wiring = writerWiring(listOf("way.empty", "way.hold")).copy(whenCondition = "false")
                    val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring)), metadata)
                    f.runner.use { runner ->
                        repeat(2) {
                            shouldThrow<AutomationException> { runner.evaluate() }.code shouldBe
                                ErrorCode.PREVIEW_UNSUPPORTED
                        }
                        f.journals.get() shouldBe 0
                        f.reads.get() shouldBe 5
                    }
                }
            }
        }

        "generated defaults honor explicit values and null never triggers generation" {
            for (value in listOf("9007199254740993", "null")) {
                supervisorScope {
                    val wiring = writerWiring().let { it.copy(columns = it.columns + ("id" to value)) }
                    val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring)), generatedMetadata())
                    f.runner.use { runner ->
                        if (value == "null") {
                            shouldThrow<AutomationException> { runner.evaluate() }.code shouldBe
                                ErrorCode.PREVIEW_UNSUPPORTED
                            f.journals.get() shouldBe 0
                        } else {
                            runner.evaluate()
                            f.plan!!
                                .actions
                                .single()
                                .arguments["id"] shouldBe JsonPrimitive(9007199254740993L)
                            f.journals.get() shouldBe 1
                        }
                        f.reads.get() shouldBe 5
                    }
                }
            }
        }

        "generated collisions with an explicit insert refuse the assembled preview atomically" {
            for (generatedFirst in listOf(true, false)) {
                supervisorScope {
                    val id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
                    val candidate =
                        buildJsonObject {
                            put("order_number", "4712")
                            put("tenant", "a")
                        }
                    val collision = PreviewWriterIds.bigintV1(id, if (generatedFirst) 0 else 1, "hold", "id", candidate)
                    val generated = writerWiring().let { it.copy(columns = it.columns - "id") }
                    val explicit = writerWiring().let { it.copy(columns = it.columns + ("id" to collision.toString())) }
                    val wirings = if (generatedFirst) listOf(generated, explicit) else listOf(explicit, generated)
                    val f = PreviewFixture(this, previewRecipe().withWiring(wirings), generatedMetadata(), runId = id)
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

        "a generated key alone needs no explicit scalar expressions" {
            supervisorScope {
                val original = generatedMetadata()
                val metadata =
                    original.copy(
                        entities =
                            original.entities.map {
                                it.copy(
                                    attributes =
                                        it.attributes.filter { a ->
                                            a.qname.name.endsWith(".order_number")
                                        },
                                )
                            },
                        actions =
                            original.actions.mapValues { (_, action) ->
                                action.copy(
                                    columns = action.columns.filter { it.name == "id" },
                                    dryRun =
                                        LocalizedText(
                                            mapOf(
                                                "en" to "would insert {id}",
                                            ),
                                        ),
                                )
                            },
                    )
                val wiring = writerWiring().copy(columns = emptyMap())
                val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring)), metadata)
                f.runner.use { runner ->
                    runner.evaluate()
                    f.plan!!
                        .actions
                        .single()
                        .arguments
                        .getValue("id")
                        .jsonPrimitive.long
                        .let { it < 0 } shouldBe true
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                }
            }
        }

        "valid generated defaults permit empty routes without allocating an ID" {
            supervisorScope {
                val wiring = writerWiring(listOf("way.empty")).let { it.copy(columns = it.columns - "id") }
                val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring)), generatedMetadata())
                f.runner.use { runner ->
                    runner.evaluate()
                    f.plan!!.actions shouldBe emptyList()
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                }
            }
        }

        "authored defaults survive real metadata loading and supply pinned context and exact scalar values" {
            supervisorScope {
                val metadata =
                    defaultMetadata(
                        """
                        writer_defaults: {
                            total: { expression: ":threshold + 1.5" },
                            label: { expression: "department" },
                            state: { expression: "\"placed\"" },
                            run: { context: run_id },
                            opened: { context: started_at }
                        },
                        """.trimIndent(),
                    )
                val wiring =
                    writerWiring(listOf("way.release", "way.hold")).let {
                        it.copy(columns = it.columns - "total")
                    }
                val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring, wiring)), metadata)
                f.runner.use { runner ->
                    runner.evaluate()
                    val inserted = f.plan!!.rows.filter { it.entity == "er.entity.order" }
                    inserted.size shouldBe 4
                    for (row in inserted) {
                        row.row!!["value"]!!
                            .jsonPrimitive.content
                            .toBigDecimal() shouldBe BigDecimal("6.060")
                        row.row!!["held_for"] shouldBe JsonPrimitive("Sales")
                        row.row!!["state"] shouldBe JsonPrimitive("placed")
                        row.row!!["run"] shouldBe JsonPrimitive(runner.context.id.toString())
                        OffsetDateTime.parse(row.row!!["opened"]!!.jsonPrimitive.content).toInstant() shouldBe
                            runner.context.startedAt
                    }
                    f.plan!!
                        .actions
                        .map { it.arguments["run"] }
                        .distinct() shouldBe
                        listOf(JsonPrimitive(runner.context.id.toString()))
                    f.plan!!.actions.map { it.batchRows } shouldBe inserted.map { listOf(it.seq) }
                    runner.evaluate()
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                }
            }
        }

        val quarter =
            listOf(
                "concat(concat(substring(cast(:as_of as text), 1, 4), '-Q'),",
                "case when substring(cast(:as_of as text), 6, 2) <= '03' then '1'",
                "when substring(cast(:as_of as text), 6, 2) <= '06' then '2'",
                "when substring(cast(:as_of as text), 6, 2) <= '09' then '3' else '4' end)",
            ).joinToString(" ")
        val quarterMetadata by lazy {
            defaultMetadata(
                """
                writer_defaults: {
                    label: { expression: "$quarter" },
                    run: { context: run_id },
                    opened: { context: started_at }
                },
                """.trimIndent(),
            )
        }

        "quarter-label defaults use pinned as-of at UTC quarter boundaries and preserve replay rows" {
            val startedAt = Instant.parse("2031-08-15T12:00:00Z")
            val cases =
                listOf(
                    "2019-02-14T12:00:00Z" to "2019-Q1",
                    "2026-03-31T23:59:59Z" to "2026-Q1",
                    "2026-04-01T00:00:00Z" to "2026-Q2",
                    "2026-06-30T23:59:59Z" to "2026-Q2",
                    "2026-07-01T00:00:00Z" to "2026-Q3",
                    "2026-09-30T23:59:59Z" to "2026-Q3",
                    "2026-10-01T00:00:00Z" to "2026-Q4",
                    "2026-12-31T23:59:59Z" to "2026-Q4",
                    "2027-01-01T00:00:00Z" to "2027-Q1",
                )
            for ((instant, expected) in cases) {
                withClue("as-of $instant, Open $startedAt") {
                    supervisorScope {
                        val f =
                            PreviewFixture(
                                this,
                                previewRecipe().withWiring(listOf(writerWiring())),
                                quarterMetadata,
                                asOf = Instant.parse(instant),
                                startedAt = startedAt,
                            )
                        f.runner.use { runner ->
                            runner.evaluate()
                            val plan = f.plan!!
                            val row = plan.rows.single { it.entity == "er.entity.order" }.row!!
                            row["held_for"] shouldBe JsonPrimitive(expected)
                            row["run"] shouldBe JsonPrimitive(runner.context.id.toString())
                            OffsetDateTime.parse(row["opened"]!!.jsonPrimitive.content).toInstant() shouldBe startedAt
                            plan.actions.single().arguments["label"] shouldBe row["held_for"]
                            val replay =
                                ApplyPlanner(
                                    Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC),
                                ).preview(runner)
                            replay.actions shouldBe plan.actions

                            fun withoutWrittenAt(rows: List<ApplyRow>) =
                                rows.map { it.copy(row = it.row?.let { row -> JsonObject(row - "written_at") }) }
                            withoutWrittenAt(replay.rows) shouldBe withoutWrittenAt(plan.rows)
                            runner.evaluate()
                            f.journals.get() shouldBe 1
                            f.reads.get() shouldBe 5
                            f.legacy.get() shouldBe 0
                        }
                    }
                }
            }
        }

        "empty routes validate quarter-label defaults with the published concat catalog" {
            supervisorScope {
                val recipe = previewRecipe().withWiring(listOf(writerWiring(listOf("way.empty"))))
                val f = PreviewFixture(this, recipe, quarterMetadata)
                f.runner.use { runner ->
                    runner.evaluate()
                    f.plan!!.actions shouldBe emptyList()
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                    f.legacy.get() shouldBe 0
                }
                // The valid case alone cannot detect accidentally skipping validation over zero rows.
                val invalid =
                    defaultMetadata(
                        """writer_defaults: { label: { expression: "concat('Q', 1)" } },""",
                    )
                val refused = PreviewFixture(this, recipe, invalid)
                refused.runner.use { runner ->
                    val error = shouldThrow<AutomationException> { runner.evaluate() }
                    error.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
                    error.message shouldBe "unsupported action expression"
                    refused.journals.get() shouldBe 0
                    refused.reads.get() shouldBe 5
                    refused.legacy.get() shouldBe 0
                }
            }
        }

        "explicit values and explicit null override defaults without evaluating unused results" {
            supervisorScope {
                val metadata =
                    defaultMetadata(
                        """
                        writer_defaults: {
                            total: { expression: "cast(1 / 0 as decimal)" },
                            label: { expression: "\"fallback\"" },
                            run: { context: run_id },
                            opened: { expression: ":as_of" }
                        },
                        """.trimIndent(),
                    )
                val wiring = writerWiring().let { it.copy(columns = it.columns + ("label" to "null")) }
                val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring)), metadata)
                f.runner.use { runner ->
                    runner.evaluate()
                    val row =
                        f.plan!!
                            .rows
                            .single { it.entity == "er.entity.order" }
                            .row!!
                    row["value"]!!.jsonPrimitive.content.toBigDecimal() shouldBe BigDecimal("6.06")
                    row["held_for"] shouldBe JsonNull
                    OffsetDateTime.parse(row["opened"]!!.jsonPrimitive.content).toInstant() shouldBe PREVIEW_NOW
                    f.plan!!
                        .actions
                        .single()
                        .arguments["label"] shouldBe JsonNull
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                }
            }
        }

        "malformed or unknown defaults refuse even on an unselected route with explicit overrides" {
            for (declaration in listOf(
                "writer_defaults: null,",
                "writer_defaults: { label: \"fallback\" },",
                "writer_defaults: { missing: { expression: \"1\" } },",
                "writer_defaults: { id: { expression: \"1\" } },",
                "writer_defaults: { label: { context: clock_now } },",
                "writer_defaults: { label: { expression: \"1\", context: run_id } },",
                "writer_defaults: { label: { unknown: \"1\" } },",
                "writer_defaults: { label: { expression: 1 } },",
                "writer_defaults: { label: { expression: \"\" } },",
                "writer_defaults: { label: { context: run_id }, label: { context: run_id } },",
                "writer_defaults: { total: { expression: \"missing_column\" } },",
                "writer_defaults: { total: { expression: \"\\\"text\\\"\" } },",
                "writer_defaults: { total: { expression: \"now()\" } },",
                "writer_defaults: { total: { expression: \"random()\" } },",
                "writer_defaults: { label: { expression: \":run\" }, run: { context: run_id } },",
                "writer_defaults: { label: { context: started_at } },",
            )) {
                supervisorScope {
                    val metadata = defaultMetadata(declaration)
                    val wiring = writerWiring().copy(whenCondition = "false")
                    val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring)), metadata)
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

        "defaults on non-writer actions cannot be silently ignored" {
            supervisorScope {
                val original = legacyPreviewMetadata(BundleFixture.load())
                val action = original.actions.getValue("hold")
                val metadata =
                    original.copy(
                        actions =
                            original.actions + (
                                "hold" to
                                    action.copy(
                                        properties =
                                            defaultMetadata(
                                                "writer_defaults: {},",
                                            ).actions.getValue("hold").properties,
                                    )
                            ),
                    )
                val f = PreviewFixture(this, metadata = metadata)
                f.runner.use { runner ->
                    shouldThrow<AutomationException> { runner.evaluate() }.code shouldBe
                        ErrorCode.PREVIEW_UNSUPPORTED
                    f.journals.get() shouldBe 0
                }
            }
        }

        "invalid defaults fail atomically and discarded rows do not evaluate division" {
            for ((expression, condition, succeeds) in listOf(
                Triple("null", null, false),
                Triple("cast(1 / 0 as decimal)", null, false),
                Triple("cast(1 / (amount - 4.56) as decimal)", "amount < 4", true),
            )) {
                supervisorScope {
                    val metadata = defaultMetadata("writer_defaults: { total: { expression: \"$expression\" } },")
                    val wiring =
                        writerWiring(listOf("way.release", "way.hold")).let {
                            it.copy(columns = it.columns - "total", whenCondition = condition)
                        }
                    val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring)), metadata)
                    f.runner.use { runner ->
                        if (succeeds) {
                            runner.evaluate()
                            f.plan!!.actions.size shouldBe 1
                            f.journals.get() shouldBe 1
                        } else {
                            repeat(2) {
                                shouldThrow<AutomationException> { runner.evaluate() }.code shouldBe
                                    ErrorCode.PREVIEW_UNSUPPORTED
                            }
                            f.journals.get() shouldBe 0
                            f.legacy.get() shouldBe 0
                        }
                        f.reads.get() shouldBe 5
                    }
                }
            }
        }

        "valid defaults permit empty selections and explicit context overrides" {
            for (route in listOf("way.empty", "way.hold")) {
                supervisorScope {
                    val metadata =
                        defaultMetadata(
                            """
                            writer_defaults: {
                                total: { expression: ":threshold" },
                                run: { context: run_id }
                            },
                            """.trimIndent(),
                        )
                    val wiring =
                        writerWiring(listOf(route)).let {
                            it.copy(columns = (it.columns - "total") + ("run" to "\"explicit-run\""))
                        }
                    val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring)), metadata)
                    f.runner.use { runner ->
                        runner.evaluate()
                        f.plan!!.actions.map { it.arguments["run"] } shouldBe
                            if (route == "way.empty") emptyList() else listOf(JsonPrimitive("explicit-run"))
                        f.journals.get() shouldBe 1
                        f.reads.get() shouldBe 5
                    }
                }
            }
        }

        "default values obey target decimal text and state constraints before completion" {
            for (source in listOf(
                "total: { expression: \"100\" }",
                "label: { expression: \"\\\"😀a\\\"\" }",
                "state: { expression: \"\\\"unknown-state\\\"\" }",
            )) {
                supervisorScope {
                    val original = defaultMetadata("writer_defaults: { $source },")
                    val metadata =
                        original.copy(
                            entities =
                                original.entities.map { target ->
                                    target.copy(
                                        attributes =
                                            target.attributes.map {
                                                when {
                                                    it.qname.name.endsWith(
                                                        ".value",
                                                    ) -> it.copy(length = 4, precision = 2)
                                                    it.qname.name.endsWith(".held_for") -> it.copy(length = 1)
                                                    else -> it
                                                }
                                            },
                                    )
                                },
                        )
                    val wiring =
                        writerWiring().let {
                            if (source.startsWith("total:")) it.copy(columns = it.columns - "total") else it
                        }
                    val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring)), metadata)
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

        "unsupported effects and invalid conditions fail once before any decision or preview journal" {
            for (problem in listOf("writer", "condition", "key-lookup", "expression", "text", "types", "syntax")) {
                supervisorScope {
                    val original = legacyPreviewMetadata(BundleFixture.load())
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
                                    recipe.recipe.wiring.map {
                                        it.copy(
                                            whenCondition = "amount > 0 and unknown_column < 9",
                                        )
                                    },
                                )
                        "types" ->
                            recipe =
                                recipe.withWiring(
                                    recipe.recipe.wiring.map { it.copy(whenCondition = "amount = \"4.56\"") },
                                )
                        "syntax" ->
                            recipe =
                                recipe.withWiring(
                                    recipe.recipe.wiring.map { it.copy(whenCondition = "amount > 0; select 1") },
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

        "compound preview predicates preserve repeated wiring and share one atomic completion" {
            supervisorScope {
                val wiring =
                    ManifestWiring(
                        listOf("way.hold"),
                        "release",
                        mapOf("order" to "order_number"),
                        "amount >= :threshold and (department > \"AR\" or department is null)",
                    )
                val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring, wiring)))
                f.runner.use { runner ->
                    runner.evaluate()
                    f.plan!!.actions.map { it.action } shouldBe listOf("release", "release")
                    f.plan!!.actions.map { it.batchRows.size } shouldBe listOf(1, 1)
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                }
            }
        }

        "native writer inserts use compiler arguments and declared composite keys in route order" {
            supervisorScope {
                val wiring = writerWiring(listOf("way.release", "way.hold"))
                val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring)), writerMetadata())
                f.runner.use { runner ->
                    runner.evaluate()
                    val plan = f.plan!!
                    val inserted = plan.rows.filter { it.entity == "er.entity.order" }
                    inserted.map { it.op } shouldBe listOf("insert", "insert")
                    inserted.map { it.key } shouldBe
                        listOf(
                            buildJsonObject {
                                put("order_number", "4711")
                                put("tenant", "a")
                            },
                            buildJsonObject {
                                put("order_number", "4712")
                                put("tenant", "a")
                            },
                        )
                    inserted.map {
                        it.row!!
                            .getValue("value")
                            .jsonPrimitive.content
                            .toBigDecimal()
                    } shouldBe
                        listOf(BigDecimal("2.73"), BigDecimal("6.06"))
                    inserted.all { !it.row!!.containsKey("held_for") } shouldBe true
                    plan.actions.map { it.batchRows } shouldBe inserted.map { listOf(it.seq) }
                    plan.actions.map { it.would } shouldBe
                        listOf("would insert order 4711 for a", "would insert order 4712 for a")
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                }
            }
        }

        "native writer preserves explicit null and repeated wiring without inventing defaults" {
            supervisorScope {
                val wiring = writerWiring().let { it.copy(columns = it.columns + ("label" to "null")) }
                val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring, wiring)), writerMetadata())
                f.runner.use { runner ->
                    runner.evaluate()
                    val inserted = f.plan!!.rows.filter { it.entity == "er.entity.order" }
                    inserted.size shouldBe 2
                    inserted.forEach { it.row!!["held_for"] shouldBe JsonNull }
                    inserted.map { it.key }.distinct().size shouldBe 1
                    f.plan!!.actions.map { it.batchRows } shouldBe inserted.map { listOf(it.seq) }
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                }
            }
        }

        "native writer target types validate even on empty or unselected routes" {
            for (route in listOf("way.hold", "way.empty")) {
                supervisorScope {
                    val wiring = writerWiring(listOf(route), total = "\"secret amount\"").copy(whenCondition = "false")
                    val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring)), writerMetadata())
                    f.runner.use { runner ->
                        repeat(2) {
                            shouldThrow<AutomationException> { runner.evaluate() }.message shouldBe
                                "unsupported action expression"
                        }
                        f.journals.get() shouldBe 0
                        f.reads.get() shouldBe 5
                    }
                }
            }
        }

        "native writer refuses approximate exact-number assignments before any journal" {
            for ((type, column) in listOf("int" to "total", "decimal" to "total", "decimal" to "id")) {
                supervisorScope {
                    val wiring = writerWiring(total = "1").let { it.copy(columns = it.columns + (column to "3 / 2")) }
                    val recipe = previewRecipe().withWiring(listOf(wiring))
                    val f = PreviewFixture(this, recipe, writerMetadata(type))
                    f.runner.use { runner ->
                        repeat(2) {
                            shouldThrow<AutomationException> { runner.evaluate() }.message shouldBe
                                "unsupported action expression"
                        }
                        f.journals.get() shouldBe 0
                        f.legacy.get() shouldBe 0
                        f.reads.get() shouldBe 5
                    }
                }
            }
        }

        "native writer applies temporal widening to inserted values and evidence" {
            supervisorScope {
                val wiring = writerWiring(total = "cast(:as_of as date)")
                val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring)), writerMetadata("datetime"))
                f.runner.use { runner ->
                    runner.evaluate()
                    val expected =
                        JsonPrimitive(
                            PREVIEW_NOW.atOffset(ZoneOffset.UTC).toLocalDate().toString() + "T00:00Z",
                        )
                    val inserted = f.plan!!.rows.single { it.entity == "er.entity.order" }
                    inserted.row!!["value"] shouldBe expected
                    f.plan!!
                        .actions
                        .single()
                        .arguments["total"] shouldBe expected
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                }
            }
        }

        "native writer value refusal after earlier effects leaves no partial journal" {
            supervisorScope {
                val recipe =
                    previewRecipe().withWiring(
                        listOf(
                            ManifestWiring(
                                listOf("way.release"),
                                "release",
                                mapOf(
                                    "order" to "order_number",
                                    "scope" to "tenant",
                                ),
                            ),
                            writerWiring(total = "amount + 100"),
                        ),
                    )
                val unbounded = writerMetadata()
                val original =
                    unbounded.copy(
                        entities =
                            unbounded.entities.map { entity ->
                                entity.copy(
                                    attributes =
                                        entity.attributes.map {
                                            if (it.qname.name.endsWith(".value")) {
                                                it.copy(length = 4, precision = 2)
                                            } else {
                                                it
                                            }
                                        },
                                )
                            },
                    )
                val release = original.actions.getValue("release")
                val metadata =
                    original.copy(
                        actions =
                            original.actions + (
                                "release" to
                                    release.copy(
                                        columns = release.columns + AutomationColumn("scope", "order.tenant"),
                                    )
                            ),
                    )
                val f = PreviewFixture(this, recipe, metadata)
                f.runner.use { runner ->
                    repeat(2) {
                        shouldThrow<AutomationException> { runner.evaluate() }.message shouldBe
                            "unsupported native writer mapping or value"
                    }
                    f.journals.get() shouldBe 0
                    f.legacy.get() shouldBe 0
                    f.reads.get() shouldBe 5
                }
            }
        }

        "native writer enforces declared text bounds before completing a batch" {
            for (label in listOf("😀", "😀a")) {
                supervisorScope {
                    val original = writerMetadata()
                    val metadata =
                        original.copy(
                            entities =
                                original.entities.map { entity ->
                                    entity.copy(
                                        attributes =
                                            entity.attributes.map {
                                                if (it.qname.name.endsWith(".held_for")) it.copy(length = 1) else it
                                            },
                                    )
                                },
                        )
                    val wiring = writerWiring().let { it.copy(columns = it.columns + ("label" to "\"$label\"")) }
                    val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring)), metadata)
                    f.runner.use { runner ->
                        if (label == "😀") {
                            runner.evaluate()
                            f.plan!!
                                .rows
                                .single { it.entity == "er.entity.order" }
                                .row!!["held_for"] shouldBe
                                JsonPrimitive(label)
                            f.journals.get() shouldBe 1
                        } else {
                            repeat(2) {
                                shouldThrow<AutomationException> { runner.evaluate() }.code shouldBe
                                    ErrorCode.PREVIEW_UNSUPPORTED
                            }
                            f.journals.get() shouldBe 0
                        }
                        f.legacy.get() shouldBe 0
                        f.reads.get() shouldBe 5
                    }
                }
            }
        }

        "guarded division feeds predicates native effects and would-text without new Query reads" {
            supervisorScope {
                val wiring =
                    ManifestWiring(
                        listOf("way.hold"),
                        "hold",
                        mapOf("order" to "order_number", "department" to "cast(6 / 2 as text)"),
                        "amount / 2 > 0",
                    )
                val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring)))
                f.runner.use { runner ->
                    runner.evaluate()
                    val action = f.plan!!.actions.single()
                    action.arguments["department"] shouldBe JsonPrimitive("3.0")
                    action.would shouldBe "would hold order 4712 for 3.0"
                    f.plan!!
                        .rows
                        .single { it.op == "update" }
                        .set shouldBe
                        buildJsonObject { put("held_for", "3.0") }
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                }
            }
        }

        "zero divisions after earlier wiring refuse atomically in predicates and arguments" {
            for (predicate in listOf(false, true)) {
                supervisorScope {
                    val bad =
                        ManifestWiring(
                            listOf("way.hold"),
                            "hold",
                            mapOf(
                                "order" to "order_number",
                                "department" to if (predicate) "department" else "cast(amount / 0 as text)",
                            ),
                            if (predicate) "amount / 0 > 1" else null,
                        )
                    val recipe =
                        previewRecipe().withWiring(
                            listOf(
                                ManifestWiring(listOf("way.release"), "release", mapOf("order" to "order_number")),
                                bad,
                            ),
                        )
                    val f = PreviewFixture(this, recipe)
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

        "discarded rows do not execute division arguments" {
            supervisorScope {
                val wiring =
                    ManifestWiring(
                        listOf("way.release", "way.hold"),
                        "hold",
                        mapOf("order" to "order_number", "department" to "cast(amount / 0 as text)"),
                        "false",
                    )
                val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring)))
                f.runner.use { runner ->
                    runner.evaluate()
                    f.plan!!.actions shouldBe emptyList()
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                }
            }
        }

        "compiler scalar arguments feed native effects and authored text with one completion" {
            supervisorScope {
                val original = previewRecipe()
                val recipe =
                    original.withWiring(
                        listOf(
                            ManifestWiring(
                                listOf("way.hold"),
                                "hold",
                                mapOf(
                                    "order" to "cast(cast(order_number as int) + 1 as text)",
                                    "department" to "case when amount >= :threshold then \"AR\" else department end",
                                ),
                            ),
                        ),
                    )
                val f = PreviewFixture(this, recipe)
                f.runner.use { runner ->
                    runner.evaluate()
                    val action = f.plan!!.actions.single()
                    action.arguments shouldBe
                        buildJsonObject {
                            put("order", "4713")
                            put("department", "AR")
                        }
                    action.would shouldBe "would hold order 4713 for AR"
                    f.plan!!.rows.filter { it.entity == "er.entity.order" }.forEach {
                        it.key shouldBe buildJsonObject { put("order_number", "4713") }
                    }
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                }
            }
        }

        "action arguments evaluate only condition-selected rows" {
            supervisorScope {
                val recipe =
                    previewRecipe().withWiring(
                        listOf(
                            ManifestWiring(
                                listOf("way.release", "way.hold"),
                                "release",
                                mapOf(
                                    "order" to "cast(cast(department as int) as text)",
                                ),
                                "false",
                            ),
                        ),
                    )
                val f = PreviewFixture(this, recipe)
                f.runner.use { runner ->
                    runner.evaluate()
                    f.plan!!.actions shouldBe emptyList()
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                }
            }
        }

        "invalid scalar expressions refuse before journals even on empty or unselected routes" {
            for (route in listOf("way.hold", "way.empty")) {
                supervisorScope {
                    val recipe =
                        previewRecipe().withWiring(
                            listOf(
                                ManifestWiring(
                                    listOf(route),
                                    "hold",
                                    mapOf(
                                        "order" to "order_number",
                                        "department" to "department +",
                                    ),
                                    "false",
                                ),
                            ),
                        )
                    val f = PreviewFixture(this, recipe)
                    f.runner.use { runner ->
                        repeat(2) {
                            shouldThrow<AutomationException> { runner.evaluate() }.code shouldBe
                                ErrorCode.PREVIEW_UNSUPPORTED
                        }
                        f.journals.get() shouldBe 0
                        f.reads.get() shouldBe 5
                    }
                }
            }
        }

        "scalar argument failures after earlier wiring leave no partial decision or preview" {
            supervisorScope {
                val recipe =
                    previewRecipe().withWiring(
                        listOf(
                            ManifestWiring(listOf("way.release"), "release", mapOf("order" to "order_number")),
                            ManifestWiring(
                                listOf("way.hold"),
                                "hold",
                                mapOf(
                                    "order" to "order_number",
                                    "department" to "cast(cast(department as int) as text)",
                                ),
                            ),
                        ),
                    )
                val f = PreviewFixture(this, recipe)
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

        "nested action and working-day duration preview preserve partial intent" {
            supervisorScope {
                val original = legacyPreviewMetadata(BundleFixture.load())
                val ask =
                    original.actions.getValue("ask").copy(
                        dryRun =
                            LocalizedText(
                                mapOf(
                                    "en" to "would ask {who} to approve {action} for {about}, due {due}",
                                ),
                            ),
                    )
                val wiring =
                    ManifestWiring(
                        listOf("way.hold"),
                        "ask",
                        mapOf(
                            "who" to "role.buyer",
                            "about" to "department",
                            "action" to "start { process: concat('purchase-', tenant) }",
                            "group_by" to "tenant",
                            "due" to "2 working_days",
                        ),
                    )
                val f =
                    PreviewFixture(
                        this,
                        previewRecipe().withWiring(listOf(wiring)),
                        original.copy(
                            actions =
                                original.actions + ("ask" to ask),
                        ),
                    )
                f.runner.use { runner ->
                    runner.evaluate()
                    val action = f.plan!!.actions.single()
                    action.arguments["action"] shouldBe
                        buildJsonObject {
                            put("action", "start")
                            put("arguments", buildJsonObject { put("process", "purchase-a") })
                            put("deferred", true)
                        }
                    action.arguments["due"] shouldBe
                        buildJsonObject {
                            put("amount", 2)
                            put("unit", "working_days")
                        }
                    action.arguments["group_by"] shouldBe JsonPrimitive("a")
                    action.batchRows shouldBe emptyList()
                    action.would shouldBe
                        "would ask role.buyer to approve start { process: \"purchase-a\" } for Sales, due 2 working_days"
                    runner.evaluate()
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                    f.legacy.get() shouldBe 0
                }
            }
        }

        "empty routes accept nested action intents without creating tasks or deadlines" {
            supervisorScope {
                val wiring =
                    ManifestWiring(
                        listOf("way.empty"),
                        "ask",
                        mapOf(
                            "who" to "role.buyer",
                            "about" to "department",
                            "action" to "start { process: \"purchasing\" }",
                            "due" to "2 working_days",
                        ),
                    )
                val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring)))
                f.runner.use { runner ->
                    runner.evaluate()
                    f.plan!!.actions shouldBe emptyList()
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                }
            }
        }

        "invalid deferred intent after earlier writer work refuses atomically on nonempty and empty routes" {
            for (route in listOf("way.hold", "way.empty")) {
                supervisorScope {
                    val ask =
                        ManifestWiring(
                            listOf(route),
                            "ask",
                            mapOf(
                                "who" to "role.buyer",
                                "about" to "department",
                                "action" to "start { process: \"private\", process: \"duplicate\" }",
                                "due" to "2 working_days",
                            ),
                        )
                    val recipe = previewRecipe().withWiring(listOf(writerWiring(listOf("way.release")), ask))
                    val f = PreviewFixture(this, recipe, writerMetadata())
                    f.runner.use { runner ->
                        repeat(2) {
                            shouldThrow<AutomationException> { runner.evaluate() }.code shouldBe
                                ErrorCode.PREVIEW_UNSUPPORTED
                        }
                        f.plan shouldBe null
                        f.journals.get() shouldBe 0
                        f.reads.get() shouldBe 5
                        f.legacy.get() shouldBe 0
                    }
                }
            }
        }

        "unselected deferred scalar arguments validate without executing their casts" {
            supervisorScope {
                val wiring =
                    ManifestWiring(
                        listOf("way.hold"),
                        "ask",
                        mapOf(
                            "who" to "role.buyer",
                            "about" to "department",
                            "action" to "start { process: cast('private' as int) }",
                            "due" to "2 working_days",
                        ),
                        "false",
                    )
                val f = PreviewFixture(this, previewRecipe().withWiring(listOf(wiring)))
                f.runner.use { runner ->
                    runner.evaluate()
                    f.plan!!.actions shouldBe emptyList()
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                }
            }
        }

        "bound-date conditions and arguments share the pinned run time and one atomic completion" {
            supervisorScope {
                val recipe =
                    previewRecipe().withWiring(
                        listOf(
                            ManifestWiring(
                                listOf("way.hold"),
                                "hold",
                                mapOf(
                                    "order" to "order_number",
                                    "department" to "cast(cast(:as_of - days(30) as date) as text)",
                                ),
                                "cast(:as_of - days(30) as date) < cast(:as_of as date)",
                            ),
                        ),
                    )
                val f = PreviewFixture(this, recipe)
                f.runner.use { runner ->
                    runner.evaluate()
                    val expected =
                        PREVIEW_NOW
                            .atOffset(ZoneOffset.UTC)
                            .minusDays(30)
                            .toLocalDate()
                            .toString()
                    f.plan!!
                        .actions
                        .single()
                        .arguments["department"] shouldBe JsonPrimitive(expected)
                    f.plan!!
                        .actions
                        .single()
                        .would shouldBe "would hold order 4712 for $expected"
                    f.plan!!
                        .rows
                        .single { it.op == "update" }
                        .set shouldBe
                        buildJsonObject { put("held_for", expected) }
                    f.journals.get() shouldBe 1
                    f.reads.get() shouldBe 5
                }
            }
        }

        "unsupported calendar durations fail once before any preview completion" {
            supervisorScope {
                val recipe =
                    previewRecipe().withWiring(
                        listOf(
                            ManifestWiring(
                                listOf("way.hold"),
                                "hold",
                                mapOf(
                                    "order" to "order_number",
                                    "department" to "cast(:as_of + working_days(2) as text)",
                                ),
                            ),
                        ),
                    )
                val f = PreviewFixture(this, recipe)
                f.runner.use { runner ->
                    repeat(2) {
                        shouldThrow<AutomationException> { runner.evaluate() }.code shouldBe
                            ErrorCode.PREVIEW_UNSUPPORTED
                    }
                    f.journals.get() shouldBe 0
                    f.reads.get() shouldBe 5
                }
            }
        }

        "each action describes only its own detail limit while the batch keeps the shared snapshot" {
            supervisorScope {
                val original = legacyPreviewMetadata(BundleFixture.load())
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
                val original = legacyPreviewMetadata(BundleFixture.load())
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
            val actions = legacyPreviewMetadata(BundleFixture.load()).actions
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
    metadata: PreviewMetadata = legacyPreviewMetadata(BundleFixture.load()),
    refuseJournal: Boolean = false,
    runId: UUID = UUID.randomUUID(),
    asOf: Instant = PREVIEW_NOW,
    startedAt: Instant = PREVIEW_NOW,
    store: StagingStore = LocalStagingStore(Files.createTempDirectory("au-preview")),
) {
    val reads = AtomicInteger()
    val journals = AtomicInteger()
    val legacy = AtomicInteger()
    var plan: PreviewPlan? = null
    val runner =
        PartRunner(
            runnerContext(recipe).copy(
                id = runId,
                startedAt = startedAt,
                keys = null,
                detailLimit = 2,
                previewMetadata = metadata,
                bindings =
                    mapOf(
                        "as_of" to BoundValue("datetime", asOf),
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
