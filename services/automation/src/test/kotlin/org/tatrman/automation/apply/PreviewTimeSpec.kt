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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

class PreviewTimeSpec :
    StringSpec({
        val input =
            Table(
                listOf(ManifestColumn("due", "date")),
                listOf(
                    listOf(LocalDate.parse("2026-09-23")),
                    listOf(LocalDate.parse("2026-09-24")),
                    listOf(null),
                ),
            )
        val values =
            mapOf(
                "as_of" to BoundValue("datetime", Instant.parse("2026-10-24T12:30:00Z")),
                "lookback" to BoundValue("days", 30L),
                "count" to BoundValue("int", 2L),
            )

        fun project(
            expression: String,
            bindings: Map<String, BoundValue> = values,
            table: Table = input,
        ) = PreviewExpressions.project(mapOf("value" to expression), table, bindings)

        fun day(
            expression: String,
            bindings: Map<String, BoundValue> = values,
        ) = project("cast($expression as date)", bindings).rows.first().single()

        "compiler lowers bound durations in predicates without calendar or Query reads" {
            PreviewConditions.select("due >= cast(:as_of - days(30) as date)", input, values) shouldBe setOf(1)
            PreviewConditions.select("due >= cast(:as_of - :lookback as date)", input, values) shouldBe setOf(1)
        }
        "literal and parameter duration spellings resolve against pinned values" {
            day(":as_of - days(:lookback)") shouldBe LocalDate.parse("2026-09-24")
            day(":as_of - days(30)") shouldBe LocalDate.parse("2026-09-24")
            day(":as_of + weeks(:count)") shouldBe LocalDate.parse("2026-11-07")
            day(":as_of - :count") shouldBe LocalDate.parse("2026-10-22")
            day(":as_of - days(:work)", values + ("work" to BoundValue("working_days", 30L))) shouldBe
                LocalDate.parse("2026-09-24")
        }
        "nested derived dates support different units and original argument order" {
            val result =
                PreviewExpressions.project(
                    linkedMapOf(
                        "month" to "cast(:as_of + months(1) as date)",
                        "nested" to "cast((:as_of - days(30)) + weeks(1) as date)",
                        "minute" to ":as_of + minutes(15)",
                    ),
                    input,
                    values,
                )
            result.columns.map { it.name } shouldBe listOf("month", "nested", "minute")
            result.rows.first() shouldBe
                listOf(
                    LocalDate.parse("2026-11-24"),
                    LocalDate.parse("2026-10-01"),
                    OffsetDateTime.parse("2026-10-24T12:45:00Z"),
                )
        }
        "month boundaries follow UTC calendar arithmetic with no wall clock or DST dependency" {
            val dates = mapOf("as_of" to BoundValue("datetime", Instant.parse("2024-03-31T00:30:00Z")))
            day(":as_of - months(1)", dates) shouldBe LocalDate.parse("2024-02-29")
            project(":as_of + hours(24)", dates).rows.first().single() shouldBe
                OffsetDateTime.parse("2024-04-01T00:30:00Z")
        }
        "typed date parameters remain dates and subday arithmetic yields timestamps" {
            val dates = mapOf("start" to BoundValue("date", LocalDate.parse("2024-01-31")))
            project(":start + months(1)", dates).rows.first().single() shouldBe LocalDate.parse("2024-02-29")
            project(":start + hours(2)", dates).rows.first().single() shouldBe
                LocalDateTime.parse("2024-01-31T02:00:00")
        }
        "local timestamp and offset timestamp bindings preserve their typed UTC values" {
            val dates =
                mapOf(
                    "local" to BoundValue("timestamp", LocalDateTime.parse("2026-10-24T12:00:00")),
                    "offset" to BoundValue("timestamptz", OffsetDateTime.parse("2026-10-24T14:00:00+02:00")),
                )
            project(":local + minutes(5)", dates).rows.first().single() shouldBe
                LocalDateTime.parse("2026-10-24T12:05:00")
            project(":offset - hours(1)", dates).rows.first().single() shouldBe
                OffsetDateTime.parse("2026-10-24T11:00:00Z")
        }
        "null bound dates propagate without losing the static result type" {
            val dates = mapOf("start" to BoundValue("date", null))
            val result = project(":start + hours(1)", dates)
            result.rows shouldBe List(3) { listOf(null) }
            result.columns.single().type shouldBe "timestamp"
        }
        "numeric parameter arithmetic remains numeric and duration counts can be projected" {
            project(":count + :count").rows shouldBe List(3) { listOf(4L) }
            project(":lookback").rows shouldBe List(3) { listOf(30L) }
        }
        "authored bindings are never overwritten by compiler-derived or private names" {
            val bindings =
                values +
                    mapOf(
                        "as_of_minus_30_days" to BoundValue("text", "authored"),
                        "_au_date_0" to BoundValue("text", "private-looking"),
                    )
            val result =
                PreviewExpressions.project(
                    linkedMapOf(
                        "date" to "cast(:as_of - days(30) as date)",
                        "authored" to ":as_of_minus_30_days",
                        "private" to ":_au_date_0",
                    ),
                    input,
                    bindings,
                )
            result.rows.first() shouldBe listOf(LocalDate.parse("2026-09-24"), "authored", "private-looking")
        }
        "generated names cannot make unknown authored references resolve" {
            for (name in listOf("as_of_minus_30_days", "_au_date_0")) {
                shouldThrow<AutomationException> {
                    PreviewExpressions.project(
                        linkedMapOf("date" to ":as_of - days(30)", "missing" to ":$name"),
                        input,
                        values,
                    )
                }.message shouldBe "unsupported action expression"
            }
        }
        "working days calendar reads column date arithmetic and invalid durations still refuse" {
            for (expression in listOf(
                ":as_of - 30 days",
                ":as_of - working_days(2)",
                "due + days(1)",
                "working_days(between: due, and: :as_of)",
                ":as_of - days(:missing)",
                ":as_of + days(:as_of)",
                ":as_of + days(1.5)",
                ":as_of + minutes(9223372036854775807)",
                ":as_of + :as_of",
            )) {
                shouldThrow<AutomationException> { project(expression) }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
            }
        }
        "empty routes still validate bound durations and unknown references" {
            project(":as_of - days(30)", table = input.copy(rows = emptyList())).rows shouldBe emptyList()
            shouldThrow<AutomationException> {
                project(":as_of - days(:missing)", table = input.copy(rows = emptyList()))
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }
    })
