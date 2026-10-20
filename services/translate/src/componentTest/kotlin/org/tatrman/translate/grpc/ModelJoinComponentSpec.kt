// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.grpc

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.model.StaticModelHandleProvider
import org.tatrman.translate.v1.ExplainRequest
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translate.v1.TranslateRequest
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

        // `translate.refuse-unconditioned-bare-joins = false`: the behaviour before the refusal, the switch's fallback.
        val warnOnly =
            TranslatorServiceImpl(
                StaticModelHandleProvider(HartlandErFixture.handle()),
                refuseUnconditionedBareJoins = false,
            )

        /** ER SQL → plan (physical) → PostgreSQL; every message either side produced, verbatim. */
        suspend fun erToPg(
            sql: String,
            via: TranslatorServiceImpl = service,
        ): Pair<String, List<String>> {
            val parse =
                via.parseToRelNode(
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
                via.unparseFromRelNode(
                    UnparseRequest
                        .newBuilder()
                        .setPlan(parse.plan)
                        .setTargetLanguage(Language.SQL)
                        .setTargetDialect(SqlDialect.POSTGRESQL)
                        .build(),
                )
            return unparse.output to
                parseMessages + unparse.messagesList.map { "${it.severity}/${it.code}: ${it.humanMessage}" }
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

        // ttr-server#232 — a bare JOIN the model cannot condition is refused (translate sets the translator's
        // `refuseUnconditionedBareJoins`): on the call for a DB target, and for query's two calls on the DB re-entry of
        // the ER half's plan, which carries the join marked. The CROSS JOIN form is the author's own Cartesian product
        // (⚑MJ-3: a WARNING), and the caller is told so. translate used to answer a successful parse with the plan alone.
        val cartesian =
            "WARNING/JOIN_NO_RELATION: no declared relation between entity.date_dim and entity.item; " +
                "Cartesian product preserved"
        val refusal =
            "ERROR/join_unconditioned: a JOIN without ON that the model cannot condition: no relation or foreign key " +
                "joins entity.date_dim and entity.item; write the ON"

        fun unrelated(join: String) =
            """
            SELECT "date_dim"."year" AS "year", "item"."brand" AS "brand"
            FROM "date_dim" $join "item"
            """.trimIndent()

        fun said(messages: List<org.tatrman.common.v1.ResponseMessage>) =
            messages.map { "${it.severity}/${it.code}: ${it.humanMessage}" }

        suspend fun erHalf(sql: String) =
            service.parseToRelNode(
                ParseRequest
                    .newBuilder()
                    .setSource(sql)
                    .setSourceLanguage(Language.SQL)
                    .setSourceSchema(SchemaCode.ER)
                    .setTargetSchema(SchemaCode.ER)
                    .setExpandLogicalBodies(true)
                    .build(),
            )

        suspend fun translated(
            sql: String,
            via: TranslatorServiceImpl = service,
        ) = via.translate(
            TranslateRequest
                .newBuilder()
                .setSource(sql)
                .setSourceLanguage(Language.SQL)
                .setSourceSchema(SchemaCode.ER)
                .setTargetSchema(SchemaCode.DB)
                .setTargetLanguage(Language.SQL)
                .setTargetDialect(SqlDialect.POSTGRESQL)
                .build(),
        )

        suspend fun reEntered(erPlan: org.tatrman.plan.v1.PlanNode) =
            service.parseToRelNode(
                ParseRequest
                    .newBuilder()
                    .setSource(String(erPlan.toByteArray(), Charsets.ISO_8859_1))
                    .setSourceLanguage(Language.REL_NODE)
                    .setTargetSchema(SchemaCode.DB)
                    .build(),
            )

        /** Whether any join in [node] carries the SQL carrier's `condition_required` mark. */
        fun marked(node: com.google.protobuf.Message): Boolean =
            node.allFields.any { (field, value) ->
                when (value) {
                    is com.google.protobuf.Message -> marked(value)
                    is List<*> -> value.any { it is com.google.protobuf.Message && marked(it) }
                    else -> field.name == "condition_required" && value == true
                }
            }

        "a bare JOIN the model cannot condition is refused, naming the sides: DB target, and Translate" {
            val (sql, messages) = erToPg(unrelated("JOIN"))
            sql shouldBe ""
            messages shouldBe listOf(refusal)
            said(translated(unrelated("JOIN")).messagesList) shouldBe listOf(refusal)
        }

        // The ER half cannot decide: the physical stage, which may still condition the join from a table FK, runs on
        // the DB re-entry. So the ER half answers the plan with the join marked, and the re-entry refuses it.
        "query's two-half pipeline: the ER half marks the bare join, and the DB re-entry refuses it" {
            val er = erHalf(unrelated("JOIN"))
            er.hasPlan() shouldBe true
            marked(er.plan) shouldBe true
            // The logical joiner's verdict, as on any ER half; query passes on only the DB half's, which decides.
            said(er.messagesList) shouldBe listOf(cartesian)
            val db = reEntered(er.plan)
            db.hasPlan() shouldBe false
            said(db.messagesList) shouldBe listOf(refusal)
        }

        fun unrelatedWhere(condition: String) = unrelated("JOIN") + "\nWHERE " + condition

        "a bare JOIN its WHERE conditions is the author's join: not refused, and parsed as with the switch off" {
            val sql = unrelatedWhere("\"date_dim\".\"sk\" = \"item\".\"sk\"")
            val answer = erToPg(sql)
            answer shouldBe erToPg(sql, warnOnly)
            // The joiners' warning, as with the switch off: that it is false here is ttr-core#218.
            answer.second shouldBe listOf(cartesian)
        }

        // A WHERE condition that reads both sides of a bare join over an FK-only relation (every hartland relation) is a
        // filter, and the join takes its FK as with the switch off. translator 0.12.5 made such a condition the join's ON.
        "a bare JOIN over an FK-only relation keeps its foreign key under a WHERE filter reading both sides" {
            val sql =
                """
                SELECT "date_dim"."year" AS "year", SUM("catalog_sales"."quantity") AS "quantity"
                FROM "catalog_sales" JOIN "date_dim"
                WHERE "date_dim"."year" = 2000 OR "catalog_sales"."quantity" > 100
                GROUP BY "date_dim"."year"
                """.trimIndent()
            val answer = erToPg(sql)
            answer.first shouldContain "ON \"t\".\"sold_date\" = \"t0\".\"sk\""
            answer shouldBe erToPg(sql, warnOnly)
        }

        "a disjunction reading both sides does not condition a bare JOIN: refused" {
            erToPg(unrelatedWhere("\"date_dim\".\"year\" = 2000 OR \"item\".\"brand\" = 'X'")).second shouldBe
                listOf(refusal)
        }

        "Explain says the refusal the parse gives" {
            val explained =
                service.explain(
                    ExplainRequest
                        .newBuilder()
                        .setParse(
                            ParseRequest
                                .newBuilder()
                                .setSource(unrelated("JOIN"))
                                .setSourceLanguage(Language.SQL),
                        ).build(),
                )
            said(explained.messagesList).single() shouldContain "a JOIN without ON that the model cannot condition"
        }

        "with the switch off a bare JOIN the model cannot condition runs, with its warning" {
            val (sql, messages) = erToPg(unrelated("JOIN"), warnOnly)
            sql shouldNotBe ""
            messages shouldBe listOf(cartesian)
            said(translated(unrelated("JOIN"), warnOnly).messagesList) shouldBe listOf(cartesian)
        }

        "a CROSS JOIN between unrelated entities parses to a Cartesian product, and ParseToRelNode says so" {
            val (_, messages) = erToPg(unrelated("CROSS JOIN"))
            messages shouldBe listOf(cartesian)
        }

        // query's two calls: the ER half (target ER), then the plan re-entered for the DB half. query reads the warning
        // from the second, the plan that runs; this pins that the re-entry decides the join again and says so.
        "query's two-half pipeline: the DB re-entry says so again" {
            val er = erHalf(unrelated("CROSS JOIN"))
            marked(er.plan) shouldBe false
            val dbHalf = reEntered(er.plan)
            dbHalf.hasPlan() shouldBe true
            said(dbHalf.messagesList) shouldBe listOf(cartesian)
        }

        "Translate says so too" {
            val resp = translated(unrelated("CROSS JOIN"))
            resp.output shouldNotBe ""
            said(resp.messagesList) shouldBe listOf(cartesian)
        }

        "a join left to its foreign key, which conditions it, is passed on as the translator's INFO" {
            val (sql, messages) =
                erToPg(
                    """
                    SELECT "date_dim"."month" AS "month", SUM("catalog_sales"."ext_sales_price") AS "ext_sales_price"
                    FROM "catalog_sales" JOIN "date_dim"
                    GROUP BY "date_dim"."month"
                    """.trimIndent(),
                )
            sql shouldContain "ON \"t\".\"sold_date\" = \"t0\".\"sk\""
            messages.map { it.substringBefore(":") } shouldBe listOf("INFO/JOIN_RELATION_WITHOUT_PAIRS")
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
