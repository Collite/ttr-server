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
                    val result = PreviewWiringValues.project(mapOf("due" to source), ask, table, bindings).single()
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
                    )
                }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
            }
            shouldThrow<AutomationException> {
                PreviewWiringValues.project(mapOf("due" to "9223372036854775808 working_days"), ask, table, emptyMap())
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }

        "unselected nested scalar casts validate but never execute and excessive call nesting refuses" {
            PreviewWiringValues.project(
                mapOf("action" to "start { process: cast('private' as int) }"),
                ask,
                table.copy(rows = emptyList()),
                emptyMap(),
            ) shouldBe
                emptyList()
            val nested = (1..18).fold("start {}") { source, _ -> "later { action: $source }" }
            shouldThrow<AutomationException> {
                PreviewWiringValues.project(mapOf("action" to nested), ask, table.copy(rows = emptyList()), emptyMap())
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }
    })
