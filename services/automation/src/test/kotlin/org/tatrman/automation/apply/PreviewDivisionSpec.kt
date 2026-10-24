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

class PreviewDivisionSpec :
    StringSpec({
        fun input(vararg rows: List<Any?>) =
            Table(listOf(ManifestColumn("n", "float"), ManifestColumn("d", "float")), rows.toList())

        fun project(
            expression: String,
            table: Table,
        ) = PreviewExpressions.project(mapOf("value" to expression), table, emptyMap()).rows.map { it.single() }

        fun refuses(
            expression: String,
            table: Table,
        ) {
            val error = shouldThrow<AutomationException> { project(expression, table) }
            error.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
            error.message shouldBe "unsupported action expression"
        }

        "ordinary integer and fractional division use true division in route order" {
            val table = input(listOf(5.0, 2.0), listOf(-6.0, 2.0), listOf(3.0, -2.0))
            project("n / d", table) shouldBe listOf(2.5, -3.0, -1.5)
            val integers = Table(listOf(ManifestColumn("n", "int")), listOf(listOf(5L), listOf(7L)))
            project("n / 2", integers) shouldBe listOf(2.5, 3.5)
        }

        "compiler division guards apply before predicate filtering including null-safe divisors" {
            val table = input(listOf(5.0, 2.0), listOf(1.0, 2.0), listOf(4.0, null))
            PreviewConditions.select("n / d > 1", table, emptyMap()) shouldBe setOf(0)
            PreviewConditions.select("n / d is null", table, emptyMap()) shouldBe setOf(2)
            val zero = input(listOf(5.0, 2.0), listOf(1.0, 0.0))
            shouldThrow<AutomationException> {
                PreviewConditions.select("n / d > 1", zero, emptyMap())
            }.message shouldBe "unsupported action condition"
        }

        "null operands succeed but a null numerator does not excuse a zero divisor" {
            project("n / d", input(listOf(null, 2.0), listOf(3.0, null), listOf(null, null))) shouldBe
                listOf(null, null, null)
            refuses("n / d", input(listOf(null, 0.0)))
        }

        "positive negative and computed zero divisors refuse before a result is accepted" {
            for (zero in listOf(0.0, -0.0)) refuses("n / d", input(listOf(1.0, zero)))
            refuses("n / (d - d)", input(listOf(1.0, 3.0)))
            refuses("n / cast(0 as float)", input(listOf(1.0, 3.0)))
        }

        "nested divisions validate dependencies before evaluating outer denominators" {
            project("n / (d / cast(2 as float))", input(listOf(9.0, 3.0))) shouldBe listOf(6.0)
            project("(n / d) / cast(2 as float)", input(listOf(9.0, 3.0))) shouldBe listOf(1.5)
            refuses("n / (d / cast(0 as float))", input(listOf(9.0, 3.0)))
            refuses("n / (d / cast(2 as float))", input(listOf(9.0, 0.0)))
            refuses("(n / d) / cast(2 as float)", input(listOf(9.0, 0.0)))
        }

        "case boolean and casts cannot hide a rejected division" {
            val zero = input(listOf(1.0, 0.0))
            refuses("case when false then n / d else cast(1 as float) end", zero)
            refuses("cast(n / d as text)", zero)
            shouldThrow<AutomationException> {
                PreviewConditions.select("true or n / d > 1", zero, emptyMap())
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }

        "nonfinite divisor and overflowed quotient refuse even inside predicates and cases" {
            val overflow = input(listOf(Double.MAX_VALUE, 0.5))
            refuses("n / d", overflow)
            refuses("case when n / d > 1 then 1 else 0 end", overflow)
            shouldThrow<AutomationException> {
                PreviewConditions.select("n / d > 1", overflow, emptyMap())
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
            for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                refuses("n / d", input(listOf(1.0, bad)))
            }
            refuses("n / (d * d)", input(listOf(1.0, Double.MAX_VALUE)))
        }

        "multiple expressions retain independent guards and parameter values" {
            val table = input(listOf(9.0, 3.0))
            PreviewExpressions
                .project(
                    linkedMapOf("first" to "n / d", "second" to "n / :divisor"),
                    table,
                    mapOf("divisor" to BoundValue("float", 2.0)),
                ).rows shouldBe listOf(listOf(3.0, 4.5))
            shouldThrow<AutomationException> {
                PreviewExpressions.project(
                    linkedMapOf("first" to "n / d", "second" to "n / :divisor"),
                    table,
                    mapOf("divisor" to BoundValue("float", 0.0)),
                )
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }

        "empty routes validate types and names without inventing rows or rejected values" {
            val empty = input()
            project("n / d", empty) shouldBe emptyList()
            project("n / cast(0 as float)", empty) shouldBe emptyList()
            refuses("n / 2", empty) // Compiler requires an explicit float/integer cast.
            refuses("n / missing", empty)
            refuses("n / \"secret\"", empty)
        }

        "source names that match guard aliases retain their own values" {
            val table = Table(listOf(ManifestColumn("_au_value_0", "int")), listOf(listOf(8L)))
            project("_au_value_0 / 2", table) shouldBe listOf(4.0)
        }
    })
