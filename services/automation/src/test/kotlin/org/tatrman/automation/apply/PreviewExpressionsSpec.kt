// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.runner.BoundValue
import org.tatrman.automation.runner.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class PreviewExpressionsSpec :
    StringSpec({
        val input =
            Table(
                listOf(
                    ManifestColumn("id", "int"),
                    ManifestColumn("amount", "decimal"),
                    ManifestColumn("name", "text"),
                ),
                listOf(
                    listOf(9007199254740993L, BigDecimal("4.560000000000000001"), "Sales"),
                    listOf(2L, null, null),
                    listOf(1L, BigDecimal("1.00"), "AR"),
                ),
            )
        val bindings = mapOf("increment" to BoundValue("decimal", BigDecimal("1.000000000000000001")))

        fun project(vararg expressions: Pair<String, String>) =
            PreviewExpressions.project(linkedMapOf(*expressions), input, bindings)

        "scalar projections retain authored argument order, route order, exact numbers and nulls" {
            val result = project("key" to "id", "sum" to "amount + :increment", "label" to "name")
            result.columns.map { it.name } shouldBe listOf("key", "sum", "label")
            result.rows.map { it[0] } shouldBe listOf(9007199254740993L, 2L, 1L)
            (result.rows[0][1] as BigDecimal).compareTo(BigDecimal("5.560000000000000002")) shouldBe 0
            result.rows[1] shouldBe listOf(2L, null, null)
        }
        "case expressions and boolean null tests are scalar values" {
            val result =
                project(
                    "label" to "case when name is null then \"missing\" else name end",
                    "missing" to "amount is null",
                )
            result.rows shouldBe listOf(listOf("Sales", false), listOf("missing", true), listOf("AR", false))
        }
        "subtraction multiplication and explicit text casts use compiler semantics" {
            val result = project("count" to "(id - 1) * 2", "label" to "cast(id as text)")
            result.rows.first() shouldBe listOf(18014398509481984L, "9007199254740993")
            result.rows.last() shouldBe listOf(0L, "1")
        }
        "constant literals and nulls repeat once per route row without double conversion" {
            val result =
                project(
                    "large" to "9223372036854775808",
                    "fraction" to "4.560000000000000001",
                    "none" to "null",
                )
            result.rows.size shouldBe 3
            result.rows.forEach { row ->
                (row[0] as BigDecimal).compareTo(BigDecimal("9223372036854775808")) shouldBe 0
                (row[1] as BigDecimal).compareTo(BigDecimal("4.560000000000000001")) shouldBe 0
                row[2] shouldBe null
            }
        }
        "date expressions use pinned UTC as-of without consulting the clock" {
            val result =
                PreviewExpressions.project(
                    mapOf("day" to "cast(:as_of as date)"),
                    input,
                    mapOf("as_of" to BoundValue("datetime", Instant.parse("2026-10-24T23:30:00Z"))),
                )
            result.rows shouldBe List(3) { listOf(LocalDate.parse("2026-10-24")) }
        }
        "invalid syntax types names functions aggregates and division refuse without bound data" {
            for (expression in listOf(
                "missing",
                "_au_condition_3 + 1",
                ":missing + 1",
                "amount +",
                "id; select 1",
                "name + 1",
                "sum(id)",
                "read_csv(\"/secret\")",
                "amount / 0",
                "id = name",
            )) {
                val error = shouldThrow<AutomationException> { project("value" to expression) }
                error.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
                error.message shouldBe "unsupported action expression"
            }
        }
        "execution errors and non-finite scalar results refuse without leaking data" {
            shouldThrow<AutomationException> { project("value" to "cast(name as int)") }.message shouldBe
                "unsupported action expression"
            val floats = Table(listOf(ManifestColumn("value", "float")), listOf(listOf(Double.MAX_VALUE)))
            shouldThrow<AutomationException> {
                PreviewExpressions.project(mapOf("value" to "value * value"), floats, emptyMap())
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }

        "empty routes still validate scalar expressions and retain result schema" {
            val empty = input.copy(rows = emptyList())
            val result = PreviewExpressions.project(mapOf("value" to "amount + 1"), empty, bindings)
            result.rows shouldBe emptyList()
            result.columns.single().name shouldBe "value"
            shouldThrow<AutomationException> {
                PreviewExpressions.project(mapOf("value" to "unknown"), empty, bindings)
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }
        "bound SQL-shaped text stays data and unreferenced opaque columns stay outside DuckDB" {
            val text = "Sales'; select read_csv('/secret'); -- ? :other"
            val opaque =
                input.copy(
                    columns = input.columns + ManifestColumn("opaque", "object"),
                    rows =
                        input.rows.map {
                            it +
                                mapOf("secret" to 1)
                        },
                )
            PreviewExpressions
                .project(
                    mapOf("value" to ":text"),
                    opaque,
                    mapOf("text" to BoundValue("text", text)),
                ).rows shouldBe
                List(3) { listOf(text) }
        }
        "no scalar arguments still retain one empty argument record per selected row" {
            project().rows shouldBe List(3) { emptyList() }
        }
        "generated projection aliases do not mask source columns or reorder rows" {
            val collision = Table(listOf(ManifestColumn("_au_value_0", "int")), listOf(listOf(8L), listOf(7L)))
            PreviewExpressions.project(mapOf("key" to "_au_value_0 + 1"), collision, emptyMap()).rows shouldBe
                listOf(listOf(9L), listOf(8L))
        }
    })
