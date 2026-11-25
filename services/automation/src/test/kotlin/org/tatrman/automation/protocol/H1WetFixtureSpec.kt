// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.protocol

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
            document.assertSealedFixture()
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
        }

        "the wet open records the granted decider identity and all four observed verdict routes" {
            val opened =
                document.sections
                    .getValue("automation")
                    .first()
                    .payload.jsonObject
            opened["recipe"] shouldBe JsonPrimitive("release_order")
            opened["deployment"] shouldBe JsonPrimitive("release_morning_cz")
            opened["dryRun"] shouldBe JsonPrimitive(false)
            opened["caller"] shouldBe JsonPrimitive("operator")
            opened["runAs"] shouldBe JsonPrimitive("svc-hartland-automation")
            val routes =
                document.sections
                    .getValue("decision")
                    .single()
                    .payload.jsonObject
                    .getValue("verdict")
                    .jsonObject
                    .getValue("routes")
                    .jsonObject
            routes.mapValues { (_, route) -> route.jsonObject.getValue("rowCount") } shouldBe
                mapOf(
                    "hold_sales" to JsonPrimitive(19),
                    "hold_ar" to JsonPrimitive(7),
                    "release_confirmed" to JsonPrimitive(2),
                    "release" to JsonPrimitive(127),
                )
        }

        "the applied writer evidence distinguishes successful rows and history from approval-held rows" {
            val writeEntries = document.sections.getValue("writes").map { it.payload.jsonObject }
            writeEntries.map { it.getValue("batch") }.distinct() shouldBe
                listOf(JsonPrimitive("e9d47e9d-9cd6-3efc-bba2-6a96a1c5e385"))
            val writes = writeEntries.last()
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
            val order = entities.getValue("er.entity.order").jsonObject
            order["applied"] shouldBe JsonPrimitive(179)
            order["history"] shouldBe Json.parseToJsonElement("""{"failed":0,"applied":148,"unknown":0}""")
            entities.getValue("er.entity.release_order_verdict").jsonObject["applied"] shouldBe JsonPrimitive(155)
            entities.getValue("er.entity.release_order_verdict_detail").jsonObject["applied"] shouldBe JsonPrimitive(26)
            entities.getValue("er.entity.release_order_verdict_current").jsonObject["applied"] shouldBe
                JsonPrimitive(308)
            val entityApplied =
                entities.values.sumOf { entity ->
                    entity.jsonObject
                        .getValue("applied")
                        .jsonPrimitive.int
                }
            val rows = writes.getValue("rows").jsonPrimitive.int
            val applied = writes.getValue("applied").jsonPrimitive.int
            entityApplied shouldBe applied
            rows - applied shouldBe writes.getValue("held").jsonPrimitive.int
        }
    })
