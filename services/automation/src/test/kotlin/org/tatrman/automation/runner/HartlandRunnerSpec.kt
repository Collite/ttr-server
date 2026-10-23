// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.coroutineScope
import org.tatrman.automation.bundle.*
import org.tatrman.automation.staging.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val FIXTURE = Path.of("src/test/resources/runner/hartland")

internal fun realRecipe(name: String): LoadedRecipe {
    val dir = FIXTURE.resolve("$name.bundle")
    val m = RecipeManifest.parse(Files.readString(dir.resolve("manifest.json")))
    val statements =
        Files.walk(dir).use { paths ->
            paths
                .filter { it.toString().endsWith(".sql") }
                .toList()
                .associate { dir.relativize(it).toString() to Files.readString(it) }
        }
    return LoadedRecipe(name, m, requireNotNull(m.recipe), statements)
}

class HartlandRunnerSpec :
    StringSpec({
        "all six real au.2 bundles decode with every executable file hash checked" {
            for (name in listOf(
                "release_order",
                "decide_return",
                "monitor_inbound",
                "settle",
                "reorder_proposals",
                "winback",
            )) {
                val r = realRecipe(name)
                r.manifest.toolchain shouldBe "org.tatrman:ttrp:0.17.3-au.2"
                for (island in r.manifest.islands) {
                    for (o in island.outputs) {
                        Fingerprints.sha256(r.statements.getValue(o.file)) shouldBe o.sha256
                        o.combine?.let { c -> Fingerprints.sha256(r.statements.getValue(c.file)) shouldBe c.sha256 }
                    }
                }
                for (part in r.recipe.parts) Fingerprints.sha256(r.statements.getValue(part.file)) shouldBe part.sha256
            }
        }
        for (name in listOf("monitor_inbound", "settle")) {
            "$name: separated governed sources and combines match every output of the single-database oracle" {
                coroutineScope {
                    val r = realRecipe(name)
                    val bindings =
                        r.recipe.parameters.associate { it.name to BoundValue(it.type, it.value!!.toLong()) } +
                            mapOf("as_of" to BoundValue("datetime", LocalDateTime.parse("2026-11-02T08:00:00")))
                    DriverManager.getConnection("jdbc:duckdb:").use { erp ->
                        DriverManager.getConnection("jdbc:duckdb:").use { ledger ->
                            DriverManager.getConnection("jdbc:duckdb:").use { oracle ->
                                val erpSql =
                                    Files.readString(FIXTURE.resolve("erp.sql")) +
                                        Files.readString(FIXTURE.resolve("erp-to-er.sql"))
                                val ledgerSql = Files.readString(FIXTURE.resolve("automation.sql"))
                                erp.createStatement().use { it.execute(erpSql) }
                                ledger.createStatement().use { it.execute(ledgerSql) }
                                oracle.createStatement().use { it.execute(erpSql + ledgerSql) }
                                val reads = ConcurrentHashMap<String, AtomicInteger>()
                                // The governed door is faked; sources are in-process fixture databases.
                                val door =
                                    Door { read ->
                                        reads.computeIfAbsent(read.island) { AtomicInteger() }.incrementAndGet()
                                        val db =
                                            when (read.storage) {
                                                "erp_db" -> erp
                                                "automation_db" -> ledger
                                                else -> error("unknown storage")
                                            }
                                        val table = synchronized(db) { JdbcTables.query(db, read.sql, read.bindings) }
                                        ReadResult(ArrowTables.decode(listOf(ArrowTables.encode(table)), read.columns))
                                    }
                                val staged = mutableListOf<org.tatrman.automation.ledger.RunOutput>()
                                PartRunner(
                                    runnerContext(r).copy(bindings = bindings),
                                    door,
                                    Staging(LocalStagingStore(Files.createTempDirectory("au-real-$name"))) {
                                        synchronized(staged) {
                                            staged +=
                                                it
                                        }
                                    },
                                    this,
                                    { _, _ -> },
                                ).use { runner ->
                                    val results = runner.all().associateBy { "${it.output.island}.${it.output.port}" }
                                    for (island in r.manifest.islands) {
                                        for (o in island.outputs) {
                                            val c = o.combine
                                            val sql =
                                                if (c == null) {
                                                    r.statements.getValue(o.file)
                                                } else {
                                                    // One-database oracle, with no part materialization/copy.
                                                    val ctes =
                                                        c.parts.map { p ->
                                                            val part = r.recipe.parts.single { it.name == p }
                                                            "${quoted(p)} AS (${r.statements.getValue(part.file)})"
                                                        }
                                                    "WITH ${ctes.joinToString()} ${r.statements.getValue(c.file)}"
                                                }
                                            val raw = JdbcTables.query(oracle, sql, bindings)
                                            val isDetail =
                                                island.name == r.recipe.roles?.decision &&
                                                    r.recipe.roles!!.routes.any { route ->
                                                        route.detail.any {
                                                            o.port ==
                                                                "${route.name}.$it"
                                                        }
                                                    }
                                            val expected =
                                                if (isDetail) {
                                                    boundedDetails(
                                                        raw,
                                                        r.recipe.key.map { it.substringAfterLast('.') },
                                                        200,
                                                    )
                                                } else {
                                                    raw
                                                }
                                            val actual = results.getValue("${island.name}.${o.port}").table
                                            withClue("$name ${island.name}.${o.port}") {
                                                actual.columns.map { it.name } shouldBe expected.columns.map { it.name }
                                                canonicalRows(actual) shouldBe canonicalRows(expected)
                                            }
                                        }
                                    }
                                    reads.values.forEach { it.get() shouldBe 1 }
                                    val expectedKeys =
                                        if (name ==
                                            "monitor_inbound"
                                        ) {
                                            mapOf(
                                                "complete" to listOf(913),
                                                "damaged" to listOf(914),
                                                "expired" to listOf(912),
                                                "remind" to listOf(911, 916),
                                            )
                                        } else {
                                            mapOf(
                                                "credit_note_issued" to listOf(923),
                                                "money_received" to listOf(923),
                                                "goods_shipped" to listOf(922),
                                                "money_overdue" to listOf(921, 924),
                                                "goods_overdue" to emptyList(),
                                                "closed" to listOf(922, 923),
                                            )
                                        }
                                    val decision = r.recipe.roles!!.decision!!
                                    for ((route, keys) in expectedKeys) {
                                        val table = results.getValue("$decision.$route").table
                                        val index = table.columns.indexOfFirst { it.name == "line_id" }
                                        table.rows.map { (it[index] as Number).toInt() }.sorted() shouldBe keys.sorted()
                                    }
                                    val evaluation = runner.evaluate()
                                    evaluation.verdict.routes.mapValues { it.value.rowCount } shouldBe
                                        expectedKeys.mapValues { it.value.size.toLong() }
                                    val candidates =
                                        results.values.single {
                                            it.output.island ==
                                                r.recipe.roles!!.candidates
                                        }
                                    val keyColumn = candidates.table.columns.indexOfFirst { it.name == "line_id" }
                                    val candidateKeys =
                                        candidates.table.rows
                                            .map { (it[keyColumn] as Number).toInt() }
                                            .toSet()
                                    evaluation.verdict.none shouldBe
                                        (candidateKeys - expectedKeys.values.flatten().toSet()).size.toLong()
                                    reads.values.forEach { it.get() shouldBe 1 }
                                }
                            }
                        }
                    }
                }
            }
        }
    })

private fun canonicalRows(table: Table): List<String> =
    table.rows
        .map { row ->
            row.joinToString("|") {
                when (it) {
                    null -> "<NULL>"
                    is Number ->
                        java.math
                            .BigDecimal(it.toString())
                            .stripTrailingZeros()
                            .toPlainString()
                    else -> it.toString()
                }
            }
        }.sorted()
