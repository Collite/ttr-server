// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.apply.ApplyRow
import org.tatrman.automation.apply.BatchRowStatus
import org.tatrman.automation.apply.DryRunAction
import org.tatrman.automation.apply.RunBatchPlan
import org.tatrman.automation.ledger.RevertState
import org.tatrman.automation.ledger.RunNames
import java.time.Duration
import java.util.UUID

/** S2.3 T6 — `-rev`'s inverse rows, from the `prior` recorded at apply time (D-66). */
class RevertInverseSpec :
    StringSpec({
        val key = buildJsonObject { put("order_number", "4711") }
        val entities =
            org.tatrman.automation.bundle.BundleFixture
                .load()
                .entities
        val rows =
            listOf(
                ApplyRow(
                    1,
                    "er.entity.release_order_verdict",
                    "insert",
                    key,
                    row = buildJsonObject { put("route", "hold") },
                ),
                ApplyRow(2, "er.entity.order", "update", key, set = buildJsonObject { put("held_for", "Sales") }),
                ApplyRow(
                    3,
                    "er.entity.order",
                    "transition",
                    key,
                    attribute = "state",
                    from = listOf("placed"),
                    to = JsonPrimitive("held"),
                    history = true,
                ),
                ApplyRow(
                    4,
                    "er.entity.order",
                    "insert",
                    key,
                    row =
                        buildJsonObject {
                            put("order_number", "9001")
                            put("state", "placed")
                        },
                ),
                ApplyRow(
                    5,
                    "er.entity.order",
                    "transition",
                    key,
                    attribute = "state",
                    to = JsonPrimitive("held"),
                    history = true,
                ),
            )
        val prior = buildJsonObject { put("state", "placed") }

        fun state(statuses: List<BatchRowStatus>) =
            RevertState(
                UUID.randomUUID(),
                UUID.randomUUID(),
                RunNames("release_order", null, null, null, null),
                RunBatchPlan("b", "r", rows, "auto", statuses),
                listOf(
                    DryRunAction(
                        key,
                        "hold",
                        "hold",
                        "transition",
                        JsonObject(emptyMap()),
                        listOf(2, 3),
                        dryRun = false,
                    ),
                    DryRunAction(key, "hold", "propose", "writer", JsonObject(emptyMap()), listOf(4), dryRun = false),
                ),
                null,
                Duration.ofMinutes(30),
            )

        "verdicts are never reverted; update/transition come back from prior; a writer insert is deleted by its key" {
            val inverse =
                Revert.inverse(
                    state(
                        listOf(
                            BatchRowStatus(1, "applied"),
                            BatchRowStatus(2, "applied", prior = prior),
                            BatchRowStatus(3, "applied", prior = prior),
                            BatchRowStatus(4, "applied"),
                            BatchRowStatus(5, "failed", "stale-state", prior),
                        ),
                    ),
                    entities,
                )
            inverse shouldBe
                listOf(
                    ApplyRow(1, "er.entity.order", "update", key, set = buildJsonObject { put("held_for", JsonNull) }),
                    ApplyRow(
                        2,
                        "er.entity.order",
                        "transition",
                        key,
                        attribute = "state",
                        from = listOf("held"),
                        to = JsonPrimitive("placed"),
                        history = true,
                    ),
                    ApplyRow(3, "er.entity.order", "delete", buildJsonObject { put("order_number", "9001") }),
                )
        }

        "a self-transition or a null prior state is not reverted" {
            Revert.inverse(
                state(listOf(BatchRowStatus(3, "applied", prior = buildJsonObject { put("state", "held") }))),
                emptyList(),
            ) shouldBe emptyList()
            Revert.inverse(
                state(listOf(BatchRowStatus(3, "applied", prior = buildJsonObject { put("state", JsonNull) }))),
                emptyList(),
            ) shouldBe emptyList()
        }
    })
