// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.mssql.pipeline

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.tatrman.worker.mssql.client.TranslatorClient
import org.tatrman.worker.mssql.connection.ConnectionConfig
import org.tatrman.worker.mssql.connection.ConnectionPoolManager
import org.tatrman.common.v1.Severity
import org.tatrman.testkit.CiOnly
import org.tatrman.testkit.containers.Containers
import org.tatrman.testkit.sql.SqlScripts
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.translate.v1.UnparseRequest
import org.tatrman.translate.v1.UnparseResponse
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.ExecutionOptions
import org.tatrman.worker.v1.RlsOutcome
import org.tatrman.worker.v1.StatementKind
import java.io.ByteArrayInputStream
import java.sql.DriverManager

/**
 * Stage 1.2 T2/T3 — the highest-value real-dep coverage: a representative
 * Translate-emitted SQL string (a real JOIN + filter) executed through Mssql's
 * **actual worker pipeline** ([ExecutePipeline]) against a **real MSSQL** in
 * Testcontainers, asserting the Arrow result shape + values. This is what
 * catches real-dialect / type-mapping divergences the mocked unit specs can't.
 *
 * **CI-only** ([CiOnly]) — the MSSQL image is amd64-only (no native ARM64), so
 * the spec runs on the native-amd64 CI runner and is *skipped* on the dev laptop
 * (`-DmssqlLocal` forces an emulated local run). See testing architecture §9.
 *
 * The translator is faked (returning the SQL directly) exactly as the unit spec
 * does — no real Translate needed; the rest of the path (pool → JDBC → ResultSet →
 * Arrow IPC → ResultBatch) is the real production code.
 */
@Tags("component")
@EnabledIf(CiOnly::class)
class MssqlMssqlComponentSpec :
    StringSpec({
        val limits =
            ExecutePipeline.ExecutionLimits(
                defaultBatchSizeRows = 100,
                maxBatchSizeRows = 1_000,
                defaultTimeoutSeconds = 30,
                maxTimeoutSeconds = 300,
                maxBlobBytesPerCell = 8 * 1024 * 1024,
            )

        val query =
            """
            SELECT o.id, o.tenant_id, o.region, o.amount, r.region_name
            FROM dbo.sample_orders o
            JOIN dbo.sample_regions r ON r.region = o.region
            WHERE o.tenant_id = 't-alpha'
            ORDER BY o.id
            """.trimIndent()

        "a Translate-emitted JOIN+filter runs against real MSSQL and yields the expected Arrow result" {
            Containers.mssql().use { mssql ->
                mssql.start()
                val host = mssql.host
                val port = mssql.firstMappedPort
                val sa = mssql.username
                val pw = mssql.password

                // Seed: create the DB on a master connection, then run the seed script
                // on a kantheon_local connection (the worker pool is read-only).
                val masterUrl = "jdbc:sqlserver://$host:$port;encrypt=false;trustServerCertificate=true"
                DriverManager.getConnection(masterUrl, sa, pw).use { c ->
                    c.createStatement().use {
                        it.execute(
                            "IF DB_ID('kantheon_local') IS NULL CREATE DATABASE kantheon_local",
                        )
                    }
                }
                val dbUrl =
                    "jdbc:sqlserver://$host:$port;databaseName=kantheon_local;encrypt=false;trustServerCertificate=true"
                DriverManager.getConnection(dbUrl, sa, pw).use { c ->
                    SqlScripts.runResource(c, "seed/mssql-sample.sql")
                }

                // Drive the real worker pipeline with a faked translator (= Translate output).
                val pool =
                    ConnectionPoolManager(
                        mapOf(
                            "df-test" to
                                ConnectionConfig(
                                    id = "df-test",
                                    jdbcUrl = dbUrl,
                                    username = sa,
                                    password = pw,
                                    database = "kantheon_local",
                                ),
                        ),
                    )
                val translator =
                    object : TranslatorClient {
                        override suspend fun unparse(request: UnparseRequest): UnparseResponse =
                            UnparseResponse
                                .newBuilder()
                                .setOutput(query)
                                .setContext(request.context)
                                .build()

                        override suspend fun probe() = Unit
                    }

                val batches =
                    try {
                        runBlocking {
                            ExecutePipeline(pool, translator, limits)
                                .execute(
                                    ExecuteRequest
                                        .newBuilder()
                                        .setPlan(scan("dbo", "sample_orders"))
                                        .setContext(PipelineContext.getDefaultInstance())
                                        .setConnectionId("df-test")
                                        .setOptions(ExecutionOptions.getDefaultInstance())
                                        .build(),
                                ).toList()
                        }
                    } finally {
                        pool.close()
                    }

                // No error surfaced; exactly the two t-alpha rows came back.
                batches.flatMap { it.messagesList }.any { it.severity == Severity.ERROR } shouldBe false
                batches.sumOf { it.batchRowCount } shouldBe 2L

                // The first data batch announces the schema fingerprint + carries Arrow IPC.
                val dataBatch = batches.first { !it.arrowIpc.isEmpty }
                (dataBatch.schemaFingerprint.isNotBlank()) shouldBe true

                // ES-P0·S0.2 — the tail marker carries the statement half: the statement the
                // engine was handed, verbatim, with a real duration and the rows it returned.
                // (RLS is `RLS_NOT_REQUIRED` always here — the MSSQL worker has no tenant
                // envelope to report on, and the receipt says only what it knows.)
                val tail = batches.last()
                tail.isLast shouldBe true
                tail.hasReceipt() shouldBe true
                tail.receipt.statementKind shouldBe StatementKind.SQL_EXECUTED
                tail.receipt.dialect shouldBe "MSSQL"
                tail.receipt.statement shouldBe query
                tail.receipt.statementTruncated shouldBe false
                tail.receipt.connectionId shouldBe "df-test"
                tail.receipt.engineLabel shouldBe "worker-mssql@df-test"
                tail.receipt.rowsTotal shouldBe 2L
                tail.receipt.durationMs shouldBeGreaterThan 0L
                tail.receipt.rls shouldBe RlsOutcome.RLS_NOT_REQUIRED

                // Deserialize the Arrow and assert the real-MSSQL-derived schema + a sampled value.
                RootAllocator(Long.MAX_VALUE).use { allocator ->
                    ArrowStreamReader(ByteArrayInputStream(dataBatch.arrowIpc.toByteArray()), allocator).use { reader ->
                        reader.loadNextBatch() shouldBe true
                        val root = reader.vectorSchemaRoot
                        root.schema.fields.map { it.name } shouldBe
                            listOf("id", "tenant_id", "region", "amount", "region_name")
                        root.rowCount shouldBe 2
                        // ORDER BY id → row 0 is id=1, region EU → "Europe" (the JOIN resolved).
                        root.getVector("region_name").getObject(0).toString() shouldBe "Europe"
                    }
                }
            }
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
                    .setSchemaCode(SchemaCode.DB)
                    .setNamespace(namespace)
                    .setName(name),
            ),
        ).build()
