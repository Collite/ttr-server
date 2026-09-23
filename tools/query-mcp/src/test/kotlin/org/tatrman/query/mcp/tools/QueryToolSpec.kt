// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.tools

import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.query.v1.CompileResponse
import org.tatrman.query.v1.RunRequest
import org.tatrman.validate.v1.SecurityRuleApplied
import org.tatrman.worker.v1.BoundParameter
import org.tatrman.worker.v1.ExecutionReceipt
import org.tatrman.worker.v1.ResultBatch
import org.tatrman.worker.v1.RlsOutcome
import org.tatrman.worker.v1.StatementKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainText
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import shared.formatter.core.ColumnDecoration
import shared.formatter.core.LocalizedString
import org.tatrman.query.mcp.QueryMcpConfig
import org.tatrman.query.mcp.upstream.MetadataServiceClient
import org.tatrman.query.mcp.upstream.QueryRunnerClient
import com.google.protobuf.ByteString
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels

class QueryToolSpec :
    StringSpec({

        val cfg =
            QueryMcpConfig(
                serverPort = 7401,
                mcpTransport = "streamable-http",
                mcpPath = "/mcp",
                upstream =
                    QueryMcpConfig.Upstream(
                        queryRunner = QueryMcpConfig.GrpcEndpoint("h", 1, 30),
                        translator = QueryMcpConfig.GrpcEndpoint("h", 1, 30),
                        validator = QueryMcpConfig.GrpcEndpoint("h", 1, 30),
                        metadata = QueryMcpConfig.GrpcEndpoint("h", 1, 30),
                    ),
                limits =
                    QueryMcpConfig.Limits(
                        rowLimitDefault = 500,
                        rowLimitMax = 5000,
                        requestTimeoutSeconds = 120,
                        maxMessageBytes =
                            32 * 1024 * 1024,
                    ),
                security = QueryMcpConfig.Security(requireIdentity = false),
                toolTimeoutsMs = mapOf("query" to 120_000L, "compile" to 60_000L),
            )

        fun fakeRunner(
            batches: List<ResultBatch>,
            compileResponse: CompileResponse = CompileResponse.getDefaultInstance(),
        ) = object : QueryRunnerClient {
            override fun run(request: RunRequest): Flow<ResultBatch> = flowOf(*batches.toTypedArray())

            override suspend fun compile(request: RunRequest): CompileResponse = compileResponse
        }

        // Default fake metadata client returns no decorations — tests that
        // care about decoration construct their own.
        val fakeMetadata =
            object : MetadataServiceClient {
                override suspend fun attributeDecorationsByLocalName(): Map<String, ColumnDecoration> = emptyMap()
            }

        fun fakeMetadataWith(decorations: Map<String, ColumnDecoration>) =
            object : MetadataServiceClient {
                override suspend fun attributeDecorationsByLocalName(): Map<String, ColumnDecoration> = decorations
            }

        // Build a minimal Arrow IPC byte stream with a single non-nullable Int64
        // column. Used by the Phase 2.2 side-channel tests below.
        fun arrowIpcOneIntColumn(
            name: String,
            values: LongArray,
        ): ByteArray {
            val field = Field(name, FieldType.notNullable(ArrowType.Int(64, true)), null)
            val schema = Schema(listOf(field))
            val out = ByteArrayOutputStream()
            RootAllocator(Long.MAX_VALUE).use { alloc ->
                VectorSchemaRoot.create(schema, alloc).use { root ->
                    val vec = root.getVector(name) as BigIntVector
                    vec.allocateNew(values.size)
                    for ((i, v) in values.withIndex()) vec.set(i, v)
                    root.setRowCount(values.size)
                    ArrowStreamWriter(root, null, Channels.newChannel(out)).use { w ->
                        w.start()
                        w.writeBatch()
                        w.end()
                    }
                }
            }
            return out.toByteArray()
        }

        "missing source/source_language returns missing_required_field" {
            val tool = QueryTool(cfg, fakeRunner(emptyList()), fakeMetadata)
            val req =
                CallToolRequest(params = CallToolRequestParams(name = "query", arguments = JsonObject(emptyMap())))
            val res = runBlocking { tool.execute(req, identity = null) }
            res.isError shouldBe true
            val msgs = (res.structuredContent!!["messages"] as JsonArray)
            ((msgs[0] as JsonObject)["code"] as JsonPrimitive).content shouldBe "missing_required_field"
        }

        "row_limit out of range returns row_limit_out_of_range" {
            val tool = QueryTool(cfg, fakeRunner(emptyList()), fakeMetadata)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT 1"))
                    put("source_language", JsonPrimitive("sql"))
                    put("row_limit", JsonPrimitive(0))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                        identity = null,
                    )
                }
            res.isError shouldBe true
            val msgs = (res.structuredContent!!["messages"] as JsonArray)
            ((msgs[0] as JsonObject)["code"] as JsonPrimitive).content shouldBe "row_limit_out_of_range"
        }

        "source_language=sql defaults source_schema to DB on the RunRequest" {
            var captured: RunRequest? = null
            val runner =
                object : QueryRunnerClient {
                    override fun run(request: RunRequest): Flow<ResultBatch> {
                        captured = request
                        return flowOf()
                    }

                    override suspend fun compile(request: RunRequest): CompileResponse =
                        CompileResponse.getDefaultInstance()
                }
            val tool = QueryTool(cfg, runner, fakeMetadata)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT 1"))
                    put("source_language", JsonPrimitive("sql"))
                }
            runBlocking {
                tool.execute(
                    CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                    identity = null,
                )
            }
            captured!!.sourceSchema shouldBe SchemaCode.DB
        }

        "an explicit source_schema arg overrides the SQL->DB default" {
            var captured: RunRequest? = null
            val runner =
                object : QueryRunnerClient {
                    override fun run(request: RunRequest): Flow<ResultBatch> {
                        captured = request
                        return flowOf()
                    }

                    override suspend fun compile(request: RunRequest): CompileResponse =
                        CompileResponse.getDefaultInstance()
                }
            val tool = QueryTool(cfg, runner, fakeMetadata)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT 1"))
                    put("source_language", JsonPrimitive("sql"))
                    put("source_schema", JsonPrimitive("er"))
                }
            runBlocking {
                tool.execute(
                    CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                    identity = null,
                )
            }
            captured!!.sourceSchema shouldBe SchemaCode.ER
        }

        "invalid hide_columns_matching regex returns invalid_regex" {
            val tool = QueryTool(cfg, fakeRunner(emptyList()), fakeMetadata)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT 1"))
                    put("source_language", JsonPrimitive("sql"))
                    put(
                        "hide_columns_matching",
                        buildJsonArray { add(JsonPrimitive("[unclosed")) },
                    )
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                        identity = null,
                    )
                }
            res.isError shouldBe true
            val msgs = (res.structuredContent!!["messages"] as JsonArray)
            ((msgs[0] as JsonObject)["code"] as JsonPrimitive).content shouldBe "invalid_regex"
        }

        "happy path with empty arrow returns ok=true and zero rows" {
            // First (and only) batch with empty Arrow IPC bytes → DataFormatter handles
            // empty input as zero columns / zero rows (verified by data-formatter spec).
            val emptyBatch =
                ResultBatch
                    .newBuilder()
                    .setBatchIndex(0)
                    .setIsFirst(true)
                    .setIsLast(true)
                    .setArrowIpc(ByteString.EMPTY)
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()
            val tool = QueryTool(cfg, fakeRunner(listOf(emptyBatch)), fakeMetadata)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT 1"))
                    put("source_language", JsonPrimitive("sql"))
                    put("format", JsonPrimitive("json"))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                        identity = null,
                    )
                }
            res.isError shouldBe false
            (res.structuredContent!!["ok"] as JsonPrimitive).content shouldBe "true"
            (res.structuredContent!!["rowCount"] as JsonPrimitive).content shouldBe "0"
            (res.structuredContent!!["truncated"] as JsonPrimitive).content shouldBe "false"
            // Empty content: JSON empty array.
            (res.content[0] as TextContent).text shouldBe "[]"
        }

        // Phase 08 D5 / DF-Q02 — multi-batch streaming. Pre-D5 the first batch with isLast=false
        // surfaced partial_results_truncated regardless of row count; now all batches are
        // collected up to row_limit, and partial_results_truncated fires only when row_limit
        // would have been exceeded.
        "row_limit cap fires partial_results_truncated when worker produces more rows than the cap allows" {
            // Two batches summing to 4 rows; row_limit=2 → cap clipped after batch 1 (3 rows),
            // moreBatchesAvailable=true → partial_results_truncated warning + truncated=true.
            val batch1 = arrowIpcOneIntColumn("v", longArrayOf(1L, 2L, 3L))
            val batch2 = arrowIpcOneIntColumn("v", longArrayOf(4L))
            val rb1 =
                ResultBatch
                    .newBuilder()
                    .setBatchIndex(0)
                    .setIsFirst(true)
                    .setIsLast(false)
                    .setBatchRowCount(3)
                    .setArrowIpc(ByteString.copyFrom(batch1))
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()
            val rb2 =
                ResultBatch
                    .newBuilder()
                    .setBatchIndex(1)
                    .setIsLast(true)
                    .setBatchRowCount(1)
                    .setArrowIpc(ByteString.copyFrom(batch2))
                    .build()
            val tool = QueryTool(cfg, fakeRunner(listOf(rb1, rb2)), fakeMetadata)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT 1"))
                    put("source_language", JsonPrimitive("sql"))
                    put("row_limit", JsonPrimitive(2))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                        identity = null,
                    )
                }
            (res.structuredContent!!["truncated"] as JsonPrimitive).content shouldBe "true"
            val msgs = (res.structuredContent!!["messages"] as JsonArray)
            val codes = msgs.map { ((it as JsonObject)["code"] as JsonPrimitive).content }
            codes shouldContain "partial_results_truncated"
        }

        "row_limit met exactly across multiple batches → no partial_results_truncated, all rows visible" {
            val batch1 = arrowIpcOneIntColumn("v", longArrayOf(1L, 2L))
            val batch2 = arrowIpcOneIntColumn("v", longArrayOf(3L, 4L))
            val rb1 =
                ResultBatch
                    .newBuilder()
                    .setBatchIndex(0)
                    .setIsFirst(true)
                    .setIsLast(false)
                    .setBatchRowCount(2)
                    .setArrowIpc(ByteString.copyFrom(batch1))
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()
            val rb2 =
                ResultBatch
                    .newBuilder()
                    .setBatchIndex(1)
                    .setIsLast(true)
                    .setBatchRowCount(2)
                    .setArrowIpc(ByteString.copyFrom(batch2))
                    .build()
            val tool = QueryTool(cfg, fakeRunner(listOf(rb1, rb2)), fakeMetadata)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT 1"))
                    put("source_language", JsonPrimitive("sql"))
                    put("row_limit", JsonPrimitive(10))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                        identity = null,
                    )
                }
            (res.structuredContent!!["truncated"] as JsonPrimitive).content shouldBe "false"
            val codes =
                (res.structuredContent!!["messages"] as JsonArray)
                    .map { ((it as JsonObject)["code"] as JsonPrimitive).content }
            codes.contains("partial_results_truncated") shouldBe false
            (res.structuredContent!!["rowCount"] as JsonPrimitive).content shouldBe "4"
        }

        "unknown source_language returns unknown_source_language" {
            val tool = QueryTool(cfg, fakeRunner(emptyList()), fakeMetadata)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("..."))
                    put("source_language", JsonPrimitive("brainfuck"))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                        identity = null,
                    )
                }
            res.isError shouldBe true
            val msgs = (res.structuredContent!!["messages"] as JsonArray)
            ((msgs[0] as JsonObject)["code"] as JsonPrimitive).content shouldBe "unknown_source_language"
        }

        "G7: pipelineWarnings array is always present (empty when upstream emits none)" {
            val emptyBatch =
                ResultBatch
                    .newBuilder()
                    .setBatchIndex(0)
                    .setIsFirst(true)
                    .setIsLast(true)
                    .setArrowIpc(ByteString.EMPTY)
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()
            val tool = QueryTool(cfg, fakeRunner(listOf(emptyBatch)), fakeMetadata)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT 1"))
                    put("source_language", JsonPrimitive("sql"))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                        identity = null,
                    )
                }
            (res.structuredContent!!["pipelineWarnings"] as JsonArray).size shouldBe 0
        }

        "G7: warnings on first batch's PipelineContext surface in pipelineWarnings with right severity" {
            val ctx =
                PipelineContext
                    .newBuilder()
                    .addWarnings(
                        org.tatrman.plan.v1.Warning
                            .newBuilder()
                            .setCode("security_predicate_applied")
                            .setMessage("Filter applied")
                            .setSourceService("validator")
                            .setSourceStage("security")
                            .build(),
                    ).addWarnings(
                        org.tatrman.plan.v1.Warning
                            .newBuilder()
                            .setCode("model_version_mismatch_minor")
                            .setMessage("model X != Y")
                            .setSourceService("validator")
                            .setSourceStage("schema_check")
                            .build(),
                    ).build()
            val batch =
                ResultBatch
                    .newBuilder()
                    .setBatchIndex(0)
                    .setIsFirst(true)
                    .setIsLast(true)
                    .setArrowIpc(ByteString.EMPTY)
                    .setContext(ctx)
                    .build()
            val tool = QueryTool(cfg, fakeRunner(listOf(batch)), fakeMetadata)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT 1"))
                    put("source_language", JsonPrimitive("sql"))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                        identity = null,
                    )
                }
            val warnings = (res.structuredContent!!["pipelineWarnings"] as JsonArray)
            warnings.size shouldBe 2
            ((warnings[0] as JsonObject)["severity"] as JsonPrimitive).content shouldBe "info"
            ((warnings[1] as JsonObject)["severity"] as JsonPrimitive).content shouldBe "warn"
            ((warnings[0] as JsonObject)["sourceService"] as JsonPrimitive).content shouldBe "validator"
        }

        "Smoke: input schema includes G3 fields" {
            val tool = QueryTool(cfg, fakeRunner(emptyList()), fakeMetadata)
            val schemaProps = tool.inputSchema.properties!!
            schemaProps.keys shouldContain "hide_columns_matching"
            schemaProps.keys shouldContain "row_numbering"
        }

        // ----- Phase 2.2 — side-channel decoration + new input fields -----

        "Phase 2.2: input schema includes language / substitute_value_labels / include_column_labels" {
            val tool = QueryTool(cfg, fakeRunner(emptyList()), fakeMetadata)
            val schemaProps = tool.inputSchema.properties!!
            schemaProps.keys shouldContain "language"
            schemaProps.keys shouldContain "substitute_value_labels"
            schemaProps.keys shouldContain "include_column_labels"
        }

        "Phase 2.2: side-channel decoration applies to a real Arrow batch" {
            // Build a tiny Arrow IPC stream with one column "stav" containing 1, 2.
            val arrow = arrowIpcOneIntColumn(name = "stav", values = longArrayOf(1L, 2L))
            val batch =
                ResultBatch
                    .newBuilder()
                    .setBatchIndex(0)
                    .setIsFirst(true)
                    .setIsLast(true)
                    .setArrowIpc(ByteString.copyFrom(arrow))
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()
            val decorations =
                mapOf(
                    "stav" to
                        ColumnDecoration(
                            displayLabel = LocalizedString(mapOf("cs" to "Stav", "en" to "Status")),
                            valueLabels =
                                mapOf(
                                    "1" to LocalizedString(mapOf("cs" to "Aktivní", "en" to "Active")),
                                    "2" to LocalizedString(mapOf("cs" to "Neaktivní", "en" to "Inactive")),
                                ),
                        ),
                )
            val tool = QueryTool(cfg, fakeRunner(listOf(batch)), fakeMetadataWith(decorations))
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT stav"))
                    put("source_language", JsonPrimitive("sql"))
                    put("format", JsonPrimitive("markdown"))
                    put("language", JsonPrimitive("cs"))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                        identity = null,
                    )
                }
            res.isError shouldBe false
            val text = (res.content[0] as TextContent).text
            // Header is the localised label, not the raw column name.
            (text.contains("| Stav |")) shouldBe true
            // Cells are localised.
            (text.contains("Aktivní")) shouldBe true
            (text.contains("Neaktivní")) shouldBe true
        }

        "Phase 2.2: substitute_value_labels=false leaves raw cells" {
            val arrow = arrowIpcOneIntColumn(name = "stav", values = longArrayOf(1L))
            val batch =
                ResultBatch
                    .newBuilder()
                    .setBatchIndex(0)
                    .setIsFirst(true)
                    .setIsLast(true)
                    .setArrowIpc(ByteString.copyFrom(arrow))
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()
            val decorations =
                mapOf(
                    "stav" to
                        ColumnDecoration(
                            valueLabels =
                                mapOf("1" to LocalizedString(mapOf("cs" to "Aktivní"))),
                        ),
                )
            val tool = QueryTool(cfg, fakeRunner(listOf(batch)), fakeMetadataWith(decorations))
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT stav"))
                    put("source_language", JsonPrimitive("sql"))
                    put("format", JsonPrimitive("markdown"))
                    put("substitute_value_labels", JsonPrimitive(false))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                        identity = null,
                    )
                }
            val text = (res.content[0] as TextContent).text
            (text.contains("Aktivní")) shouldBe false
            (text.contains("| 1 |")) shouldBe true
        }

        "Phase 2.2: include_column_labels emits __columnLabels in JSON output" {
            val arrow = arrowIpcOneIntColumn(name = "stav", values = longArrayOf(1L))
            val batch =
                ResultBatch
                    .newBuilder()
                    .setBatchIndex(0)
                    .setIsFirst(true)
                    .setIsLast(true)
                    .setArrowIpc(ByteString.copyFrom(arrow))
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()
            val decorations =
                mapOf(
                    "stav" to
                        ColumnDecoration(
                            displayLabel = LocalizedString(mapOf("cs" to "Stav")),
                        ),
                )
            val tool = QueryTool(cfg, fakeRunner(listOf(batch)), fakeMetadataWith(decorations))
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT stav"))
                    put("source_language", JsonPrimitive("sql"))
                    put("format", JsonPrimitive("json"))
                    put("language", JsonPrimitive("cs"))
                    put("include_column_labels", JsonPrimitive(true))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                        identity = null,
                    )
                }
            val text = (res.content[0] as TextContent).text
            (text.contains("__columnLabels")) shouldBe true
            (text.contains("\"stav\":\"Stav\"")) shouldBe true
        }

        "Phase 2.2: metadata client failure degrades silently to no decoration" {
            val arrow = arrowIpcOneIntColumn(name = "stav", values = longArrayOf(1L))
            val batch =
                ResultBatch
                    .newBuilder()
                    .setBatchIndex(0)
                    .setIsFirst(true)
                    .setIsLast(true)
                    .setArrowIpc(ByteString.copyFrom(arrow))
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()
            val brokenMetadata =
                object : MetadataServiceClient {
                    override suspend fun attributeDecorationsByLocalName(): Map<String, ColumnDecoration> =
                        throw RuntimeException("metadata down")
                }
            val tool = QueryTool(cfg, fakeRunner(listOf(batch)), brokenMetadata)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT stav"))
                    put("source_language", JsonPrimitive("sql"))
                    put("format", JsonPrimitive("markdown"))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                        identity = null,
                    )
                }
            // Tool succeeds; output uses raw column name and raw values.
            res.isError shouldBe false
            val text = (res.content[0] as TextContent).text
            (text.contains("| stav |")) shouldBe true
        }

        // ---- Phase 09 E1 / DF-MCP01 — v0 → v1 workflow parity ---------------------------
        //
        // The v0 `erp-data-mcp` tool surface (entity_query / free_sql / pattern_query) is
        // collapsed into v1's single `query` tool. The shape mapping is documented in
        // `docs/history/v1/tasks-phase-02-5-v0-retirement.md`; these tests pin it by proving
        // each v0 call shape now runs cleanly through v1's `query`. The fake runner stays
        // empty-result — we're verifying call-shape acceptance, not result content (which is
        // already covered by the cases above).

        "v0 free_sql → v1 query: raw SQL with connection_id round-trips" {
            // v0:  { tool: "free_sql", arguments: { sql: "...", database: "erp", format: "csv" } }
            // v1:  { tool: "query",    arguments: { source: "...", source_language: "sql",
            //                                       connection_id: "erp", format: "csv" } }
            val emptyBatch =
                ResultBatch
                    .newBuilder()
                    .setBatchIndex(0)
                    .setIsFirst(true)
                    .setIsLast(true)
                    .setArrowIpc(ByteString.EMPTY)
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()
            val tool = QueryTool(cfg, fakeRunner(listOf(emptyBatch)), fakeMetadata)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT id, name FROM customers WHERE id = 7"))
                    put("source_language", JsonPrimitive("sql"))
                    put("connection_id", JsonPrimitive("erp"))
                    put("format", JsonPrimitive("csv"))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                        identity = null,
                    )
                }
            res.isError shouldBe false
            (res.structuredContent!!["ok"] as JsonPrimitive).content shouldBe "true"
            (res.structuredContent!!["format"] as JsonPrimitive).content shouldBe "csv"
        }

        "v0 entity_query → v1 query: DataFrame DSL pipeline with from + groupby for `count` workflow" {
            // v0:  { tool: "entity_query", arguments: { entity: "orders", queryType: "count",
            //                                            database: "erp" } }
            // v1:  { tool: "query", arguments: { source: <DFDSL JSON>, source_language:
            //                                    "dataframe_dsl", connection_id: "erp" } }
            val dfDslPipeline =
                """
                {
                  "ops": [
                    {"from": {"table": {"schemaCode": "db", "namespace": "dbo", "name": "orders"}}},
                    {"groupby": {
                      "aggregates": [
                        {"function": "count", "alias": "n",
                         "args": [{"name": "id", "type": "unknown"}]}
                      ]
                    }}
                  ]
                }
                """.trimIndent()
            val emptyBatch =
                ResultBatch
                    .newBuilder()
                    .setBatchIndex(0)
                    .setIsFirst(true)
                    .setIsLast(true)
                    .setArrowIpc(ByteString.EMPTY)
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()
            val tool = QueryTool(cfg, fakeRunner(listOf(emptyBatch)), fakeMetadata)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive(dfDslPipeline))
                    put("source_language", JsonPrimitive("dataframe_dsl"))
                    put("connection_id", JsonPrimitive("erp"))
                    put("format", JsonPrimitive("json"))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                        identity = null,
                    )
                }
            res.isError shouldBe false
            (res.structuredContent!!["ok"] as JsonPrimitive).content shouldBe "true"
        }

        // Stage 04 task 4-5: QueryTool end-to-end — typed {value,type} param → RunRequest binding
        "typed parameters {value,type} reach RunRequest.context.parametersList with correct type and value" {
            var capturedRequest: RunRequest? = null
            val capturingRunner =
                object : org.tatrman.query.mcp.upstream.QueryRunnerClient {
                    override fun run(
                        request: RunRequest,
                    ): kotlinx.coroutines.flow.Flow<org.tatrman.worker.v1.ResultBatch> {
                        capturedRequest = request
                        val emptyBatch =
                            org.tatrman.worker.v1.ResultBatch
                                .newBuilder()
                                .setBatchIndex(0)
                                .setIsFirst(true)
                                .setIsLast(true)
                                .setArrowIpc(com.google.protobuf.ByteString.EMPTY)
                                .setContext(
                                    org.tatrman.plan.v1.PipelineContext
                                        .getDefaultInstance(),
                                ).build()
                        return kotlinx.coroutines.flow.flowOf(emptyBatch)
                    }

                    override suspend fun compile(request: RunRequest): org.tatrman.query.v1.CompileResponse =
                        org.tatrman.query.v1.CompileResponse
                            .getDefaultInstance()
                }
            val tool = QueryTool(cfg, capturingRunner, fakeMetadata)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT * FROM z WHERE s = {nazev_strediska}"))
                    put("source_language", JsonPrimitive("sql"))
                    put("format", JsonPrimitive("json"))
                    put(
                        "parameters",
                        buildJsonObject {
                            put(
                                "nazev_strediska",
                                buildJsonObject {
                                    put("value", JsonPrimitive("DF ADNAK"))
                                    put("type", JsonPrimitive("varchar"))
                                },
                            )
                        },
                    )
                }
            runBlocking {
                tool.execute(
                    CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                    identity = null,
                )
            }
            val params = capturedRequest!!.context.parametersList
            params.size shouldBe 1
            val p = params.first()
            p.name shouldBe "nazev_strediska"
            p.type shouldBe "text"
            p.value.stringValue shouldBe "DF ADNAK"
        }

        "v0 pattern_query → v1 query: TransDSL with query_ref pointing at a stored named query" {
            // v0:  { tool: "pattern_query", arguments: { query: "<NL pattern>", database: "erp" } }
            // v1:  the NL → stored-query lowering is now an agent concern (golem/resolver);
            //      once they've produced a stored-query name, the call shape is:
            //      { tool: "query", arguments: { source: <TransDSL with query_ref>,
            //                                    source_language: "transformation_dsl",
            //                                    parameters: {...}, connection_id: "erp" } }
            val transDsl =
                """
                {
                  "core": [{"queryRef": "db.queries.order_status_by_id"}],
                  "columns": []
                }
                """.trimIndent()
            val emptyBatch =
                ResultBatch
                    .newBuilder()
                    .setBatchIndex(0)
                    .setIsFirst(true)
                    .setIsLast(true)
                    .setArrowIpc(ByteString.EMPTY)
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()
            val tool = QueryTool(cfg, fakeRunner(listOf(emptyBatch)), fakeMetadata)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive(transDsl))
                    put("source_language", JsonPrimitive("transformation_dsl"))
                    put("connection_id", JsonPrimitive("erp"))
                    put(
                        "parameters",
                        buildJsonObject {
                            put("order_id", JsonPrimitive("123"))
                        },
                    )
                    put("format", JsonPrimitive("json"))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "query", arguments = args)),
                        identity = null,
                    )
                }
            res.isError shouldBe false
            (res.structuredContent!!["ok"] as JsonPrimitive).content shouldBe "true"
        }
        // ── ES-P0·S0.5 — the `execution` object (contracts §2, ⚑ES-5) ────────────────────────
        //
        // The receipt arrives in two halves on two batches (⚑ES-1). query-mcp is where they
        // become ONE object, so that golem — and every other MCP client — never has to know
        // there were two. Exposure is unconditional: a caller that gets `rowCount` gets
        // `execution` too, because "what ran" is not a debug flag.

        "both halves of the receipt become ONE execution object" {
            val tool = QueryTool(cfg, fakeRunner(receiptBatches()), fakeMetadata)
            val res = runBlocking { tool.execute(queryCall(), identity = null) }

            res.isError shouldBe false
            val execution = res.structuredContent!!["execution"] as JsonObject

            // statement half — the worker's
            (execution["statementKind"] as JsonPrimitive).content shouldBe "SQL_EXECUTED"
            (execution["dialect"] as JsonPrimitive).content shouldBe "POSTGRESQL"
            (execution["statement"] as JsonPrimitive).content shouldBe "SELECT account_id FROM public.positions"
            (execution["statementRef"] as JsonPrimitive).content shouldBe ""
            (execution["statementTruncated"] as JsonPrimitive).content shouldBe "false"
            (execution["connectionId"] as JsonPrimitive).content shouldBe "pg-hartland"
            (execution["engineLabel"] as JsonPrimitive).content shouldBe "worker-postgres@pg-hartland"
            (execution["rowsTotal"] as JsonPrimitive).content shouldBe "12"
            (execution["durationMs"] as JsonPrimitive).content shouldBe "184"
            (execution["rls"] as JsonPrimitive).content shouldBe "RLS_APPLIED"

            // ⚑ES-2 — the roster crosses the JSON boundary; the value does not.
            val parameters = execution["parameters"] as JsonArray
            parameters.size shouldBe 1
            val p0 = parameters[0] as JsonObject
            (p0["name"] as JsonPrimitive).content shouldBe "year_from"
            (p0["type"] as JsonPrimitive).content shouldBe "int"
            (p0["value"] as JsonPrimitive).content shouldBe "<masked>"
            (p0["masked"] as JsonPrimitive).content shouldBe "true"

            // plan half — the query service's
            (execution["planOmittedReason"] as JsonPrimitive).content shouldBe ""
            (execution["dispatchTarget"] as JsonPrimitive).content shouldBe "worker-postgres:7401"
            (execution["effectiveSchema"] as JsonPrimitive).content shouldBe "DB"
            (execution["cacheHit"] as JsonPrimitive).content shouldBe "false"
            (execution["compileMs"] as JsonPrimitive).content shouldBe "31"

            // ⚑ES-3 — the plan crosses as TEXT, through the one formatter `compile` already uses.
            val planText = (execution["dispatchedPlanText"] as JsonPrimitive).content
            planText shouldContainText "table_scan"
            planText shouldContainText "positions"

            // A-1's set, finally readable from outside the server.
            val rules = execution["securityApplied"] as JsonArray
            rules.size shouldBe 1
            ((rules[0] as JsonObject)["ruleId"] as JsonPrimitive).content shouldBe "rls.tenant"
            ((rules[0] as JsonObject)["predicateSummary"] as JsonPrimitive).content shouldBe
                "WHERE tenant_id = (your tenant)"
        }

        "a statement half alone yields an execution object with empty plan fields, no error" {
            val batches =
                listOf(
                    emptyBatchWith(index = 0, isFirst = true, isLast = false, receipt = null),
                    emptyBatchWith(index = 1, isLast = true, receipt = statementHalfReceipt()),
                )
            val tool = QueryTool(cfg, fakeRunner(batches), fakeMetadata)
            val res = runBlocking { tool.execute(queryCall(), identity = null) }

            res.isError shouldBe false
            val execution = res.structuredContent!!["execution"] as JsonObject
            (execution["statement"] as JsonPrimitive).content shouldBe "SELECT account_id FROM public.positions"
            (execution["dispatchedPlanText"] as JsonPrimitive).content shouldBe ""
            (execution["effectiveSchema"] as JsonPrimitive).content shouldBe ""
            (execution["securityApplied"] as JsonArray).size shouldBe 0
        }

        "a plan half alone yields an execution object with empty statement fields" {
            val batches =
                listOf(
                    emptyBatchWith(index = 0, isFirst = true, isLast = true, receipt = planHalfReceipt()),
                )
            val tool = QueryTool(cfg, fakeRunner(batches), fakeMetadata)
            val res = runBlocking { tool.execute(queryCall(), identity = null) }

            val execution = res.structuredContent!!["execution"] as JsonObject
            (execution["statementKind"] as JsonPrimitive).content shouldBe "STATEMENT_KIND_UNSPECIFIED"
            (execution["statement"] as JsonPrimitive).content shouldBe ""
            (execution["effectiveSchema"] as JsonPrimitive).content shouldBe "DB"
        }

        "an over-cap plan says so instead of shipping a truncated one" {
            val half =
                planHalfReceipt()
                    .toBuilder()
                    .clearDispatchedPlan()
                    .setPlanOmittedReason("over_cap")
                    .build()
            val tool =
                QueryTool(
                    cfg,
                    fakeRunner(listOf(emptyBatchWith(index = 0, isFirst = true, isLast = true, receipt = half))),
                    fakeMetadata,
                )
            val res = runBlocking { tool.execute(queryCall(), identity = null) }

            val execution = res.structuredContent!!["execution"] as JsonObject
            (execution["dispatchedPlanText"] as JsonPrimitive).content shouldBe ""
            (execution["planOmittedReason"] as JsonPrimitive).content shouldBe "over_cap"
        }

        "no receipt at all — an older server — emits NO execution key" {
            val tool =
                QueryTool(
                    cfg,
                    fakeRunner(listOf(emptyBatchWith(index = 0, isFirst = true, isLast = true, receipt = null))),
                    fakeMetadata,
                )
            val res = runBlocking { tool.execute(queryCall(), identity = null) }

            res.isError shouldBe false
            res.structuredContent!!.keys shouldNotContain "execution"
        }
    })

private fun queryCall(): CallToolRequest =
    CallToolRequest(
        params =
            CallToolRequestParams(
                name = "query",
                arguments =
                    buildJsonObject {
                        put("source", JsonPrimitive("SELECT account_id FROM positions"))
                        put("source_language", JsonPrimitive("sql"))
                    },
            ),
    )

private fun emptyBatchWith(
    index: Int,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    receipt: ExecutionReceipt?,
): ResultBatch =
    ResultBatch
        .newBuilder()
        .setBatchIndex(index)
        .setIsFirst(isFirst)
        .setIsLast(isLast)
        .setArrowIpc(ByteString.EMPTY)
        .setContext(PipelineContext.getDefaultInstance())
        .apply { if (receipt != null) setReceipt(receipt) }
        .build()

private fun statementHalfReceipt(): ExecutionReceipt =
    ExecutionReceipt
        .newBuilder()
        .setStatementKind(StatementKind.SQL_EXECUTED)
        .setDialect("POSTGRESQL")
        .setStatement("SELECT account_id FROM public.positions")
        .setConnectionId("pg-hartland")
        .setEngineLabel("worker-postgres@pg-hartland")
        .setRowsTotal(12)
        .setDurationMs(184)
        .setRls(RlsOutcome.RLS_APPLIED)
        .addParameters(
            BoundParameter
                .newBuilder()
                .setName("year_from")
                .setType("int")
                .setValueMasked("<masked>")
                .setMasked(true),
        ).build()

private fun planHalfReceipt(): ExecutionReceipt =
    ExecutionReceipt
        .newBuilder()
        .setDispatchedPlan(
            PlanNode.newBuilder().setTableScan(
                TableScanNode.newBuilder().setTable(
                    QualifiedName
                        .newBuilder()
                        .setSchemaCode(SchemaCode.DB)
                        .setNamespace("public")
                        .setName("positions"),
                ),
            ),
        ).addSecurityApplied(
            SecurityRuleApplied
                .newBuilder()
                .setRuleId("rls.tenant")
                .setPredicateSummary("WHERE tenant_id = (your tenant)"),
        ).setDispatchTarget("worker-postgres:7401")
        .setEffectiveSchema("DB")
        .setCompileMs(31)
        .build()

/** The wire as ⚑ES-1 shapes it: plan half on the first batch, both halves on the last. */
private fun receiptBatches(): List<ResultBatch> {
    val merged =
        statementHalfReceipt()
            .toBuilder()
            .mergeFrom(planHalfReceipt())
            .build()
    return listOf(
        emptyBatchWith(index = 0, isFirst = true, receipt = planHalfReceipt()),
        emptyBatchWith(index = 1, isLast = true, receipt = merged),
    )
}
