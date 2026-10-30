// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.mcp

import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.tatrman.assertion.v1.Assertion
import org.tatrman.concept.Diagnostic
import org.tatrman.concept.Layer
import org.tatrman.concept.PlaceholderStyle
import org.tatrman.concept.RenderMode
import org.tatrman.concept.RenderOptions
import org.tatrman.concept.Severity
import org.tatrman.concept.v1.Candidate
import org.tatrman.concept.v1.ConceptRef
import org.tatrman.concept.v1.MemberRef
import org.tatrman.resolver.concept.ConceptModel
import org.tatrman.resolver.concept.ConceptModels
import org.tatrman.resolver.concept.ConceptSearch
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.statement.v1.Statement
import java.time.Instant
import java.time.format.DateTimeParseException

// The pinned door tool ids (cn contracts §4 + §9 naming ledger; mcp-surface §2.2) — the four cn families.
const val STATEMENT_VALIDATE_TOOL_NAME: String = "statement.validate:v1"
const val STATEMENT_LOWER_TOOL_NAME: String = "statement.lower:v1"
const val STATEMENT_EXPLAIN_TOOL_NAME: String = "statement.explain:v1"
const val ASSERTION_VALIDATE_TOOL_NAME: String = "assertion.validate:v1"
const val ASSERTION_LOWER_TOOL_NAME: String = "assertion.lower:v1"
const val ASSERTION_EXPLAIN_TOOL_NAME: String = "assertion.explain:v1"
const val CONCEPT_SEARCH_TOOL_NAME: String = "concept.search:v1"
const val DEFINITION_RENDER_TOOL_NAME: String = "definition.render:v1"

/**
 * CN-P5 S5.1 (cn contracts §4, AM-10) — the four cn door families, co-hosted beside `resolve.bind:v1` on the
 * resolver's MCP server. Like that door, they expose the deterministic only: every tool is one of ttr-concept's pure
 * functions over a snapshot-pinned model ([ConceptModels]), or (`concept.search:v1`) a view over the matcher and the
 * resolver's own lift ([ConceptSearch]). No LLM, no escalation ladder (RS-29: composing one is the frame builder's), no
 * write (`assertion.lower:v1` returns rows; nothing on this classpath can store them — `verifyNoDbClient`).
 *
 * **Marshalling is thin, as on `resolve.bind:v1`.** A frame arrives as its protobuf JSON (`JsonFormat`, strict: an
 * unknown field is an argument error, never ignored) and the result leaves as [ConceptJson]'s one shape per result.
 * Flat arguments are snake_case, the door family's spelling (`resume_token`, `max_candidates`, `placeholder_style`).
 *
 * **Refusal over guess.** A frame the module refuses is a result with `refused: true` and its cn findings
 * (A-CN-57) — `isError` is for what is not the frame's fault: bad arguments, no model ([ConceptModels.State]), a
 * resume token that does not verify.
 *
 * **Resume tokens are verified, never minted (architecture §8, C-Δ3).** A frame's `Candidate.resume_token` or
 * `MemberRef.resume_token` is a resolver clarification token; the door checks it with the resolver's own codec — the
 * signature, its age, the caller it was issued to, and that it offered what the slot names — and refuses the call
 * (`RG-RES-002`) on any failure, so a frame cannot carry a pin the resolver never offered. `concept.search:v1` returns
 * candidates without one.
 */
class ConceptDoor(
    private val models: ConceptModels,
    private val search: ConceptSearch,
    private val tokens: ResumeTokenCodec,
) {
    val tools: List<Tool> =
        listOf(
            frameTool(
                STATEMENT_VALIDATE_TOOL_NAME,
                "statement",
                "Validate a cn statement frame (org.tatrman.statement.v1.Statement, protobuf JSON) against " +
                    "the estate's conceptual model: structural, referential, ambiguity, time and per-consumer " +
                    "reachability checks. Refusal over guess — an ambiguous slot is a `cn/ambiguous-statement` " +
                    "finding, never a choice.",
            ),
            frameTool(
                STATEMENT_LOWER_TOOL_NAME,
                "statement",
                "Lower a cn statement frame to a plan.v1 read over the md tier, with its dot-path and provenance " +
                    "(binding paths, inlined definitions, parameters, as_of). Refuses — no partial plan — on any " +
                    "validation error.",
            ),
            frameTool(
                STATEMENT_EXPLAIN_TOOL_NAME,
                "statement",
                "Explain a cn statement frame in words (en or cs): concept labels, phase definitions as prose, " +
                    "parameters named. A refused frame is still explained, with its refusal.",
                withLocale = true,
            ),
            frameTool(
                ASSERTION_VALIDATE_TOOL_NAME,
                "assertion",
                "Validate a cn assertion frame (org.tatrman.assertion.v1.Assertion, protobuf JSON): refs resolved, " +
                    "the concept assertable (an event, a validity row, a phase episode or a subkind), participant " +
                    "labels and paths, qualities, units, period and asserted_as_of.",
            ),
            frameTool(
                ASSERTION_LOWER_TOOL_NAME,
                "assertion",
                "Lower a cn assertion frame to the concept's row payload (foreign-key values via the labelled " +
                    "paths). Never writes. Refuses — no rows — on any validation error.",
            ),
            frameTool(
                ASSERTION_EXPLAIN_TOOL_NAME,
                "assertion",
                "Explain a cn assertion frame in words (en or cs) — the inspector's sentence.",
                withLocale = true,
            ),
            Tool(
                name = CONCEPT_SEARCH_TOOL_NAME,
                description =
                    "Find the concepts a span of text can name: the concept terms it matches and the concepts " +
                        "its element or member matches lift to, each with its stereotype, `via` and every source. " +
                        "Returns concepts only — never a member — after the resolver's own ordering (a phase " +
                        "yields to its bearer; no phase is a default sense).",
                inputSchema = searchSchema(),
                outputSchema = null,
            ),
            Tool(
                name = DEFINITION_RENDER_TOOL_NAME,
                description =
                    "Render a concept's definition (a phase, a defined quality, a member-restricted kind) on " +
                        "one layer: `er` — SQL over entities in the shared dialect, `{name}` placeholders for the " +
                        "parameters it reads; `md` — the PL predicate on its bearer's cubelet. Refuses a concept " +
                        "the layer cannot reach.",
                inputSchema = renderSchema(),
                outputSchema = null,
            ),
        )

    /** One call — [tool] is one of [tools]' names; [callerSubject] is the OBO subject the identity gate resolved. */
    suspend fun call(
        tool: String,
        args: JsonObject?,
        callerSubject: String = "",
    ): CallToolResult =
        try {
            when (tool) {
                CONCEPT_SEARCH_TOOL_NAME -> search(args)
                else -> withModel { model -> dispatch(tool, args, model, callerSubject) }
            }
        } catch (e: BadArgument) {
            error(INVALID_ARGUMENT, e.message ?: "invalid arguments")
        }

    private suspend fun dispatch(
        tool: String,
        args: JsonObject?,
        model: ConceptModel,
        callerSubject: String,
    ): CallToolResult {
        val snapshot = model.resolved.snapshotHash
        val cn = model.service
        return when (tool) {
            STATEMENT_VALIDATE_TOOL_NAME ->
                statement(args).let { s ->
                    verify(s, callerSubject) ?: validation(snapshot, cn.statementValidate(s))
                }
            STATEMENT_LOWER_TOOL_NAME ->
                statement(args).let { s ->
                    verify(s, callerSubject) ?: cn.statementLower(s).let { r ->
                        result(
                            summary(
                                r.diagnostics,
                                when {
                                    r.plan != null -> "Lowered: ${r.dotPath}"
                                    r.erSql.isNotEmpty() -> "Lowered on E-R: ${r.erSql.lineSequence().first()} …"
                                    else -> null
                                },
                            ),
                            ConceptJson.lowering(snapshot, r),
                        )
                    }
                }
            STATEMENT_EXPLAIN_TOOL_NAME ->
                statement(args).let { s ->
                    verify(s, callerSubject) ?: cn.statementExplain(s, args.str("locale").orEmpty()).let { e ->
                        result(e.text, ConceptJson.explanation(snapshot, e))
                    }
                }
            ASSERTION_VALIDATE_TOOL_NAME ->
                assertion(args).let { a ->
                    verify(a, callerSubject) ?: validation(snapshot, cn.assertionValidate(a))
                }
            ASSERTION_LOWER_TOOL_NAME ->
                assertion(args).let { a ->
                    verify(a, callerSubject) ?: cn.assertionLower(a).let { r ->
                        val head =
                            if (r.rows.isNotEmpty()) {
                                "Row payload: ${r.rows.joinToString {
                                    it.entity
                                }}"
                            } else {
                                null
                            }
                        result(summary(r.diagnostics, head), ConceptJson.rows(snapshot, r))
                    }
                }
            ASSERTION_EXPLAIN_TOOL_NAME ->
                assertion(args).let { a ->
                    verify(a, callerSubject) ?: cn.assertionExplain(a, args.str("locale").orEmpty()).let { e ->
                        result(e.text, ConceptJson.explanation(snapshot, e))
                    }
                }
            DEFINITION_RENDER_TOOL_NAME -> render(args, model)
            else -> error(INVALID_ARGUMENT, "unknown tool: $tool")
        }
    }

    // ----- concept.search:v1 -----

    private suspend fun search(args: JsonObject?): CallToolResult {
        val text = args.str("text")?.takeIf { it.isNotBlank() } ?: throw BadArgument("Missing required argument: text")
        val max = (args?.get("max_candidates") as? JsonPrimitive)?.intOrNull ?: ConceptSearch.DEFAULT_MAX
        // The span names what the resolver's vocabulary names, so the snapshot is the registry's — the model is not
        // read, and a search answers on an estate whose doors have no model configured.
        val (snapshot, hits) = search.search(text, max)
        val head =
            if (hits.isEmpty()) {
                "No concept for „$text“."
            } else {
                "Concepts for „$text“: " + hits.joinToString { it.qname }
            }
        return result(head, ConceptJson.search(snapshot, args.str("locale").orEmpty(), hits))
    }

    // ----- definition.render:v1 -----

    private fun render(
        args: JsonObject?,
        model: ConceptModel,
    ): CallToolResult {
        val concept =
            args.str("concept")?.takeIf { it.isNotBlank() } ?: throw BadArgument("Missing required argument: concept")
        val layer =
            when (args.str("layer")?.lowercase()) {
                "er" -> Layer.ER
                "md" -> Layer.MD
                null -> throw BadArgument("Missing required argument: layer (`er` or `md`)")
                else -> throw BadArgument("layer must be `er` or `md`, not `${args.str("layer")}`")
            }
        val options =
            RenderOptions(
                mode = enumArg(args, "mode", RenderMode.PREDICATE),
                placeholderStyle = enumArg(args, "placeholder_style", PlaceholderStyle.CURLY),
                asOf =
                    args.str("as_of")?.let {
                        try {
                            Instant.parse(it)
                        } catch (e: DateTimeParseException) {
                            throw BadArgument("as_of must be an ISO-8601 instant (e.g. 2026-01-31T00:00:00Z): $it")
                        }
                    },
                candidates = args.str("candidates")?.let { ConceptRef.newBuilder().setQname(it).build() },
                columns = (args?.get("columns") as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty(),
            )
        val r =
            model.service.definitionRender(
                ConceptRef.newBuilder().setQname(concept).build(),
                layer,
                args.str("dialect").orEmpty(),
                options,
            )
        val head = if (r.text.isNotEmpty()) r.text else null
        return result(summary(r.diagnostics, head), ConceptJson.rendering(model.resolved.snapshotHash, r))
    }

    private inline fun <reified E : Enum<E>> enumArg(
        args: JsonObject?,
        key: String,
        default: E,
    ): E {
        val raw = args.str(key) ?: return default
        return enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw BadArgument(
                "$key must be one of ${enumValues<E>().joinToString { it.name.lowercase() }}, not `$raw`",
            )
    }

    // ----- frames -----

    private fun statement(args: JsonObject?): Statement = frame(args, "statement", Statement.newBuilder())

    private fun assertion(args: JsonObject?): Assertion = frame(args, "assertion", Assertion.newBuilder())

    @Suppress("UNCHECKED_CAST")
    private fun <M : Message> frame(
        args: JsonObject?,
        key: String,
        builder: Message.Builder,
    ): M {
        val raw = args?.get(key) as? JsonObject ?: throw BadArgument("Missing required argument: $key (an object)")
        try {
            parser.merge(raw.toString(), builder)
        } catch (e: InvalidProtocolBufferException) {
            throw BadArgument("$key is not a valid ${builder.descriptorForType.fullName}: ${e.message}")
        }
        return builder.build() as M
    }

    /**
     * Every resume token [frame] carries, checked with the resolver's codec — null when all verify (or there are none),
     * else the `RG-RES-002` refusal naming the slot.
     */
    private fun verify(
        frame: Message,
        callerSubject: String,
    ): CallToolResult? {
        val found = mutableListOf<Pair<String, Message>>()
        collectTokens(frame, "", found)
        for ((slot, ref) in found) {
            val token = ref.getField(ref.descriptorForType.findFieldByName("resume_token")) as String
            val payload = tokens.verify(token).getOrElse { return tokenRefusal(slot, it.message ?: "rejected") }
            if (payload.subject != callerSubject) return tokenRefusal(slot, "issued to another caller")
            val offered =
                when (ref) {
                    is Candidate -> payload.options.any { it.conceptQname == ref.qname || it.targetRef == ref.qname }
                    is MemberRef ->
                        ref.resolvedValue.isEmpty() ||
                            payload.options.any { it.resolvedId == ref.resolvedValue }
                    else -> true
                }
            if (!offered) return tokenRefusal(slot, "the clarification it belongs to offered no such option")
        }
        return null
    }

    private fun collectTokens(
        message: Message,
        path: String,
        out: MutableList<Pair<String, Message>>,
    ) {
        if ((message is Candidate && message.resumeToken.isNotEmpty()) ||
            (message is MemberRef && message.resumeToken.isNotEmpty())
        ) {
            out += path to message
        }
        for ((field, value) in message.allFields) {
            if (field.javaType != FieldDescriptor.JavaType.MESSAGE) continue
            val name = if (path.isEmpty()) field.jsonName else "$path.${field.jsonName}"
            if (field.isRepeated) {
                (value as List<*>).forEachIndexed { i, v -> collectTokens(v as Message, "$name[$i]", out) }
            } else {
                collectTokens(value as Message, name, out)
            }
        }
    }

    private fun tokenRefusal(
        slot: String,
        reason: String,
    ): CallToolResult = error(RESUME_TOKEN_REJECTED, "the resume token at `$slot` does not verify: $reason")

    // ----- results -----

    private suspend fun withModel(body: suspend (ConceptModel) -> CallToolResult): CallToolResult =
        when (val state = models.current()) {
            is ConceptModels.State.Ready -> body(state.model)
            is ConceptModels.State.Unavailable -> error(state.code, state.message)
        }

    private fun validation(
        snapshot: String,
        diagnostics: List<Diagnostic>,
    ): CallToolResult = result(summary(diagnostics, "Valid."), ConceptJson.validation(snapshot, diagnostics))

    /** The content line: the refusal's findings when refused, else [ok] (with any warnings counted). */
    private fun summary(
        diagnostics: List<Diagnostic>,
        ok: String?,
    ): String {
        val errors = diagnostics.filter { it.severity == Severity.ERROR }
        if (errors.isNotEmpty() || ok == null) {
            return "Refused: " +
                errors.joinToString("; ") { d -> d.slug + (if (d.subject.isNotEmpty()) " (${d.subject})" else "") }
        }
        val warnings = diagnostics.count { it.severity == Severity.WARNING }
        return if (warnings == 0) ok else "$ok ($warnings warning(s))"
    }

    private fun result(
        text: String,
        structured: JsonObject,
    ): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = text)), structuredContent = structured, isError = false)

    private fun error(
        code: String,
        message: String,
    ): CallToolResult =
        CallToolResult(
            isError = true,
            content = listOf(TextContent(text = message)),
            structuredContent =
                buildJsonObject {
                    put("errorCode", code)
                    put("error", message)
                },
        )

    private class BadArgument(
        message: String,
    ) : IllegalArgumentException(message)

    // ----- schemas -----

    private fun frameTool(
        name: String,
        frame: String,
        description: String,
        withLocale: Boolean = false,
    ): Tool =
        Tool(
            name = name,
            description = description,
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject(frame) {
                                put("type", "object")
                                put(
                                    "description",
                                    "The frame as protobuf JSON (org.tatrman.$frame.v1.${frame.replaceFirstChar {
                                        it
                                            .uppercase()
                                    }}).",
                                )
                            }
                            if (withLocale) {
                                putJsonObject("locale") {
                                    put("type", "string")
                                    put(
                                        "description",
                                        "BCP-47 locale to explain in (en, cs); blank = the frame's locale.",
                                    )
                                }
                            }
                            userIdProperty()
                        },
                    required = listOf(frame),
                ),
            outputSchema = null,
        )

    private fun searchSchema(): ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "The span of text, verbatim (required).")
                    }
                    putJsonObject("locale") {
                        put("type", "string")
                        put(
                            "description",
                            "BCP-47 locale of the span. Echoed; the matcher folds across the lexicon's locales.",
                        )
                    }
                    putJsonObject("max_candidates") {
                        put("type", "integer")
                        put(
                            "description",
                            "How many matcher rows to read (default ${ConceptSearch.DEFAULT_MAX}, at most ${ConceptSearch.MAX}).",
                        )
                    }
                    userIdProperty()
                },
            required = listOf("text"),
        )

    private fun renderSchema(): ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putJsonObject("concept") {
                        put("type", "string")
                        put("description", "The concept to render, `cn.<Name>` (required).")
                    }
                    putJsonObject("layer") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add(JsonPrimitive("er"))
                            add(JsonPrimitive("md"))
                        }
                        put("description", "The layer to render on (required).")
                    }
                    putJsonObject("dialect") {
                        put("type", "string")
                        put(
                            "description",
                            "The estate's dialect. The er text is the shared dialect's; the translator unparses it per engine.",
                        )
                    }
                    putJsonObject("mode") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add(JsonPrimitive("predicate"))
                            add(JsonPrimitive("context"))
                        }
                        put(
                            "description",
                            "predicate (default): the bearer's keys where the definition holds; context (er): the bearer joined to `candidates`.",
                        )
                    }
                    putJsonObject("placeholder_style") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add(JsonPrimitive("curly"))
                            add(JsonPrimitive("colon"))
                        }
                        put("description", "curly (default): `{name}`; colon: `:name`.")
                    }
                    putJsonObject("as_of") {
                        put("type", "string")
                        put("description", "ISO-8601 instant the definition is evaluated at; absent = now, unbounded.")
                    }
                    putJsonObject("candidates") {
                        put("type", "string")
                        put("description", "context mode: the phase whose bearer keys the context, `cn.<Name>`.")
                    }
                    putJsonObject("columns") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "context mode: columns after the keys.")
                    }
                    userIdProperty()
                },
            required = listOf("concept", "layer"),
        )

    private fun kotlinx.serialization.json.JsonObjectBuilder.userIdProperty() {
        putJsonObject("user_id") {
            put("type", "string")
            put("description", "Trusted-network identity shortcut; omit when calling with an OBO Bearer token.")
        }
    }

    companion object {
        const val INVALID_ARGUMENT = "INVALID_ARGUMENT"

        /** RG-RES-002 — the resolver's own condition for a resume token that does not verify (mcp-surface §2.2). */
        const val RESUME_TOKEN_REJECTED = "RG-RES-002"

        /** Strict: a field the frame's message does not have is an argument error, never silently dropped. */
        private val parser: JsonFormat.Parser = JsonFormat.parser()

        /** Every tool this door answers. */
        val TOOL_NAMES: List<String> =
            listOf(
                STATEMENT_VALIDATE_TOOL_NAME,
                STATEMENT_LOWER_TOOL_NAME,
                STATEMENT_EXPLAIN_TOOL_NAME,
                ASSERTION_VALIDATE_TOOL_NAME,
                ASSERTION_LOWER_TOOL_NAME,
                ASSERTION_EXPLAIN_TOOL_NAME,
                CONCEPT_SEARCH_TOOL_NAME,
                DEFINITION_RENDER_TOOL_NAME,
            )
    }
}
