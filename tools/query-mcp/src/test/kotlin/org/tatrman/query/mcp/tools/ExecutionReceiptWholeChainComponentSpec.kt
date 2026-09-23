// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.tools

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.tatrman.dispatch.client.WorkerClient
import org.tatrman.dispatch.grpc.DispatchServiceImpl
import org.tatrman.dispatch.registry.WorkerEntry
import org.tatrman.dispatch.registry.WorkerRegistry
import org.tatrman.dispatch.sticky.StickyRegistry
import org.tatrman.dispatch.v1.WorkerHealthStatus
import org.tatrman.dispatch.world.WorldConfig
import org.tatrman.mcp.identity.IdentitySource
import org.tatrman.mcp.identity.UserIdentity
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.query.cache.CompiledPlanCache
import org.tatrman.query.client.DispatcherClient
import org.tatrman.query.client.TranslatorDetectClient
import org.tatrman.query.client.TranslatorTranslateClient
import org.tatrman.query.client.ValidatorClient
import org.tatrman.query.grpc.QueryServiceImpl
import org.tatrman.query.mcp.QueryMcpConfig
import org.tatrman.query.mcp.upstream.MetadataServiceClient
import org.tatrman.query.mcp.upstream.QueryRunnerClient
import org.tatrman.query.retry.RetryPolicy
import org.tatrman.query.v1.CompileResponse
import org.tatrman.query.v1.RunRequest
import org.tatrman.testkit.containers.Containers
import org.tatrman.translate.v1.DetectSchemaResponse
import org.tatrman.translate.v1.ParseResponse
import org.tatrman.translate.v1.SchemaDecision
import org.tatrman.translate.v1.TranslateResponse
import org.tatrman.validate.v1.SecurityRuleApplied
import org.tatrman.validate.v1.ValidateResponse
import org.tatrman.worker.postgres.connection.ConnectionConfig
import org.tatrman.worker.postgres.connection.ConnectionPoolManager
import org.tatrman.worker.postgres.pipeline.ExecutePipeline
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.GetCapabilitiesResponse
import org.tatrman.worker.v1.ResultBatch
import shared.formatter.core.ColumnDecoration
import java.sql.DriverManager
import org.tatrman.worker.postgres.client.TranslatorClient as WorkerTranslatorClient

/**
 * **ES-P0 DoD — `execution.statement` is the statement the DATABASE ran.**
 *
 * The list's DoD asks for a `query` tool call against a local compose whose `execution.statement`
 * matches the SQL in the worker's log for the same correlation id. This repo has no compose (and
 * no kind harness) — so the claim is made here instead, and made harder: the whole chain runs in
 * one process against a **real Postgres**, and the statement in the MCP JSON is compared against
 * what **Postgres itself logged** (`log_statement=all`), not against what the worker says it sent.
 * A log the producer writes about itself can agree with the producer and still be wrong; the
 * database's own log cannot.
 *
 * The chain is production code at every hop that matters:
 *
 *     QueryTool → QueryServiceImpl → DispatchServiceImpl → ExecutePipeline (worker-postgres) → Postgres
 *
 * Only Translate, Validate and Metadata are stood in for — they hold none of the receipt's facts
 * except `security_applied`, which the stub supplies so the Security half is exercised too.
 */
@Tags("component")
class ExecutionReceiptWholeChainComponentSpec :
    StringSpec({

        "the whole chain: the statement in `execution` is the one Postgres logged, same run" {
            Containers
                .postgres()
                // Make the database say, for itself, what it was asked to run.
                .withCommand("postgres", "-c", "log_statement=all")
                .use { pg ->
                    pg.start()
                    DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c ->
                        c.createStatement().use { st ->
                            st.execute("CREATE TABLE positions (account_id BIGINT NOT NULL, label VARCHAR(64))")
                            st.execute("INSERT INTO positions VALUES (1001, 'alpha'), (1002, 'beta')")
                        }
                    }

                    val pool =
                        ConnectionPoolManager(
                            mapOf(
                                CONNECTION to
                                    ConnectionConfig(
                                        id = CONNECTION,
                                        jdbcUrl = pg.jdbcUrl,
                                        username = pg.username,
                                        password = pg.password,
                                        database = pg.databaseName,
                                        defaultSchema = "public",
                                        requiresTenantId = false,
                                        readOnly = true,
                                    ),
                            ),
                        )

                    val result =
                        try {
                            runBlocking {
                                QueryTool(cfg(), runnerOver(queryService(pool)), noMetadata).execute(
                                    CallToolRequest(
                                        params =
                                            CallToolRequestParams(
                                                name = "query",
                                                arguments =
                                                    buildJsonObject {
                                                        put("source", JsonPrimitive(SQL))
                                                        put("source_language", JsonPrimitive("sql"))
                                                    },
                                            ),
                                    ),
                                    identity =
                                        UserIdentity(
                                            id = "alice",
                                            roles = setOf("analyst"),
                                            source = IdentitySource.TOKEN,
                                        ),
                                )
                            }
                        } finally {
                            pool.close()
                        }

                    result.isError shouldBe false
                    (result.structuredContent!!["rowCount"] as JsonPrimitive).content shouldBe "2"

                    val execution = result.structuredContent!!["execution"] as JsonObject
                    val statement = (execution["statement"] as JsonPrimitive).content

                    // 1. the statement crossed worker → query → query-mcp intact…
                    statement shouldBe SQL
                    // 2. …and it is what the database was actually asked to run. JDBC prepares,
                    //    so Postgres logs it as `execute <unnamed>: …` rather than `statement: …`.
                    val logged = pg.logs.lineSequence().firstOrNull { it.contains(statement) }
                    logged.shouldNotBeNull() shouldContain "LOG:"

                    // The DoD asks for both sides pasted, so the run prints what it compared.
                    println("ES DoD · execution.statement = $statement")
                    println("ES DoD · postgres log        = ${logged.trim()}")

                    // The rest of the receipt describes the same real run.
                    (execution["statementKind"] as JsonPrimitive).content shouldBe "SQL_EXECUTED"
                    (execution["dialect"] as JsonPrimitive).content shouldBe "POSTGRESQL"
                    (execution["connectionId"] as JsonPrimitive).content shouldBe CONNECTION
                    (execution["engineLabel"] as JsonPrimitive).content shouldBe "worker-postgres@$CONNECTION"
                    (execution["rowsTotal"] as JsonPrimitive).content shouldBe "2"
                    (execution["rls"] as JsonPrimitive).content shouldBe "RLS_NOT_REQUIRED"
                    (execution["effectiveSchema"] as JsonPrimitive).content shouldBe "DB"
                    (execution["dispatchTarget"] as JsonPrimitive).content shouldBe ENDPOINT
                    (execution["dispatchedPlanText"] as JsonPrimitive).content shouldContain "positions"
                    (execution["securityApplied"] as kotlinx.serialization.json.JsonArray).size shouldBe 1
                }
        }
    })

private const val CONNECTION = "pg-main"
private const val ENDPOINT = "worker-postgres:7401"
private const val SQL = "SELECT account_id, label FROM positions ORDER BY account_id"

private val noMetadata =
    object : MetadataServiceClient {
        override suspend fun attributeDecorationsByLocalName(): Map<String, ColumnDecoration> = emptyMap()
    }

private fun cfg(): QueryMcpConfig =
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
                maxMessageBytes = 32 * 1024 * 1024,
            ),
        security = QueryMcpConfig.Security(requireIdentity = false),
        toolTimeoutsMs = mapOf("query" to 120_000L, "compile" to 60_000L),
    )

private fun plan(): PlanNode =
    PlanNode
        .newBuilder()
        .setTableScan(
            TableScanNode.newBuilder().setTable(
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.DB)
                    .setNamespace("public")
                    .setName("positions"),
            ),
        ).build()

/** The worker's Translate stand-in: unparses the dispatched plan to the SQL under test. */
private fun workerTranslator(): WorkerTranslatorClient =
    object : WorkerTranslatorClient {
        override suspend fun unparse(
            request: org.tatrman.translate.v1.UnparseRequest,
        ): org.tatrman.translate.v1.UnparseResponse =
            org.tatrman.translate.v1.UnparseResponse
                .newBuilder()
                .setOutput(SQL)
                .setContext(request.context)
                .build()

        override suspend fun probe() = Unit
    }

/** The real worker pipeline, wearing dispatch's client interface. */
private class PipelineWorkerClient(
    pool: ConnectionPoolManager,
) : WorkerClient {
    private val pipeline =
        ExecutePipeline(
            pool,
            workerTranslator(),
            ExecutePipeline.ExecutionLimits(
                defaultBatchSizeRows = 100,
                maxBatchSizeRows = 1_000,
                defaultTimeoutSeconds = 30,
                maxTimeoutSeconds = 300,
                maxBlobBytesPerCell = 8 * 1024 * 1024,
            ),
        )

    val capabilities: GetCapabilitiesResponse =
        GetCapabilitiesResponse
            .newBuilder()
            .setEngineName("postgres")
            .addSupportedConnections(CONNECTION)
            .build()

    override suspend fun getCapabilities(): GetCapabilitiesResponse = capabilities

    override fun execute(request: ExecuteRequest): Flow<ResultBatch> = pipeline.execute(request)

    override fun close() = Unit
}

private fun dispatchService(pool: ConnectionPoolManager): DispatchServiceImpl {
    val client = PipelineWorkerClient(pool)
    val registry = WorkerRegistry()
    registry.seed(
        listOf(
            WorkerEntry(
                endpoint = ENDPOINT,
                roleHint = "postgres",
                client = client,
                capabilities = client.capabilities,
                health = WorkerHealthStatus.HEALTHY,
                lastPolled = null,
                consecutiveFailures = 0,
            ),
        ),
    )
    return DispatchServiceImpl(
        registry,
        StickyRegistry(),
        WorldConfig.fromConfig(
            com.typesafe.config.ConfigFactory.parseString(
                """
                world {
                  default-connection = "$CONNECTION"
                  table-connections { "db.public.*" = "$CONNECTION" }
                }
                """.trimIndent(),
            ),
        ),
    )
}

private fun queryService(pool: ConnectionPoolManager): QueryServiceImpl {
    val dispatch = dispatchService(pool)
    return QueryServiceImpl(
        { req ->
            ParseResponse
                .newBuilder()
                .setPlan(plan())
                .setContext(req.context)
                .build()
        },
        TranslatorDetectClient {
            DetectSchemaResponse
                .newBuilder()
                .setDecision(SchemaDecision.CONFIRMED)
                .setEffectiveSchema(SchemaCode.DB)
                .build()
        },
        TranslatorTranslateClient { req ->
            TranslateResponse
                .newBuilder()
                .setOutput(SQL)
                .setContext(req.context)
                .build()
        },
        ValidatorClient { req ->
            ValidateResponse
                .newBuilder()
                .setPlan(req.plan)
                .setContext(req.context)
                .addSecurityApplied(
                    SecurityRuleApplied
                        .newBuilder()
                        .setRuleId("rls.tenant")
                        .setPredicateSummary("WHERE tenant_id = (your tenant)"),
                ).build()
        },
        DispatcherClient { req -> dispatch.dispatch(req) },
        CompiledPlanCache(100, java.time.Duration.ofMinutes(60)),
        RetryPolicy(maxAttempts = 1, initialBackoffMillis = 1, multiplier = 1.0, jitterPercent = 0),
    )
}

private fun runnerOver(query: QueryServiceImpl): QueryRunnerClient =
    object : QueryRunnerClient {
        override fun run(request: RunRequest): Flow<ResultBatch> = query.run(request)

        override suspend fun compile(request: RunRequest): CompileResponse = query.compile(request)
    }
