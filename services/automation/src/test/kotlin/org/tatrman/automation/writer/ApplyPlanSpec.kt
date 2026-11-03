// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.tatrman.automation.apply.*
import org.tatrman.automation.bundle.ManifestVerdictSchema

class ApplyPlanSpec :
    StringSpec({
        val planner = ApplyPlanner(java.time.Clock.systemUTC())
        val schema =
            ManifestVerdictSchema(
                "release_order_verdict",
                detail = "release_order_verdict_detail",
                current = "release_order_verdict_current",
            )
        val key = buildJsonObject { put("order_number", 4711) }
        val verdict = JsonObject(key + mapOf("run" to JsonPrimitive("run-1"), "route" to JsonPrimitive("hold_sales")))

        fun source() = PlanSource(schema, mapOf("er.entity.order" to "erp_db"), "automation_db")

        "H-1 plan keeps effects and excludes non-writer sinks, with current delete then every route insert" {
            val input =
                PreviewPlan(
                    "batch",
                    "run-1",
                    listOf(
                        ApplyRow(1, "er.entity.release_order_verdict", "insert", key, row = verdict),
                        ApplyRow(
                            2,
                            "er.entity.order",
                            "update",
                            key,
                            set = buildJsonObject { put("held_for", "Sales") },
                        ),
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
                    ),
                    listOf(
                        DryRunAction(key, "hold_sales", "hold", "transition", JsonObject(emptyMap()), listOf(2, 3)),
                        DryRunAction(key, "hold_sales", "notify", "deliver", JsonObject(emptyMap()), emptyList()),
                    ),
                    source = source(),
                    candidateKeys = listOf(key),
                )
            val plan = planner.plan(input)
            plan.rows.map { it.seq } shouldBe (1..5).toList()
            plan.rows.take(3) shouldBe input.rows
            plan.rows[3].op shouldBe "delete"
            plan.rows[3].key shouldBe key
            plan.rows[4].row shouldBe verdict
            plan.rows.drop(3).all { it.entity == "er.entity.release_order_verdict_current" } shouldBe true
            plan.groups.map { it.storage to it.entity } shouldBe
                listOf("automation_db" to "er.entity.release_order_verdict", "erp_db" to "er.entity.order")
            plan.groups
                .first()
                .rows
                .map { it.seq } shouldBe listOf(1, 4, 5)
            Json.encodeToString(RunBatchPlan(plan.batch, plan.run, plan.rows, plan.mode)).contains("signal") shouldBe
                false
        }

        "none is not materialized as a verdict or current row" {
            val none =
                ApplyRow(
                    1,
                    "er.entity.release_order_verdict",
                    "insert",
                    key,
                    row = JsonObject(verdict + ("route" to JsonPrimitive("none"))),
                )
            val plan =
                planner.plan(
                    PreviewPlan(
                        "batch",
                        "run-1",
                        listOf(none),
                        emptyList(),
                        source = source(),
                        candidateKeys = listOf(key),
                    ),
                )
            plan.rows.map { it.op } shouldBe listOf("delete")
            plan.rows.single().key shouldBe key
            plan.groups.single().entity shouldBe "er.entity.release_order_verdict"
        }

        "details respect the action limit and retain a single truncated marker" {
            fun detail(n: Int) =
                ApplyRow(
                    n + 1,
                    "er.entity.release_order_verdict_detail",
                    "insert",
                    key,
                    row =
                        JsonObject(
                            key +
                                mapOf(
                                    "run" to JsonPrimitive("run-1"),
                                    "route" to JsonPrimitive("hold_sales"),
                                    "context" to JsonPrimitive("lines"),
                                    "row_no" to JsonPrimitive(n),
                                    "amount" to JsonPrimitive(n.toString()),
                                ),
                        ),
                )
            val input =
                PreviewPlan(
                    "batch",
                    "run-1",
                    listOf(ApplyRow(1, "er.entity.release_order_verdict", "insert", key, row = verdict)) +
                        (1..3).map(::detail),
                    listOf(
                        DryRunAction(
                            key,
                            "hold_sales",
                            "notify",
                            "deliver",
                            buildJsonObject {
                                put(
                                    "detail",
                                    buildJsonObject {
                                        put(
                                            "refs",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        put("context", "lines")
                                                        put("limit", 1)
                                                        put("truncated", true)
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                            emptyList(),
                        ),
                    ),
                    source = source(),
                    candidateKeys = listOf(key),
                )
            val rows = planner.plan(input).rows.filter { it.entity.endsWith("_detail") }
            rows.size shouldBe 2
            rows.first().row!!.getValue("amount") shouldBe JsonPrimitive("1")
            rows.last().row!!.getValue("truncated") shouldBe JsonPrimitive(true)
            planner
                .plan(
                    input.copy(rows = emptyList(), actions = emptyList(), candidateKeys = emptyList()),
                ).rows shouldBe
                emptyList()
        }

        "unrouted candidates clear current; overlapping none is omitted; outside keys stay untouched" {
            val other = buildJsonObject { put("order_number", 4722) }

            fun route(
                n: Int,
                name: String,
            ) = ApplyRow(
                n,
                "er.entity.release_order_verdict",
                "insert",
                key,
                row = JsonObject(verdict + ("route" to JsonPrimitive(name))),
            )
            val empty =
                planner.plan(
                    PreviewPlan("b", "r", emptyList(), emptyList(), source = source(), candidateKeys = listOf(other)),
                )
            empty.rows shouldBe listOf(ApplyRow(1, "er.entity.release_order_verdict_current", "delete", other))
            for (routes in listOf(listOf("none", "remind"), listOf("remind"))) {
                val plan =
                    planner.plan(
                        PreviewPlan(
                            "b",
                            "r",
                            routes.mapIndexed { i, r -> route(i + 1, r) },
                            emptyList(),
                            source = source(),
                            candidateKeys = listOf(key),
                        ),
                    )
                plan.rows.filter { it.entity.endsWith("_current") }.map { it.op } shouldBe listOf("delete", "insert")
                plan.rows.filter { it.op == "insert" }.forEach { it.row!!["route"] shouldBe JsonPrimitive("remind") }
                plan.rows.all { it.key == key } shouldBe true
            }
        }

        "staged truncation marker follows retained rows without a gap, with or without an action reader" {
            val rows =
                (1..201).map { n ->
                    ApplyRow(
                        n,
                        "er.entity.release_order_verdict_detail",
                        "insert",
                        key,
                        row =
                            JsonObject(
                                key +
                                    mapOf(
                                        "run" to JsonPrimitive("r"),
                                        "route" to JsonPrimitive("hold_sales"),
                                        "context" to JsonPrimitive("lines"),
                                        "row_no" to JsonPrimitive(n),
                                        "truncated" to JsonPrimitive(n == 201),
                                    ),
                            ),
                    )
                }
            for (limit in listOf<Int?>(null, 50)) {
                val actions =
                    limit
                        ?.let {
                            listOf(
                                DryRunAction(
                                    key,
                                    "hold_sales",
                                    "notify",
                                    "deliver",
                                    buildJsonObject {
                                        put(
                                            "detail",
                                            buildJsonObject {
                                                put(
                                                    "refs",
                                                    buildJsonArray {
                                                        add(
                                                            buildJsonObject {
                                                                put("context", "lines")
                                                                put("limit", it)
                                                            },
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                    },
                                    emptyList(),
                                ),
                            )
                        }.orEmpty()
                val planned = planner.plan(PreviewPlan("b", "r", rows, actions, source = source()))
                planned.rows.map { it.row!!["row_no"]!!.jsonPrimitive.int } shouldBe (1..((limit ?: 200) + 1)).toList()
                planned.rows.last().row!!["truncated"] shouldBe JsonPrimitive(true)
            }
        }
    })
