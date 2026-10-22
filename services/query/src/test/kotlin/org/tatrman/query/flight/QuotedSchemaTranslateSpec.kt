// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator

/**
 * BI (ttr-server#275) — the text the edge sends, read by the door's own translator (`ttr-translator` over its hartland
 * fixture model) rather than a scripted door. The edge's other specs record what reaches a fake door. This one shows
 * that the door resolves it, so a rewrite the door cannot read fails here, not in a desktop client.
 *
 * The catalog publishes `er.entity` and `db.dbo` as one name each, and a JDBC tool that builds its SQL from the
 * catalog quotes them for their dot (`"er.entity".shopper`).
 */
class QuotedSchemaTranslateSpec :
    StringSpec({
        val translator = Translator(HartlandJoinModel.handle())

        fun translate(
            sql: String,
            schema: SchemaCode = SchemaCode.ER,
        ) = translator.parseToRelNode(sql, Language.SQL, targetSchema = schema, sourceSchema = schema)

        fun sent(sql: String) =
            CatalogQualifier.resolve(sql, "hartland_cz").shouldBeInstanceOf<CatalogQualifier.Outcome.Run>().sql

        "as a JDBC tool writes it, the door refuses the quoted dotted schema: the text the edge must not pass on" {
            translate("""SELECT t.* FROM "er.entity".shopper t""")
                .shouldBeInstanceOf<ParseResult.Failure>()
                .message shouldContain "er.entity"
        }

        "every spelling a JDBC tool writes reaches the door as names it resolves, at the length it was written" {
            listOf(
                """SELECT t.* FROM "er.entity".shopper t""" to SchemaCode.ER,
                """SELECT t.* FROM "er.entity"."shopper" t""" to SchemaCode.ER,
                """SELECT * FROM hartland_cz."er.entity".shopper""" to SchemaCode.ER,
                """SELECT * FROM "hartland_cz"."er.entity"."shopper"""" to SchemaCode.ER,
                """SELECT "er.entity".shopper.* FROM "er.entity".shopper""" to SchemaCode.ER,
                """SELECT t.* FROM "db.dbo".customer t""" to SchemaCode.DB,
                """SELECT * FROM "hartland_cz"."db.dbo"."customer"""" to SchemaCode.DB,
            ).forEach { (sql, schema) ->
                val text = sent(sql)
                text.length shouldBe sql.length
                translate(text, schema).shouldBeInstanceOf<ParseResult.Success>()
            }
        }

        "a refusal after the rewritten schema points at the characters the client wrote there (C-11)" {
            val sql = """SELECT * FROM hartland_cz."er.entity".shopper t WHERE t.nope = 1"""
            val failure = translate(sent(sql)).shouldBeInstanceOf<ParseResult.Failure>()
            val (line, column) = Regex("""line (\d+), column (\d+)""").find(failure.message)!!.destructured
            line shouldBe "1"
            val at = sql.indexOf("t.nope")
            (column.toInt() - 1) shouldBeInRange (at until at + "t.nope".length)
        }
    })
