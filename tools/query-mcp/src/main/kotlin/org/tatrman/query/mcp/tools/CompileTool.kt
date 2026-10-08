// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.tools

import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.parseSchemaCode
import org.tatrman.translate.v1.UnparseRequest
import org.tatrman.validate.v1.ValidateRequest
import org.tatrman.validate.v1.ValidationOptions
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import org.tatrman.query.mcp.QueryMcpConfig
import org.tatrman.mcp.identity.UserIdentity
import org.tatrman.query.mcp.mcp.MessageEntry
import org.tatrman.query.mcp.mcp.McpTool
import org.tatrman.query.mcp.mcp.PipelineWarnings
import org.tatrman.query.mcp.mcp.boolFieldOr
import org.tatrman.query.mcp.mcp.buildErrorResult
import com.google.protobuf.TextFormat
import org.tatrman.query.mcp.mcp.messagesArray
import org.tatrman.query.mcp.mcp.objectFieldOrEmpty
import org.tatrman.query.mcp.mcp.stringFieldOrNull
import org.tatrman.query.mcp.upstream.TranslatorClient
import org.tatrman.query.mcp.upstream.ValidatorClient
import java.util.UUID

/**
 * MCP `compile` tool — runs the front of the pipeline (Translator + optional
 * Validator) without executing, returning the SQL that *would* be sent to
 * the Worker. Useful for dry-run / explain surfaces and for agents that
 * want to inspect security predicates wrapped by Validator.
 */
class CompileTool(
    private val cfg: QueryMcpConfig,
    private val translator: TranslatorClient,
    private val validator: ValidatorClient,
) : McpTool {
    private val logger = LoggerFactory.getLogger(CompileTool::class.java)

    override val name: String = "compile"
    override val description: String =
        """Compile a query into SQL without executing. Runs Translator → optional Validator → Translator
        UnparseFromRelNode in the chosen target dialect; returns the SQL plus a structured envelope.
        Default applies row-level security predicates; admins may bypass with apply_security=false.
        Optional source_schema (er | db | obj) names the catalog the source reads, as for query."""

    override val inputSchema: ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    put("source", literalSchemaProp("string", "Query source text in the chosen language."))
                    put(
                        "source_language",
                        enumSchemaProp(
                            description = "Source language: sql | transdsl | dfdsl | rel_node",
                            values = listOf("sql", "transdsl", "dfdsl", "rel_node"),
                        ),
                    )
                    put(
                        "target_dialect",
                        enumSchemaProp(
                            description = "Target SQL dialect.",
                            values = listOf("mssql", "postgresql", "mysql_mariadb"),
                        ),
                    )
                    put(
                        "source_schema",
                        enumSchemaProp(
                            description =
                                "Catalog the source's identifiers resolve against: er (entity model) | " +
                                    "db (physical tables) | obj. Optional; when absent the translator detects " +
                                    "it from the source. Pass the value given to `query` (which treats an " +
                                    "absent value as db for sql) to compile the query `query` runs.",
                            values = listOf("er", "db", "obj"),
                        ),
                    )
                    put(
                        "parameters",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Bound parameters keyed by name. Bare scalar: type inferred from value. " +
                                        "Typed object: {\"value\":<v>,\"type\":\"varchar|int|decimal|bool|date|…\"}," +
                                        " declared type takes precedence over inference.",
                                ),
                            )
                        },
                    )
                    put(
                        "apply_security",
                        literalSchemaProp(
                            "boolean",
                            "Apply row-level security predicates (default true). Setting false requires admin role.",
                        ),
                    )
                    put(
                        "user_id",
                        literalSchemaProp(
                            "string",
                            "Trusted-network user_id override; ignored when an Authorization token resolves.",
                        ),
                    )
                },
            required = listOf("source", "source_language", "target_dialect"),
        )

    override suspend fun execute(
        request: CallToolRequest,
        identity: UserIdentity?,
    ): CallToolResult {
        val args = (request.params.arguments ?: JsonObject(emptyMap()))
        val source = args.stringFieldOrNull("source")
        val sourceLanguageStr = args.stringFieldOrNull("source_language")
        val targetDialectStr = args.stringFieldOrNull("target_dialect")

        if (source.isNullOrBlank() || sourceLanguageStr.isNullOrBlank() || targetDialectStr.isNullOrBlank()) {
            return buildErrorResult(
                name,
                "missing_required_field",
                "source, source_language, and target_dialect are required.",
            )
        }
        val sourceLanguage =
            parseSourceLanguage(sourceLanguageStr)
                ?: return buildErrorResult(
                    name,
                    "unknown_source_language",
                    "Unknown source_language '$sourceLanguageStr'. Use sql | transdsl | dfdsl | rel_node.",
                )
        val targetDialect =
            parseSqlDialect(targetDialectStr)
                ?: return buildErrorResult(
                    name,
                    "unknown_target_dialect",
                    "Unknown target_dialect '$targetDialectStr'. Use mssql | postgresql | mysql_mariadb.",
                )
        // Parsed with `parseSchemaCode`, as `query` parses it. Absent (or blank), the ParseRequest
        // carries no source schema, exactly as before this argument existed, and the translator
        // decides which catalog the source reads. An explicit value is passed through unchanged;
        // an unrecognised one is rejected rather than ignored.
        val sourceSchemaStr = args.stringFieldOrNull("source_schema")?.takeIf { it.isNotBlank() }
        val sourceSchema =
            sourceSchemaStr?.let {
                parseSchemaCode(it)
                    ?: return buildErrorResult(
                        name,
                        "unknown_source_schema",
                        "Unknown source_schema '$it'. Use er | db | obj.",
                    )
            }
        val applySecurity = args.boolFieldOr("apply_security", true)
        if (!applySecurity && identity?.isAdmin != true) {
            return buildErrorResult(
                name,
                "permission_denied",
                "apply_security=false requires admin role.",
            )
        }

        val parametersObj = args.objectFieldOrEmpty("parameters")
        val correlationId = UUID.randomUUID().toString()
        val context0 =
            PipelineContext
                .newBuilder()
                .setCorrelationId(correlationId)
                .setUserId(identity?.id ?: "")
                .addAllAuthRoles(identity?.roles ?: emptySet())
                .addAllParameters(parametersToBindings(parametersObj.toMap()))
                .build()

        // Step 1 — Translator.ParseToRelNode (source → PlanNode in target schema=DB).
        val parseResp =
            try {
                translator.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource(source)
                        .setSourceLanguage(sourceLanguage)
                        .setTargetSchema(SchemaCode.DB)
                        .setContext(context0)
                        .also { b -> sourceSchema?.let { b.setSourceSchema(it) } }
                        .build(),
                )
            } catch (e: StatusRuntimeException) {
                return errorFromStatus("translator_unreachable", e.status)
            } catch (e: StatusException) {
                return errorFromStatus("translator_unreachable", e.status)
            }
        if (translatorRejected(parseResp.messagesList)) {
            return rejectionResult("translator_rejected", parseResp.messagesList, parseResp.context)
        }
        val planAfterParse = parseResp.plan
        val ctxAfterParse = parseResp.context

        // Step 2 — optional Validator (security predicates + rules).
        val planForUnparse =
            if (applySecurity) {
                val validateResp =
                    try {
                        validator.validate(
                            ValidateRequest
                                .newBuilder()
                                .setPlan(planAfterParse)
                                .setContext(ctxAfterParse)
                                .setOptions(
                                    ValidationOptions
                                        .newBuilder()
                                        .setApplySecurity(true)
                                        .setLlmGuard(false)
                                        .build(),
                                ).build(),
                        )
                    } catch (e: StatusRuntimeException) {
                        return errorFromStatus("validator_unreachable", e.status)
                    } catch (e: StatusException) {
                        return errorFromStatus("validator_unreachable", e.status)
                    }
                if (validatorRejected(validateResp)) {
                    return rejectionResult("validator_rejected", validateResp.messagesList, validateResp.context)
                }
                validateResp.plan
            } else {
                planAfterParse
            }

        // Step 3 — Translator.UnparseFromRelNode → dialect-specific SQL.
        val unparseResp =
            try {
                translator.unparseFromRelNode(
                    UnparseRequest
                        .newBuilder()
                        .setPlan(planForUnparse)
                        .setTargetLanguage(Language.SQL)
                        .setTargetDialect(targetDialect)
                        .setOptimize(true)
                        .setContext(ctxAfterParse)
                        .build(),
                )
            } catch (e: StatusRuntimeException) {
                return errorFromStatus("translator_unreachable", e.status)
            } catch (e: StatusException) {
                return errorFromStatus("translator_unreachable", e.status)
            }
        if (translatorRejected(unparseResp.messagesList)) {
            return rejectionResult("translator_rejected", unparseResp.messagesList, unparseResp.context)
        }

        val sql = unparseResp.output
        val finalCtx = unparseResp.context

        // G7: surface accumulated upstream warnings (translator + validator stages).
        val pipelineWarnings = PipelineWarnings.toJsonArray(finalCtx.warningsList)

        val structured =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("tool", JsonPrimitive(name))
                put("compiledSql", JsonPrimitive(sql))
                put("targetDialect", JsonPrimitive(targetDialectStr.lowercase()))
                put("appliedSecurity", JsonPrimitive(applySecurity))
                // The plan itself, as text. `CompileResponse` has carried the PlanNode all
                // along; this tool simply never surfaced it, so a caller wanting to show what
                // it was about to run had only the SQL — the compiled form, after the plan had
                // been unparsed away.
                //
                // ⚑ This is `planForUnparse`: the plan AFTER the validator, which is the one
                // that runs. Rendering `planAfterParse` would show a reader a plan without the
                // row-level predicates the estate injected — a materially different query
                // presented as the real one.
                //
                // Proto TEXT FORMAT rather than a bespoke pretty-printer. PlanNode is a oneof of
                // thirteen node types with nested inputs; a hand-written renderer would have to
                // grow a case per node and would silently omit any it had not been taught,
                // which for a plan display is the one failure that matters. Text format renders
                // the tree indented, cannot omit a field, and needs no maintenance when a node
                // is added. It is the wire form, and it is named as such.
                put("relPlanText", JsonPrimitive(TextFormat.printer().printToString(planForUnparse)))
                put(
                    "parameterPlan",
                    buildJsonArray {
                        for (param in finalCtx.parametersList) {
                            add(
                                buildJsonObject {
                                    put("name", JsonPrimitive(param.name))
                                    put("type", JsonPrimitive(param.type))
                                    put("bound", JsonPrimitive(param.hasValue()))
                                    if (param.label.isNotEmpty()) put("label", JsonPrimitive(param.label))
                                },
                            )
                        }
                    },
                )
                put("messages", messagesArray(emptyList()))
                put("pipelineWarnings", pipelineWarnings)
            }

        return CallToolResult(
            content = listOf(TextContent(text = sql)),
            isError = false,
            structuredContent = structured,
        )
    }

    private fun translatorRejected(messages: List<ResponseMessage>): Boolean =
        messages.any {
            it.severity ==
                Severity.ERROR
        }

    private fun validatorRejected(resp: org.tatrman.validate.v1.ValidateResponse): Boolean =
        resp.messagesList.any { it.severity == Severity.ERROR } || !resp.hasPlan()

    private fun rejectionResult(
        code: String,
        messages: List<ResponseMessage>,
        ctx: PipelineContext,
    ): CallToolResult {
        val msgEntries =
            messages.map { m ->
                MessageEntry(
                    severity = m.severity.toAgentString(),
                    code = m.code.ifEmpty { code },
                    text = m.humanMessage,
                )
            }
        val structured =
            buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("tool", JsonPrimitive(name))
                put("messages", messagesArray(msgEntries))
                // pipelineWarnings is always present, even on rejection.
                put("pipelineWarnings", JsonArray(emptyList()))
            }
        return CallToolResult(
            content =
                listOf(
                    TextContent(
                        text = msgEntries.firstOrNull()?.let { "${it.code}: ${it.text}" } ?: code,
                    ),
                ),
            isError = true,
            structuredContent = structured,
        )
    }

    private fun errorFromStatus(
        defaultCode: String,
        status: Status,
    ): CallToolResult {
        val code =
            when (status.code) {
                Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED -> defaultCode
                Status.Code.PERMISSION_DENIED -> "permission_denied"
                Status.Code.INVALID_ARGUMENT -> "invalid_argument"
                Status.Code.CANCELLED -> "cancelled"
                else -> defaultCode
            }
        return buildErrorResult(name, code, status.description ?: status.code.name)
    }

    private fun Severity.toAgentString(): String =
        when (this) {
            Severity.INFO -> "info"
            Severity.WARNING -> "warn"
            Severity.ERROR -> "error"
            Severity.SEVERITY_UNSPECIFIED, Severity.UNRECOGNIZED -> "warn"
        }

    private fun literalSchemaProp(
        type: String,
        description: String,
    ): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive(type))
            put("description", JsonPrimitive(description))
        }

    private fun enumSchemaProp(
        description: String,
        values: List<String>,
    ): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("string"))
            put(
                "enum",
                buildJsonArray { for (v in values) add(JsonPrimitive(v)) },
            )
            put("description", JsonPrimitive(description))
        }
}
