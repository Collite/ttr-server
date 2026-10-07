// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.grpc

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.Value
import org.tatrman.translate.model.StaticModelHandleProvider
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translate.v1.UnparseRequest
import java.io.File

/**
 * DQ C-6 — **the Golem's query door asks the same question in either language.**
 *
 * The door writes a lattice-covered question as SQL over entities (bare `JOIN`s, `{pN}`
 * placeholders) by default and as a `transdsl.v1.Query` on rollback. Golem has no translator on its
 * classpath, so whether the two are the same query is checked here, on the pairs its specs write
 * (`door-parity/`, see `PROVENANCE.md`): both take the query service's own route to the physical
 * plan (see `pg`), are unparsed for PostgreSQL, and must agree byte for byte, bindings included.
 *
 * A pair that cannot be made equal is named in [KNOWN_DIFFERENCES] with the reason, and the
 * difference itself is asserted — never dropped silently.
 */
@Tags("component")
class DoorParityComponentSpec :
    StringSpec({

        val service = TranslatorServiceImpl(StaticModelHandleProvider(HartlandErFixture.handle()))
        val dir = File(System.getProperty("user.dir"), "src/componentTest/resources/door-parity")
        val names =
            dir
                .listFiles()
                .orEmpty()
                .map { it.name }
                .filter { it.endsWith(".sql") }
                .map { it.removeSuffix(".sql") }
                .sorted()

        "the copied pairs are all here" {
            names.shouldNotBeEmpty()
            names.forEach { File(dir, "$it.transdsl.json").exists() shouldBe true }
        }

        /**
         * `{"p0":{"value":"…","type":"text"}}` → the bindings query-mcp would build from it. The
         * envelope is the door's fixed shape (string values, `\\` and `\"` escapes only), so a
         * pattern reads it; this module has no JSON library on its component classpath.
         */
        fun context(name: String): PipelineContext {
            val file = File(dir, "$name.params.json")
            val builder = PipelineContext.newBuilder()
            if (!file.exists()) return builder.build()
            val text = file.readText().trim()
            val entries = PARAM.findAll(text).toList()
            // Every byte accounted for: a shape this pattern does not read must fail, not shrink.
            entries.joinToString(",", "{", "}") { it.value } shouldBe text
            entries.forEach { m ->
                val (param, value, type) = m.destructured
                builder.addParameters(
                    ParameterBinding
                        .newBuilder()
                        .setName(param)
                        .setType(type)
                        .setValue(Value.newBuilder().setStringValue(value.replace(ESCAPED, "$1"))),
                )
            }
            return builder.build()
        }

        fun failures(messages: List<ResponseMessage>): List<String> =
            messages.filter { it.severityValue >= 2 }.map { "${it.code}: ${it.humanMessage}" }

        /**
         * The query service's own route to the physical plan (`QueryServiceImpl`): a source the
         * schema detector reads as DB is parsed DB → DB in one step; anything else — every TransDSL
         * source, and SQL over entities — is parsed to the ER plan (`target_schema = ER`, no stated
         * source schema: `parseAndCache`), then re-entered as REL_NODE for DB (`translateToDbPlain`).
         * The validator between the two hops (row-level security) is the same for both languages
         * and is left out. Then PostgreSQL, the bound parameters after it.
         */
        suspend fun pg(
            source: String,
            language: Language,
            dbDetected: Boolean,
            ctx: PipelineContext,
        ): String {
            val physical =
                if (dbDetected) {
                    service.parseToRelNode(
                        ParseRequest
                            .newBuilder()
                            .setSource(source)
                            .setSourceLanguage(language)
                            .setSourceSchema(SchemaCode.DB)
                            .setTargetSchema(SchemaCode.DB)
                            .setContext(ctx)
                            .build(),
                    )
                } else {
                    val er =
                        service.parseToRelNode(
                            ParseRequest
                                .newBuilder()
                                .setSource(source)
                                .setSourceLanguage(language)
                                .setTargetSchema(SchemaCode.ER)
                                .setContext(ctx)
                                .build(),
                        )
                    if (!er.hasPlan() || failures(er.messagesList).isNotEmpty()) {
                        return "ER PARSE FAILED ${failures(er.messagesList)}"
                    }
                    service.parseToRelNode(
                        ParseRequest
                            .newBuilder()
                            .setSource(String(er.plan.toByteArray(), Charsets.ISO_8859_1))
                            .setSourceLanguage(Language.REL_NODE)
                            .setTargetSchema(SchemaCode.DB)
                            .setContext(ctx)
                            .build(),
                    )
                }
            if (!physical.hasPlan() || failures(physical.messagesList).isNotEmpty()) {
                return "DB PARSE FAILED ${failures(physical.messagesList)}"
            }
            val unparse =
                service.unparseFromRelNode(
                    UnparseRequest
                        .newBuilder()
                        .setPlan(physical.plan)
                        .setTargetLanguage(Language.SQL)
                        .setTargetDialect(SqlDialect.POSTGRESQL)
                        .setContext(ctx)
                        .build(),
                )
            if (unparse.messagesList.isNotEmpty()) return "UNPARSE FAILED ${unparse.messagesList.map { it.code }}"
            val bindings = unparse.context.parametersList.map { "${it.name}=${it.value.stringValue}" }
            return unparse.output + "\n-- bindings: $bindings"
        }

        "the date-only bound's SQL spelling is free: every form compiles to the same physical SQL" {
            // B2's probe, kept. A DATETIME column compared to any spelling of `2025-01-01` validates
            // to TIMESTAMP '2025-01-01 00:00:00' on the SQL route — so no spelling reproduces the
            // wire's DATE literal (see KNOWN_DIFFERENCES), and the door may write the one that
            // reads best (`DATE '…'`).
            val sql = File(dir, "revenue_2025_by_month.sql").readText()
            val ctx = PipelineContext.getDefaultInstance()
            val compiled =
                listOf(
                    "DATE '%s'",
                    "TIMESTAMP '%s 00:00:00'",
                    "CAST('%s' AS TIMESTAMP)",
                    "CAST('%s' AS DATE)",
                    "'%s'",
                ).map { f ->
                    pg(
                        sql
                            .replace(
                                "DATE '2025-01-01'",
                                f.format("2025-01-01"),
                            ).replace("DATE '2026-01-01'", f.format("2026-01-01")),
                        Language.SQL,
                        false,
                        ctx,
                    )
                }
            compiled.distinct().size shouldBe 1
            compiled.first() shouldContain "\"cal_date\" >= TIMESTAMP '2025-01-01 00:00:00'"
        }

        // LR C-2 — the shapes only the SQL language can write (a ranking, an implied trend grain).
        // TransDSL refuses them by name, so there is no pair to compare: what is left of parity is
        // that each COMPILES on the same route and keeps the clause it was written for.
        val sqlOnly = File(System.getProperty("user.dir"), "src/componentTest/resources/door-sql")
        val sqlOnlyNames =
            sqlOnly
                .listFiles()
                .orEmpty()
                .map { it.name }
                .filter { it.endsWith(".sql") }
                .map { it.removeSuffix(".sql") }
                .sorted()

        "the SQL-only goldens are all here" {
            sqlOnlyNames.shouldNotBeEmpty()
        }

        sqlOnlyNames.forEach { name ->
            "door-sql/$name — compiles to PostgreSQL, ordered as written" {
                val pg =
                    pg(File(sqlOnly, "$name.sql").readText(), Language.SQL, false, PipelineContext.getDefaultInstance())
                pg shouldNotContain "FAILED"
                pg shouldContain "ORDER BY"
                // A ranking keeps its ORDER BY <measure> DESC and its LIMIT — Calcite resolves the
                // select alias to the aggregate and unparses LIMIT as FETCH NEXT on PostgreSQL.
                if (name.startsWith("top_")) {
                    pg shouldContain "DESC"
                    pg shouldContain "FETCH NEXT"
                }
            }
        }

        names.forEach { name ->
            "$name — TransDSL and SQL compile to the same PostgreSQL" {
                val ctx = context(name)
                // query-mcp sends TransDSL schema-less (detection runs for SQL only), so it always
                // takes the ER route; SQL is detected DB only for the `db_` fixtures.
                val viaTransDsl =
                    pg(File(dir, "$name.transdsl.json").readText(), Language.TRANSFORMATION_DSL, false, ctx)
                val viaSql = pg(File(dir, "$name.sql").readText(), Language.SQL, name.startsWith("db_"), ctx)
                val known = KNOWN_DIFFERENCES[name]
                if (known == null) {
                    viaSql shouldBe viaTransDsl
                } else {
                    // Not skipped: the difference is asserted, so the reason stays true or fails.
                    known.check(viaTransDsl, viaSql)
                }
            }
        }
    }) {
    /** A pair that cannot be made equal: why, and the check that the difference is exactly that. */
    class Difference(
        val reason: String,
        val check: (viaTransDsl: String, viaSql: String) -> Unit,
    )

    companion object {
        private val TRANSDSL_REFUSES_UNFILTERED_JOIN =
            Difference(
                "the TransDSL codec refuses several cores with no `filter` (join_condition_required); " +
                    "the SQL form's bare JOIN is conditioned from the model, so only SQL answers",
            ) { viaTransDsl, viaSql ->
                viaTransDsl shouldStartWith "ER PARSE FAILED [join_condition_required"
                viaSql shouldNotContain "FAILED"
                viaSql shouldContain "INNER JOIN"
            }

        /**
         * Named pairs that differ, by fixture name → why. Every entry is asserted, not skipped.
         *
         * - `revenue_by_month`, `quantity_by_brand_and_year`, `revenue_by_year_and_month` — see
         *   [TRANSDSL_REFUSES_UNFILTERED_JOIN].
         * - `revenue_2025_by_month` — literal typing: the wire keeps a date-only bound a DATE, SQL
         *   validation coerces it to TIMESTAMP '… 00:00:00' against the DATETIME column (the same
         *   comparison on PostgreSQL) — and SQL orders by the grain, see [ORDERED_BY_GRAIN].
         * - `revenue_2025_by_month_instants` — [ORDERED_BY_GRAIN] only.
         * - `products_equal_to`, `products_not_equal_to` — the SQL route case-folds `=` / `<>` against a
         *   text parameter (`CaseFoldingParams`: `LOWER(col) = LOWER(?)`); the TransDSL wire does not.
         *   Everything else is identical.
         */
        val KNOWN_DIFFERENCES: Map<String, Difference> =
            mapOf(
                "revenue_by_month" to TRANSDSL_REFUSES_UNFILTERED_JOIN,
                "quantity_by_brand_and_year" to TRANSDSL_REFUSES_UNFILTERED_JOIN,
                "revenue_2025_by_month" to
                    Difference(
                        "a date-only bound: DATE on the wire, TIMESTAMP at midnight via SQL; and SQL orders by the grain",
                    ) { viaTransDsl, viaSql ->
                        withoutGrainOrder(viaSql) shouldBe
                            viaTransDsl.replace(Regex("""DATE '(\d{4}-\d{2}-\d{2})'"""), "TIMESTAMP '$1 00:00:00'")
                        viaSql shouldNotBe viaTransDsl
                    },
                "products_equal_to" to CASE_FOLDED,
                "products_not_equal_to" to CASE_FOLDED,
                // LR C-2 — SQL orders a grain grouping; TransDSL (no ORDER BY) cannot. Same rows.
                "revenue_2025_by_month_instants" to ORDERED_BY_GRAIN,
                "revenue_by_year_and_month" to TRANSDSL_REFUSES_UNFILTERED_JOIN,
            )

        /** The `ORDER BY` line the SQL form adds (LR C-2), removed: what is left must be the TransDSL plan. */
        private fun withoutGrainOrder(pg: String): String = pg.replace(Regex("""\nORDER BY [^\n]*"""), "")

        /**
         * LR C-2 — the SQL form orders a time-grain grouping (`ORDER BY "month"`); the TransDSL wire
         * has no ORDER BY, so the rollback answers the same rows unordered. The order is asserted to
         * be the ONLY difference.
         */
        private val ORDERED_BY_GRAIN: Difference
            get() =
                Difference(
                    "SQL orders by the grouped time grain (LR C-2); TransDSL has no ORDER BY",
                ) { viaTransDsl, viaSql ->
                    viaSql shouldContain "ORDER BY"
                    viaTransDsl shouldNotContain "ORDER BY"
                    withoutGrainOrder(viaSql) shouldBe viaTransDsl
                }

        private val CASE_FOLDED: Difference
            get() =
                Difference(
                    "SQL case-folds = / <> against a text parameter (CaseFoldingParams)",
                ) { viaTransDsl, viaSql ->
                    viaSql shouldContain "LOWER(\"product_name\")"
                    viaSql shouldContain "LOWER(?)"
                    viaSql.replace("LOWER(\"product_name\")", "\"product_name\"").replace("LOWER(?)", "?") shouldBe
                        viaTransDsl
                }

        private val PARAM = Regex(""""(p\d+)":\{"value":"((?:[^"\\]|\\.)*)","type":"(\w+)"\}""")
        private val ESCAPED = Regex("""\\(.)""")
    }
}
