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

class PreviewConditionsSpec :
    StringSpec({
        val table =
            Table(
                listOf(
                    ManifestColumn("id", "int"),
                    ManifestColumn("amount", "decimal"),
                    ManifestColumn("department", "text"),
                    ManifestColumn("enabled", "bool"),
                ),
                listOf(
                    listOf(10L, BigDecimal("4.560"), "Sales", true),
                    listOf(11L, BigDecimal("9.01"), null, false),
                    listOf(12L, null, "AR", null),
                    listOf(13L, BigDecimal("4.559999999999999999"), "Sales", true),
                    listOf(14L, BigDecimal("4.560000000000000001"), "Sales", false),
                ),
            )
        val bindings =
            mapOf(
                "threshold" to BoundValue("decimal", BigDecimal("4.560")),
                "unused_secret" to BoundValue("text", "must-not-forward"),
            )

        fun select(expression: String) = PreviewConditions.select(expression, table, bindings)

        "compiler predicates combine parameters, parentheses and SQL null tests" {
            select("amount >= :threshold and (department = \"Sales\" or department is null)") shouldBe setOf(0, 1, 4)
        }
        "unknown stays unknown through not and false or unknown does not select" {
            select("not (amount >= :threshold)") shouldBe setOf(3)
            select("department <> \"Sales\" or enabled") shouldBe setOf(0, 2, 3)
            select("amount = null") shouldBe emptySet()
        }
        "case, text ordering and membership use compiler lowering" {
            select(
                "case when department is null then false else department >= \"Sales\" end and id in (10, 13, 14)",
            ) shouldBe
                setOf(0, 3, 4)
            select("id not in (10, 11, 12, 13)") shouldBe setOf(4)
        }
        "decimal literals and numeric arithmetic retain differences beyond double precision" {
            select("amount > 4.560000000000000000") shouldBe setOf(1, 4)
            select("amount + 1.000000000000000001 > 5.56") shouldBe setOf(0, 1, 4)
        }
        "big integers are not rounded through double or narrowed to int32" {
            val large =
                Table(
                    listOf(ManifestColumn("id", "int")),
                    listOf(
                        listOf(9007199254740992L),
                        listOf(9007199254740993L),
                        listOf(Long.MAX_VALUE),
                    ),
                )
            PreviewConditions.select("id > 9007199254740992", large, emptyMap()) shouldBe setOf(1, 2)
            PreviewConditions.select("id < 9223372036854775808", large, emptyMap()) shouldBe setOf(0, 1, 2)
        }
        "constant predicates, all-null columns and empty routes retain a typed schema" {
            select("true") shouldBe (0..4).toSet()
            select("false") shouldBe emptySet()
            val nulls = Table(listOf(ManifestColumn("amount", "decimal")), listOf(listOf(null)))
            PreviewConditions.select("amount is null", nulls, emptyMap()) shouldBe setOf(0)
            PreviewConditions.select("amount > 1", table.copy(rows = emptyList()), emptyMap()) shouldBe emptySet()
        }
        "unknown names, invalid syntax, nonboolean roots, coercions and aggregate predicates refuse" {
            for (bad in listOf(
                "unknown > 1",
                "_au_condition_4 > 1",
                "amount > :missing",
                "amount / 0 > 1",
                "amount / 2 > 1",
                "amount >",
                "amount > 0; select 1",
                "amount = \"4.56\"",
                "amount in (\"4.56\")",
                "amount",
                "sum(amount) > 0",
                "read_csv(\"/tmp/x\") = 1",
            )) {
                val error = shouldThrow<AutomationException> { select(bad) }
                error.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
                error.message shouldBe "unsupported action condition"
            }
        }
        "invalid predicates also refuse on empty routes" {
            shouldThrow<AutomationException> {
                PreviewConditions.select("missing = 1", table.copy(rows = emptyList()), emptyMap())
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }
        "date casts compare typed route dates with the pinned as-of binding in UTC" {
            val dated =
                Table(
                    listOf(ManifestColumn("due", "date")),
                    listOf(
                        listOf(LocalDate.parse("2026-10-23")),
                        listOf(LocalDate.parse("2026-10-24")),
                        listOf(null),
                    ),
                )
            val clock = mapOf("as_of" to BoundValue("datetime", Instant.parse("2026-10-24T12:00:00Z")))
            PreviewConditions.select("due < cast(:as_of as date)", dated, clock) shouldBe setOf(0)
        }
        "binding text stays data and failures do not disclose its contents" {
            val text = "Sales'; select read_csv('/secret'); -- :secret ?"
            val input = Table(listOf(ManifestColumn("name", "text")), listOf(listOf(text), listOf("Sales")))
            val values = mapOf("name" to BoundValue("text", text))
            PreviewConditions.select("name = :name", input, values) shouldBe setOf(0)
            shouldThrow<AutomationException> {
                PreviewConditions.select("name = 1", input, values)
            }.message shouldBe "unsupported action condition"
        }

        "unreferenced route columns do not participate in predicate execution" {
            val wide =
                Table(
                    table.columns + ManifestColumn("opaque", "object"),
                    table.rows.map {
                        it +
                            mapOf("secret" to 1)
                    },
                )
            PreviewConditions.select("id = 10", wide, bindings) shouldBe setOf(0)
        }

        "internal column names cannot shadow authored route columns" {
            val collision = Table(listOf(ManifestColumn("_au_condition_1", "int")), listOf(listOf(7L), listOf(8L)))
            PreviewConditions.select("_au_condition_1 = 8", collision, emptyMap()) shouldBe setOf(1)
        }
    })
