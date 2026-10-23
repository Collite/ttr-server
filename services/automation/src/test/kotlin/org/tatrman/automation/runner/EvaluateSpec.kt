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
import java.math.BigDecimal
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class EvaluateSpec :
    StringSpec({
        "overlapping routes count unmatched composite keys by union, sharing scheduled reads and the journal" {
            coroutineScope {
                val reads = ConcurrentHashMap<String, AtomicInteger>()
                val order = java.util.Collections.synchronizedList(mutableListOf<String>())
                val recorded = java.util.Collections.synchronizedList(mutableListOf<EvaluationResult>())
                PartRunner(
                    runnerContext(evaluationRecipe()),
                    Door { read ->
                        order += read.sql
                        reads.computeIfAbsent(read.sql) { AtomicInteger() }.incrementAndGet()
                        delay(10)
                        ReadResult(Table(read.columns, evaluationRows(read.sql)))
                    },
                    Staging(LocalStagingStore(Files.createTempDirectory("au-evaluate"))) {},
                    this,
                    { _, result -> recorded += result },
                ).use { runner ->
                    val replies =
                        listOf(
                            async { runner.evaluate() },
                            async { runner.evaluate() },
                            async {
                                runner.island("way")
                                runner.evaluate()
                            },
                        ).awaitAll()
                    replies.distinct().size shouldBe 1
                    val result = replies.first()
                    result.verdict.routes.mapValues { it.value.rowCount } shouldBe
                        mapOf("yes" to 2L, "also" to 1L, "empty" to 0L)
                    result.verdict.none shouldBe 1L // key 1 is on two routes, key 3 on none.
                    result.mode shouldBe "overlapping"
                    result.verdict.routes
                        .getValue("yes")
                        .ref shouldBe
                        "/automation/runs/${runner.context.id}/outputs/way.yes"
                    recorded shouldBe listOf(result)
                    order.first() shouldBe "candidates"
                    order[1] shouldBe "context"
                    reads.values.all { it.get() == 1 } shouldBe true
                    reads.size shouldBe 5
                    result.guards.map { it.code }.toSet() shouldBe
                        setOf(
                            "restricted-view",
                            "candidates-truncated",
                            "duplicate-key",
                            "out-of-scope",
                            "out-of-scope-read",
                        )
                    result.guards.all { it.status == "passed" } shouldBe true
                    runner.evaluate() shouldBe result
                }
            }
        }

        "reads-only evaluation executes all waves and records an empty verdict" {
            coroutineScope {
                val recipe =
                    evaluationRecipe().let { r ->
                        val metadata = r.recipe.copy(roles = ManifestRoles(candidates = "candidates"))
                        r.copy(recipe = metadata, manifest = r.manifest.copy(recipe = metadata))
                    }
                val calls = AtomicInteger()
                PartRunner(
                    runnerContext(recipe),
                    Door { ReadResult(Table(it.columns, evaluationRows(it.sql))) },
                    Staging(LocalStagingStore(Files.createTempDirectory("au-eval-read"))) {},
                    this,
                    { _, _ -> calls.incrementAndGet() },
                ).use { runner ->
                    runner.evaluate().verdict shouldBe VerdictSummary(emptyMap(), 0)
                    calls.get() shouldBe 1
                    runner.guards.any { it.code == "restricted-view" } shouldBe false
                }
            }
        }

        "guard refusals never publish a decision and remain sticky on repeated evaluate" {
            supervisorScope {
                val writes = AtomicInteger()
                PartRunner(
                    runnerContext(evaluationRecipe()),
                    Door { ReadResult(Table(it.columns, evaluationRows(it.sql)), setOf("security_predicate_applied")) },
                    Staging(LocalStagingStore(Files.createTempDirectory("au-eval-refuse"))) {},
                    this,
                    { _, _ -> writes.incrementAndGet() },
                ).use { runner ->
                    repeat(2) {
                        shouldThrow<AutomationException> { runner.evaluate() }.code shouldBe
                            ErrorCode.RESTRICTED_VIEW
                    }
                    writes.get() shouldBe 0
                }
            }
        }

        "a journal failure returns no success and is shared without rerunning inputs" {
            supervisorScope {
                val reads = AtomicInteger()
                val writes = AtomicInteger()
                PartRunner(
                    runnerContext(evaluationRecipe()),
                    Door {
                        reads.incrementAndGet()
                        ReadResult(Table(it.columns, evaluationRows(it.sql)))
                    },
                    Staging(LocalStagingStore(Files.createTempDirectory("au-eval-journal"))) {},
                    this,
                    { _, _ ->
                        writes.incrementAndGet()
                        error("journal unavailable")
                    },
                ).use { runner ->
                    repeat(2) {
                        shouldThrow<IllegalStateException> { runner.evaluate() }.message shouldBe
                            "journal unavailable"
                    }
                    reads.get() shouldBe 5
                    writes.get() shouldBe 1
                }
            }
        }

        "evaluation fails closed when no durable journal has been configured" {
            supervisorScope {
                PartRunner(
                    runnerContext(evaluationRecipe()),
                    Door { ReadResult(Table(it.columns, evaluationRows(it.sql))) },
                    Staging(LocalStagingStore(Files.createTempDirectory("au-eval-unwired"))) {},
                    this,
                ).use { runner ->
                    shouldThrow<IllegalStateException> { runner.evaluate() }.message shouldBe
                        "evaluation journal unavailable"
                }
            }
        }

        for (problem in listOf(
            "foreign",
            "duplicate",
            "exclusive",
            "wrong-route",
            "missing-reason",
            "missing-port",
            "null-key",
        )) {
            "malformed decision refuses before journalling: $problem" {
                supervisorScope {
                    var recipe = evaluationRecipe(if (problem == "exclusive") "exclusive" else "overlapping")
                    if (problem in setOf("missing-reason", "missing-port")) {
                        val islands =
                            recipe.manifest.islands.map { island ->
                                if (island.name != "way") {
                                    island
                                } else {
                                    island.copy(
                                        outputs =
                                            if (problem == "missing-port") {
                                                island.outputs.dropLast(1)
                                            } else {
                                                island.outputs.map { it.copy(columns = ROUTE_COLUMNS.take(2)) }
                                            },
                                    )
                                }
                            }
                        recipe = recipe.copy(manifest = recipe.manifest.copy(islands = islands))
                    }
                    val writes = AtomicInteger()
                    PartRunner(
                        runnerContext(recipe),
                        Door { read ->
                            var rows = evaluationRows(read.sql)
                            if (read.sql == "yes") {
                                rows =
                                    when (problem) {
                                        "foreign" -> listOf(listOf(99L, "a", "yes", "from SQL"))
                                        "duplicate" -> rows + listOf(rows.first())
                                        "wrong-route" -> listOf(listOf(1L, "a", "also", "from SQL"))
                                        "null-key" -> listOf(listOf(null, "a", "yes", "from SQL"))
                                        "missing-reason" -> rows.map { it.take(2) }
                                        else -> rows
                                    }
                            }
                            if (problem == "missing-reason" &&
                                read.sql in setOf("also", "empty")
                            ) {
                                rows = rows.map { it.take(2) }
                            }
                            ReadResult(Table(read.columns, rows))
                        },
                        Staging(LocalStagingStore(Files.createTempDirectory("au-eval-bad"))) {},
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

private val KEY_COLUMNS = listOf(ManifestColumn("id", "decimal"), ManifestColumn("tenant", "text"))
private val ROUTE_COLUMNS =
    listOf(
        ManifestColumn("id", "int"),
        ManifestColumn("tenant", "text"),
        ManifestColumn("route", "text"),
        ManifestColumn("verdict_reason", "text"),
    )

internal fun evaluationRecipe(mode: String = "overlapping"): LoadedRecipe {
    fun output(
        port: String,
        file: String,
        route: Boolean = false,
    ) = ManifestOutput(
        port,
        file,
        columns = if (route) ROUTE_COLUMNS else KEY_COLUMNS,
        keyed = true,
        storage = "erp_db",
    )
    val recipe =
        ManifestRecipe(
            "r",
            key = listOf("item.id", "item.tenant"),
            roles =
                ManifestRoles(
                    candidates = "candidates",
                    contexts = listOf(ManifestContext("context", "per-candidate")),
                    decision = "way",
                    routes =
                        listOf("yes", "also", "empty").map {
                            ManifestRoute(it, because = "must not re-render this")
                        },
                    switchMode = mode,
                ),
        )
    val islands =
        listOf(
            ManifestIsland("candidates", outputs = listOf(output("rows", "candidates"))),
            ManifestIsland("context", outputs = listOf(output("rows", "context"))),
            ManifestIsland("way", outputs = listOf("yes", "also", "empty").map { output(it, it, true) }),
        )
    return LoadedRecipe(
        "r",
        RecipeManifest(
            3,
            islands = islands,
            waves = listOf(listOf("candidates"), listOf("context"), listOf("way")),
            recipe = recipe,
        ),
        recipe,
        listOf("candidates", "context", "yes", "also", "empty").associateWith { it },
    )
}

internal fun evaluationRows(name: String): List<List<Any?>> =
    when (name) {
        "candidates" -> listOf(1L, 2L, 3L).map { listOf(BigDecimal.valueOf(it), "a") }
        "context" -> emptyList()
        "yes" -> listOf(listOf(1L, "a", "yes", "SQL reason"), listOf(2L, "a", "yes", null))
        "also" -> listOf(listOf(1L, "a", "also", "another SQL reason"))
        "empty" -> emptyList()
        else -> error("unexpected statement")
    }
