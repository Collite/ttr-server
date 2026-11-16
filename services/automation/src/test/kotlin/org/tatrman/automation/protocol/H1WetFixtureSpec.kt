// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.protocol

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/**
 * S2.4 T5 / D-211: the H-1 wet protocol captured on bp-dsk (automation 0.11.38), run 814fb2e9.
 * Caller and author are anonymised; UUIDs, counts, demo keys and timestamps retain the live evidence.
 * The two approval-held rows remain undecided: a sealed protocol does not imply a committed batch.
 */
class H1WetFixtureSpec :
    StringSpec({
        val document =
            Json.decodeFromString<ProtocolDocument>(
                checkNotNull(javaClass.getResource("/protocol/h1-wet.json")).readText(),
            )

        "the live wet document reads as protocol v1 with ordered sections and one final seal" {
            document.schema shouldBe "protocol/v1"
            document.run shouldBe "814fb2e9-876a-48ec-8e17-057161afe5c5"
            document.sections.mapValues { (_, entries) -> entries.size } shouldBe
                linkedMapOf(
                    "automation" to 3,
                    "islands" to 11,
                    "decision" to 1,
                    "actions" to 181,
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
            val seal = document.sections.getValue("automation").last()
            seal.payload shouldBe Json.parseToJsonElement("""{"sealed":true}""")
            val entries = document.sections.values.flatten()
            entries.count { it.payload.jsonObject["sealed"] == JsonPrimitive(true) } shouldBe 1
            entries.all { Instant.parse(it.at) <= Instant.parse(seal.at) } shouldBe true
        }

        "the wet open records the granted decider identity and all four observed verdict routes" {
            val opened = document.sections.getValue("automation").first().payload.jsonObject
            opened["recipe"] shouldBe JsonPrimitive("release_order")
            opened["deployment"] shouldBe JsonPrimitive("release_morning_cz")
            opened["dryRun"] shouldBe JsonPrimitive(false)
            opened["caller"] shouldBe JsonPrimitive("operator")
            opened["runAs"] shouldBe JsonPrimitive("svc-hartland-automation")
            val routes =
                document.sections.getValue("decision").single().payload.jsonObject
                    .getValue("verdict").jsonObject.getValue("routes").jsonObject
            routes.mapValues { (_, route) -> route.jsonObject.getValue("rowCount") } shouldBe
                mapOf(
                    "hold_sales" to JsonPrimitive(19),
                    "hold_ar" to JsonPrimitive(7),
                    "release_confirmed" to JsonPrimitive(2),
                    "release" to JsonPrimitive(127),
                )
        }

        "the applied writer evidence distinguishes successful rows and history from approval-held rows" {
            val writes = document.sections.getValue("writes").last().payload.jsonObject
            writes["event"] shouldBe JsonPrimitive("closed")
            writes["state"] shouldBe JsonPrimitive("held")
            writes["rows"] shouldBe JsonPrimitive(670)
            writes["applied"] shouldBe JsonPrimitive(668)
            writes["failed"] shouldBe JsonPrimitive(0)
            writes["unknown"] shouldBe JsonPrimitive(0)
            writes["held"] shouldBe JsonPrimitive(2)
            writes["groups"] shouldBe JsonPrimitive(2)
            writes["history"] shouldBe Json.parseToJsonElement("""{"failed":0,"applied":148,"unknown":0}""")
            val entities = writes.getValue("entities").jsonObject
            entities.getValue("er.entity.release_order_verdict").jsonObject["applied"] shouldBe JsonPrimitive(155)
            entities.getValue("er.entity.release_order_verdict_detail").jsonObject["applied"] shouldBe JsonPrimitive(26)
            entities.getValue("er.entity.release_order_verdict_current").jsonObject["applied"] shouldBe JsonPrimitive(308)
        }
    })
