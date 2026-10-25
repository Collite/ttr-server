// SPDX-License-Identifier: Apache-2.0
package org.tatrman.nlp.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.tatrman.nlp.mcp.client.NlpAnalyzeResult
import org.tatrman.nlp.mcp.client.NlpClient
import org.tatrman.nlp.mcp.client.NlpClientException
import org.tatrman.nlp.mcp.telemetry.NlpMcpTelemetry

/**
 * The single `analyze` MCP tool — a zero-logic wrapper translating MCP tool
 * calls into `NlpService.Analyze` (gRPC) on the Nlp service (contracts §1:
 * nlp-mcp exposes `Analyze` only; the predecessor platform's `parse` convenience shorthand
 * is dropped in the lean fork). [client] is nullable: a blank backend host →
 * `null` and every invocation surfaces a "not wired" error rather than crashing
 * boot (local-without-cluster mode, mirroring meta-mcp / fuzzy-mcp).
 */
class Tools(
    private val client: NlpClient?,
    private val telemetry: NlpMcpTelemetry,
) {
    private val logger = LoggerFactory.getLogger(Tools::class.java)

    private fun formatAnalyzeResultAsText(result: NlpAnalyzeResult): String {
        val sb = StringBuilder()
        sb.appendLine("=== NLP Analysis Results ===")
        sb.appendLine("Language: ${result.language} (confidence: ${result.languageConfidence})")
        sb.appendLine("Primary Engine: ${result.engineUsed}")
        sb.appendLine()

        if (result.tokens.isNotEmpty()) {
            sb.appendLine("--- Tokens (${result.tokens.size}) ---")
            result.tokens.forEach { token ->
                sb.appendLine(
                    "  ${token.text} [${token.upos}/${token.xpos}] lemma=${token.lemma} head=${token.depHead}:${token.depRelation}",
                )
            }
            sb.appendLine()
        }

        if (result.sentences.isNotEmpty()) {
            sb.appendLine("--- Sentences (${result.sentences.size}) ---")
            result.sentences.forEachIndexed { idx, span ->
                sb.appendLine("  Sentence ${idx + 1}: chars ${span.charStart}-${span.charEnd}")
            }
            sb.appendLine()
        }

        if (result.entities.isNotEmpty()) {
            sb.appendLine("--- Entities (${result.entities.size}) ---")
            result.entities.forEach { entity ->
                sb.appendLine(
                    "  ${entity.text} [${entity.label}] chars ${entity.charStart}-${entity.charEnd} from ${entity.sourceEngine}",
                )
            }
            sb.appendLine()
        }

        if (result.messages.isNotEmpty()) {
            sb.appendLine("--- Messages ---")
            result.messages.forEach { msg ->
                sb.appendLine("  [${msg.severity}] ${msg.code}: ${msg.message}")
            }
        }

        sb.appendLine()
        sb.appendLine("Trace: ${result.traceId} | Elapsed: ${result.elapsedMs}ms")
        return sb.toString()
    }

    @Suppress("ktlint:standard:max-line-length")
    private fun createAnalyzeTool(): Tool =
        Tool(
            name = "analyze",
            description = "Run full NLP analysis (tokenization, lemmatization, POS tagging, dependency parsing, NER) on input text",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("text") {
                                put("type", "string")
                                put("description", "Input text to analyze")
                            }
                            putJsonObject("language") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Language code (e.g., 'cs' for Czech, 'en' for English). Empty triggers auto-detection.",
                                )
                            }
                            putJsonObject("ops") {
                                put("type", "array")
                                put(
                                    "description",
                                    "Operations to perform: TOKENIZE, SENTENCE_SPLIT, LEMMATIZE, POS_TAG, DEP_PARSE, NER, DETECT_LANGUAGE",
                                )
                                putJsonObject("items") {
                                    put("type", "string")
                                }
                            }
                            putJsonObject("mode") {
                                put("type", "string")
                                put("description", "Analysis mode: NORMAL (default) or COMPARE (runs all engines)")
                            }
                            putJsonObject("engineHints") {
                                put("type", "object")
                                put("description", "Engine override per operation (e.g., {'NER': 'nametag'})")
                            }
                        },
                    required = listOf("text", "ops"),
                ),
            outputSchema = null,
        )

    val analyzeTool = createAnalyzeTool()

    private fun getJsonPrimitive(element: JsonElement?): String? = element?.jsonPrimitive?.content

    suspend fun analyzeCallback(request: CallToolRequest): CallToolResult {
        val args = request.arguments

        val text = getJsonPrimitive(args?.get("text"))
        if (text.isNullOrBlank()) {
            logger.info("analyze completed | missing text | isError=true")
            return CallToolResult(
                isError = true,
                content = listOf(TextContent(text = "Missing required argument: text")),
            )
        }

        val language = getJsonPrimitive(args?.get("language")) ?: ""
        val mode = getJsonPrimitive(args?.get("mode")) ?: "NORMAL"

        val opsArray = args?.get("ops")?.jsonArray
        val ops = opsArray?.mapNotNull { it.jsonPrimitive.content }?.toSet() ?: emptySet()

        if (ops.isEmpty()) {
            logger.info("analyze completed | missing ops | isError=true")
            return CallToolResult(
                isError = true,
                content =
                    listOf(
                        TextContent(text = "Missing required argument: ops (must include at least one operation)"),
                    ),
            )
        }

        if (client == null) {
            logger.info("analyze completed | client not wired | isError=true")
            return CallToolResult(
                isError = true,
                content = listOf(TextContent(text = "Error: nlp service not wired (nlp.host is blank)")),
            )
        }

        val engineHintsElement = args?.get("engineHints")?.jsonObject
        val engineHints =
            engineHintsElement?.entries?.associate { it.key to it.value.jsonPrimitive.content } ?: emptyMap()

        logger.info(
            "MCP analyze tool invocation: text_len={}, language='{}', ops={}, mode='{}', engineHints={}",
            text.length,
            language,
            ops,
            mode,
            engineHints,
        )

        return try {
            val result = client.analyze(text, language, ops, mode, engineHints)
            val output = formatAnalyzeResultAsText(result)
            logger.info(
                "analyze completed | success | language={} | engine={} | tokens={} | entities={} | elapsed={}ms | isError=false",
                result.language,
                result.engineUsed,
                result.tokens.size,
                result.entities.size,
                result.elapsedMs,
            )
            CallToolResult(
                content = listOf(TextContent(text = output)),
            )
        } catch (e: NlpClientException) {
            logger.error("Error in analyze tool: {}", e.message)
            logger.info("analyze completed | error | isError=true")
            CallToolResult(
                isError = true,
                content = listOf(TextContent(text = "Error: ${e.message}")),
            )
        } catch (e: Exception) {
            logger.error("Unexpected error in analyze tool", e)
            logger.info("analyze completed | unexpected error | isError=true")
            CallToolResult(
                isError = true,
                content = listOf(TextContent(text = "Internal error: ${e.message}")),
            )
        }
    }
}
