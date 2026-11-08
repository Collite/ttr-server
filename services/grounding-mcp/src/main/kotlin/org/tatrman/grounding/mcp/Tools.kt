// SPDX-License-Identifier: Apache-2.0
package org.tatrman.grounding.mcp

import com.google.protobuf.util.JsonFormat
import org.tatrman.common.v1.Severity
import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.ScanRequest
import org.tatrman.grounding.v1.ScanResponse
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.tatrman.grounding.mcp.client.GroundingClient

/**
 * The grounding MCP tools — thin wrappers over the chrono / geo / money `GroundingService`s, plus `scan_time` over
 * chrono's `Scan` (TS-P0 S0.3, contracts §3.6)
 * (service-vs-MCP rule: NO grounding logic here). Args are the camelCase mirror of `GroundRequest`;
 * `structuredContent` is the camelCase mirror of `GroundResponse` — both via protobuf [JsonFormat],
 * so the mapping stays faithful in both directions with no hand-written field plumbing (contracts §2).
 *
 * `isError` is reserved for transport/internal failures and bad tool args; AWAITING_CLARIFICATION and
 * UNGROUNDABLE are normal structured outcomes returned with `isError = false`.
 */
class Tools(
    private val chrono: GroundingClient,
    private val geo: GroundingClient,
    private val money: GroundingClient,
) {
    private val logger = LoggerFactory.getLogger(Tools::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // ----- tool definitions -----

    val groundTimeTool =
        tool(
            "ground_time",
            "Ground a DATE_TIME expression (e.g. \"May period\", \"last quarter\", \"do konce roku\") to a " +
                "normalized interval + a filter/join recipe, via the chrono service. " +
                "context.referenceDatetime (ISO-8601 with offset) is REQUIRED — it is load-bearing (the " +
                "service never reads a clock).",
        )

    val groundGeoTool =
        tool(
            "ground_geo",
            "Ground a LOCATION expression (e.g. \"within 20 km of Brno\", \"in Prague\") to a geo point/shape " +
                "+ a filter/join recipe, via the geo service.",
        )

    val groundMoneyTool =
        tool(
            "ground_money",
            "Ground a MONEY expression (e.g. \"over 100k CZK\", \"kolem 2 mil\", \"over 5000 EUR\") to a " +
                "normalized amount + a filter/join recipe, via the money service.",
        )

    val scanTimeTool =
        Tool(
            name = "scan_time",
            description =
                "Find the time phrases in a question (e.g. \"minulý měsíc\", \"Q2 2025\", " +
                    "\"od začátku roku\"), via the chrono service's own rules. Returns spans with UTF-16 " +
                    "offsets into the text as sent; blank quoted literals first, keeping their length. " +
                    "context.referenceDatetime (ISO-8601 with offset) is REQUIRED. Ground each span with ground_time.",
            inputSchema = scanInputSchema(),
            outputSchema = scanOutputSchema(),
        )

    // ----- callbacks -----

    suspend fun groundTimeCallback(request: CallToolRequest): CallToolResult =
        handleGround(request, EntityKind.DATE_TIME, chrono, requireReferenceDatetime = true)

    suspend fun groundGeoCallback(request: CallToolRequest): CallToolResult =
        handleGround(request, EntityKind.LOCATION, geo, requireReferenceDatetime = false)

    suspend fun groundMoneyCallback(request: CallToolRequest): CallToolResult =
        handleGround(request, EntityKind.MONEY, money, requireReferenceDatetime = false)

    /** `scan_time(text, context, package)` → `{spans: [{start, end, text, kind, confidence, trigger}], messages}`. */
    suspend fun scanTimeCallback(request: CallToolRequest): CallToolResult {
        val args = request.arguments
        val text = args?.get("text")?.jsonPrimitive?.contentOrNull
        if (text.isNullOrBlank()) return argError("Missing required argument: text")
        val ref =
            args["context"]
                ?.jsonObject
                ?.get("referenceDatetime")
                ?.jsonPrimitive
                ?.contentOrNull
        if (ref.isNullOrBlank()) {
            return argError(
                "Missing required argument: context.referenceDatetime (load-bearing for Scan)",
            )
        }
        val scanRequest =
            try {
                ScanRequest
                    .newBuilder()
                    .also {
                        JsonFormat.parser().ignoringUnknownFields().merge(
                            args.toString(),
                            it,
                        )
                    }.build()
            } catch (e: Exception) {
                logger.warn("bad scan args: {}", e.message)
                return argError("Invalid arguments: ${e.message}")
            }
        val response =
            try {
                chrono.scan(scanRequest)
            } catch (e: Exception) {
                logger.error("scan call to '{}' failed", chrono.serviceName, e)
                return transportError(chrono.serviceName, e)
            }
        logger.info("scan tool ok | service={} | spans={}", chrono.serviceName, response.spansCount)
        // Every field printed, defaults too: a span at offset 0 has start 0, and no spans is an empty list.
        val structured =
            json
                .parseToJsonElement(JsonFormat.printer().alwaysPrintFieldsWithNoPresence().print(response))
                .jsonObject
        return CallToolResult(
            content = listOf(TextContent(text = scanSummary(response))),
            structuredContent = structured,
            isError = response.messagesList.any { it.severity == Severity.ERROR },
        )
    }

    private fun scanSummary(response: ScanResponse): String =
        buildList {
            if (response.spansCount > 0) {
                add(
                    "Time phrases:\n" +
                        response.spansList.joinToString("\n") { "- [${it.start}, ${it.end}) ${it.text}" },
                )
            } else {
                add("No time phrases.")
            }
            response.messagesList.forEach { add("${it.code}: ${it.humanMessage}") }
        }.joinToString("\n")

    // ----- shared handler -----

    private suspend fun handleGround(
        request: CallToolRequest,
        kind: EntityKind,
        client: GroundingClient,
        requireReferenceDatetime: Boolean,
    ): CallToolResult {
        val args = request.arguments
        val spanText = args?.get("spanText")?.jsonPrimitive?.contentOrNull
        if (spanText.isNullOrBlank()) return argError("Missing required argument: spanText")
        if (requireReferenceDatetime) {
            val ref =
                args["context"]
                    ?.jsonObject
                    ?.get("referenceDatetime")
                    ?.jsonPrimitive
                    ?.contentOrNull
            if (ref.isNullOrBlank()) {
                return argError("Missing required argument: context.referenceDatetime (load-bearing for ${kind.name})")
            }
        }

        val grpcRequest =
            try {
                toGroundRequest(args, kind)
            } catch (e: Exception) {
                logger.warn("bad grounding args for {}: {}", client.serviceName, e.message)
                return argError("Invalid arguments: ${e.message}")
            }

        val response =
            try {
                client.ground(grpcRequest)
            } catch (e: Exception) {
                logger.error("grounding call to '{}' failed for span='{}'", client.serviceName, spanText, e)
                return transportError(client.serviceName, e)
            }

        logger.info(
            "grounding tool ok | service={} | span='{}' | status={}",
            client.serviceName,
            spanText,
            response.status.name,
        )
        return toCallResult(response)
    }

    // ----- mapping (JsonFormat, both directions) -----

    /** camelCase args JSON → GroundRequest (via proto JSON), then stamp the router [kind]. */
    private fun toGroundRequest(
        args: JsonObject,
        kind: EntityKind,
    ): GroundRequest {
        val builder = GroundRequest.newBuilder()
        JsonFormat.parser().ignoringUnknownFields().merge(args.toString(), builder)
        return builder.setKind(kind).build()
    }

    /** GroundResponse → CallToolResult: camelCase mirror in structuredContent, readable text summary. */
    private fun toCallResult(response: GroundResponse): CallToolResult {
        val structured = json.parseToJsonElement(JsonFormat.printer().print(response)).jsonObject
        return CallToolResult(
            content = listOf(TextContent(text = textSummary(response))),
            structuredContent = structured,
            isError = false,
        )
    }

    private fun textSummary(response: GroundResponse): String =
        when (response.status) {
            GroundResponse.Status.OK ->
                buildString {
                    append(response.result.explanation.ifEmpty { "Grounded." })
                    if (response.result.sqlPreview.isNotEmpty()) {
                        append("\n\nSQL preview:\n").append(response.result.sqlPreview)
                    }
                }
            GroundResponse.Status.AWAITING_CLARIFICATION ->
                "Needs clarification — pick one:\n" +
                    response.optionsList.joinToString("\n") { "- [${it.id}] ${it.label}" }
            GroundResponse.Status.UNGROUNDABLE ->
                "Ungroundable" +
                    response.messagesList
                        .firstOrNull()
                        ?.humanMessage
                        ?.let { ": $it" }
                        .orEmpty()
            else -> response.status.name
        }

    private fun argError(message: String): CallToolResult =
        CallToolResult(
            isError = true,
            content = listOf(TextContent(text = message)),
            structuredContent =
                buildJsonObject {
                    put("errorCode", "INVALID_ARGUMENT")
                    put("error", message)
                },
        )

    private fun transportError(
        service: String,
        e: Exception,
    ): CallToolResult =
        CallToolResult(
            isError = true,
            content = listOf(TextContent(text = "grounding service '$service' call failed: ${e.message}")),
            structuredContent =
                buildJsonObject {
                    put("errorCode", "TRANSPORT_ERROR")
                    put("service", service)
                    put("error", e.message ?: "unknown error")
                },
        )

    // ----- schema -----

    private fun tool(
        name: String,
        description: String,
    ): Tool =
        Tool(
            name = name,
            description = description,
            inputSchema = inputSchema(),
            outputSchema = outputSchema(),
        )

    private fun inputSchema(): ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putJsonObject("spanText") {
                        put("type", "string")
                        put("description", "The entity span to ground, verbatim (e.g. \"May period\").")
                    }
                    putJsonObject("questionText") {
                        put("type", "string")
                        put("description", "The full user question, for context.")
                    }
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Metadata package scope, e.g. \"cnc\".")
                    }
                    putJsonObject("context") {
                        put("type", "object")
                        put("description", "Grounding context (turn state).")
                        putJsonObject("properties") {
                            putJsonObject("referenceDatetime") {
                                put("type", "string")
                                put("description", "ISO-8601 with offset — the turn's 'now'. Required for ground_time.")
                            }
                            putJsonObject("timezone") { put("type", "string") }
                            putJsonObject("locale") { put("type", "string") }
                            putJsonObject("defaultCurrency") { put("type", "string") }
                            putJsonObject("herePlaceRef") { put("type", "string") }
                            putJsonObject("fxPolicy") {
                                put("type", "string")
                                put("description", "TRANSACTION_DATE | CURRENT")
                            }
                            putJsonObject("tolerancePct") { put("type", "number") }
                        }
                    }
                    putJsonObject("clarificationAnswerId") {
                        put("type", "string")
                        put("description", "Echo a prior AWAITING_CLARIFICATION option id to resume (HITL).")
                    }
                    putJsonObject("correlationId") { put("type", "string") }
                },
            required = listOf("spanText"),
        )

    private fun scanInputSchema(): ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "The question, quoted literals blanked (length kept).")
                    }
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Metadata package scope, e.g. \"cnc\".")
                    }
                    putJsonObject("context") {
                        put("type", "object")
                        put("description", "Grounding context (turn state).")
                        putJsonObject("properties") {
                            putJsonObject("referenceDatetime") {
                                put("type", "string")
                                put("description", "ISO-8601 with offset — the turn's 'now'. Required.")
                            }
                            putJsonObject("timezone") { put("type", "string") }
                            putJsonObject("locale") { put("type", "string") }
                            putJsonObject("priorQuestions") {
                                put("type", "array")
                                put("description", "The user's earlier questions, newest first.")
                                putJsonObject("items") { put("type", "string") }
                            }
                        }
                    }
                },
            required = listOf("text", "context"),
        )

    private fun scanOutputSchema(): ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putJsonObject("spans") {
                        put("type", "array")
                        put(
                            "description",
                            "The time phrases, in text order: start, end (UTF-16 offsets, end exclusive), text, " +
                                "kind (DATE_TIME), confidence, trigger.",
                        )
                    }
                    putJsonObject("messages") {
                        put("type", "array")
                        put("description", "Diagnostic messages (e.g. a missing reference date).")
                    }
                },
            required = listOf("spans"),
        )

    private fun outputSchema(): ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putJsonObject("status") {
                        put("type", "string")
                        put("description", "OK | AWAITING_CLARIFICATION | UNGROUNDABLE")
                    }
                    putJsonObject("result") {
                        put("type", "object")
                        put("description", "GroundingResult (normalized + recipe + sqlPreview) when status = OK.")
                    }
                    putJsonObject("options") {
                        put("type", "array")
                        put("description", "Clarification options when status = AWAITING_CLARIFICATION.")
                    }
                    putJsonObject("messages") {
                        put("type", "array")
                        put("description", "Diagnostic messages (e.g. the ungroundable reason).")
                    }
                },
            required = listOf("status"),
        )
}

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (this is kotlinx.serialization.json.JsonNull) null else content
