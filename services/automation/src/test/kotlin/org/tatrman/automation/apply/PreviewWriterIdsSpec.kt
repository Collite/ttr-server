// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import java.math.BigDecimal
import java.util.UUID

class PreviewWriterIdsSpec :
    StringSpec({
        val run = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
        val key =
            buildJsonObject {
                put("item", 42)
                put("warehouse", "cz")
            }

        "bigint v1 matches the independent byte-format vector and canonical numeric keys" {
            PreviewWriterIds.bigintV1(run, 3, "propose", "id", key) shouldBe -1871603600069624238L
            val reordered =
                buildJsonObject {
                    put("warehouse", "cz")
                    put("item", JsonPrimitive(BigDecimal("42.000")))
                }
            PreviewWriterIds.bigintV1(run, 3, "propose", "id", reordered) shouldBe -1871603600069624238L
        }

        "identity covers run wiring route column and typed composite candidate key" {
            val ids =
                listOf(
                    PreviewWriterIds.bigintV1(run, 3, "propose", "id", key),
                    PreviewWriterIds.bigintV1(UUID(0, 1), 3, "propose", "id", key),
                    PreviewWriterIds.bigintV1(run, 4, "propose", "id", key),
                    PreviewWriterIds.bigintV1(run, 3, "other", "id", key),
                    PreviewWriterIds.bigintV1(run, 3, "propose", "other", key),
                    PreviewWriterIds.bigintV1(
                        run,
                        3,
                        "propose",
                        "id",
                        buildJsonObject {
                            put("item", "42")
                            put("warehouse", "cz")
                        },
                    ),
                    PreviewWriterIds.bigintV1(
                        run,
                        3,
                        "propose",
                        "id",
                        buildJsonObject {
                            put("item", 43)
                            put("warehouse", "cz")
                        },
                    ),
                )
            ids.distinct().size shouldBe ids.size
            ids.all { it < 0 } shouldBe true
        }

        "reordered candidate rows retain IDs without ordinal dependence" {
            val keys =
                (1..20).map {
                    buildJsonObject {
                        put("item", it)
                        put("warehouse", "cz")
                    }
                }

            fun ids(rows: List<JsonObject>) =
                rows.associateWith { PreviewWriterIds.bigintV1(run, 3, "propose", "id", it) }
            ids(keys.reversed()) shouldBe ids(keys)
            for (invalid in listOf(
                JsonObject(emptyMap()),
                buildJsonObject {
                    put("id", JsonNull)
                },
                buildJsonObject { put("id", JsonArray(emptyList())) },
            )) {
                shouldThrow<AutomationException> {
                    PreviewWriterIds.bigintV1(
                        run,
                        3,
                        "propose",
                        "id",
                        invalid,
                    )
                }.code shouldBe
                    ErrorCode.PREVIEW_UNSUPPORTED
            }
        }

        "generated collisions refuse including explicit overrides in either order and numeric normalization" {
            val row = ApplyRow(1, "er.entity.proposal", "insert", key, row = key)
            for ((first, second) in listOf(true to true, true to false, false to true)) {
                val keys = PreviewWriterIds.Keys()
                keys.record(row, first)
                val normalized =
                    buildJsonObject {
                        put("warehouse", "cz")
                        put("item", JsonPrimitive(BigDecimal("42.0")))
                    }
                shouldThrow<AutomationException> { keys.record(row.copy(key = normalized), second) }.code shouldBe
                    ErrorCode.PREVIEW_UNSUPPORTED
            }
            PreviewWriterIds.Keys().apply {
                record(row, true)
                record(row.copy(entity = "er.entity.other"), true)
                record(row.copy(key = buildJsonObject { put("id", 43) }), true)
            }
            PreviewWriterIds.Keys().apply {
                record(row, false)
                record(row, false)
            }
        }
    })
