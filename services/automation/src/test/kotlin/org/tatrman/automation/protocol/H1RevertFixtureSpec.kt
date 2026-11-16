// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.protocol

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.automation.apply.RunBatchPlan
import org.tatrman.automation.writer.RevertResult

/**
 * S2.4 / AU-S24-4: bp-dsk's H-1 stand-in revert, after the operator committed its two held groups.
 * POST revert identifies the inverse; GET runs/{id}/batch still serves the original apply plan.
 * These are two actual wire captures, not an invented inverse-batch read endpoint. Operator names
 * are anonymised; IDs, demo keys, row outcomes and prior values retain the observed evidence.
 */
class H1RevertFixtureSpec :
    StringSpec({
        val result =
            Json.decodeFromString<RevertResult>(
                checkNotNull(javaClass.getResource("/protocol/h1-revert-response.json")).readText(),
            )
        val original =
            Json.decodeFromString<RunBatchPlan>(
                checkNotNull(javaClass.getResource("/protocol/h1-batch-after-revert.json")).readText(),
            )

        "the live revert response identifies its inverse and all 181 successful row outcomes" {
            result.batch shouldBe "f6aced16-b550-45fc-8315-1c2bbc8db6cd"
            result.revOf shouldBe "e9d47e9d-9cd6-3efc-bba2-6a96a1c5e385"
            (result.batch != result.revOf) shouldBe true
            result.rows shouldBe 181
            result.applied shouldBe result.rows
            result.failed shouldBe 0
        }

        "the durable batch reader retains the original plan and its committed held decisions after revert" {
            original.batch shouldBe result.revOf
            original.run shouldBe "814fb2e9-876a-48ec-8e17-057161afe5c5"
            original.mode shouldBe "auto"
            original.rows.size shouldBe 670
            original.statuses.size shouldBe original.rows.size
            original.statuses.map { it.seq }.toSet() shouldBe original.rows.map { it.seq }.toSet()
            original.statuses.all { it.status == "applied" && it.error == null } shouldBe true
            original.rows.count { it.entity == "er.entity.release_order_verdict" } shouldBe 155
            val heldSequences =
                original.rows
                    .filter { it.group != null }
                    .map { it.seq }
                    .toSet()
            heldSequences.size shouldBe 2
            val decisions = original.statuses.filter { it.seq in heldSequences }.map { checkNotNull(it.decided) }
            decisions.all {
                it["decision"] ==
                    JsonPrimitive(
                        "commit",
                    ) &&
                    it["by"] == JsonPrimitive("operator")
            } shouldBe
                true
            original.statuses.count { it.history == "applied" } shouldBe 150
        }
    })
