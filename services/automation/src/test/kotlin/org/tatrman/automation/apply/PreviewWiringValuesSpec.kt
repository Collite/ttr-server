// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.bundle.legacyPreviewMetadata
import org.tatrman.automation.runner.BoundValue
import org.tatrman.automation.runner.Table
import org.tatrman.ttr.metadata.model.AutomationDuration
import java.time.LocalDate

class PreviewWiringValuesSpec :
    StringSpec({
        val ask = legacyPreviewMetadata(BundleFixture.load()).actions.getValue("ask")
        val table =
            Table(
                listOf(ManifestColumn("id", "bigint"), ManifestColumn("label", "text")),
                listOf(listOf(9007199254740993L, "purchasing")),
            )

        "deferred call leaves use typed compiler projection without losing large integers" {
            val result =
                PreviewWiringValues
                    .project(
                        mapOf("action" to "later { value: id, label: concat(label, '-batch') }"),
                        ask,
                        table,
                        emptyMap(),
                        roleNames = emptySet(),
                    ).single()
            val call = result.getValue("action").jsonObject
            call["deferred"] shouldBe JsonPrimitive(true)
            call["action"] shouldBe JsonPrimitive("later")
            call
                .getValue("arguments")
                .jsonObject["value"]!!
                .jsonPrimitive.long shouldBe 9007199254740993L
            call.getValue("arguments").jsonObject["label"] shouldBe JsonPrimitive("purchasing-batch")
        }

        "relative durations retain every published unit and exact pinned parameter counts" {
            for (unit in listOf("days", "working_days", "weeks", "months", "minutes", "hours")) {
                for ((source, bindings, amount) in listOf(
                    Triple("2 $unit", emptyMap(), 2L),
                    Triple("$unit(:count)", mapOf("count" to BoundValue("int", 9007199254740993L)), 9007199254740993L),
                )) {
                    val result =
                        PreviewWiringValues
                            .project(
                                mapOf("due" to source),
                                ask,
                                table,
                                bindings,
                                roleNames = emptySet(),
                            ).single()
                    result["due"] shouldBe
                        buildJsonObject {
                            put("amount", amount)
                            put("unit", unit)
                        }
                    PreviewWiringValues.display(result.getValue("due")) shouldBe "$amount $unit"
                }
            }
        }

        "on-due intent supports nested calls but never dispatches them" {
            val result =
                PreviewWiringValues
                    .project(
                        mapOf("on_due" to "later { action: start { process: label }, due: 2 working_days }"),
                        ask,
                        table,
                        emptyMap(),
                        roleNames = emptySet(),
                    ).single()
                    .getValue("on_due")
            PreviewWiringValues.display(result) shouldBe
                "later { action: start { process: \"purchasing\" }, due: 2 working_days }"
        }

        "invalid wiring shapes and references refuse even over empty selections without exposing source data" {
            val bad =
                listOf(
                    "start { process: \"private one\", process: \"private two\" }",
                    "start { process: :private_missing }",
                    "start { rows: lines }",
                    "start { detail: lines }",
                    "start { process: a, b }",
                    "start {} } } actions { route.value -> target { value: start {}",
                    "2 working_days",
                )
            for (source in bad) {
                val error =
                    shouldThrow<AutomationException> {
                        PreviewWiringValues.project(
                            mapOf("action" to source),
                            ask,
                            table.copy(rows = emptyList()),
                            emptyMap(),
                            roleNames = emptySet(),
                        )
                    }
                error.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
                error.message shouldBe "unsupported action expression"
            }
        }

        "duration parameters cannot truncate null fractional wrong-kind negative or overflowing counts" {
            for (bound in listOf(
                BoundValue("int", null),
                BoundValue("int", 1.5),
                BoundValue("decimal", 2L),
                BoundValue("int", -1L),
            )) {
                shouldThrow<AutomationException> {
                    PreviewWiringValues.project(
                        mapOf("due" to "working_days(:count)"),
                        ask,
                        table.copy(rows = emptyList()),
                        mapOf(
                            "count" to bound,
                        ),
                        roleNames = emptySet(),
                    )
                }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
            }
            shouldThrow<AutomationException> {
                PreviewWiringValues.project(
                    mapOf("due" to "9223372036854775808 working_days"),
                    ask,
                    table,
                    emptyMap(),
                    roleNames = emptySet(),
                )
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }

        "unselected nested scalar casts validate but never execute and excessive call nesting refuses" {
            PreviewWiringValues.project(
                mapOf("action" to "start { process: cast('private' as int) }"),
                ask,
                table.copy(rows = emptyList()),
                emptyMap(),
                roleNames = emptySet(),
            ) shouldBe
                emptyList()
            val nested = (1..18).fold("start {}") { source, _ -> "later { action: $source }" }
            shouldThrow<AutomationException> {
                PreviewWiringValues.project(
                    mapOf("action" to nested),
                    ask,
                    table.copy(rows = emptyList()),
                    emptyMap(),
                    roleNames = emptySet(),
                )
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }

        "nested context and candidate names refuse at every depth while root and non-context columns project" {
            val roles = setOf("on_hand", "weekly_demand", "candidates")
            val route = Table(listOf(ManifestColumn("on_hand", "int")), listOf(listOf(2)))
            for ((name, source) in listOf(
                "action" to "escalate { lines: on_hand }",
                "action" to "escalate { items: candidates }",
                "on_due" to "hold { next: escalate { items: weekly_demand } }",
                "action" to "start { next: escalate { items: on_hand } }",
            )) {
                for (rows in listOf(route, route.copy(rows = emptyList()))) {
                    shouldThrow<AutomationException> {
                        PreviewWiringValues.project(mapOf(name to source), ask, rows, emptyMap(), roleNames = roles)
                    }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
                }
            }
            PreviewWiringValues
                .project(
                    mapOf("on_hand" to "on_hand"),
                    ask,
                    route,
                    emptyMap(),
                    roleNames = roles,
                ).single()["on_hand"] shouldBe
                JsonPrimitive(2)
            val safe =
                PreviewWiringValues
                    .project(
                        mapOf("action" to "start { process: \"purchasing\", quantity: id }"),
                        ask,
                        table,
                        emptyMap(),
                        roleNames = roles,
                    ).single()
                    .getValue("action")
                    .jsonObject
                    .getValue("arguments")
                    .jsonObject
            safe["quantity"] shouldBe JsonPrimitive(9007199254740993L)
        }

        "pinned due defaults equal explicit evidence with presence wins and declared-column protection" {
            val action = ask.copy(due = AutomationDuration(2.0, "working_days"))
            val implicit =
                PreviewWiringValues.project(
                    emptyMap(),
                    action,
                    table,
                    emptyMap(),
                    roleNames = emptySet(),
                )
            implicit shouldBe
                PreviewWiringValues.project(
                    mapOf("due" to "2 working_days"),
                    action,
                    table,
                    emptyMap(),
                    roleNames = emptySet(),
                )
            implicit.single()["due"] shouldBe
                buildJsonObject {
                    put("amount", 2)
                    put("unit", "working_days")
                }
            PreviewWiringValues.project(
                emptyMap(),
                action,
                table,
                emptyMap(),
                roleNames = emptySet(),
            ) shouldBe implicit
            PreviewWiringValues
                .project(
                    mapOf("due" to "5 days"),
                    action,
                    table,
                    emptyMap(),
                    roleNames = emptySet(),
                ).single()["due"] shouldBe
                buildJsonObject {
                    put("amount", 5)
                    put("unit", "days")
                }
            PreviewWiringValues
                .project(
                    mapOf("due" to "null"),
                    action,
                    table,
                    emptyMap(),
                    roleNames = emptySet(),
                ).single()["due"] shouldBe
                JsonNull
            PreviewWiringValues
                .project(
                    emptyMap(),
                    ask.copy(due = null),
                    table,
                    emptyMap(),
                    roleNames = emptySet(),
                ).single()
                .containsKey("due") shouldBe
                false
            val declared =
                action.copy(
                    columns =
                        action.columns + action.columns.first().copy(name = "due", type = "text"),
                )
            PreviewWiringValues
                .project(
                    emptyMap(),
                    declared,
                    table,
                    emptyMap(),
                    roleNames = emptySet(),
                ).single()
                .containsKey("due") shouldBe
                false
        }

        "invalid default durations refuse even empty and exact integral bounds remain valid" {
            for ((amount, unit) in listOf(
                2.5 to "days",
                -1.0 to "days",
                Double.NaN to "days",
                Double.POSITIVE_INFINITY to "days",
                Long.MAX_VALUE.toDouble() to "days",
                2.0 to "unknown",
            )) {
                val action = ask.copy(due = AutomationDuration(amount, unit))
                shouldThrow<AutomationException> {
                    PreviewWiringValues.project(
                        emptyMap(),
                        action,
                        table.copy(rows = emptyList()),
                        emptyMap(),
                        roleNames = emptySet(),
                    )
                }.code shouldBe
                    ErrorCode.PREVIEW_UNSUPPORTED
                PreviewWiringValues
                    .project(
                        mapOf("due" to "null"),
                        action,
                        table,
                        emptyMap(),
                        roleNames = emptySet(),
                    ).single()["due"] shouldBe
                    JsonNull
            }
            for (amount in listOf(0.0, Math.nextDown(Long.MAX_VALUE.toDouble()))) {
                PreviewWiringValues
                    .project(
                        emptyMap(),
                        ask.copy(due = AutomationDuration(amount, "days")),
                        table,
                        emptyMap(),
                        roleNames = emptySet(),
                    ).single()
                    .getValue("due")
                    .jsonObject
                    .getValue("amount")
                    .jsonPrimitive.long shouldBe
                    amount.toLong()
            }
        }

        "nested literals retain original backslashes and code-point spans after emoji" {
            val literal = "\"a\\\\b\""
            val values =
                PreviewWiringValues
                    .project(
                        mapOf(
                            "about" to literal,
                            "action" to "start { first: \"😀\", path: $literal, last: \"c\\d\" }",
                        ),
                        ask,
                        table,
                        emptyMap(),
                        roleNames = emptySet(),
                    ).single()
            val args =
                values
                    .getValue("action")
                    .jsonObject
                    .getValue("arguments")
                    .jsonObject
            args["path"] shouldBe values["about"]
            args["first"] shouldBe JsonPrimitive("😀")
            args["last"] shouldBe JsonPrimitive("c\\d")
        }

        "argumentless intent renders without doubled spaces" {
            val value =
                PreviewWiringValues
                    .project(
                        mapOf("action" to "start {}"),
                        ask,
                        table,
                        emptyMap(),
                        roleNames = emptySet(),
                    ).single()
                    .getValue("action")
            PreviewWiringValues.display(value) shouldBe "start {}"
        }

        "explicit wiring properties refuse wrong AST kinds even with defaults and empty selections" {
            val action = ask.copy(due = AutomationDuration(2.0, "working_days"))
            val route = Table(listOf(ManifestColumn("on_hand", "int")), listOf(listOf(2)))
            for ((name, source) in listOf(
                "due" to "on_hand",
                "due" to "\"2 days\"",
                "due" to "2",
                "due" to "true",
                "due" to "hold {}",
                "on_due" to "hold",
                "on_due" to "weekly_demand",
                "on_due" to "2 days",
            )) {
                for (selected in listOf(route, route.copy(rows = emptyList()))) {
                    val error =
                        shouldThrow<AutomationException> {
                            PreviewWiringValues.project(
                                mapOf(name to source),
                                action,
                                selected,
                                emptyMap(),
                                roleNames = setOf("on_hand", "weekly_demand"),
                            )
                        }
                    error.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
                    error.message shouldBe "unsupported action expression"
                }
            }
        }

        "null properties and declared date and text columns retain their own paths" {
            val action = ask.copy(due = AutomationDuration(2.0, "working_days"))
            val nulls =
                PreviewWiringValues
                    .project(
                        mapOf("due" to "null", "on_due" to "null"),
                        action,
                        table,
                        emptyMap(),
                        roleNames = emptySet(),
                    ).single()
            nulls["due"] shouldBe JsonNull
            nulls["on_due"] shouldBe JsonNull
            val dated =
                Table(
                    listOf(ManifestColumn("date_value", "date"), ManifestColumn("on_hand", "int")),
                    listOf(listOf(LocalDate.parse("2026-11-03"), 2)),
                )
            val declared =
                action.copy(
                    columns =
                        action.columns +
                            listOf(
                                action.columns.first().copy(name = "due", type = "date"),
                                action.columns.first().copy(name = "on_due", type = "text"),
                            ),
                )
            val result =
                PreviewWiringValues
                    .project(
                        mapOf(
                            "due" to "date_value",
                            "on_due" to "\"hold\"",
                            "group_by" to "on_hand",
                        ),
                        declared,
                        dated,
                        emptyMap(),
                        roleNames = setOf("on_hand"),
                    ).single()
            result["due"] shouldBe JsonPrimitive("2026-11-03")
            result["on_due"] shouldBe JsonPrimitive("hold")
            result["group_by"] shouldBe JsonPrimitive(2)
        }
    })
