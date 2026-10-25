// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.*
import org.tatrman.ttrp.resolve.TargetResolution

class PreviewEffectsSpec :
    StringSpec({
        val bundle = BundleFixture.load(ApprovalFixture.copyTo(tempdir().toPath().resolve("branch")))
        val checked = bundle.recipes.getValue("release_order").coverage
        val base = checked.actions.getValue("hold")
        val original = checked.targets.getValue("hold")
        val metadata = PreviewMetadata(checked.actions, bundle.entities, checked.targets)
        val planner = ApplyPlanner(BundleFixture.clock)
        val args =
            mapOf(
                "order" to JsonPrimitive("4711"),
                "department" to JsonPrimitive("Sales"),
                "to" to JsonPrimitive("held"),
            )

        // Independent spelling choices exercise both mixed qualification directions through the pinned branch.
        for (qualifiedKey in listOf(false, true)) {
            for (qualifiedEffect in listOf(false, true)) {
                "pinned set and transition accept key=$qualifiedKey effect=$qualifiedEffect" {
                    fun key(ref: String) = if (qualifiedKey) ref else ref.removePrefix("hartland.")

                    fun effect(ref: String) = if (qualifiedEffect) ref else ref.removePrefix("hartland.")
                    val action =
                        base.copy(
                            columns = base.columns.map { if (it.name == "order") it.copy(type = key(it.type)) else it },
                            set = base.set!!.copy(attribute = effect(base.set!!.attribute)),
                            transition = base.transition!!.copy(attribute = effect(base.transition!!.attribute)),
                        )
                    val pins =
                        original.entries.associate { (ref, value) ->
                            (if (value.attribute.isKey) key(ref) else effect(ref)) to value
                        }
                    val rows = mutableListOf<ApplyRow>()
                    planner.effects(action, args, metadata, pins, rows)
                    rows.map { it.op } shouldBe listOf("update", "transition")
                    rows.forEach { it.key shouldBe buildJsonObject { put("order_number", "4711") } }
                    rows[0].set shouldBe buildJsonObject { put("held_for", "Sales") }
                    rows[1].to shouldBe JsonPrimitive("held")
                }
            }
        }

        "set-only and column-driven transition use the same pinned targets" {
            val setRows = mutableListOf<ApplyRow>()
            planner.effects(base.copy(transition = null), args, metadata, original, setRows)
            setRows.single().op shouldBe "update"
            val transition = base.transition!!.copy(to = null, toColumn = "to")
            val rows = mutableListOf<ApplyRow>()
            planner.effects(base.copy(set = null, transition = transition), args, metadata, original, rows)
            rows.single().to shouldBe JsonPrimitive("held")
            shouldThrow<IllegalArgumentException> {
                planner.effects(
                    base.copy(set = null, transition = transition),
                    args + ("to" to JsonPrimitive("invalid")),
                    metadata,
                    original,
                    mutableListOf(),
                )
            }
        }

        "missing effect or key pins never fall back even when the legacy name would resolve" {
            val legacy =
                base.copy(
                    columns = base.columns.map { it.copy(type = it.type.removePrefix("hartland.")) },
                    set = base.set!!.copy(attribute = base.set!!.attribute.removePrefix("hartland.")),
                    transition =
                        base.transition!!.copy(
                            attribute = base.transition!!.attribute.removePrefix("hartland."),
                        ),
                )
            val pins = original.mapKeys { it.key.removePrefix("hartland.") }
            for (ref in pins.keys + "all") {
                val missing = if (ref == "all") emptyMap<String, TargetResolution.Resolved>() else pins - ref
                shouldThrow<AutomationException> {
                    planner.effects(legacy, args, metadata, missing, mutableListOf())
                }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
            }
            val rows = mutableListOf<ApplyRow>()
            planner.effects(legacy, args, metadata, null, rows)
            rows.size shouldBe 2
        }
    })
