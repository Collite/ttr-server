// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.grpc

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.model.StaticModelHandleProvider
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translate.v1.UnparseRequest
import java.nio.file.Files
import java.nio.file.Path

/**
 * DQ-S0 A1 — **a bare ER `JOIN` compiles** (the gate G0 names).
 *
 * The Golem's LLM lane is told to *"rely on natural joins — do not specify the join conditions"*
 * (hartland `golem-hartland/prompts/en/intent.yaml`), and from DQ on the lattice-covered door
 * writes the same form. Before ttr-translator 0.11.1 (MJ's `ModelJoinRewriter`) Calcite refused it
 * at validation — *"INNER, LEFT, RIGHT, FULL, or ASOF join requires a condition"* — so the pin this
 * repo carries decides whether either lane can ask a multi-entity question at all.
 *
 * Driven against [HartlandErFixture], whose relations carry no join pairs (the estate's own shape):
 * the condition below comes from the FK `fk_cs_date` after MAP_TO_PHYSICAL, which is the route a
 * hartland join actually takes.
 *
 * The comma form is pinned to a golden that was recorded on the PRE-bump translator (0.10.3): MJ's
 * claim is that the comma form's plan is untouched, and this is that claim checked on our side.
 * `RECORD_GOLDEN=1` rewrites the goldens, like [TranslateUnparseComponentSpec].
 */
@Tags("component")
class ModelJoinComponentSpec :
    StringSpec({

        val service = TranslatorServiceImpl(StaticModelHandleProvider(HartlandErFixture.handle()))

        /** ER SQL → plan (physical) → PostgreSQL; every message either side produced, verbatim. */
        suspend fun erToPg(sql: String): Pair<String, List<String>> {
            val parse =
                service.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource(sql)
                        .setSourceLanguage(Language.SQL)
                        .setSourceSchema(SchemaCode.ER)
                        .setTargetSchema(SchemaCode.DB)
                        .build(),
                )
            val parseMessages = parse.messagesList.map { "${it.severity}/${it.code}: ${it.humanMessage}" }
            if (!parse.hasPlan()) return "" to parseMessages
            val unparse =
                service.unparseFromRelNode(
                    UnparseRequest
                        .newBuilder()
                        .setPlan(parse.plan)
                        .setTargetLanguage(Language.SQL)
                        .setTargetDialect(SqlDialect.POSTGRESQL)
                        .build(),
                )
            return unparse.output to parseMessages + unparse.messagesList.map { "${it.severity}/${it.code}: ${it.humanMessage}" }
        }

        fun assertGolden(
            name: String,
            actual: String,
        ) {
            val resource = "translate/$name.sql"
            if (System.getenv("RECORD_GOLDEN") == "1") {
                // Gradle runs a module's tests with the MODULE as the working directory; nothing in
                // this build sets `integrationHarness.repoRoot` for the componentTest task.
                val dir =
                    System
                        .getProperty("integrationHarness.repoRoot")
                        ?.let { Path.of(it, "services/translate/src/componentTest/resources/translate") }
                        ?: Path.of(System.getProperty("user.dir"), "src/componentTest/resources/translate")
                Files.createDirectories(dir)
                Files.writeString(dir.resolve("$name.sql"), actual.trimEnd() + "\n")
                return
            }
            val golden =
                ModelJoinComponentSpec::class.java.classLoader
                    .getResourceAsStream(resource)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: error("golden not found: $resource — run once with RECORD_GOLDEN=1 to create it")
            actual.trimEnd() shouldBe golden.trimEnd()
        }

        "a bare ER JOIN with no ON compiles to exactly the comma form's SQL — the relation's FK equality" {
            val (sql, messages) =
                erToPg(
                    """
                    SELECT "date_dim"."month" AS "month", SUM("catalog_sales"."ext_sales_price") AS "ext_sales_price"
                    FROM "catalog_sales" JOIN "date_dim"
                    GROUP BY "date_dim"."month"
                    """.trimIndent(),
                )
            // Only INFO-level joiner notes may remain; a refusal or a Cartesian warning may not.
            messages.filterNot { it.startsWith("SEVERITY_INFO/") || it.startsWith("INFO/") } shouldBe emptyList()
            // hartland's relation has no join pairs, so the condition is the FK's, over the renamed
            // columns MAP_TO_PHYSICAL projects: `"t"."sold_date" = "t0"."sk"`.
            sql shouldContain "ON \"t\".\"sold_date\" = \"t0\".\"sk\""
            sql shouldNotContain "ON TRUE"
            // The strongest form of the claim: a bare JOIN is the comma form, byte for byte.
            assertGolden("dq_comma_join_two_entities", sql)
        }

        "a bare chain of three ER entities conditions every join from the model" {
            val (sql, messages) =
                erToPg(
                    """
                    SELECT "item"."brand" AS "brand", "date_dim"."year" AS "year", SUM("catalog_sales"."quantity") AS "quantity"
                    FROM "catalog_sales" JOIN "date_dim" JOIN "item"
                    GROUP BY "item"."brand", "date_dim"."year"
                    """.trimIndent(),
                )
            messages.filterNot { it.startsWith("SEVERITY_INFO/") || it.startsWith("INFO/") } shouldBe emptyList()
            sql shouldContain ".\"sold_date\" = "
            sql shouldContain ".\"item\" = "
            sql shouldNotContain "ON TRUE"
            sql shouldNotContain "CROSS JOIN"
            assertGolden("dq_bare_join_three_entities", sql)
        }

        "the comma form still yields the plan it yielded before the bump" {
            val (sql, messages) =
                erToPg(
                    """
                    SELECT "date_dim"."month" AS "month", SUM("catalog_sales"."ext_sales_price") AS "ext_sales_price"
                    FROM "catalog_sales", "date_dim"
                    GROUP BY "date_dim"."month"
                    """.trimIndent(),
                )
            messages.filterNot { it.startsWith("SEVERITY_INFO/") || it.startsWith("INFO/") } shouldBe emptyList()
            assertGolden("dq_comma_join_two_entities", sql)
        }
    })
