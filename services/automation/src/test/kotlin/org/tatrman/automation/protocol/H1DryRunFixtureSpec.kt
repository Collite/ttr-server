// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.protocol

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/**
 * P1X: the complete sealed protocol from bp-dsk run c75844a1 (automation 0.11.35).
 * Personal identity, run/batch UUIDs and demo order keys are replaced consistently, including refs
 * and prose. Timestamps, counts, parameters and all 200 records retain the observed execution.
 * D-155 accepts 30/155 CZ oracle pairs: threshold 50000 instead of 1250000 shifts 125 releases to
 * release_confirmed. CZ program overrides belong to hartland P3Z.
 */
class H1DryRunFixtureSpec :
    StringSpec({
        val text = checkNotNull(javaClass.getResource("/protocol/h1-dry-run.json")).readText()
        val document = Json.decodeFromString<ProtocolDocument>(text)

        "the live H-1 protocol reads as protocol/v1 with nine ordered sections and complete sequences" {
            document.schema shouldBe "protocol/v1"
            document.sections.keys.toList() shouldBe
                listOf(
                    "automation",
                    "islands",
                    "decision",
                    "actions",
                    "writes",
                    "people",
                    "turns",
                    "signals",
                    "receipts",
                )
            document.sections.mapValues { (_, entries) -> entries.size } shouldBe
                linkedMapOf(
                    "automation" to 3,
                    "islands" to 11,
                    "decision" to 1,
                    "actions" to 183,
                    "writes" to 2,
                    "people" to 0,
                    "turns" to 0,
                    "signals" to 0,
                    "receipts" to 0,
                )
            document.sections.values.forEach { entries ->
                entries.map { it.seq } shouldBe (1..entries.size).toList()
                entries.forEach { Instant.parse(it.at) }
            }
        }

        "the caller dry run closes with one preview batch and sealed true as the final automation entry" {
            val automation = document.sections.getValue("automation")
            val opened = automation.first().payload.jsonObject
            opened["recipe"] shouldBe JsonPrimitive("release_order")
            opened["deployment"] shouldBe JsonPrimitive("release_debug")
            opened["dryRun"] shouldBe JsonPrimitive(true)
            opened["runAs"] shouldBe opened.getValue("caller")
            val closed = automation[1].payload.jsonObject
            closed["event"] shouldBe JsonPrimitive("closed")
            closed["status"] shouldBe JsonPrimitive("closed")
            val seal = automation.last()
            seal.payload shouldBe Json.parseToJsonElement("""{"sealed":true}""")
            val entries = document.sections.values.flatten()
            entries.count { it.payload.jsonObject["sealed"] == JsonPrimitive(true) } shouldBe 1
            entries.all { Instant.parse(it.at) <= Instant.parse(seal.at) } shouldBe true
            val writes = document.sections.getValue("writes").map { it.payload.jsonObject }
            writes.map { it.getValue("batch") }.distinct() shouldBe listOf(closed.getValue("batch"))
            writes.forEach {
                it["state"] shouldBe JsonPrimitive("preview")
                it["rows"] shouldBe JsonPrimitive(242)
            }
            writes.last()["applied"] shouldBe JsonPrimitive(0)
        }
    })
