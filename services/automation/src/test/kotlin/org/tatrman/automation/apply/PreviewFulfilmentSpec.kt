// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.ApprovalFixture
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.runner.Table
import org.tatrman.ttr.metadata.model.AutomationColumn
import org.tatrman.ttrp.resolve.TargetResolution

class PreviewFulfilmentSpec :
    StringSpec({
        val bundle = BundleFixture.load(ApprovalFixture.copyTo(tempdir().toPath().resolve("branch"), fulfilment = true))
        val coverage = bundle.recipes.getValue("release_order").coverage
        coverage.refusal shouldBe null
        val metadata = PreviewMetadata(coverage.actions, bundle.entities, coverage.targets)
        val action = metadata.actions.getValue("fulfil")
        val pinned = metadata.targets.getValue("fulfil")
        val columns = listOf("id", "entity", "key", "kind", "state").map { ManifestColumn(it, "text") }
        val args =
            mapOf(
                "line" to JsonPrimitive(9007199254740993L),
                "kind" to JsonPrimitive("inbound"),
                "to" to JsonPrimitive("fulfilled"),
            )

        fun row(
            id: String = "ob-1",
            key: String = "{\"line_id\":9007199254740993}",
            state: String = "open",
        ) = listOf(id, "er.entity.return_request_line", key, "inbound", state)

        fun snapshot(vararg rows: List<Any?>) = PreviewFulfilment.Snapshot(Table(columns, rows.toList()))

        fun mapping() = PreviewFulfilment.resolve(action, metadata, pinned)

        "qualified fulfilment uses the exact verifier targets and incomplete pins cannot fall back" {
            val expected = mapping().transition(args, snapshot(row()), 7)!!
            expected.key shouldBe buildJsonObject { put("id", "ob-1") }
            expected.seq shouldBe 7
            for (pins in listOf(
                emptyMap(),
                pinned - "automation.obligation.state",
                pinned - "hartland.return_request_line.line_id",
            )) {
                shouldThrow<AutomationException> { PreviewFulfilment.resolve(action, metadata, pins) }.code shouldBe
                    ErrorCode.PREVIEW_UNSUPPORTED
            }
            val legacy =
                action.copy(
                    columns =
                        action.columns.map {
                            it.copy(type = it.type.removePrefix("hartland."))
                        },
                    transition = action.transition!!.copy(attribute = "obligation.state"),
                )
            PreviewFulfilment.resolve(legacy, metadata, null).transition(args, snapshot(row()), 7) shouldBe expected
        }

        "lookup uses exact stored key text and distinguishes adjacent numbers and strings" {
            for (spelling in listOf("1", "1.0", "1E0")) {
                mapping().identity(args + ("line" to Json.parseToJsonElement(spelling))).key shouldBe "{\"line_id\":1}"
            }
            for (key in listOf(
                "{\"line_id\":9007199254740993.0}",
                "{\"line_id\":9007199254740992}",
                "{\"line_id\":\"9007199254740993\"}",
                "{\"wrong\":9007199254740993}",
            )) {
                shouldThrow<AutomationException> { mapping().transition(args, snapshot(row(key = key)), 1) }
            }
        }

        "composite lookup canonicalizes arguments but requires exact stored field order" {
            val original = pinned.getValue("hartland.return_request_line.line_id").entity
            val tenant =
                original.attributes
                    .single {
                        it.isKey
                    }.copy(qname = original.qname.copy(name = "return_request_line.tenant"), type = "text")
            val entity = original.copy(attributes = original.attributes + tenant)
            val changed = metadata.copy(entities = metadata.entities.map { if (it == original) entity else it })
            val pins =
                pinned.mapValues { (_, value) ->
                    if (value.entity ==
                        original
                    ) {
                        value.copy(entity = entity)
                    } else {
                        value
                    }
                } +
                    ("hartland.return_request_line.tenant" to TargetResolution.Resolved(entity, tenant))
            val target =
                PreviewFulfilment.resolve(
                    action.copy(
                        columns =
                            (
                                action.columns +
                                    AutomationColumn("scope", "hartland.return_request_line.tenant")
                            ).reversed(),
                    ),
                    changed,
                    pins,
                )
            val key =
                buildJsonObject {
                    put("line_id", 9007199254740993L)
                    put("tenant", "a\"\\b")
                }.toString()
            target.transition(args + ("scope" to JsonPrimitive("a\"\\b")), snapshot(row(key = key)), 1)!!.key shouldBe
                buildJsonObject { put("id", "ob-1") }
            shouldThrow<AutomationException> { target.transition(args, snapshot(row(key = key)), 1) }
            val reversed =
                JsonObject(
                    Json
                        .parseToJsonElement(key)
                        .jsonObject
                        .toList()
                        .reversed()
                        .toMap(),
                ).toString()
            shouldThrow<AutomationException> {
                target.transition(args + ("scope" to JsonPrimitive("a\"\\b")), snapshot(row(key = reversed)), 1)
            }
        }

        "one active match wins over closed history while ambiguous active rows and absent rows refuse" {
            mapping().transition(args, snapshot(row(), row(id = "old", state = "fulfilled")), 1)!!.key shouldBe
                buildJsonObject { put("id", "ob-1") }
            mapping().transition(
                args,
                snapshot(row(state = "fulfilled"), row(id = "old", state = "fulfilled")),
                1,
            ) shouldBe
                null
            for (data in listOf(
                snapshot(),
                snapshot(row(), row(id = "other", state = "reopened")),
                snapshot(row(state = "cancelled")),
            )) {
                shouldThrow<AutomationException> { mapping().transition(args, data, 1) }
            }
            mapping().transition(args + ("to" to JsonPrimitive("open")), snapshot(row()), 1) shouldBe null
        }

        "cross-product rows never match another requested identity" {
            val data =
                snapshot(row(), listOf("other", "er.entity.return_request_line", "{\"line_id\":42}", "payment", "open"))
            data.find(
                PreviewFulfilment.Identity(
                    "er.entity.return_request_line",
                    "{\"line_id\":9007199254740993}",
                    "payment",
                ),
            ) shouldBe
                emptyList()
            data.find(
                PreviewFulfilment.Identity("er.entity.return_request_line", "{\"line_id\":42}", "inbound"),
            ) shouldBe
                emptyList()
        }

        "malformed cross-product history is ignored but malformed requested rows refuse" {
            val requested =
                setOf(
                    PreviewFulfilment.Identity(
                        "er.entity.return_request_line",
                        "{\"line_id\":9007199254740993}",
                        "inbound",
                    ),
                )
            val unrelated = listOf("old", "er.entity.return_request_line", "{\"line_id\":42}", "inbound", "unknown")
            val data = PreviewFulfilment.Snapshot(Table(columns, listOf(row(), unrelated)), requested)
            mapping().transition(args, data, 1)!!.key shouldBe buildJsonObject { put("id", "ob-1") }
            shouldThrow<AutomationException> {
                PreviewFulfilment.Snapshot(Table(columns, listOf(row(state = "unknown"))), requested)
            }
        }

        "malformed snapshots cannot supply a match" {
            val invalid =
                listOf(
                    Table(columns.reversed(), listOf(row())),
                    Table(columns.map { it.copy(type = "int") }, listOf(row())),
                    Table(columns, listOf(row().map { null })),
                    Table(columns, listOf(row(), row())),
                    Table(columns, listOf(row(key = "{}"))),
                    Table(columns, listOf(row(key = "{\"line_id\":2,\"line_id\":9007199254740993}"))),
                    Table(columns, listOf(row(key = "{\"line_id\":null}"))),
                    Table(columns, listOf(row(key = "{\"line_id\":{}}"))),
                    Table(columns, listOf(row(state = "unknown"))),
                )
            for (table in invalid) shouldThrow<AutomationException> { PreviewFulfilment.Snapshot(table) }
        }

        "mapping schema state constraints and values are validated before an effect" {
            for (changed in listOf(
                action.copy(set = metadata.actions.getValue("hold").set),
                action.copy(
                    columns =
                        action.columns + AutomationColumn("other", "text"),
                ),
                action.copy(
                    columns =
                        action.columns + action.columns.first(),
                ),
            )) {
                shouldThrow<AutomationException> { PreviewFulfilment.resolve(changed, metadata, pinned) }
            }
            val state = pinned.getValue("automation.obligation.state")
            val shadow = state.entity.copy(sourceFile = "/checkout/custom/obligation.ttrm")
            shouldThrow<AutomationException> {
                PreviewFulfilment.resolve(
                    action,
                    metadata.copy(
                        entities =
                            metadata.entities.map {
                                if (it ==
                                    state.entity
                                ) {
                                    shadow
                                } else {
                                    it
                                }
                            },
                    ),
                    pinned,
                )
            }
            for (bad in listOf(
                args + ("to" to JsonPrimitive("unknown")),
                args + ("kind" to JsonPrimitive("")),
                args + ("line" to JsonNull),
                args + ("to" to JsonNull),
            )) {
                shouldThrow<AutomationException> { mapping().transition(bad, snapshot(row()), 1) }
            }
            val from = action.copy(transition = action.transition!!.copy(from = listOf("reopened")))
            shouldThrow<AutomationException> {
                PreviewFulfilment.resolve(from, metadata, pinned).transition(args, snapshot(row()), 1)
            }
        }
    })
