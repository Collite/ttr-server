// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.tatrman.automation.apply.*

class ObligationPlanSpec :
    StringSpec({
        "resolved expect insert and fulfil transition survive planning without a signal field" {
            val key = buildJsonObject { put("id", "ob-1") }
            val rows =
                listOf(
                    ApplyRow(
                        1,
                        "er.entity.obligation",
                        "insert",
                        key,
                        row = JsonObject(key + ("state" to JsonPrimitive("open"))),
                    ),
                    ApplyRow(
                        2,
                        "er.entity.obligation",
                        "transition",
                        key,
                        attribute = "state",
                        from = listOf("open"),
                        to = JsonPrimitive("fulfilled"),
                        history = true,
                    ),
                )
            val source = PlanSource(null, emptyMap(), "verdict_db", obligationStorage = "obligation_db")
            val plan =
                ApplyPlanner(
                    java.time.Clock.systemUTC(),
                ).plan(PreviewPlan("batch", "run", rows, emptyList(), source = source))
            plan.rows shouldBe rows
            plan.groups.single().storage shouldBe "obligation_db"
            val json = Json.encodeToString(RunBatchPlan(plan.batch, plan.run, plan.rows, plan.mode))
            json.contains("signal") shouldBe false
            plan.rows.map { ApplyRow.restore(it.seq, it.entity, it.op, it.key, it.storedRow(), it.group) } shouldBe rows
        }
    })
