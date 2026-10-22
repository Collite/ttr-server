// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.tatrman.automation.bundle.*
import org.tatrman.automation.identity.*
import org.tatrman.automation.ledger.RunOutput
import org.tatrman.automation.staging.*
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PartRunnerSpec :
    StringSpec({
        "shared parts execute once on their declared storages; combines filter EVERY keyed output before staging" {
            coroutineScope {
                val reads = ConcurrentHashMap<String, AtomicInteger>()
                val recorded = java.util.Collections.synchronizedList(mutableListOf<RunOutput>())
                val recipe = splitRecipe()
                val context = runnerContext(recipe).copy(keys = listOf(mapOf("id" to 12)))
                val door =
                    Door { r ->
                        reads.computeIfAbsent(r.storage) { AtomicInteger() }.incrementAndGet()
                        r.bindings.keys shouldBe setOf("limit")
                        delay(20)
                        ReadResult(
                            if (r.storage == "erp_db") {
                                Table(COLUMNS, listOf(listOf(12L, "x"), listOf(13L, "y")))
                            } else {
                                Table(listOf(ManifestColumn("key", "text")), listOf(listOf("12"), listOf("13")))
                            },
                            setOf("security_predicate_applied"),
                        )
                    }
                val dir = Files.createTempDirectory("au-stage")
                PartRunner(context, door, Staging(LocalStagingStore(dir), recorded::add), this).use { runner ->
                    val results = listOf(async { runner.all() }, async { runner.all() }).awaitAll()
                    reads.mapValues { it.value.get() } shouldBe mapOf("erp_db" to 1, "automation_db" to 1)
                    recorded.size shouldBe 2
                    results.flatten().forEach { it.table.rows shouldBe listOf(listOf(12L, "x")) }
                    runner.warnings shouldBe setOf("security_predicate_applied")
                    runner.warningsByRead.keys shouldBe setOf("parts/erp", "parts/ledger")
                    for (o in recorded) {
                        val bytes = Files.readAllBytes(dir.resolve(o.path!!))
                        bytes.size.toLong() shouldBe o.bytes
                        ArrowTables.decode(listOf(bytes)).rows shouldBe listOf(listOf(12L, "x"))
                    }
                }
            }
        }
        "candidate wrappers bind scalar keys for direct and local combine outputs; storage parts stay complete" {
            coroutineScope {
                val outputs =
                    listOf(
                        ManifestOutput("direct", "direct.sql", columns = COLUMNS, keyed = true, storage = "erp_db"),
                        ManifestOutput(
                            "combined",
                            "compat.sql",
                            columns = COLUMNS,
                            keyed = true,
                            combine = ManifestCombine("combine.sql", parts = listOf("source")),
                        ),
                    )
                val recipe =
                    LoadedRecipe(
                        "r",
                        RecipeManifest(
                            3,
                            islands = listOf(ManifestIsland("candidate", outputs = outputs)),
                            waves = listOf(listOf("candidate")),
                        ),
                        ManifestRecipe(
                            "r",
                            key = listOf("order.id"),
                            roles = ManifestRoles(candidates = "candidate"),
                            parts = listOf(ManifestPart("source", "erp_db", file = "part.sql", columns = COLUMNS)),
                        ),
                        mapOf(
                            "direct.sql" to "SELECT * FROM (VALUES (12, 'x'), (13, 'y')) AS x(id, label);",
                            "part.sql" to "SOURCE",
                            "combine.sql" to "SELECT * FROM source",
                        ),
                    )
                val direct = CompletableDeferred<DoorRead>()
                val door =
                    Door { read ->
                        if (read.island == "parts/source") {
                            read.sql shouldBe "SOURCE"
                            read.bindings shouldBe emptyMap()
                            ReadResult(Table(COLUMNS, listOf(listOf(12L, "x"), listOf(13L, "y"))))
                        } else {
                            direct.complete(read)
                            DuckDbCombiner().use { db -> ReadResult(db.query(read.sql, read.bindings)) }
                        }
                    }
                PartRunner(
                    runnerContext(recipe).copy(keys = listOf(mapOf("id" to 12L))),
                    door,
                    Staging(LocalStagingStore(Files.createTempDirectory("au-scoped"))) {},
                    this,
                ).use { runner ->
                    runner.all().map { it.table.rows } shouldBe
                        listOf(listOf(listOf(12L, "x")), listOf(listOf(12L, "x")))
                    direct.await().bindings shouldBe mapOf("_au_port_0_0" to BoundValue("int", 12L))
                }
            }
        }
        "a later output without warnings cannot erase an earlier restricted read notice" {
            coroutineScope {
                val base = splitRecipe()
                val outputs =
                    listOf("restricted", "plain").map { port ->
                        ManifestOutput(port, "$port.sql", columns = COLUMNS, storage = "erp_db")
                    }
                val recipe =
                    base.copy(
                        manifest =
                            RecipeManifest(
                                3,
                                islands = listOf(ManifestIsland("both", outputs = outputs)),
                                waves = listOf(listOf("both")),
                            ),
                        recipe = base.recipe.copy(parts = emptyList()),
                        statements = mapOf("restricted.sql" to "restricted", "plain.sql" to "plain"),
                    )
                val restrictedFinished = CompletableDeferred<Unit>()
                val door =
                    Door { read ->
                        val warnings =
                            if (read.sql == "restricted") {
                                restrictedFinished.complete(Unit)
                                setOf("security_predicate_applied")
                            } else {
                                restrictedFinished.await()
                                delay(20)
                                emptySet()
                            }
                        ReadResult(Table(COLUMNS, emptyList()), warnings)
                    }
                PartRunner(
                    runnerContext(recipe),
                    door,
                    Staging(LocalStagingStore(Files.createTempDirectory("au-notices"))) {},
                    this,
                ).use { runner ->
                    runner.all()
                    runner.warnings shouldBe setOf("security_predicate_applied")
                    runner.warningsByRead.getValue("both") shouldBe setOf("security_predicate_applied")
                }
            }
        }

        "failed parts fail every dependent output; never stage an empty success or retry the failed part" {
            coroutineScope {
                val calls = AtomicInteger()
                val recorded = mutableListOf<RunOutput>()
                val door =
                    Door { r ->
                        calls.incrementAndGet()
                        if (r.storage == "automation_db") error("ledger refused")
                        ReadResult(Table(COLUMNS, emptyList()))
                    }
                // Supervisor scope isolates the deliberately failing work from the test caller.
                val work = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob())
                try {
                    PartRunner(
                        runnerContext(splitRecipe()),
                        door,
                        Staging(LocalStagingStore(Files.createTempDirectory("au-failed")), recorded::add),
                        work,
                    ).use { runner ->
                        shouldThrow<IllegalStateException> { runner.all() }
                        shouldThrow<IllegalStateException> { runner.all() }
                        calls.get() shouldBe 2
                        recorded.size shouldBe 0
                    }
                } finally {
                    work.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                }
            }
        }
        "on-demand decision runs earlier waves first, caps concurrent door reads at four and caches direct outputs" {
            coroutineScope {
                val active = AtomicInteger()
                val maximum = AtomicInteger()
                val order = java.util.Collections.synchronizedList(mutableListOf<String>())
                val gate = CompletableDeferred<Unit>()
                val islands =
                    (0..6).map { n ->
                        ManifestIsland(
                            "i$n",
                            outputs = listOf(ManifestOutput("rows", "i$n.sql", columns = COLUMNS, storage = "erp_db")),
                        )
                    }
                val recipe =
                    LoadedRecipe(
                        "r",
                        RecipeManifest(
                            3,
                            islands = islands,
                            waves = listOf(listOf("i0"), (1..5).map { "i$it" }, listOf("i6")),
                        ),
                        ManifestRecipe("r", key = listOf("order.id")),
                        islands.associate {
                            it.file.ifEmpty { "${it.name}.sql" } to
                                "SELECT 1"
                        },
                    )
                val door =
                    Door { r ->
                        order += r.island
                        val n = active.incrementAndGet()
                        maximum.updateAndGet { maxOf(it, n) }
                        if (n == 4) gate.complete(Unit)
                        if (r.island in (1..5).map { "i$it" }) gate.await()
                        delay(30)
                        active.decrementAndGet()
                        ReadResult(Table(COLUMNS, listOf(listOf(1L, "v"))))
                    }
                PartRunner(
                    runnerContext(recipe),
                    door,
                    Staging(LocalStagingStore(Files.createTempDirectory("au-waves"))) {},
                    this,
                ).use { runner ->
                    runner.island("i6")
                    runner.island("i6")
                    maximum.get() shouldBe 4
                    order.first() shouldBe "i0"
                    order.last() shouldBe "i6"
                    order.size shouldBe 7
                }
            }
        }
    })

private val COLUMNS = listOf(ManifestColumn("id", "int"), ManifestColumn("label", "text"))

internal fun runnerContext(recipe: LoadedRecipe): RunContext {
    val caller = Caller("sub", "operator", setOf("ops"), TokenKind.PERSON, "not-logged")
    return RunContext(
        UUID.randomUUID(),
        recipe,
        "demo",
        "pinned-model",
        RunIdentity(caller, RunAsRef.Caller, caller.principal, null),
        mapOf("limit" to BoundValue("int", 5L), "unused_secret" to BoundValue("text", "must-not-forward")),
    )
}

private fun splitRecipe(): LoadedRecipe {
    val combine = ManifestCombine("combine.sql", parts = listOf("erp", "ledger"), params = listOf("limit"))
    val outputs =
        listOf("main", "detail").map {
            ManifestOutput(it, "compatibility-never-run.sql", columns = COLUMNS, keyed = true, combine = combine)
        }
    val recipe =
        ManifestRecipe(
            "r",
            key = listOf("order.id"),
            parts =
                listOf(
                    ManifestPart("erp", "erp_db", file = "erp.sql", columns = COLUMNS, params = listOf("limit")),
                    ManifestPart(
                        "ledger",
                        "automation_db",
                        file = "ledger.sql",
                        columns = listOf(ManifestColumn("key", "text")),
                        params = listOf("limit"),
                    ),
                ),
        )
    return LoadedRecipe(
        "r",
        RecipeManifest(
            3,
            islands = listOf(ManifestIsland("decision", schema = "db", outputs = outputs)),
            waves = listOf(listOf("decision")),
        ),
        recipe,
        mapOf(
            "erp.sql" to "ERP",
            "ledger.sql" to "LEDGER",
            "combine.sql" to
                "SELECT e.id,e.label FROM erp e JOIN ledger l ON CAST(e.id AS VARCHAR)=l.key WHERE e.id > :limit",
        ),
    )
}
