// SPDX-License-Identifier: Apache-2.0
package org.tatrman.grounding.mcp

import org.tatrman.grounding.v1.ClarificationOption
import org.tatrman.grounding.v1.DateTimeInterval
import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.grounding.v1.Normalized
import org.tatrman.grounding.v1.ScanRequest
import org.tatrman.grounding.v1.ScanResponse
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.tatrman.grounding.mcp.client.GroundingClient

/**
 * A11.1 — the grounding-mcp tool matrix over mocked backing clients: request mapping (assert on the
 * captured GroundRequest proto — data flow, not call counts), OK/clarification/ungroundable →
 * isError=false structured outcomes, transport failure → isError=true, arg validation, and routing.
 */
class ToolsTest :
    StringSpec({
        fun client(
            name: String,
            slot: CapturingSlot<GroundRequest>? = null,
            response: GroundResponse = okResponse(),
        ): GroundingClient {
            val c = mockk<GroundingClient>()
            every { c.serviceName } returns name
            every { c.close() } just Runs
            if (slot != null) {
                coEvery { c.ground(capture(slot)) } returns response
            } else {
                coEvery { c.ground(any()) } returns response
            }
            return c
        }

        fun request(args: JsonObject?): CallToolRequest {
            val r = mockk<CallToolRequest>()
            every { r.arguments } returns args
            return r
        }

        fun textOf(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): String =
            (result.content.first() as TextContent).text ?: ""

        "ground_time maps camelCase args onto GroundRequest (spanText, kind, context)" {
            val slot = slot<GroundRequest>()
            val tools = Tools(chrono = client("chrono", slot), geo = client("geo"), money = client("money"))

            tools.groundTimeCallback(
                request(
                    buildJsonObject {
                        put("spanText", "May period")
                        put("package", "cnc")
                        putJsonObject("context") {
                            put("referenceDatetime", "2026-05-15T12:00:00+02:00")
                            put("timezone", "Europe/Prague")
                        }
                    },
                ),
            )

            val captured = slot.captured
            captured.spanText shouldBe "May period"
            captured.kind shouldBe EntityKind.DATE_TIME
            captured.getPackage() shouldBe "cnc"
            captured.context.referenceDatetime shouldBe "2026-05-15T12:00:00+02:00"
            captured.context.timezone shouldBe "Europe/Prague"
        }

        "OK response → isError=false, structuredContent mirror, text = explanation + sql_preview" {
            val tools = Tools(client("chrono"), client("geo"), client("money"))
            val result =
                tools.groundTimeCallback(
                    request(
                        buildJsonObject {
                            put("spanText", "May period")
                            putJsonObject("context") { put("referenceDatetime", "2026-05-15T12:00:00+02:00") }
                        },
                    ),
                )

            result.isError shouldBe false
            val structured = result.structuredContent as JsonObject
            structured["status"]!!.jsonPrimitive.content shouldBe "OK"
            structured["result"]!!.jsonObject["sqlPreview"]!!.jsonPrimitive.content shouldContain "\"date\""
            val text = textOf(result)
            text shouldContain "Resolved to May 2026"
            text shouldContain "SQL preview:"
        }

        "AWAITING_CLARIFICATION → isError=false with status + options in structuredContent" {
            val tools = Tools(client("chrono", response = awaitingResponse()), client("geo"), client("money"))
            val result =
                tools.groundTimeCallback(
                    request(
                        buildJsonObject {
                            put("spanText", "December")
                            putJsonObject("context") { put("referenceDatetime", "2026-05-15T12:00:00+02:00") }
                        },
                    ),
                )
            result.isError shouldBe false
            (result.structuredContent as JsonObject)["status"]!!.jsonPrimitive.content shouldBe "AWAITING_CLARIFICATION"
            textOf(result) shouldContain "202612"
        }

        "UNGROUNDABLE → isError=false with the reason surfaced" {
            val tools = Tools(client("chrono"), client("geo", response = ungroundableResponse()), client("money"))
            val result =
                tools.groundGeoCallback(request(buildJsonObject { put("spanText", "within 20 km of Atlantis") }))
            result.isError shouldBe false
            (result.structuredContent as JsonObject)["status"]!!.jsonPrimitive.content shouldBe "UNGROUNDABLE"
            textOf(result) shouldContain "could not resolve"
        }

        "transport failure → isError=true" {
            val failing = mockk<GroundingClient>()
            every { failing.serviceName } returns "geo"
            coEvery { failing.ground(any()) } throws RuntimeException("connection refused")
            val tools = Tools(client("chrono"), failing, client("money"))

            val result = tools.groundGeoCallback(request(buildJsonObject { put("spanText", "in Brno") }))
            result.isError shouldBe true
            textOf(result) shouldContain "connection refused"
        }

        "missing spanText → tool-arg error (isError=true), service not called" {
            val geo = client("geo")
            val tools = Tools(client("chrono"), geo, client("money"))
            val result = tools.groundGeoCallback(request(buildJsonObject { put("package", "cnc") }))
            result.isError shouldBe true
            textOf(result) shouldContain "spanText"
            coVerify(exactly = 0) { geo.ground(any()) }
        }

        "ground_time without context.referenceDatetime → tool-arg error (load-bearing)" {
            val chrono = client("chrono")
            val tools = Tools(chrono, client("geo"), client("money"))
            val result = tools.groundTimeCallback(request(buildJsonObject { put("spanText", "May period") }))
            result.isError shouldBe true
            textOf(result) shouldContain "referenceDatetime"
            coVerify(exactly = 0) { chrono.ground(any()) }
        }

        "routing: each tool calls only its own service" {
            val chrono = client("chrono")
            val geo = client("geo")
            val money = client("money")
            val tools = Tools(chrono, geo, money)

            tools.groundMoneyCallback(request(buildJsonObject { put("spanText", "over 100k CZK") }))
            coVerify(exactly = 1) { money.ground(any()) }
            coVerify(exactly = 0) { chrono.ground(any()) }
            coVerify(exactly = 0) { geo.ground(any()) }
        }

        // TS-P0 S0.3 T5 — scan_time over chrono's Scan: the args mirror ScanRequest, the response is replayed as
        // recorded, every field printed (a span at offset 0 keeps start 0; no spans is an empty list).
        "scan_time maps args onto ScanRequest and replays a recorded ScanResponse into structuredContent" {
            val captured = slot<ScanRequest>()
            val chrono = client("chrono")
            coEvery { chrono.scan(capture(captured)) } returns replayedScan()
            val tools = Tools(chrono, client("geo"), client("money"))

            val result =
                tools.scanTimeCallback(
                    request(
                        buildJsonObject {
                            put("text", "minulý měsíc v Brně a letos")
                            put("package", "prodej")
                            putJsonObject("context") {
                                put("referenceDatetime", "2026-11-01T12:00:00+01:00")
                                put("locale", "cs-CZ")
                                putJsonArray("priorQuestions") { add("Tržby v roce 2025") }
                            }
                        },
                    ),
                )

            captured.captured.text shouldBe "minulý měsíc v Brně a letos"
            captured.captured.getPackage() shouldBe "prodej"
            captured.captured.context.locale shouldBe "cs-CZ"
            captured.captured.context.priorQuestionsList shouldBe listOf("Tržby v roce 2025")
            result.isError shouldBe false
            val spans = (result.structuredContent as JsonObject)["spans"]!!.jsonArray
            spans.size shouldBe 2
            spans[0].jsonObject.let {
                it["start"]!!.jsonPrimitive.content shouldBe "0"
                it["end"]!!.jsonPrimitive.content shouldBe "12"
                it["text"]!!.jsonPrimitive.content shouldBe "minulý měsíc"
                it["kind"]!!.jsonPrimitive.content shouldBe "DATE_TIME"
                it["confidence"]!!.jsonPrimitive.content shouldBe "0.9"
                it["trigger"]!!.jsonPrimitive.content shouldBe "relative"
            }
            spans[1].jsonObject["text"]!!.jsonPrimitive.content shouldBe "letos"
            textOf(result) shouldContain "[0, 12) minulý měsíc"
        }

        "scan_time with no spans gives an empty list, not a missing field" {
            val chrono = client("chrono")
            coEvery { chrono.scan(any()) } returns ScanResponse.getDefaultInstance()
            val tools = Tools(chrono, client("geo"), client("money"))
            val result =
                tools.scanTimeCallback(
                    request(
                        buildJsonObject {
                            put("text", "Tržby v Brně")
                            putJsonObject("context") { put("referenceDatetime", "2026-11-01T12:00:00+01:00") }
                        },
                    ),
                )
            (result.structuredContent as JsonObject)["spans"]!!.jsonArray.size shouldBe 0
            textOf(result) shouldContain "No time phrases"
        }

        "scan_time without text or context.referenceDatetime → tool-arg error, chrono not called" {
            val chrono = client("chrono")
            val tools = Tools(chrono, client("geo"), client("money"))
            tools.scanTimeCallback(request(buildJsonObject { put("text", "minulý měsíc") })).let {
                it.isError shouldBe true
                textOf(it) shouldContain "referenceDatetime"
            }
            tools
                .scanTimeCallback(
                    request(
                        buildJsonObject {
                            putJsonObject("context") { put("referenceDatetime", "2026-11-01T12:00:00+01:00") }
                        },
                    ),
                ).let {
                    it.isError shouldBe true
                    textOf(it) shouldContain "text"
                }
            coVerify(exactly = 0) { chrono.scan(any()) }
        }

        "scan_time transport failure → isError=true" {
            val chrono = client("chrono")
            coEvery { chrono.scan(any()) } throws RuntimeException("connection refused")
            val tools = Tools(chrono, client("geo"), client("money"))
            val result =
                tools.scanTimeCallback(
                    request(
                        buildJsonObject {
                            put("text", "minulý měsíc")
                            putJsonObject("context") { put("referenceDatetime", "2026-11-01T12:00:00+01:00") }
                        },
                    ),
                )
            result.isError shouldBe true
            textOf(result) shouldContain "connection refused"
        }

        "kind mismatch is passed through, not rerouted (A11.4): a geo span to ground_time hits chrono w/ DATE_TIME" {
            val slot = slot<GroundRequest>()
            val tools = Tools(chrono = client("chrono", slot), geo = client("geo"), money = client("money"))

            tools.groundTimeCallback(
                request(
                    buildJsonObject {
                        put("spanText", "within 20 km of Brno")
                        putJsonObject("context") { put("referenceDatetime", "2026-05-15T12:00:00+02:00") }
                    },
                ),
            )
            slot.captured.kind shouldBe EntityKind.DATE_TIME
            slot.captured.spanText shouldBe "within 20 km of Brno"
        }
    })

/** A ScanResponse as chrono answers it, recorded as JSON (the wire's camelCase) and replayed. */
private fun replayedScan(): ScanResponse =
    ScanResponse
        .newBuilder()
        .also {
            JsonFormat.parser().merge(
                """
                {"spans": [
                  {"end": 12, "text": "minulý měsíc", "kind": "DATE_TIME", "confidence": 0.9, "trigger": "relative"},
                  {"start": 22, "end": 27, "text": "letos", "kind": "DATE_TIME", "confidence": 0.9,
                   "trigger": "relative"}
                ]}
                """.trimIndent(),
                it,
            )
        }.build()

private fun okResponse(): GroundResponse =
    GroundResponse
        .newBuilder()
        .setStatus(GroundResponse.Status.OK)
        .setResult(
            GroundingResult
                .newBuilder()
                .setNormalized(
                    Normalized
                        .newBuilder()
                        .setInterval(
                            DateTimeInterval
                                .newBuilder()
                                .setStart("2026-05-01T00:00:00+02:00")
                                .setEnd("2026-06-01T00:00:00+02:00"),
                        ),
                ).setSqlPreview("t.\"date\" >= {start} AND t.\"date\" < {end}")
                .setConfidence(0.95f)
                .setSource(GroundingResult.Source.RULES)
                .setExplanation("Resolved to May 2026."),
        ).build()

private fun awaitingResponse(): GroundResponse =
    GroundResponse
        .newBuilder()
        .setStatus(GroundResponse.Status.AWAITING_CLARIFICATION)
        .addOptions(ClarificationOption.newBuilder().setId("202612").setLabel("December 2026 (202612)"))
        .build()

private fun ungroundableResponse(): GroundResponse =
    GroundResponse
        .newBuilder()
        .setStatus(GroundResponse.Status.UNGROUNDABLE)
        .addMessages(
            ResponseMessage
                .newBuilder()
                .setSeverity(Severity.INFO)
                .setCode("ungroundable")
                .setHumanMessage("could not resolve the place 'Atlantis'"),
        ).build()
