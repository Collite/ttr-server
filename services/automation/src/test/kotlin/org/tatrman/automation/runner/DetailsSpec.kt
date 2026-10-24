// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.*
import org.tatrman.automation.staging.*
import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttr.parser.model.SourceLocation
import java.math.BigDecimal
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class DetailsSpec :
    StringSpec({
        "authored action limits resolve per route and shared snapshots satisfy every wired action" {
            val action =
                BundleFixture
                    .load()
                    .actions.values
                    .first()

            fun limit(n: Int) =
                action.copy(
                    properties =
                        listOf(
                            "detail_limit" to
                                AutoValue.Num(n.toDouble(), SourceLocation.UNKNOWN, n.toString()),
                        ),
                )
            val recipe =
                detailRecipe().recipe.copy(
                    wiring =
                        listOf(
                            ManifestWiring(listOf("yes"), "small"),
                            ManifestWiring(listOf("yes"), "large"),
                        ),
                )
            detailLimits(recipe, mapOf("small" to limit(2), "large" to limit(5)), 200) shouldBe mapOf("yes" to 5)
            detailLimits(recipe.copy(wiring = recipe.wiring.take(1)), mapOf("small" to limit(2)), 200) shouldBe
                mapOf("yes" to 2)
            detailLimits(
                recipe,
                mapOf("small" to limit(2), "large" to action.copy(properties = emptyList())),
                7,
            ) shouldBe
                mapOf("yes" to 7)
            detailLimits(recipe.copy(wiring = emptyList()), emptyMap(), 7) shouldBe mapOf("yes" to 7)
        }

        "invalid authored action limits cannot silently fall back to a default" {
            val action =
                BundleFixture
                    .load()
                    .actions.values
                    .first()
            val recipe = detailRecipe().recipe.copy(wiring = listOf(ManifestWiring(listOf("yes"), "a")))
            for (text in listOf("0", "-1", "1.5", "2147483648")) {
                shouldThrow<IllegalArgumentException> {
                    detailLimits(
                        recipe,
                        mapOf(
                            "a" to
                                action.copy(
                                    properties =
                                        listOf(
                                            "detail_limit" to
                                                AutoValue.Num(text.toDouble(), SourceLocation.UNKNOWN, text),
                                        ),
                                ),
                        ),
                        200,
                    )
                }
            }
            shouldThrow<IllegalArgumentException> {
                detailLimits(
                    recipe,
                    mapOf(
                        "a" to
                            action.copy(
                                properties =
                                    listOf(
                                        "detail_limit" to
                                            AutoValue.Str("2", false, SourceLocation.UNKNOWN),
                                    ),
                            ),
                    ),
                    200,
                )
            }
            shouldThrow<NoSuchElementException> { detailLimits(recipe, emptyMap(), 200) }
        }

        "composite numeric keys get independent bounds with one marker only beyond the exact limit" {
            val table =
                Table(
                    DETAIL_COLUMNS,
                    listOf(
                        listOf(BigDecimal("1.00"), "a", "first", "yes", "context"),
                        listOf(BigDecimal("2"), "a", "exact 1", "yes", "context"),
                        listOf(BigDecimal("1"), "a", "second", "yes", "context"),
                        listOf(BigDecimal("2.0"), "a", "exact 2", "yes", "context"),
                        listOf(BigDecimal("1.0"), "a", "extra", "yes", "context"),
                        listOf(BigDecimal("1"), "a", "must disappear", "yes", "context"),
                        listOf(BigDecimal("1"), "b", "different tenant", "yes", "context"),
                    ),
                )
            val snapshot = boundedDetails(table, listOf("id", "tenant"), 2)
            snapshot.columns shouldBe DETAIL_COLUMNS + ManifestColumn("truncated", "bool")
            snapshot.rows.map { it[2] } shouldBe
                listOf("first", "exact 1", "second", "exact 2", null, "different tenant")
            snapshot.rows.count { it.last() == true } shouldBe 1
            snapshot.rows[4] shouldBe listOf(BigDecimal("1.0"), "a", null, null, null, true)
            boundedDetails(table.copy(rows = emptyList()), listOf("id", "tenant"), 2).rows shouldBe emptyList()
            boundedDetails(table, listOf("id", "tenant"), Int.MAX_VALUE).rows.size shouldBe 7
        }

        "invalid detail schemas and null keys refuse rather than losing groups" {
            val table = Table(DETAIL_COLUMNS, listOf(listOf(null, "a", "note", "yes", "context")))
            shouldThrow<IllegalArgumentException> { boundedDetails(table, listOf("id", "tenant"), 2) }
            shouldThrow<IllegalArgumentException> {
                boundedDetails(
                    table.copy(rows = emptyList()),
                    listOf("missing"),
                    2,
                )
            }
            shouldThrow<IllegalArgumentException> { boundedDetails(table.copy(rows = emptyList()), emptyList(), 2) }
            shouldThrow<IllegalArgumentException> { boundedDetails(table, listOf("id"), 0) }
            shouldThrow<IllegalArgumentException> {
                boundedDetails(
                    Table(DETAIL_COLUMNS + ManifestColumn("truncated", "bool"), emptyList()),
                    listOf("id"),
                    2,
                )
            }
        }

        "detail snapshots are bounded before immutable Arrow publication and shared with evaluation" {
            coroutineScope {
                val root = Files.createTempDirectory("au-details")
                val store = LocalStagingStore(root)
                val writes = AtomicInteger()
                val reads = AtomicInteger()
                PartRunner(
                    runnerContext(detailRecipe()).copy(detailLimit = 1, detailLimits = mapOf("yes" to 2)),
                    Door {
                        reads.incrementAndGet()
                        ReadResult(Table(it.columns, if (it.sql == "detail") detailRows() else evaluationRows(it.sql)))
                    },
                    Staging(store) { writes.incrementAndGet() },
                    this,
                    { _, _ -> },
                ).use { runner ->
                    val results =
                        listOf(
                            async { runner.evaluate() },
                            async {
                                runner.island("way")
                                runner.evaluate()
                            },
                        ).awaitAll()
                    results.distinct().size shouldBe 1
                    val snapshot = runner.island("way").single { it.output.port == "yes.context" }
                    snapshot.table.rows.size shouldBe 4 // key 1: two rows + marker; key 2: one row.
                    snapshot.output.rows shouldBe 4
                    ArrowTables.decode(listOf(store.get(snapshot.output.path!!)), snapshot.table.columns) shouldBe
                        snapshot.table
                    results
                        .first()
                        .verdict.routes
                        .getValue("yes")
                        .detail shouldBe
                        mapOf(
                            "context" to "/automation/runs/${runner.context.id}/routes/yes/details/context",
                        )
                    reads.get() shouldBe 6
                    writes.get() shouldBe 6
                    results
                        .first()
                        .verdict.routes
                        .getValue("yes")
                        .rowCount shouldBe 2
                }
            }
        }

        "a source reaching the read ceiling still refuses before detail bounding can hide it" {
            supervisorScope {
                val writes = AtomicInteger()
                PartRunner(
                    runnerContext(detailRecipe()).copy(detailLimit = 1, readCeiling = 4),
                    Door {
                        ReadResult(Table(it.columns, if (it.sql == "detail") detailRows() else evaluationRows(it.sql)))
                    },
                    Staging(LocalStagingStore(Files.createTempDirectory("au-details-cap"))) {
                        if (it.port == "yes.context") writes.incrementAndGet()
                    },
                    this,
                    { _, _ -> error("must not journal") },
                ).use { runner ->
                    shouldThrow<AutomationException> { runner.evaluate() }.code shouldBe ErrorCode.CANDIDATES_TRUNCATED
                    writes.get() shouldBe 0
                }
            }
        }

        for (problem in listOf("foreign-key", "wrong-route", "wrong-context", "unkeyed")) {
            "malformed details fail before recording the evaluation: $problem" {
                supervisorScope {
                    val writes = AtomicInteger()
                    PartRunner(
                        runnerContext(detailRecipe(problem != "unkeyed")),
                        Door { read ->
                            val rows =
                                if (read.sql == "detail") {
                                    detailRows().map { row ->
                                        row.mapIndexed { index, value ->
                                            when {
                                                problem == "foreign-key" && index == 0 -> BigDecimal("99")
                                                problem == "wrong-route" && index == 3 -> "also"
                                                problem == "wrong-context" && index == 4 -> "other"
                                                else -> value
                                            }
                                        }
                                    }
                                } else {
                                    evaluationRows(read.sql)
                                }
                            ReadResult(Table(read.columns, rows))
                        },
                        Staging(LocalStagingStore(Files.createTempDirectory("au-details-bad"))) {},
                        this,
                        { _, _ -> writes.incrementAndGet() },
                    ).use { runner ->
                        shouldThrow<IllegalArgumentException> { runner.evaluate() }
                        writes.get() shouldBe 0
                    }
                }
            }
        }
    })

internal val DETAIL_COLUMNS =
    listOf(
        ManifestColumn("id", "decimal"),
        ManifestColumn("tenant", "text"),
        ManifestColumn("note", "text"),
        ManifestColumn("route", "text"),
        ManifestColumn("context", "text"),
    )

internal fun detailRows(): List<List<Any?>> =
    listOf(
        listOf(BigDecimal("1"), "a", "one", "yes", "context"),
        listOf(BigDecimal("2"), "a", "two", "yes", "context"),
        listOf(BigDecimal("1"), "a", "three", "yes", "context"),
        listOf(BigDecimal("1"), "a", "four", "yes", "context"),
    )

internal fun detailRecipe(keyed: Boolean = true): LoadedRecipe {
    val original = evaluationRecipe()
    val recipe =
        original.recipe.copy(
            roles =
                original.recipe.roles!!.let { roles ->
                    roles.copy(
                        routes =
                            roles.routes.map {
                                if (it.name ==
                                    "yes"
                                ) {
                                    it.copy(detail = listOf("context"))
                                } else {
                                    it
                                }
                            },
                    )
                },
        )
    return original.copy(
        recipe = recipe,
        manifest =
            original.manifest.copy(
                recipe = recipe,
                islands =
                    original.manifest.islands.map { island ->
                        if (island.name != "way") {
                            island
                        } else {
                            island.copy(
                                outputs =
                                    island.outputs +
                                        ManifestOutput(
                                            "yes.context",
                                            "detail",
                                            columns = DETAIL_COLUMNS,
                                            keyed = keyed,
                                            storage = "erp_db",
                                        ),
                            )
                        }
                    },
            ),
        statements = original.statements + ("detail" to "detail"),
    )
}
