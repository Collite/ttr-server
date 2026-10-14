// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.mcp

import com.google.protobuf.util.JsonFormat
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.Json
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.Option
import org.tatrman.resolver.v1.ResolveContext
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.ResumeAnswer

/** The pinned door tool id (RG-P6.S1, Decision A / naming ledger — the `resolve.*:v1` family). */
const val RESOLVE_TOOL_NAME: String = "resolve.bind:v1"

/**
 * The `resolve.bind:v1` MCP door tool (RG-P6.S1, RS-27). It exposes the deterministic
 * resolver core ONLY — no LLM, provenance-carrying, refusal-over-guess. One tool handles
 * both a fresh question and a clarification-resume (the proto `input` oneof): a `resume_token`
 * + `selected_option_id` routes the resume branch, otherwise `text` routes the fresh branch.
 *
 * Marshalling is deliberately thin (the "door exposes, never decides" line): a flat, agent-
 * ergonomic arg surface is hand-mapped into `ResolveRequest`, and `ResolveResponse` is mirrored
 * verbatim into `structuredContent` via protobuf [JsonFormat] (parse passthrough + capability
 * echo ride along by construction — F-T1/F-T3). The [resolve] seam is the pipeline's
 * `resolve(ResolveRequest)`; injecting it keeps the door unit-testable without an LLM or upstreams.
 */
class ResolveDoor(
    private val resolve: suspend (ResolveRequest) -> ResolveResponse,
) {
    val tool: Tool =
        Tool(
            name = RESOLVE_TOOL_NAME,
            description =
                "Resolve entity spans in a question to declared vocabulary via the deterministic core " +
                    "(zero LLM, provenance on every binding; refusal-over-guess — an ambiguous or " +
                    "below-threshold span returns a clarification, never a guessed binding). Fresh: pass " +
                    "`text` (+ optional `locale`). Resume a clarification: pass the opaque `resume_token` " +
                    "from the prior response plus the chosen `selected_option_id`.",
            inputSchema = inputSchema(),
            outputSchema = null,
        )

    /**
     * @param callerSubject the OBO subject id resolved by the fail-closed gate (empty
     *   on the dev-network no-identity path). It is stamped onto the request so the
     *   pipeline can sign it into a clarification's resume token and re-check it on
     *   resume (RG-P6 review C). The door never derives identity itself.
     */
    suspend fun call(
        args: JsonObject?,
        callerSubject: String = "",
    ): CallToolResult {
        val conversationId = args.str("conversation_id")
        if (conversationId.isNullOrBlank()) return argError("Missing required argument: conversation_id")

        val request =
            try {
                toRequest(args, conversationId, callerSubject)
            } catch (e: IllegalArgumentException) {
                return argError("Invalid arguments: ${e.message}")
            }

        val response = resolve(request)
        return toResult(response)
    }

    // ----- request marshalling (flat args → the fresh|resume oneof) -----

    private fun toRequest(
        args: JsonObject?,
        conversationId: String,
        callerSubject: String,
    ): ResolveRequest {
        val builder = ResolveRequest.newBuilder().setConversationId(conversationId)
        if (callerSubject.isNotBlank()) builder.callerSubject = callerSubject
        val resumeToken = args.str("resume_token")
        if (!resumeToken.isNullOrBlank()) {
            val selected =
                args.str("selected_option_id")
                    ?: throw IllegalArgumentException("a resume requires 'selected_option_id' alongside 'resume_token'")
            val resume = ResumeAnswer.newBuilder().setToken(resumeToken).setSelectedOptionId(selected)
            args.str("free_text_answer")?.let { resume.freeTextAnswer = it }
            builder.resume = resume.build()
        } else {
            val text =
                args.str("text")
                    ?: throw IllegalArgumentException(
                        "a fresh resolve requires 'text' (or 'resume_token' + 'selected_option_id' to resume)",
                    )
            val fresh = FreshQuestion.newBuilder().setText(text)
            args.str("locale")?.let { fresh.locale = it }
            builder.fresh = fresh.build()
        }

        val referenceDatetime = args.str("reference_datetime")
        val tenant = args.str("tenant")
        if (referenceDatetime != null || tenant != null) {
            val context = ResolveContext.newBuilder()
            referenceDatetime?.let { context.referenceDatetime = it }
            tenant?.let { context.tenant = it }
            builder.context = context.build()
        }
        return builder.build()
    }

    // ----- response marshalling (ResolveResponse → structuredContent verbatim) -----

    private fun toResult(response: ResolveResponse): CallToolResult {
        val structured = json.parseToJsonElement(printer.print(response)).jsonObject
        return CallToolResult(
            content = listOf(TextContent(text = summary(response))),
            structuredContent = structured,
            isError = false,
        )
    }

    private fun summary(response: ResolveResponse): String =
        when {
            response.hasAwaiting() -> askSummary(response.awaiting.optionsList)
            response.hasResolution() -> {
                val r = response.resolution
                val head = "Resolved ${r.bindingsCount} binding(s) (confidence ${r.confidence})."
                if (r.rationale.isNotEmpty()) "$head ${r.rationale}" else head
            }
            else -> "No resolution."
        }

    /**
     * The options of one span are one question. With one span the summary is what it always was. With
     * several (ttr-server#231: the gate asks about a member and a grounding kernel about a period) it
     * asks them one at a time, in the order the core put them, and says the same token redeems each —
     * a flat "pick one" over both would invite one pick for two questions and lose the other.
     */
    private fun askSummary(options: List<Option>): String {
        val questions = options.groupBy { it.span.start to it.span.end }.values.toList()
        if (questions.size <= 1) {
            return "Needs clarification — pick one:\n" + options.joinToString("\n") { "- [${it.id}] ${it.label}" }
        }
        return questions
            .mapIndexed { i, question ->
                val words = question.first().span.text
                val head =
                    if (i == 0) {
                        "Needs clarification, one question at a time. Pick one for „$words“:"
                    } else {
                        "Then pick one for „$words“ (resume again with the same resume_token):"
                    }
                head + "\n" + question.joinToString("\n") { "- [${it.id}] ${it.label}" }
            }.joinToString("\n")
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

    // ----- schema -----

    private fun inputSchema(): ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putJsonObject("conversation_id") {
                        put("type", "string")
                        put("description", "Stable id for the conversation this resolve belongs to (required).")
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "The user's question, verbatim. Required for a fresh resolve.")
                    }
                    putJsonObject("locale") {
                        put("type", "string")
                        put("description", "BCP-47 locale of the question (e.g. \"cs\"). Optional.")
                    }
                    putJsonObject("resume_token") {
                        put("type", "string")
                        put(
                            "description",
                            "The opaque token from a prior clarification response. Present it (with " +
                                "selected_option_id) to resume; omit it for a fresh resolve.",
                        )
                    }
                    putJsonObject("selected_option_id") {
                        put("type", "string")
                        put("description", "The id of the chosen clarification option. Required when resuming.")
                    }
                    putJsonObject("reference_datetime") {
                        put("type", "string")
                        put("description", "ISO-8601 with offset — the turn's 'now', for relative grounding. Optional.")
                    }
                    putJsonObject("tenant") {
                        put("type", "string")
                        put("description", "Tenant scope for vocabulary. Optional.")
                    }
                    putJsonObject("user_id") {
                        put("type", "string")
                        put(
                            "description",
                            "Trusted-network identity shortcut; omit when calling with an OBO Bearer token.",
                        )
                    }
                },
            required = listOf("conversation_id"),
        )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        // Proto3 default-value fields are omitted; that's the intended lean structuredContent.
        private val printer: JsonFormat.Printer = JsonFormat.printer().omittingInsignificantWhitespace()
    }
}

/** Read a string field from a nullable MCP args object, or null when absent / not a JSON string. */
internal fun JsonObject?.str(key: String): String? {
    val primitive = this?.get(key) as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else null
}
