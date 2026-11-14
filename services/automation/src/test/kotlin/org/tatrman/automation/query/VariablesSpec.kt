// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.runner.Table
import java.math.BigDecimal
import java.time.LocalDate

class VariablesSpec :
    StringSpec({
        val frame =
            Table(
                listOf(
                    ManifestColumn("order_number", "int"),
                    ManifestColumn("název", "text"),
                    ManifestColumn("c", "decimal"),
                    ManifestColumn("due", "date"),
                ),
                listOf(
                    listOf(1L, "a", BigDecimal("2.345"), LocalDate.parse("2026-11-02")),
                    listOf(2L, "b", null, LocalDate.parse("2026-11-01")),
                    listOf(3L, "b", BigDecimal("-1.005"), null),
                ),
            )
        val one = frame.copy(rows = frame.rows.take(1))

        fun spec(text: String) = Variables.parse(Json.parseToJsonElement(text).jsonObject)

        fun derive(
            text: String,
            on: Table? = frame,
            truncated: Boolean = false,
            verdict: QueryVerdict = QueryVerdict.ANSWERED,
            max: Int = 500,
        ): JsonObject = Variables.derive(spec(text), on, truncated, verdict, max).values

        fun code(block: () -> Unit) = shouldThrow<AutomationException>(block).code

        "the T1 set answers beside the frame with §1's JSON types" {
            val v =
                derive(
                    """{"n":"row_count","any":"exists","total":{"kind":"aggregate","column":"c","fn":"sum"},
                       "all":{"kind":"table","columns":["order_number","due"]},"v":"verdict"}""",
                )
            v.toString() shouldBe
                """{"n":3,"any":true,"total":1.34,"all":[{"order_number":1,"due":"2026-11-02"},""" +
                """{"order_number":2,"due":"2026-11-01"},{"order_number":3,"due":null}],"v":"answered"}"""
            derive("""{"first":"row"}""", one).toString() shouldBe
                """{"order_number":1,"nazev":"a","c":2.345,"due":"2026-11-02"}"""
            derive("""{"first":{"kind":"row","prefix":"o_","columns":["c"]}}""", one).toString() shouldBe
                """{"o_c":2.345}"""
        }

        "aggregates round half away from zero; null_is_zero and empty frames follow golden" {
            derive("""{"a":{"kind":"aggregate","column":"c","round":2}}""")["a"].toString() shouldBe "1.34"
            derive("""{"a":{"kind":"aggregate","column":"c","fn":"min","round":2}}""")["a"].toString() shouldBe "-1.01"
            derive("""{"a":{"kind":"aggregate","column":"c","fn":"avg"}}""")["a"].toString() shouldBe "0.67"
            derive(
                """{"a":{"kind":"aggregate","column":"c","fn":"avg","null_is_zero":true}}""",
            )["a"].toString() shouldBe
                "0.4466666666666667"
            derive("""{"a":{"kind":"aggregate","column":"c","fn":"count"}}""")["a"].toString() shouldBe "2"
            derive("""{"a":{"kind":"aggregate","column":"order_number","fn":"max"}}""")["a"].toString() shouldBe "3"
            derive("""{"a":{"kind":"aggregate","column":"due","fn":"max"}}""")["a"].toString() shouldBe "\"2026-11-02\""
            val empty = frame.copy(rows = emptyList())
            derive("""{"a":{"kind":"aggregate","column":"c"}}""", empty)["a"] shouldBe JsonNull
            derive("""{"a":{"kind":"aggregate","column":"c","null_is_zero":true}}""", empty)["a"].toString() shouldBe
                "0.0"
            derive("""{"a":{"kind":"aggregate","column":"c","fn":"count"}}""", empty)["a"].toString() shouldBe "0"
            code { derive("""{"a":{"kind":"aggregate","column":"název"}}""") } shouldBe ErrorCode.VARIABLE
        }

        "the protocol's exact copy keeps decimals as strings" {
            Variables
                .derive(
                    spec("""{"t":{"kind":"aggregate","column":"c"}}"""),
                    frame,
                    false,
                    QueryVerdict.ANSWERED,
                ).exact
                .toString() shouldBe """{"t":"1.340"}"""
        }

        "scalar, column and row_count distinct" {
            derive("""{"s":{"kind":"scalar","column":"c"}}""", one)["s"].toString() shouldBe "2.345"
            derive(
                """{"s":"scalar"}""",
                Table(listOf(ManifestColumn("x", "int")), listOf(listOf(7L))),
            )["s"].toString() shouldBe
                "7"
            derive("""{"s":{"kind":"scalar","column":"c"}}""", frame.copy(rows = emptyList()))["s"] shouldBe JsonNull
            derive("""{"k":{"kind":"column","column":"název"}}""")["k"].toString() shouldBe """["a","b","b"]"""
            derive("""{"k":{"kind":"column","column":"název","distinct":true}}""")["k"].toString() shouldBe
                """["a","b"]"""
            derive("""{"d":{"kind":"row_count","distinct":"název"}}""")["d"].toString() shouldBe "2"
        }

        "row over > 1 row is 422 row-not-single; table over max_table_rows is 422 variable-too-large" {
            code { derive("""{"first":"row"}""") } shouldBe ErrorCode.ROW_NOT_SINGLE
            code { derive("""{"s":{"kind":"scalar","column":"c"}}""") } shouldBe ErrorCode.ROW_NOT_SINGLE
            code { derive("""{"all":"table"}""", max = 2) } shouldBe ErrorCode.VARIABLE_TOO_LARGE
            derive("""{"all":"table"}""", max = 3)["all"].toString().startsWith("[{") shouldBe true
            // A counting kind refuses a frame that holds only the first rows of the answer; exists/row/verdict do not.
            code { derive("""{"n":"row_count"}""", truncated = true) } shouldBe ErrorCode.VARIABLE_TOO_LARGE
            derive("""{"any":"exists"}""", truncated = true)["any"].toString() shouldBe "true"
        }

        "a refusal or clarification answers the verdict and nulls every other variable" {
            val v =
                derive(
                    """{"n":"row_count","v":"verdict","r":{"kind":"row","columns":["c"]}}""",
                    on = null,
                    verdict = QueryVerdict.REFUSED,
                )
            v.toString() shouldBe """{"n":null,"v":"refused","c":null}"""
            derive("""{"v":"verdict"}""", on = null, verdict = QueryVerdict.CLARIFY)["v"].toString() shouldBe
                "\"clarify\""
        }

        "names match ^[a-z][a-z0-9_]*$; malformed specs are 400 variable" {
            code { spec("""{"Total":"row_count"}""") } shouldBe ErrorCode.VARIABLE_NAME
            code { spec("""{"1n":"row_count"}""") } shouldBe ErrorCode.VARIABLE_NAME
            code { derive("""{"order_number":"row_count","r":"row"}""", one) } shouldBe ErrorCode.VARIABLE_NAME
            code { spec("""{"n":"median"}""") } shouldBe ErrorCode.VARIABLE
            code { spec("""{"n":{"kind":"aggregate"}}""") } shouldBe ErrorCode.VARIABLE
            code { spec("""{"n":{"kind":"aggregate","column":"c","fn":"median"}}""") } shouldBe ErrorCode.VARIABLE
            code { spec("""{"n":{"kind":"row_count","round":2}}""") } shouldBe ErrorCode.VARIABLE
            code { spec("""{"n":{"kind":"row_count","colour":2}}""") } shouldBe ErrorCode.VARIABLE
            code { derive("""{"s":"scalar"}""", one) } shouldBe ErrorCode.VARIABLE
            code { derive("""{"k":{"kind":"column","column":"nope"}}""") } shouldBe ErrorCode.VARIABLE
            Variables.fold("Název_Ř 2") shouldBe "nazev_r_2"
        }
    })
