// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.protocol

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Instant

internal fun ProtocolDocument.assertSealedFixture() {
    schema shouldBe "protocol/v1"
    sections.keys.toList() shouldBe
        listOf("automation", "islands", "decision", "actions", "writes", "people", "turns", "signals", "receipts")

    val seal = sections.getValue("automation").last()
    seal.payload.jsonObject shouldBe JsonObject(mapOf("sealed" to JsonPrimitive(true)))
    val sealedAt = Instant.parse(seal.at)
    val entries = sections.values.flatten()
    entries.count { it.payload.jsonObject["sealed"] == JsonPrimitive(true) } shouldBe 1

    sections.forEach { (section, records) ->
        withClue("protocol section $section") {
            records.map { it.seq } shouldBe (1..records.size).toList()
        }
        records.forEach { record ->
            withClue("protocol section $section, seq ${record.seq}, at ${record.at}, seal at ${seal.at}") {
                // D-110: the seal stands; only a `writes` entry flagged `after_seal: true` (a held decision or a revert
                // recorded after the close) may follow it.
                val afterSeal = section == "writes" && record.payload.jsonObject["after_seal"] == JsonPrimitive(true)
                Instant.parse(record.at).isAfter(sealedAt) shouldBe afterSeal
            }
        }
    }
}
