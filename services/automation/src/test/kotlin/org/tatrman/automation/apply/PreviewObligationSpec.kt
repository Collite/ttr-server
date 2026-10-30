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
import org.tatrman.automation.bundle.actions
import org.tatrman.automation.runner.runnerContext
import org.tatrman.ttr.metadata.model.AutomationColumn
import org.tatrman.ttrp.resolve.TargetResolution
import java.util.UUID

class PreviewObligationSpec :
    StringSpec({
        val bundle =
            BundleFixture.load(
                ApprovalFixture.copyTo(tempdir().toPath().resolve("branch"), obligations = true),
            )
        val coverage = bundle.recipes.getValue("release_order").coverage
        coverage.refusal shouldBe null
        val metadata = PreviewMetadata(coverage.actions, bundle.entities, coverage.targets)
        val action = metadata.actions.getValue("expect")
        val pinned = metadata.targets.getValue("expect")
        val context = runnerContext(bundle.recipes.getValue("release_order")).copy(startedAt = BundleFixture.LOADED_AT)
        val args =
            mapOf(
                "line" to JsonPrimitive(9007199254740993L),
                "kind" to JsonPrimitive("inbound"),
                "due" to JsonPrimitive("2026-11-06"),
            )

        "insert and fulfilment share exact canonical numeric and composite key text" {
            val mapping = PreviewObligation.resolve(action, metadata, pinned)
            for (spelling in listOf("1", "1.0", "1E0")) {
                val value = Json.parseToJsonElement(spelling)
                val row = mapping.insert(1, args + ("line" to value), context)
                row.row!!
                    .getValue("key")
                    .jsonPrimitive.content shouldBe
                    ObligationKeys.canonicalText(buildJsonObject { put("line_id", value) })
                row.row!!
                    .getValue("key")
                    .jsonPrimitive.content shouldBe "{\"line_id\":1}"
            }
            ObligationKeys.canonicalText(Json.parseToJsonElement("{\"b\":1.0,\"a\":2E0}").jsonObject) shouldBe
                "{\"a\":2,\"b\":1}"
            for (key in listOf("{}", "{\"a\":null}", "{\"a\":{}}", "{\"a\":[]}")) {
                shouldThrow<AutomationException> {
                    ObligationKeys.canonicalText(
                        Json.parseToJsonElement(key).jsonObject,
                    )
                }.code shouldBe
                    ErrorCode.PREVIEW_UNSUPPORTED
            }
        }

        "insert refuses a non-stdlib obligation just like fulfilment" {
            val changed =
                metadata.copy(
                    entities =
                        metadata.entities.map {
                            if (it.qname.dotted() ==
                                "er.entity.obligation"
                            ) {
                                it.copy(sourceFile = "/custom/obligation.ttrm")
                            } else {
                                it
                            }
                        },
                )
            shouldThrow<AutomationException> { PreviewObligation.resolve(action, changed, pinned) }.code shouldBe
                ErrorCode.PREVIEW_UNSUPPORTED
        }

        "incomplete pins never fall back to a matching legacy key" {
            shouldThrow<AutomationException> { PreviewObligation.resolve(action, metadata, emptyMap()) }.code shouldBe
                ErrorCode.PREVIEW_UNSUPPORTED
            val legacy = action.copy(columns = action.columns.map { it.copy(type = it.type.removePrefix("hartland.")) })
            PreviewObligation.resolve(legacy, metadata, null).insert(1, args, context).row shouldBe
                PreviewObligation.resolve(action, metadata, pinned).insert(1, args, context).row
        }

        "obligation ids separate exact adjacent large keys kinds and runs" {
            val mapping = PreviewObligation.resolve(action, metadata, pinned)
            val original = mapping.insert(1, args, context)
            val adjacent = mapping.insert(1, args + ("line" to JsonPrimitive(9007199254740992L)), context)
            val otherKind = mapping.insert(1, args + ("kind" to JsonPrimitive("payment")), context)
            val otherRun = mapping.insert(1, args, context.copy(id = UUID.randomUUID()))
            listOf(original, adjacent, otherKind, otherRun).map { it.key }.distinct().size shouldBe 4
            mapping.insert(99, args, context).key shouldBe original.key
        }

        "composite target keys are complete and canonical independent of column order" {
            val original = pinned.values.single().entity
            val id = original.attributes.single { it.isKey }
            val tenant = id.copy(qname = id.qname.copy(name = "return_request_line.tenant"), type = "text")
            val entity = original.copy(attributes = original.attributes + tenant)
            val changed = metadata.copy(entities = metadata.entities.map { if (it == original) entity else it })
            val columns = action.columns + AutomationColumn("scope", "hartland.return_request_line.tenant")
            val pins =
                mapOf(
                    action.columns.first().type to TargetResolution.Resolved(entity, id),
                    "hartland.return_request_line.tenant" to TargetResolution.Resolved(entity, tenant),
                )
            val mapping = PreviewObligation.resolve(action.copy(columns = columns), changed, pins)
            mapping.expectedTypes shouldBe mapOf("line" to "int", "scope" to "text", "kind" to "text", "due" to "date")
            val row = mapping.insert(1, args + ("scope" to JsonPrimitive("a\"\\b")), context)
            Json.parseToJsonElement(
                row.row!!
                    .getValue("key")
                    .jsonPrimitive.content,
            ) shouldBe
                buildJsonObject {
                    put("line_id", 9007199254740993L)
                    put("tenant", "a\"\\b")
                }
            PreviewObligation
                .resolve(action.copy(columns = columns.reversed()), changed, pins)
                .insert(1, args + ("scope" to JsonPrimitive("a\"\\b")), context)
                .row shouldBe row.row
            shouldThrow<AutomationException> { PreviewObligation.resolve(action, changed, pins) }
            shouldThrow<AutomationException> { mapping.insert(1, args + ("scope" to JsonNull), context) }
        }

        "non-key mixed duplicate and ambiguous target mappings refuse before values" {
            val target = pinned.values.single().entity
            val nonKey = target.attributes.first { !it.isKey }
            val other = metadata.entities.single { it.qname.name == "order" }
            val otherKey = other.attributes.single { it.isKey }
            val badColumns =
                listOf(
                    action.columns.filterNot { it.name == "line" },
                    action.columns + action.columns.first(),
                    action.columns + AutomationColumn("extra", "other"),
                )
            for (columns in badColumns) {
                shouldThrow<AutomationException> {
                    PreviewObligation.resolve(
                        action.copy(columns = columns),
                        metadata,
                        pinned,
                    )
                }
            }
            for (attribute in listOf(nonKey, target.attributes.single { it.isKey }.copy(aggregated = true))) {
                shouldThrow<AutomationException> {
                    PreviewObligation.resolve(
                        action,
                        metadata,
                        pinned.mapValues { TargetResolution.Resolved(target, attribute) },
                    )
                }
            }
            shouldThrow<AutomationException> {
                PreviewObligation.resolve(
                    action.copy(columns = action.columns + AutomationColumn("other", "other")),
                    metadata,
                    pinned + ("other" to TargetResolution.Resolved(other, otherKey)),
                )
            }
            shouldThrow<AutomationException> {
                PreviewObligation.resolve(
                    action,
                    metadata.copy(
                        entities =
                            metadata.entities + target.copy(internalId = "ambiguous"),
                    ),
                    pinned,
                )
            }
        }

        "published obligation shape must have its id key and matching field types" {
            val original = metadata.entities.single { it.qname.name == "obligation" }
            val variants =
                listOf(
                    metadata.entities - original,
                    metadata.entities + original.copy(internalId = "duplicate"),
                    metadata.entities.map { entity ->
                        if (entity !=
                            original
                        ) {
                            entity
                        } else {
                            entity.copy(
                                attributes = entity.attributes.map { if (it.isKey) it.copy(isKey = false) else it },
                            )
                        }
                    },
                    metadata.entities.map { entity ->
                        if (entity !=
                            original
                        ) {
                            entity
                        } else {
                            entity.copy(
                                attributes =
                                    entity.attributes.map {
                                        if (it.qname.name.endsWith(
                                                ".due",
                                            )
                                        ) {
                                            it.copy(type = "text")
                                        } else {
                                            it
                                        }
                                    },
                            )
                        }
                    },
                )
            for (entities in variants) {
                shouldThrow<AutomationException> {
                    PreviewObligation.resolve(
                        action,
                        metadata.copy(entities = entities),
                        pinned,
                    )
                }
            }
        }

        "required obligation values and source attribute bounds refuse without an insert" {
            val target = pinned.values.single().entity
            val id = target.attributes.single { it.isKey }
            val bounded = id.copy(type = "text", length = 1)
            val entity = target.copy(attributes = target.attributes.map { if (it == id) bounded else it })
            val changed = metadata.copy(entities = metadata.entities.map { if (it == target) entity else it })
            val mapping =
                PreviewObligation.resolve(
                    action,
                    changed,
                    pinned.mapValues {
                        TargetResolution.Resolved(entity, bounded)
                    },
                )
            shouldThrow<AutomationException> { mapping.insert(1, args + ("line" to JsonPrimitive("ab")), context) }
            val original = PreviewObligation.resolve(action, metadata, pinned)
            for (bad in listOf(
                args - "line",
                args + ("line" to JsonNull),
                args + ("kind" to JsonNull),
                args + ("kind" to JsonPrimitive(" ")),
                args + ("due" to JsonNull),
                args + ("extra" to JsonPrimitive(1)),
            )) {
                shouldThrow<AutomationException> { original.insert(1, bad, context) }
            }
        }

        "an obligation transition cannot silently become a new open obligation" {
            shouldThrow<AutomationException> {
                PreviewObligation.resolve(bundle.actions.getValue("fulfil"), metadata, metadata.targets["fulfil"])
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }
    })
