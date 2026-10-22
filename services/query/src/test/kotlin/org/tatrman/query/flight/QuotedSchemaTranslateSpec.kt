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
import org.tatrman.translator.codec.sql.ParseResult as SqlParse
import org.tatrman.translator.codec.sql.SqlParser as DoorParser

/**
 * BI (ttr-server#275) — the text the edge sends, read by the door's own translator (`ttr-translator` over its hartland
 * fixture model) rather than a scripted door. The edge's other specs record what reaches a fake door. This one shows
 * that the door resolves it to the object the client named, so a rewrite the door cannot read fails here, not in a
 * desktop client.
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

        "every spelling a JDBC tool writes reaches the door as the object it names, at the length it was written" {
            val shopper = listOf("namespace: \"entity\"", "name: \"shopper\"")
            val customer = listOf("namespace: \"dbo\"", "name: \"customer\"")
            listOf(
                Triple("""SELECT t.* FROM "er.entity".shopper t""", SchemaCode.ER, shopper),
                Triple("""SELECT t.* FROM "er.entity"."shopper" t""", SchemaCode.ER, shopper),
                Triple("""SELECT * FROM hartland_cz."er.entity".shopper""", SchemaCode.ER, shopper),
                Triple("""SELECT * FROM "hartland_cz"."er.entity"."shopper"""", SchemaCode.ER, shopper),
                Triple("""SELECT "er.entity".shopper.* FROM "er.entity".shopper""", SchemaCode.ER, shopper),
                Triple("""SELECT t.* FROM "db.dbo".customer t""", SchemaCode.DB, customer),
                Triple("""SELECT * FROM "hartland_cz"."db.dbo"."customer"""", SchemaCode.DB, customer),
            ).forEach { (sql, schema, names) ->
                val text = sent(sql)
                text.length shouldBe sql.length
                val plan = translate(text, schema).shouldBeInstanceOf<ParseResult.Success>().plan.toString()
                names.forEach { plan shouldContain it }
            }
        }

        // ⚑R123-C (review-123 R8) — the edge states the layer of the statement's qualified names, and the door reads a
        // bare name in it: the table `customer` beside `db.dbo.store_sales`; the entity `shopper` there is not found.
        "a bare name is read in the layer the statement's qualified names state" {
            val sql = "SELECT c.shopper_id FROM db.dbo.store_sales s JOIN customer c ON s.shopper_id = c.shopper_id"
            SourceSchema.of(sent(sql)) shouldBe SchemaCode.DB
            translate(sent(sql), SchemaCode.DB)
                .shouldBeInstanceOf<ParseResult.Success>()
                .plan
                .toString() shouldContain "name: \"customer\""
            val entity = "SELECT x.shopper_id FROM db.dbo.store_sales s JOIN shopper x ON s.shopper_id = x.shopper_id"
            SourceSchema.of(sent(entity)) shouldBe SchemaCode.DB
            val refused = translate(sent(entity), SchemaCode.DB).shouldBeInstanceOf<ParseResult.Failure>()
            refused.message shouldContain "shopper"
        }

        "a refusal after the rewritten schema points at the characters the client wrote there (C-11)" {
            val sql = """SELECT * FROM hartland_cz."er.entity".shopper t WHERE t.nope = 1"""
            val failure = translate(sent(sql)).shouldBeInstanceOf<ParseResult.Failure>()
            val (line, column) = Regex("""line (\d+), column (\d+)""").find(failure.message)!!.destructured
            line shouldBe "1"
            val at = sql.indexOf("t.nope")
            (column.toInt() - 1) shouldBeInRange (at until at + "t.nope".length)
        }

        "the door's parser: a namespace that is a reserved word parses only quoted, and `//` is a comment to it" {
            DoorParser.parseQuery(sent("""SELECT * FROM "db.user".t""")).shouldBeInstanceOf<SqlParse.Success>()
            DoorParser.parseQuery("SELECT * FROM db.user.t").shouldBeInstanceOf<SqlParse.Failure>()
            DoorParser.parseQuery("SELECT * FROM er.entity.shopper // db.dbo.x").shouldBeInstanceOf<SqlParse.Success>()
        }
    })
