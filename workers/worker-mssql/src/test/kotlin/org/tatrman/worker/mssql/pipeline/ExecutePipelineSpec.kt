// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.mssql.pipeline

import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.translate.v1.UnparseRequest
import org.tatrman.translate.v1.UnparseResponse
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.ExecutionOptions
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.tatrman.worker.mssql.client.TranslatorClient
import org.tatrman.worker.mssql.connection.ConnectionPoolManager

private fun translator(unparseBody: (UnparseRequest) -> UnparseResponse): TranslatorClient =
    object : TranslatorClient {
        override suspend fun unparse(request: UnparseRequest): UnparseResponse = unparseBody(request)

        override suspend fun probe() = Unit
    }

class ExecutePipelineSpec :
    StringSpec({
        val limits =
            ExecutePipeline.ExecutionLimits(
                defaultBatchSizeRows = 100,
                maxBatchSizeRows = 1_000,
                defaultTimeoutSeconds = 30,
                maxTimeoutSeconds = 300,
                maxBlobBytesPerCell = 8 * 1024 * 1024,
            )

        "unknown connection_id surfaces connection_not_supported" {
            runBlocking {
                val pool = ConnectionPoolManager(emptyMap())
                val translator =
                    translator { req ->
                        UnparseResponse
                            .newBuilder()
                            .setOutput("SELECT 1")
                            .setContext(req.context)
                            .build()
                    }
                val pipeline = ExecutePipeline(pool, translator, limits)
                val out =
                    pipeline
                        .execute(
                            ExecuteRequest
                                .newBuilder()
                                .setPlan(scan("dbo", "customers"))
                                .setContext(PipelineContext.getDefaultInstance())
                                .setConnectionId("missing-conn")
                                .setOptions(ExecutionOptions.getDefaultInstance())
                                .build(),
                        ).toList()
                out.size shouldBe 1
                out[0].messagesList[0].code shouldBe "connection_not_supported"
                out[0].isFirst shouldBe true
                out[0].isLast shouldBe true
            }
        }

        "translator error surfaces translator_failed" {
            runBlocking {
                // Pool that knows the connection but never gets used because translator fails first.
                val pool =
                    ConnectionPoolManager(
                        mapOf(
                            "mssql-default" to
                                org.tatrman.worker.mssql.connection
                                    .ConnectionConfig(
                                        id = "mssql-default",
                                        jdbcUrl = "jdbc:sqlserver://nope:1433;databaseName=X",
                                        username = "u",
                                        password = "p",
                                        database = "X",
                                    ),
                        ),
                    )
                val translator =
                    translator { req ->
                        UnparseResponse
                            .newBuilder()
                            .setContext(req.context)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.ERROR)
                                    .setCode("language_not_supported_in_v1.1")
                                    .setHumanMessage("nope"),
                            ).build()
                    }
                val pipeline = ExecutePipeline(pool, translator, limits)
                val out =
                    pipeline
                        .execute(
                            ExecuteRequest
                                .newBuilder()
                                .setPlan(scan("dbo", "customers"))
                                .setContext(PipelineContext.getDefaultInstance())
                                .setConnectionId("mssql-default")
                                .setOptions(ExecutionOptions.getDefaultInstance())
                                .build(),
                        ).toList()
                out.size shouldBe 1
                out[0].messagesList[0].code shouldBe "translator_failed"
            }
        }

        "activeQueries returns 0 between calls" {
            val pool = ConnectionPoolManager(emptyMap())
            val translator = translator { _ -> UnparseResponse.getDefaultInstance() }
            val pipeline = ExecutePipeline(pool, translator, limits)
            pipeline.activeQueries shouldBe 0
        }
    })

private fun scan(
    namespace: String,
    name: String,
): PlanNode =
    PlanNode
        .newBuilder()
        .setTableScan(
            TableScanNode.newBuilder().setTable(
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                    .setNamespace(namespace)
                    .setName(name),
            ),
        ).build()
