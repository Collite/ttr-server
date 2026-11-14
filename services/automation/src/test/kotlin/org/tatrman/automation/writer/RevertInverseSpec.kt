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
            // review-258 B2: last effect first — the writer insert (seq 4) is undone before the hold (seqs 2, 3).
            inverse.rows shouldBe
                listOf(
                    ApplyRow(1, "er.entity.order", "delete", buildJsonObject { put("order_number", "9001") }),
                    ApplyRow(2, "er.entity.order", "update", key, set = buildJsonObject { put("held_for", JsonNull) }),
                    ApplyRow(
                        3,
                        "er.entity.order",
                        "transition",
                        key,
                        attribute = "state",
                        from = listOf("held"),
                        to = JsonPrimitive("placed"),
                        history = true,
                    ),
                )
            // review-258 B1: the hold's `set:` and transition stay one compare-and-set.
            inverse.units shouldBe listOf(listOf(2, 3))
            inverse.unresolved shouldBe emptyList()
        }

        "a self-transition still guards its unit; a null prior state cannot be restored and is unresolved" {
            Revert
                .inverse(
                    state(listOf(BatchRowStatus(3, "applied", prior = buildJsonObject { put("state", "held") }))),
                    emptyList(),
                ).rows shouldBe
                listOf(
                    ApplyRow(
                        1,
                        "er.entity.order",
                        "transition",
                        key,
                        attribute = "state",
                        from = listOf("held"),
                        to = JsonPrimitive("held"),
                        history = false,
                    ),
                )
            val nullPrior =
                Revert.inverse(
                    state(listOf(BatchRowStatus(3, "applied", prior = buildJsonObject { put("state", JsonNull) }))),
                    emptyList(),
                )
            nullPrior.rows shouldBe emptyList()
            nullPrior.unresolved shouldBe listOf(3)
        }

        "review-258 B3: an unknown outcome, or an update applied with no recorded prior, is unresolved — not skipped" {
            val unknown =
                Revert.inverse(
                    state(
                        listOf(
                            BatchRowStatus(2, "staged", RowOutcome.UNKNOWN),
                            BatchRowStatus(3, "staged", RowOutcome.UNKNOWN),
                            BatchRowStatus(4, "applied"),
                        ),
                    ),
                    entities,
                )
            unknown.unresolved shouldBe listOf(2, 3)
            Revert
                .inverse(
                    state(
                        listOf(
                            BatchRowStatus(2, "applied", RowOutcome.RECONCILED),
                            BatchRowStatus(3, "applied", RowOutcome.RECONCILED, prior),
                        ),
                    ),
                    entities,
                ).unresolved shouldBe listOf(2, 3)
            // An unknown verdict row is not the revert's to settle.
            Revert
                .inverse(state(listOf(BatchRowStatus(1, "staged", RowOutcome.UNKNOWN))), entities)
                .unresolved shouldBe emptyList()
        }

        "review-258 B2: two actions setting one field unwind last-first, back to the value before the batch" {
            val twice =
                listOf(
                    ApplyRow(1, "er.entity.order", "update", key, set = buildJsonObject { put("held_for", "Sales") }),
                    ApplyRow(2, "er.entity.order", "update", key, set = buildJsonObject { put("held_for", "AR") }),
                )
            val inverse =
                Revert.inverse(
                    RevertState(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        RunNames("release_order", null, null, null, null),
                        RunBatchPlan(
                            "b",
                            "r",
                            twice,
                            "auto",
                            listOf(
                                BatchRowStatus(1, "applied", prior = buildJsonObject { put("held_for", JsonNull) }),
                                BatchRowStatus(2, "applied", prior = buildJsonObject { put("held_for", "Sales") }),
                            ),
                        ),
                        listOf(
                            DryRunAction(
                                key,
                                "a",
                                "first",
                                "update",
                                JsonObject(emptyMap()),
                                listOf(1),
                                dryRun = false,
                            ),
                            DryRunAction(
                                key,
                                "a",
                                "second",
                                "update",
                                JsonObject(emptyMap()),
                                listOf(2),
                                dryRun = false,
                            ),
                        ),
                        null,
                        Duration.ofMinutes(30),
                    ),
                    entities,
                )
            inverse.rows.map { it.set } shouldBe
                listOf(buildJsonObject { put("held_for", "Sales") }, buildJsonObject { put("held_for", JsonNull) })
            inverse.units shouldBe emptyList()
        }
    })
