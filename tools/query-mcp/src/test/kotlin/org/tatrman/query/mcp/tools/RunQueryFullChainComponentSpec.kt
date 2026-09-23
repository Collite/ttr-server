// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.tools

import com.google.protobuf.kotlin.toByteString
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainText
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.query.cache.CompiledPlanCache
import org.tatrman.query.client.DispatcherClient
import org.tatrman.query.client.TranslatorClient
import org.tatrman.query.client.TranslatorDetectClient
import org.tatrman.query.client.TranslatorTranslateClient
import org.tatrman.query.client.ValidatorClient
import org.tatrman.query.grpc.QueryServiceImpl
import org.tatrman.query.mcp.QueryMcpConfig
import org.tatrman.mcp.identity.IdentitySource
import org.tatrman.mcp.identity.UserIdentity
import org.tatrman.query.mcp.upstream.MetadataServiceClient
import org.tatrman.query.mcp.upstream.QueryRunnerClient
import org.tatrman.query.retry.RetryPolicy
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.query.v1.CompileResponse
import org.tatrman.query.v1.RunRequest
import org.tatrman.validate.v1.SecurityRuleApplied
import org.tatrman.worker.receipt.ExecutionReceiptBuilder
import org.tatrman.worker.v1.ResultBatch
import org.tatrman.worker.v1.RlsOutcome
import org.tatrman.worker.v1.StatementKind
import shared.formatter.core.ColumnDecoration
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels

/**
 * Fork Stage 3.5 T6 — full-chain component smoke. `run_query` via query-mcp's
 * QueryTool → a real in-process **Query** (QueryServiceImpl) → mocked Translate
 * (parse) → mocked Validate (validate) → mocked Dispatch (dispatch) → a mocked worker
 * emitting real Arrow IPC → decoded to JSON rows in the MCP response.
 *
 * Asserts (a) the labyrinth is walkable: rows come back as JSON; (b) the OBO
 * identity's roles travel as PipelineContext.auth_roles all the way to Validate —
 * the bearer-roles contract (kantheon-security §2/§3) end-to-end. A DataFrame-path
 * variant runs the same chain with a session_id (the Polars/stateful shape).
 *
 * True on-K3s full-chain e2e is deferred to the separate integration-test suite.
 */
class RunQueryFullChainComponentSpec :
    StringSpec({

        val cfg =
            QueryMcpConfig(
                serverPort = 7307,
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
                        maxMessageBytes = 32 * 1024 * 1024,
                    ),
                security = QueryMcpConfig.Security(requireIdentity = true),
                toolTimeoutsMs = mapOf("query" to 120_000L, "compile" to 60_000L),
            )
        val fakeMetadata =
            object : MetadataServiceClient {
                override suspend fun attributeDecorationsByLocalName(): Map<String, ColumnDecoration> = emptyMap()
            }

        // A worker (Mssql/Polars) result: real Arrow IPC, one int column `id` = [1, 2].
        fun arrowIdColumn(values: LongArray): ByteArray {
            val field = Field("id", FieldType.notNullable(ArrowType.Int(64, true)), null)
            val out = ByteArrayOutputStream()
            RootAllocator(Long.MAX_VALUE).use { alloc ->
                VectorSchemaRoot.create(Schema(listOf(field)), alloc).use { root ->
                    val vec = root.getVector("id") as BigIntVector
                    vec.allocateNew(values.size)
                    values.forEachIndexed { i, v -> vec.set(i, v) }
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

        fun dbPlan(): PlanNode =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode.newBuilder().setTable(
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(SchemaCode.DB)
                            .setNamespace("dbo")
                            .setName("customers"),
                    ),
                ).build()

        // Build a real Query wired to mocked downstreams; capture the roles Validate sees.
        fun queryWithCapture(seenRoles: MutableList<List<String>>): QueryServiceImpl {
            val detect =
                TranslatorDetectClient {
                    org.tatrman.translate.v1.DetectSchemaResponse
                        .newBuilder()
                        .setDecision(org.tatrman.translate.v1.SchemaDecision.CONFIRMED)
                        .setEffectiveSchema(SchemaCode.DB)
                        .build()
                }
            val parse =
                TranslatorClient { req ->
                    org.tatrman.translate.v1.ParseResponse
                        .newBuilder()
                        .setPlan(dbPlan())
                        .setContext(req.context)
                        .build()
                }
            val translate =
                TranslatorTranslateClient { req ->
                    org.tatrman.translate.v1.TranslateResponse
                        .newBuilder()
                        .setOutput("SELECT id FROM customers")
                        .setContext(req.context)
                        .build()
                }
            val validate =
                ValidatorClient { req ->
                    seenRoles.add(req.context.authRolesList.toList())
                    org.tatrman.validate.v1.ValidateResponse
                        .newBuilder()
                        .setPlan(req.plan)
                        .setContext(req.context)
                        .build()
                }
            val dispatch =
                DispatcherClient { _ ->
                    flowOf(
                        ResultBatch
                            .newBuilder()
                            .setIsFirst(true)
                            .setIsLast(true)
                            .setArrowIpc(arrowIdColumn(longArrayOf(1, 2)).toByteString())
                            .setContext(PipelineContext.getDefaultInstance())
                            .build(),
                    )
                }
            return QueryServiceImpl(
                parse,
                detect,
                translate,
                validate,
                dispatch,
                CompiledPlanCache(100, java.time.Duration.ofMinutes(60)),
                RetryPolicy(maxAttempts = 2, initialBackoffMillis = 1, multiplier = 1.0, jitterPercent = 0),
            )
        }

        fun runnerOver(query: QueryServiceImpl): QueryRunnerClient =
            object : QueryRunnerClient {
                override fun run(request: RunRequest): Flow<ResultBatch> = query.run(request)

                override suspend fun compile(request: RunRequest): CompileResponse = query.compile(request)
            }

        fun callToolRequest(args: Map<String, String>): CallToolRequest =
            CallToolRequest(
                params =
                    CallToolRequestParams(
                        name = "query",
                        arguments =
                            buildJsonObject {
                                args.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                            },
                    ),
            )

        "run_query walks the full chain and returns JSON rows; OBO roles reach Validate" {
            runBlocking {
                val seenRoles = mutableListOf<List<String>>()
                val tool = QueryTool(cfg, runnerOver(queryWithCapture(seenRoles)), fakeMetadata)
                val identity = UserIdentity(id = "alice", roles = setOf("analyst"), source = IdentitySource.TOKEN)

                val res =
                    tool.execute(
                        callToolRequest(mapOf("source" to "SELECT id FROM customers", "source_language" to "sql")),
                        identity = identity,
                    )

                res.isError shouldBe false
                (res.structuredContent!!["rowCount"] as JsonPrimitive).content shouldBe "2"
                // The OBO identity's roles travelled as PipelineContext.auth_roles to Validate.
                seenRoles.isNotEmpty() shouldBe true
                seenRoles.last() shouldContain "analyst"
            }
        }

        "DataFrame-path variant: a session-scoped run also walks the chain and returns rows" {
            runBlocking {
                val seenRoles = mutableListOf<List<String>>()
                val tool = QueryTool(cfg, runnerOver(queryWithCapture(seenRoles)), fakeMetadata)
                val identity = UserIdentity(id = "alice", roles = setOf("analyst"), source = IdentitySource.TOKEN)

                val res =
                    tool.execute(
                        callToolRequest(
                            mapOf(
                                "source" to "df.head()",
                                "source_language" to "dfdsl",
                                "session_id" to "sess-1",
                            ),
                        ),
                        identity = identity,
                    )

                res.isError shouldBe false
                (res.structuredContent!!["rowCount"] as JsonPrimitive).content shouldBe "2"
            }
        }
        // ── ES-P0·S0.3–S0.6 — the receipt crosses the whole in-process chain ──────────────────
        //
        // The real chain, minus the database: a worker-shaped dispatch puts its statement half on
        // the last batch, the real QueryServiceImpl adds the plan half on the first and re-attaches
        // it to the last, and query-mcp merges the two into ONE `execution` object. This is the
        // seam tatrman-server#55 is about, end to end in one process.
        "the execution receipt survives worker → ttr-query → query-mcp as one execution object" {
            runBlocking {
                val rule =
                    SecurityRuleApplied
                        .newBuilder()
                        .setRuleId("rls.tenant")
                        .setPredicateSummary("WHERE tenant_id = (your tenant)")
                        .build()
                val workerHalf =
                    ExecutionReceiptBuilder.statementHalf(
                        kind = StatementKind.SQL_EXECUTED,
                        dialect = "POSTGRESQL",
                        sql = "SELECT id FROM dbo.customers",
                        parameters = emptyList(),
                        connectionId = "pg-hartland",
                        engineLabel = "worker-postgres@pg-hartland",
                        rowsTotal = 2,
                        durationMs = 17,
                        rls = RlsOutcome.RLS_APPLIED,
                        correlationId = "corr-fullchain",
                    )!!
                val validateApplying =
                    ValidatorClient { req ->
                        org.tatrman.validate.v1.ValidateResponse
                            .newBuilder()
                            .setPlan(req.plan)
                            .setContext(req.context)
                            .addSecurityApplied(rule)
                            .build()
                    }
                // Two batches, as the wire really looks: rows first, tail marker with the
                // worker's half last — and dispatch has stamped its endpoint on the first.
                val dispatchWithReceipt =
                    DispatcherClient { _ ->
                        flowOf(
                            ResultBatch
                                .newBuilder()
                                .setBatchIndex(0)
                                .setIsFirst(true)
                                .setBatchRowCount(2)
                                .setArrowIpc(arrowIdColumn(longArrayOf(1, 2)).toByteString())
                                .setContext(PipelineContext.getDefaultInstance())
                                .setReceipt(
                                    org.tatrman.worker.v1.ExecutionReceipt
                                        .newBuilder()
                                        .setDispatchTarget("worker-postgres:7401"),
                                ).build(),
                            ResultBatch
                                .newBuilder()
                                .setBatchIndex(1)
                                .setIsLast(true)
                                .setArrowIpc(com.google.protobuf.ByteString.EMPTY)
                                .setContext(PipelineContext.getDefaultInstance())
                                .setReceipt(workerHalf)
                                .build(),
                        )
                    }
                val query =
                    QueryServiceImpl(
                        TranslatorClient { req ->
                            org.tatrman.translate.v1.ParseResponse
                                .newBuilder()
                                .setPlan(dbPlan())
                                .setContext(req.context)
                                .build()
                        },
                        TranslatorDetectClient {
                            org.tatrman.translate.v1.DetectSchemaResponse
                                .newBuilder()
                                .setDecision(org.tatrman.translate.v1.SchemaDecision.CONFIRMED)
                                .setEffectiveSchema(SchemaCode.DB)
                                .build()
                        },
                        TranslatorTranslateClient { req ->
                            org.tatrman.translate.v1.TranslateResponse
                                .newBuilder()
                                .setOutput("SELECT id FROM customers")
                                .setContext(req.context)
                                .build()
                        },
                        validateApplying,
                        dispatchWithReceipt,
                        CompiledPlanCache(100, java.time.Duration.ofMinutes(60)),
                        RetryPolicy(maxAttempts = 2, initialBackoffMillis = 1, multiplier = 1.0, jitterPercent = 0),
                    )

                val res =
                    QueryTool(cfg, runnerOver(query), fakeMetadata).execute(
                        callToolRequest(mapOf("source" to "SELECT id FROM customers", "source_language" to "sql")),
                        identity = UserIdentity(id = "alice", roles = setOf("analyst"), source = IdentitySource.TOKEN),
                    )

                res.isError shouldBe false
                val execution = res.structuredContent!!["execution"] as JsonObject

                // The worker's half — the statement that ran, which #55 was about.
                (execution["statement"] as JsonPrimitive).content shouldBe "SELECT id FROM dbo.customers"
                (execution["rowsTotal"] as JsonPrimitive).content shouldBe "2"
                (execution["rls"] as JsonPrimitive).content shouldBe "RLS_APPLIED"
                (execution["engineLabel"] as JsonPrimitive).content shouldBe "worker-postgres@pg-hartland"

                // ttr-query's half — the dispatched plan and the set A-1 could not reach.
                (execution["dispatchedPlanText"] as JsonPrimitive).content shouldContainText "customers"
                (execution["effectiveSchema"] as JsonPrimitive).content shouldBe "DB"
                val rules = execution["securityApplied"] as JsonArray
                rules.size shouldBe 1
                ((rules[0] as JsonObject)["ruleId"] as JsonPrimitive).content shouldBe "rls.tenant"

                // dispatch's fact, carried through to the tail by ttr-query.
                (execution["dispatchTarget"] as JsonPrimitive).content shouldBe "worker-postgres:7401"
            }
        }
    })
