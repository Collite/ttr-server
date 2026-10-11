// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.mssql.grpc

import org.tatrman.meta.v1.OverallStatus
import org.tatrman.translate.v1.UnparseRequest
import org.tatrman.translate.v1.UnparseResponse
import org.tatrman.worker.v1.GetCapabilitiesRequest
import org.tatrman.worker.v1.GetStatusRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr
import kotlinx.coroutines.runBlocking
import org.tatrman.worker.mssql.client.TranslatorClient
import org.tatrman.worker.mssql.client.TranslatorHealth
import org.tatrman.worker.mssql.connection.ConnectionConfig
import org.tatrman.worker.mssql.connection.ConnectionPoolManager
import org.tatrman.worker.mssql.pipeline.ExecutePipeline
import java.time.Instant

class WorkerServiceImplSpec :
    StringSpec({
        val limits =
            ExecutePipeline.ExecutionLimits(
                defaultBatchSizeRows = 10_000,
                maxBatchSizeRows = 100_000,
                defaultTimeoutSeconds = 300,
                maxTimeoutSeconds = 600,
                maxBlobBytesPerCell = 8 * 1024 * 1024,
            )

        val noopTranslator =
            object : TranslatorClient {
                override suspend fun unparse(request: UnparseRequest): UnparseResponse =
                    UnparseResponse.getDefaultInstance()

                override suspend fun probe() = Unit
            }

        fun service(
            connections: Map<String, ConnectionConfig>,
            seedProbesOk: Boolean = true,
            translatorOk: Boolean = true,
        ): Pair<WorkerServiceImpl, ConnectionPoolManager> {
            val pool = ConnectionPoolManager(connections)
            if (seedProbesOk) {
                connections.keys.forEach { id ->
                    pool.recordProbeResult(
                        ConnectionPoolManager.ProbeResult(
                            connectionId = id,
                            connected = true,
                            lastError = "",
                            lastProbed = Instant.now(),
                        ),
                    )
                }
            }
            val translatorHealth = TranslatorHealth()
            if (translatorOk) translatorHealth.recordSuccess()
            val pipeline = ExecutePipeline(pool, noopTranslator, limits)
            val svc =
                WorkerServiceImpl(
                    pipeline = pipeline,
                    pool = pool,
                    translatorHealth = translatorHealth,
                    capabilities =
                        WorkerServiceImpl.WorkerCapabilities(
                            engineName = "mssql",
                            engineVersion = "MS SQL Server 2019",
                            limits = limits,
                        ),
                )
            return svc to pool
        }

        "GetCapabilities advertises mssql + MSSQL dialect + stateful=false" {
            runBlocking {
                val (svc, _) =
                    service(
                        mapOf(
                            "mssql-finance" to
                                ConnectionConfig(
                                    id = "mssql-finance",
                                    jdbcUrl = "jdbc:sqlserver://h:1433;databaseName=X",
                                    username = "u",
                                    password = "p",
                                    database = "X",
                                ),
                        ),
                    )
                val resp = svc.getCapabilities(GetCapabilitiesRequest.getDefaultInstance())
                resp.engineName shouldBe "mssql"
                resp.engineVersion shouldBe "MS SQL Server 2019"
                resp.supportedLanguagesList shouldContain "SQL"
                resp.supportedDialectsList shouldContain "MSSQL"
                resp.supportedConnectionsList shouldContain "mssql-finance"
                resp.supportsStatefulSessions shouldBe false
                resp.maxConcurrentSessions shouldBe 0
                resp.limits.defaultTimeoutSeconds shouldBe 300L
                resp.limits.maxBatchSizeRows shouldBe 100_000
                resp.limits.maxBlobBytesPerCell shouldBe (8 * 1024 * 1024).toLong()
            }
        }

        "GetStatus reports OK when DB connection probe is up and translator is OK" {
            runBlocking {
                val (svc, _) =
                    service(
                        mapOf(
                            "mssql-default" to
                                ConnectionConfig(
                                    id = "mssql-default",
                                    jdbcUrl = "jdbc:sqlserver://h:1433;databaseName=X",
                                    username = "u",
                                    password = "p",
                                    database = "X",
                                ),
                        ),
                    )
                val resp = svc.getStatus(GetStatusRequest.getDefaultInstance())
                resp.ready shouldBe true
                resp.overallStatus shouldBe OverallStatus.OK
                resp.connectionsList shouldContain
                    resp.connectionsList.first { it.connectionId == "mssql-default" }
                val cs = resp.connectionsList.first { it.connectionId == "mssql-default" }
                cs.connected shouldBe true
                cs.database shouldBe "X"
                cs.jdbcUrl shouldContainStr "databaseName=X"
            }
        }

        "GetStatus reports DEGRADED when no connections are configured" {
            runBlocking {
                val (svc, _) = service(emptyMap())
                val resp = svc.getStatus(GetStatusRequest.getDefaultInstance())
                resp.ready shouldBe false
                resp.overallStatus shouldBe OverallStatus.DEGRADED
            }
        }

        "GetStatus reports DOWN when every configured connection failed its probe" {
            runBlocking {
                val (svc, pool) =
                    service(
                        mapOf(
                            "mssql-default" to
                                ConnectionConfig(
                                    id = "mssql-default",
                                    jdbcUrl = "jdbc:sqlserver://h:1433;databaseName=X",
                                    username = "u",
                                    password = "p",
                                    database = "X",
                                ),
                        ),
                        seedProbesOk = false,
                    )
                pool.recordProbeResult(
                    ConnectionPoolManager.ProbeResult(
                        connectionId = "mssql-default",
                        connected = false,
                        lastError = "Login failed for user 'u'.",
                        lastProbed = Instant.now(),
                    ),
                )
                val resp = svc.getStatus(GetStatusRequest.getDefaultInstance())
                resp.ready shouldBe false
                resp.overallStatus shouldBe OverallStatus.DOWN
                resp.connectionsList.first().connected shouldBe false
                resp.connectionsList.first().lastError shouldContainStr "Login failed"
            }
        }

        "GetStatus reports DEGRADED when translator is DOWN even though DB is up" {
            runBlocking {
                val (svc, _) =
                    service(
                        mapOf(
                            "mssql-default" to
                                ConnectionConfig(
                                    id = "mssql-default",
                                    jdbcUrl = "jdbc:sqlserver://h:1433;databaseName=X",
                                    username = "u",
                                    password = "p",
                                    database = "X",
                                ),
                        ),
                        translatorOk = false,
                    )
                val resp = svc.getStatus(GetStatusRequest.getDefaultInstance())
                resp.ready shouldBe false
                resp.overallStatus shouldBe OverallStatus.DEGRADED
                resp.dependenciesList.first().name shouldBe "translator"
                resp.dependenciesList.first().status shouldBe OverallStatus.OVERALL_STATUS_UNSPECIFIED
            }
        }

        "GetStatus reports translator OK when health was recorded successful" {
            runBlocking {
                val (svc, _) =
                    service(
                        mapOf(
                            "mssql-default" to
                                ConnectionConfig(
                                    id = "mssql-default",
                                    jdbcUrl = "jdbc:sqlserver://h:1433;databaseName=X",
                                    username = "u",
                                    password = "p",
                                    database = "X",
                                ),
                        ),
                    )
                val resp = svc.getStatus(GetStatusRequest.getDefaultInstance())
                resp.dependenciesList.first().name shouldBe "translator"
                resp.dependenciesList.first().status shouldBe OverallStatus.OK
            }
        }

        "GetCapabilities advertises per-connection database + default schema (issue #57 Phase B)" {
            runBlocking {
                val (svc, _) =
                    service(
                        mapOf(
                            "mssql-default" to
                                ConnectionConfig(
                                    id = "mssql-default",
                                    jdbcUrl = "jdbc:sqlserver://h:1433;databaseName=tatrman",
                                    username = "u",
                                    password = "p",
                                    database = "tatrman",
                                    defaultSchema = "dbo",
                                ),
                            "mssql-finance" to
                                ConnectionConfig(
                                    id = "mssql-finance",
                                    jdbcUrl = "jdbc:sqlserver://h:1433;databaseName=finance",
                                    username = "u",
                                    password = "p",
                                    database = "finance",
                                    defaultSchema = "fin",
                                ),
                        ),
                    )
                val resp = svc.getCapabilities(GetCapabilitiesRequest.getDefaultInstance())
                val byId = resp.connectionsList.associateBy { it.connectionId }
                byId.keys shouldContainExactlyInAnyOrder setOf("mssql-default", "mssql-finance")
                byId["mssql-default"]!!.database shouldBe "tatrman"
                byId["mssql-default"]!!.defaultSchema shouldBe "dbo"
                byId["mssql-finance"]!!.database shouldBe "finance"
                byId["mssql-finance"]!!.defaultSchema shouldBe "fin"
            }
        }
    })
