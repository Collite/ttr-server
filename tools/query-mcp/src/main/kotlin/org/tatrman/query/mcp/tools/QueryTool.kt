// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.tools

import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.parseSchemaCode
import org.tatrman.query.v1.RunRequest
import org.tatrman.translate.v1.Language
import org.tatrman.worker.v1.ExecutionOptions
import org.tatrman.common.v1.Severity
import org.tatrman.worker.v1.ResultBatch
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import shared.formatter.DataFormatter
import shared.formatter.core.ColumnDecoration
import shared.formatter.core.FormatOptions
import shared.formatter.core.OutputFormat
import shared.formatter.core.RowNumbering
import org.tatrman.query.mcp.QueryMcpConfig
import org.tatrman.mcp.identity.UserIdentity
import org.tatrman.query.mcp.mcp.MessageEntry
import org.tatrman.query.mcp.mcp.McpTool
import org.tatrman.query.mcp.mcp.PipelineWarnings
import org.tatrman.query.mcp.mcp.boolFieldOr
import org.tatrman.query.mcp.mcp.buildErrorResult
import org.tatrman.query.mcp.mcp.intFieldOr
import org.tatrman.query.mcp.mcp.messagesArray
import org.tatrman.query.mcp.mcp.objectFieldOrEmpty
import org.tatrman.query.mcp.mcp.stringArrayFieldOrEmpty
import org.tatrman.query.mcp.mcp.stringFieldOrNull
import org.tatrman.query.mcp.upstream.MetadataServiceClient
import org.tatrman.query.mcp.upstream.QueryRunnerClient
import java.util.UUID
import java.util.regex.PatternSyntaxException

/**
 * MCP `query` tool — submits a query to Query, accumulates Arrow
 * results, formats via data-formatter, returns text + structured envelope.
 *
 * v1 strategy: read the first ResultBatch only. Worker default batch is
 * 10 000 rows (workers/mssql application.conf), max 100 000; query-mcp's
 * row-limit-max is 5 000 — so a single batch always covers the result set
 * in normal config. is_last=false on the first batch surfaces a
 * `partial_results_truncated` message.
 */
class QueryTool(
    private val cfg: QueryMcpConfig,
    private val runner: QueryRunnerClient,
    private val metadata: MetadataServiceClient,
) : McpTool {
    private val logger = LoggerFactory.getLogger(QueryTool::class.java)

    override val name: String = "query"
    override val description: String =
        """Run a query against the v1 platform. Source language may be SQL, TransDSL, or DataFrame DSL.
        Returns formatted result data (json | csv | tsv | markdown) plus a structured envelope with
        row counts, columns, warnings, and pipeline messages."""

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
                    put("session_id", literalSchemaProp("string", "Session id for sticky routing on stateful workers."))
                    put(
                        "format",
                        enumSchemaProp(
                            description = "Output format. Default json. xlsx/parquet return bytes as a resource.",
                            values = listOf("json", "csv", "tsv", "markdown", "xlsx", "parquet"),
                        ),
                    )
                    put(
                        "row_limit",
                        literalSchemaProp("integer", "Max rows to return; clamped to [1, ${cfg.limits.rowLimitMax}]."),
                    )
                    put(
                        "user_id",
                        literalSchemaProp(
                            "string",
                            "Trusted-network user_id override; ignored when an Authorization token resolves.",
                        ),
                    )
                    put(
                        "hide_columns_matching",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                            put(
                                "description",
                                JsonPrimitive(
                                    "Regex patterns; columns whose names match any pattern are hidden from output.",
                                ),
                            )
                        },
                    )
                    put(
                        "row_numbering",
                        enumSchemaProp(
                            description = "Prepend a row-number column. Default none.",
                            values = listOf("none", "one_based"),
                        ),
                    )
                    put(
                        "language",
                        literalSchemaProp(
                            "string",
                            "Preferred BCP-47 language for display labels and value-label substitution. " +
                                "Default cs.",
                        ),
                    )
                    put(
                        "substitute_value_labels",
                        literalSchemaProp(
                            "boolean",
                            "When true (default), substitute integer/string codes with their localised " +
                                "labels per the Model's value_labels.",
                        ),
                    )
                    put(
                        "include_column_labels",
                        literalSchemaProp(
                            "boolean",
                            "JSON output only: include a __columnLabels companion object alongside the rows " +
                                "array. Default false.",
                        ),
                    )
                },
            required = listOf("source", "source_language"),
        )

    override suspend fun execute(
        request: CallToolRequest,
        identity: UserIdentity?,
    ): CallToolResult {
        val args = (request.params.arguments ?: JsonObject(emptyMap()))
        val source = args.stringFieldOrNull("source")
        val sourceLanguageStr = args.stringFieldOrNull("source_language")

        if (source.isNullOrBlank() || sourceLanguageStr.isNullOrBlank()) {
            return buildErrorResult(name, "missing_required_field", "source and source_language are required.")
        }
        val sourceLanguage =
            parseSourceLanguage(sourceLanguageStr)
                ?: return buildErrorResult(
                    name,
                    "unknown_source_language",
                    "Unknown source_language '$sourceLanguageStr'. Use sql | transdsl | dfdsl | rel_node.",
                )
        // Physical SQL references db-layer objects (dbo columns), which don't exist in the
        // ER model — parsing raw SQL against ER fails ("<col> is a db object; parsed against
        // er"). Default source_language=SQL to the DB schema so physical patterns resolve;
        // callers targeting the entity model can override with an explicit source_schema.
        val sourceSchema =
            args
                .stringFieldOrNull("source_schema")
                ?.let { parseSchemaCode(it) }
                ?: if (sourceLanguage == Language.SQL) SchemaCode.DB else SchemaCode.SCHEMA_CODE_UNSPECIFIED

        val format = parseOutputFormat(args.stringFieldOrNull("format"))
        val rowLimitArg = args.intFieldOr("row_limit", cfg.limits.rowLimitDefault)
        if (rowLimitArg !in 1..cfg.limits.rowLimitMax) {
            return buildErrorResult(
                name,
                "row_limit_out_of_range",
                "row_limit must be in [1, ${cfg.limits.rowLimitMax}] (was $rowLimitArg).",
            )
        }

        // G3: compile regexes; loud-fail with structured message on PatternSyntaxException.
        val hidePatternStrs = args.stringArrayFieldOrEmpty("hide_columns_matching")
        val hideRegexes =
            try {
                hidePatternStrs.map { Regex(it) }
            } catch (e: PatternSyntaxException) {
                return buildErrorResult(name, "invalid_regex", "Invalid regex pattern: ${e.message}")
            }
        val rowNumbering =
            when (args.stringFieldOrNull("row_numbering")?.lowercase()) {
                null, "", "none" -> RowNumbering.NONE
                "one_based" -> RowNumbering.ONE_BASED
                else -> RowNumbering.NONE
            }

        // Phase 2.2 — localisation inputs.
        val preferredLanguage = (args.stringFieldOrNull("language")?.takeIf { it.isNotBlank() }) ?: "cs"
        val substituteValueLabels = args.boolFieldOr("substitute_value_labels", true)
        val includeColumnLabels = args.boolFieldOr("include_column_labels", false)

        // Build PipelineContext.
        val parametersObj = args.objectFieldOrEmpty("parameters")
        val parametersMap = parametersObj.toMap()
        val correlationId = UUID.randomUUID().toString()
        val context =
            PipelineContext
                .newBuilder()
                .setCorrelationId(correlationId)
                .setUserId(identity?.id ?: "")
                .setSessionId(args.stringFieldOrNull("session_id") ?: "")
                .addAllAuthRoles(identity?.roles ?: emptySet())
                .addAllParameters(parametersToBindings(parametersMap))
                .build()

        val request0 =
            RunRequest
                .newBuilder()
                .setSource(source)
                .setSourceLanguage(sourceLanguage)
                .setSourceSchema(sourceSchema)
                .setContext(context)
                .setExecutionOptions(
                    ExecutionOptions
                        .newBuilder()
                        // Worker still applies its own caps; this is belt-and-braces.
                        .setRowLimit(rowLimitArg.toLong())
                        .build(),
                ).build()

        // Phase 08 D5 / DF-Q02 — stream all worker batches, bounded by `rowLimitArg`. Each batch
        // is a self-contained Arrow IPC stream; `DataFormatter.fromArrowBatches` decodes them in
        // turn and concatenates rows up to the cap. `moreBatchesAvailable` is true when the
        // worker stream had additional batches we didn't read because the cap was hit OR the
        // collection was bounded.
        val collected = collectAllBatches(runner.run(request0), rowLimitArg)

        if (collected.errorCode != null) {
            return buildErrorResult(name, collected.errorCode, collected.errorText ?: collected.errorCode)
        }

        // Query signals an internally-caught failure (e.g. a validate/parse failure,
        // or a dispatch failure mid-stream) as an errorBatch carrying an ERROR-severity
        // Rule-6 message rather than a gRPC status. Honor that contract: fail closed with
        // a clean typed error and DROP any rows already collected — never surface partial
        // results or a silent success when the pipeline reported an error (kantheon-security
        // §2.1 fail-closed shape; fork Stage 3.6 T4).
        val errorMessage =
            collected.batches.firstNotNullOfOrNull { b ->
                b.messagesList.firstOrNull { it.severity == Severity.ERROR }
            }
        if (errorMessage != null) {
            return buildErrorResult(
                name,
                errorMessage.code.ifBlank { "pipeline_failed" },
                errorMessage.humanMessage.ifBlank { errorMessage.code.ifBlank { "Pipeline reported an error." } },
            )
        }

        val arrowBatches: List<ByteArray> = collected.batches.map { it.arrowIpc.toByteArray() }
        val firstBatch: ResultBatch? = collected.batches.firstOrNull()
        val lastBatch: ResultBatch? = collected.batches.lastOrNull()
        val pipelineMessages = mutableListOf<MessageEntry>()
        if (collected.moreBatchesAvailable) {
            pipelineMessages.add(
                MessageEntry(
                    severity = "warning",
                    code = "partial_results_truncated",
                    text = "Result exceeded row_limit ($rowLimitArg); raise it or paginate to see more.",
                ),
            )
        }

        // Phase 2.2 — fetch the side-channel column decorations. Best-effort:
        // metadata client returns an empty map on any failure, so a missing
        // metadata service degrades the request to "no localisation" rather
        // than failing.
        val columnDecorations: Map<String, ColumnDecoration> =
            try {
                metadata.attributeDecorationsByLocalName()
            } catch (t: Throwable) {
                logger.debug("Metadata decoration fetch failed: {}", t.message)
                emptyMap()
            }

        val formatted =
            try {
                DataFormatter.fromArrowBatches(
                    arrowBatches = arrowBatches,
                    output = format,
                    options =
                        FormatOptions(
                            rowLimit = rowLimitArg,
                            hideColumnsMatching = hideRegexes,
                            rowNumbering = rowNumbering,
                            columnMetadata = columnDecorations,
                            preferredLanguage = preferredLanguage,
                            substituteValueLabels = substituteValueLabels,
                            includeColumnLabels = includeColumnLabels,
                        ),
                )
            } catch (t: Throwable) {
                logger.warn("Formatting failed: {}", t.message, t)
                return buildErrorResult(name, "format_failed", "Failed to format result: ${t.message}")
            }

        val truncated = formatted.truncated || collected.moreBatchesAvailable

        // G7: surface accumulated upstream warnings. The worker contract is that warnings
        // accumulate monotonically across the batch stream, so the LAST batch's context carries
        // the full set — switched from "first batch only" with D5's multi-batch collection.
        val pipelineWarnings = PipelineWarnings.toJsonArray(lastBatch?.context?.warningsList ?: emptyList())

        // ES-P0·S0.5 — the two halves of the execution receipt, merged once, here.
        val executionReceipt = ExecutionJson.mergeReceipts(collected.batches)

        val structured =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("tool", JsonPrimitive(name))
                put("rowCount", JsonPrimitive(formatted.rowCount))
                put("columnCount", JsonPrimitive(formatted.columnCount))
                put("truncated", JsonPrimitive(truncated))
                put("format", JsonPrimitive(args.stringFieldOrNull("format")?.lowercase() ?: "json"))
                put("mediaType", JsonPrimitive(formatted.mediaType))
                put(
                    "columns",
                    buildJsonArray {
                        for (col in formatted.columns) {
                            add(
                                buildJsonObject {
                                    put("name", JsonPrimitive(col.name))
                                    put("type", JsonPrimitive(col.logicalType.toString()))
                                    put("nullable", JsonPrimitive(col.nullable))
                                },
                            )
                        }
                    },
                )
                put("messages", messagesArray(pipelineMessages))
                put("pipelineWarnings", pipelineWarnings)
                // ES ⚑ES-5 — `execution` rides beside `rowCount`, unconditionally, whenever at
                // least one half of the receipt arrived. Not behind a flag and not only on
                // failure: "what actually ran" is an answer's provenance, not a debug aid.
                // Absent entirely when no half arrived — an older server states nothing rather
                // than stating emptiness.
                executionReceipt?.let { put("execution", ExecutionJson.toJson(it)) }
            }

        // Phase 08 D2 — binary formats (XLSX, future Parquet) travel as EmbeddedResource carrying
        // base64-encoded bytes; text formats stay as TextContent. The MCP spec's `TextContent.text`
        // is UTF-8 text — putting raw zip bytes through it would corrupt the payload.
        val contentBlock =
            if (format.binary) {
                EmbeddedResource(
                    resource =
                        BlobResourceContents(
                            blob =
                                java.util.Base64
                                    .getEncoder()
                                    .encodeToString(formatted.bytes),
                            uri = "query-result.${args
                                .stringFieldOrNull(
                                    "format",
                                )?.lowercase() ?: format.name.lowercase()}",
                            mimeType = formatted.mediaType,
                        ),
                )
            } else {
                TextContent(text = String(formatted.bytes, Charsets.UTF_8))
            }

        return CallToolResult(
            content =
                listOf(
                    contentBlock,
                ),
            isError = false,
            structuredContent = structured,
        )
    }

    /**
     * Phase 08 D5 / DF-Q02 — multi-batch collection bounded by `rowLimit`.
     *
     * Reads batches from [stream] in order, accumulating up to `rowLimit` total rows across all
     * batches. `moreBatchesAvailable = true` when the worker had more batches we didn't read
     * (either because the cap was hit or because the worker explicitly signalled a tail batch
     * but we already had enough). Empty (no-row) batches still count for context propagation but
     * not toward the row cap.
     */
    private suspend fun collectAllBatches(
        stream: Flow<ResultBatch>,
        rowLimit: Int,
    ): CollectionOutcome {
        val batches = mutableListOf<ResultBatch>()
        var rowsCollected = 0L
        var moreAvailable = false
        try {
            stream.collect { batch ->
                if (rowsCollected >= rowLimit) {
                    moreAvailable = true
                    throw StopCollection
                }
                batches.add(batch)
                rowsCollected += batch.batchRowCount
                if (batch.isLast.not() && rowsCollected >= rowLimit) {
                    moreAvailable = true
                    throw StopCollection
                }
            }
        } catch (_: StopCollection) {
            // expected — cap reached
        } catch (e: StatusException) {
            return errorOutcome(e.status)
        } catch (e: StatusRuntimeException) {
            return errorOutcome(e.status)
        }
        return CollectionOutcome(batches = batches, moreBatchesAvailable = moreAvailable)
    }

    private fun errorOutcome(status: Status): CollectionOutcome =
        CollectionOutcome(
            batches = emptyList(),
            moreBatchesAvailable = false,
            errorCode =
                when (status.code) {
                    Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED -> "upstream_unreachable"
                    Status.Code.PERMISSION_DENIED -> "permission_denied"
                    Status.Code.INVALID_ARGUMENT -> "invalid_argument"
                    Status.Code.CANCELLED -> "cancelled"
                    else -> "upstream_failed"
                },
            errorText = status.description ?: status.code.name,
        )

    private data class CollectionOutcome(
        val batches: List<ResultBatch> = emptyList(),
        val moreBatchesAvailable: Boolean = false,
        val errorCode: String? = null,
        val errorText: String? = null,
    )

    private object StopCollection : RuntimeException() {
        private fun readResolve(): Any = StopCollection
    }

    @Suppress("UNUSED_PARAMETER")
    private fun ensureLanguageBound(l: Language?) = Unit // reserved for future cross-language validation

    private fun parseOutputFormat(raw: String?): OutputFormat =
        when (raw?.lowercase()) {
            null, "", "json" -> OutputFormat.JSON
            "csv" -> OutputFormat.CSV
            "tsv" -> OutputFormat.TSV
            "markdown", "md" -> OutputFormat.MARKDOWN
            "xlsx" -> OutputFormat.XLSX
            "parquet" -> OutputFormat.PARQUET
            else -> OutputFormat.JSON
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
