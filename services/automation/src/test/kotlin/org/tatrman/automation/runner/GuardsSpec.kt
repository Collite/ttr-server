// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.*
import org.tatrman.automation.ledger.RunOutput
import org.tatrman.automation.staging.*
import java.math.BigDecimal
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class GuardsSpec :
    StringSpec({
        "any restricted decision input refuses before staging or reading the decision" {
            for (restricted in listOf("candidates", "context", "decision")) {
                guarded(
                    door =
                        Door { read ->
                            ReadResult(
                                table(),
                                if (read.island ==
                                    restricted
                                ) {
                                    setOf("security_predicate_applied")
                                } else {
                                    emptySet()
                                },
                            )
                        },
                ) { runner, staged ->
                    val e = shouldThrow<AutomationException> { runner.island("decision") }
                    e.code shouldBe ErrorCode.RESTRICTED_VIEW
                    e.detail.toString().contains(restricted) shouldBe true
                    staged.none { it.island == restricted || it.island == "decision" } shouldBe true
                    runner.guards.single { it.code == "restricted-view" && it.island == restricted }.status shouldBe
                        "refused"
                }
            }
        }
        "deployment opt-in and reads-only recipes allow restricted reads with evidence" {
            for (context in listOf(guardContext().copy(allowRestrictedView = true), guardContext(decision = false))) {
                guarded(context, Door { ReadResult(table(), setOf("security_predicate_applied")) }) { runner, _ ->
                    runner.all()
                    if (context.recipe.recipe.roles
                            ?.decision != null
                    ) {
                        runner.guards.filter { it.code == "restricted-view" }.all { it.status == "allowed" } shouldBe
                            true
                    } else {
                        runner.guards.none { it.code == "restricted-view" } shouldBe true
                    }
                }
            }
        }
        "declared ceiling equality and the validator's lower cap refuse candidate or context inputs" {
            for (island in listOf("candidates", "context")) {
                for (lowerCap in listOf(false, true)) {
                    guarded(
                        guardContext().copy(readCeiling = 2),
                        Door { read ->
                            if (read.island == island) {
                                ReadResult(
                                    table(if (lowerCap) listOf(listOf(1L)) else listOf(listOf(1L), listOf(2L))),
                                    if (lowerCap) setOf("top_n_applied") else emptySet(),
                                )
                            } else {
                                ReadResult(table())
                            }
                        },
                    ) { runner, staged ->
                        shouldThrow<AutomationException> { runner.island("decision") }.code shouldBe
                            ErrorCode.CANDIDATES_TRUNCATED
                        staged.none { it.island == island || it.island == "decision" } shouldBe true
                    }
                }
            }
        }
        "numeric duplicate candidate keys refuse before staging; lookup duplicates remain legal" {
            guarded(
                door =
                    Door { read ->
                        ReadResult(
                            table(
                                if (read.island ==
                                    "candidates"
                                ) {
                                    listOf(listOf(1L), listOf(BigDecimal("1.00")))
                                } else {
                                    listOf(listOf(1L))
                                },
                            ),
                        )
                    },
            ) { runner, staged ->
                shouldThrow<AutomationException> { runner.all() }.code shouldBe ErrorCode.DUPLICATE_KEY
                staged.size shouldBe 0
            }
            guarded(
                door =
                    Door { read ->
                        ReadResult(
                            table(
                                if (read.island ==
                                    "context"
                                ) {
                                    listOf(listOf(1L), listOf(1L))
                                } else {
                                    listOf(listOf(1L))
                                },
                            ),
                        )
                    },
            ) { runner, _ -> runner.all().size shouldBe 3 }
        }
        "candidate uniqueness uses every composite key column by name" {
            val base = guardContext()
            val columns = listOf(ManifestColumn("item", "int"), ManifestColumn("site", "text"))
            val recipe =
                base.recipe.copy(
                    recipe = base.recipe.recipe.copy(key = listOf("order.site", "line.item")),
                    manifest =
                        base.recipe.manifest.copy(
                            islands =
                                base.recipe.manifest.islands.map { island ->
                                    island.copy(outputs = island.outputs.map { it.copy(columns = columns) })
                                },
                        ),
                )
            for (site in listOf("a", "b")) {
                guarded(
                    base.copy(recipe = recipe),
                    Door {
                        ReadResult(Table(columns, listOf(listOf(1L, "a"), listOf(BigDecimal("1.0"), site))))
                    },
                ) { runner, _ ->
                    if (site == "a") {
                        shouldThrow<AutomationException> { runner.all() }.code shouldBe ErrorCode.DUPLICATE_KEY
                    } else {
                        runner.all().size shouldBe 3
                    }
                }
            }
        }

        "empty scoped candidates refuse keyed opens while batch and explicit empty selection succeed" {
            val rows = table(listOf(listOf(9L))) // The final key filter must happen before the scope check.
            guarded(
                guardContext().copy(keys = listOf(mapOf("id" to 1L))),
                Door { ReadResult(rows) },
            ) { runner, staged ->
                shouldThrow<AutomationException> { runner.all() }.code shouldBe ErrorCode.OUT_OF_SCOPE
                staged.size shouldBe 0
            }
            for (keys in listOf(null, emptyList<Map<String, Any?>>())) {
                guarded(guardContext().copy(keys = keys), Door { ReadResult(table(emptyList())) }) { runner, _ ->
                    runner.all().size shouldBe 3
                }
            }
        }
        "a refusal is sticky across cached reads and retries without repeating the door" {
            val reads = AtomicInteger()
            guarded(
                door =
                    Door { read ->
                        reads.incrementAndGet()
                        ReadResult(
                            table(),
                            if (read.island ==
                                "context"
                            ) {
                                setOf("security_predicate_applied")
                            } else {
                                emptySet()
                            },
                        )
                    },
            ) { runner, _ ->
                runner.island("candidates")
                shouldThrow<AutomationException> { runner.island("context") }.code shouldBe ErrorCode.RESTRICTED_VIEW
                shouldThrow<AutomationException> { runner.island("candidates") }.code shouldBe ErrorCode.RESTRICTED_VIEW
                shouldThrow<AutomationException> { runner.all() }.code shouldBe ErrorCode.RESTRICTED_VIEW
                reads.get() shouldBe 2
            }
        }
        "shared storage parts are guarded before loading a small aggregate combine" {
            val base = guardContext()
            val candidate =
                ManifestOutput(
                    "rows",
                    "compat.sql",
                    columns = KEY,
                    keyed = true,
                    combine = ManifestCombine("combine.sql", parts = listOf("source")),
                )
            val recipe =
                base.recipe.copy(
                    manifest =
                        base.recipe.manifest.copy(
                            islands = listOf(ManifestIsland("candidates", outputs = listOf(candidate))),
                            waves = listOf(listOf("candidates")),
                        ),
                    recipe =
                        base.recipe.recipe.copy(
                            parts = listOf(ManifestPart("source", "erp_db", file = "source.sql", columns = KEY)),
                        ),
                    statements =
                        mapOf(
                            "source.sql" to "SOURCE",
                            "combine.sql" to "SELECT MIN(id) AS id FROM source",
                        ),
                )
            for (warnings in listOf(setOf("security_predicate_applied"), emptySet())) {
                guarded(
                    base.copy(recipe = recipe, readCeiling = 2),
                    Door {
                        ReadResult(table(listOf(listOf(1L), listOf(2L))), warnings)
                    },
                ) { runner, staged ->
                    shouldThrow<AutomationException> { runner.all() }.code shouldBe
                        if (warnings.isEmpty()) ErrorCode.CANDIDATES_TRUNCATED else ErrorCode.RESTRICTED_VIEW
                    staged.size shouldBe 0
                }
            }
        }
        "local combines enforce candidate uniqueness and ceiling even when every source is short" {
            val base = guardContext()
            for (sql in listOf(
                "SELECT * FROM source UNION ALL SELECT * FROM source",
                "SELECT id FROM source UNION ALL SELECT id+1 FROM source",
            )) {
                val output =
                    ManifestOutput(
                        "rows",
                        "compat.sql",
                        columns = KEY,
                        keyed = true,
                        combine = ManifestCombine("combine.sql", parts = listOf("source")),
                    )
                val recipe =
                    base.recipe.copy(
                        manifest =
                            base.recipe.manifest.copy(
                                islands = listOf(ManifestIsland("candidates", outputs = listOf(output))),
                                waves = listOf(listOf("candidates")),
                            ),
                        recipe =
                            base.recipe.recipe.copy(
                                parts = listOf(ManifestPart("source", "erp_db", file = "source.sql", columns = KEY)),
                            ),
                        statements = mapOf("source.sql" to "SOURCE", "combine.sql" to sql),
                    )
                val ceiling = if (sql.contains("id+1")) 2 else 3
                guarded(
                    base.copy(recipe = recipe, readCeiling = ceiling),
                    Door { ReadResult(table()) },
                ) { runner, staged ->
                    shouldThrow<AutomationException> { runner.all() }.code shouldBe
                        if (ceiling == 2) ErrorCode.CANDIDATES_TRUNCATED else ErrorCode.DUPLICATE_KEY
                    staged.size shouldBe 0
                }
            }
        }
        "a concurrent successful read cannot stage after another read has refused" {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val base = guardContext()
            val context =
                base.copy(
                    recipe =
                        base.recipe.copy(
                            manifest =
                                base.recipe.manifest.copy(
                                    waves = listOf(listOf("candidates", "context"), listOf("decision")),
                                ),
                        ),
                )
            guarded(
                context,
                door =
                    Door { read ->
                        if (read.island == "candidates") {
                            entered.complete(Unit)
                            finish.await()
                            ReadResult(table())
                        } else {
                            ReadResult(table(), setOf("security_predicate_applied"))
                        }
                    },
            ) { runner, staged ->
                // Separate calls exercise the same wave boundary through shared output promises.
                supervisorScope {
                    val first = async { runner.island("candidates") }
                    entered.await()
                    shouldThrow<AutomationException> { runner.island("context") }.code shouldBe
                        ErrorCode.RESTRICTED_VIEW
                    finish.complete(Unit)
                    shouldThrow<AutomationException> { first.await() }.code shouldBe ErrorCode.RESTRICTED_VIEW
                }
                staged.size shouldBe 0
            }
        }
    })

private val KEY = listOf(ManifestColumn("id", "int"))

private fun table(rows: List<List<Any?>> = listOf(listOf(1L))) = Table(KEY, rows)

private fun guardContext(decision: Boolean = true): RunContext {
    val names = listOf("candidates", "context", "decision")
    val islands =
        names.map {
            ManifestIsland(
                it,
                outputs =
                    listOf(
                        ManifestOutput(
                            "rows",
                            "$it.sql",
                            columns = KEY,
                            keyed =
                                it != "context",
                            storage = "erp_db",
                        ),
                    ),
            )
        }
    val roles =
        ManifestRoles("candidates", listOf(ManifestContext("context", "lookup")), if (decision) "decision" else null)
    val recipe =
        LoadedRecipe(
            "guards",
            RecipeManifest(3, islands = islands, waves = names.map { listOf(it) }),
            ManifestRecipe("guards", key = listOf("order.id"), roles = roles),
            names.associate {
                "$it.sql" to
                    "SELECT id FROM source"
            },
        )
    return runnerContext(recipe)
}

private suspend fun guarded(
    context: RunContext = guardContext(),
    door: Door,
    block: suspend (PartRunner, MutableList<RunOutput>) -> Unit,
) {
    val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
    val staged = java.util.Collections.synchronizedList(mutableListOf<RunOutput>())
    val runner =
        PartRunner(
            context,
            door,
            Staging(LocalStagingStore(Files.createTempDirectory("au-guards")), staged::add),
            scope,
        )
    try {
        block(runner, staged)
    } finally {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        runner.close()
    }
}
