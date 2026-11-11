// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.postgres.pipeline

import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.translate.v1.UnparseRequest
import org.tatrman.translate.v1.UnparseResponse
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.ExecutionOptions
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.tatrman.worker.postgres.client.TranslatorClient
import org.tatrman.worker.postgres.connection.ConnectionPoolManager
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement
import java.sql.Types
import java.util.UUID

/**
 * Stage 1.3 T1 — the RLS session envelope, proven against a fake connection (statement order
 * captured), independent of a database. The real enforcement is proven by the component specs.
 */
class RlsEnvelopeSpec :
    StringSpec({
        val limits =
            ExecutePipeline.ExecutionLimits(
                defaultBatchSizeRows = 100,
                maxBatchSizeRows = 1_000,
                defaultTimeoutSeconds = 30,
                maxTimeoutSeconds = 300,
                maxBlobBytesPerCell = 8 * 1024 * 1024,
            )

        fun translator(sql: String): TranslatorClient =
            object : TranslatorClient {
                override suspend fun unparse(request: UnparseRequest): UnparseResponse =
                    UnparseResponse
                        .newBuilder()
                        .setOutput(sql)
                        .setContext(request.context)
                        .build()

                override suspend fun probe() = Unit
            }

        // An empty-result connection that records every statement executed (SET LOCAL + the query)
        // in [executed], in order. setLocalThrows simulates a bad role / GUC for the rls_set_failed path.
        fun fakeConn(
            executed: MutableList<String>,
            setLocalThrows: Boolean = false,
        ): Connection {
            val meta = mockk<ResultSetMetaData>()
            every { meta.columnCount } returns 1
            every { meta.getColumnLabel(1) } returns "n"
            every { meta.getColumnName(1) } returns "n"
            every { meta.getColumnTypeName(1) } returns "int4"
            every { meta.getColumnType(1) } returns Types.INTEGER
            every { meta.getPrecision(1) } returns 0
            every { meta.getScale(1) } returns 0
            every { meta.isNullable(1) } returns ResultSetMetaData.columnNullable

            val rs = mockk<ResultSet>(relaxed = true)
            every { rs.metaData } returns meta
            every { rs.next() } returns false // empty result → single tail batch

            val queryStmt = mockk<PreparedStatement>(relaxed = true)
            every { queryStmt.executeQuery() } returns rs

            // createStatement() is used ONLY for the SET LOCAL bind.
            val setStmt = mockk<Statement>(relaxed = true)
            every { setStmt.execute(any<String>()) } answers {
                executed.add(firstArg())
                if (setLocalThrows) throw java.sql.SQLException("permission denied to set parameter")
                false
            }

            val conn = mockk<Connection>(relaxed = true)
            every { conn.createStatement() } returns setStmt
            every { conn.prepareStatement(any()) } answers {
                executed.add(firstArg())
                queryStmt
            }
            return conn
        }

        fun pool(conn: Connection): ConnectionPoolManager {
            val p = mockk<ConnectionPoolManager>()
            every { p.supportedConnections } returns setOf("pg-midas")
            every { p.requiresTenantId("pg-midas") } returns true
            every { p.isReadOnly("pg-midas") } returns true
            every { p.acquire("pg-midas") } returns conn
            return p
        }

        fun request(tenantId: String?): ExecuteRequest {
            val ctx =
                PipelineContext
                    .newBuilder()
                    .apply { if (tenantId != null) this.tenantId = tenantId }
                    .build()
            return ExecuteRequest
                .newBuilder()
                .setPlan(scan("public", "positions"))
                .setContext(ctx)
                .setConnectionId("pg-midas")
                .setOptions(ExecutionOptions.getDefaultInstance())
                .build()
        }

        "binds the tenant with SET LOCAL before the query, then commits" {
            runBlocking {
                val tenant = UUID.fromString("11111111-1111-1111-1111-111111111111")
                val executed = mutableListOf<String>()
                val conn = fakeConn(executed)
                every { conn.commit() } just Runs
                val pipeline = ExecutePipeline(pool(conn), translator("SELECT n FROM positions"), limits)

                val out = pipeline.execute(request(tenant.toString())).toList()

                // No error batch.
                out.flatMap { it.messagesList }.any { it.severity == Severity.ERROR } shouldBe false
                // SET LOCAL ran first (transaction-scoped), then the query — in that exact order.
                executed shouldContainExactly
                    listOf(
                        "SET LOCAL app.tenant_id = '$tenant'",
                        "SELECT n FROM positions",
                    )
                verify(exactly = 1) { conn.autoCommit = false }
                verify(exactly = 1) { conn.commit() }
            }
        }

        // WS-T2 T5 — the contrast to pg-midas: a connection WITHOUT requires-tenant-id (pg-tpcds)
        // runs the query directly under autocommit — no SET LOCAL, no explicit transaction — even
        // when the request carries no tenant_id at all.
        "a connection without requires-tenant-id runs no SET LOCAL and stays on autocommit" {
            runBlocking {
                val executed = mutableListOf<String>()
                val conn = fakeConn(executed)
                val tpcdsPool = mockk<ConnectionPoolManager>()
                every { tpcdsPool.supportedConnections } returns setOf("pg-tpcds")
                every { tpcdsPool.requiresTenantId("pg-tpcds") } returns false
                every { tpcdsPool.isReadOnly("pg-tpcds") } returns true
                every { tpcdsPool.acquire("pg-tpcds") } returns conn
                val pipeline = ExecutePipeline(tpcdsPool, translator("SELECT n FROM store_sales"), limits)

                val req =
                    ExecuteRequest
                        .newBuilder()
                        .setPlan(scan("public", "store_sales"))
                        .setContext(PipelineContext.getDefaultInstance()) // NO tenant_id
                        .setConnectionId("pg-tpcds")
                        .setOptions(ExecutionOptions.getDefaultInstance())
                        .build()

                val out = pipeline.execute(req).toList()

                out.flatMap { it.messagesList }.any { it.severity == Severity.ERROR } shouldBe false
                // Only the query ran — no SET LOCAL app.tenant_id for this non-tenant connection.
                executed shouldContainExactly listOf("SELECT n FROM store_sales")
                verify(exactly = 0) { conn.autoCommit = false }
                verify(exactly = 0) { conn.commit() }
            }
        }

        "missing tenant_id fails closed with tenant_id_required and runs nothing" {
            runBlocking {
                val executed = mutableListOf<String>()
                val conn = fakeConn(executed)
                val p = pool(conn)
                val pipeline = ExecutePipeline(p, translator("SELECT n FROM positions"), limits)

                val out = pipeline.execute(request(tenantId = null)).toList()

                out.size shouldBe 1
                out[0].messagesList[0].code shouldBe "tenant_id_required"
                out[0].isFirst shouldBe true
                out[0].isLast shouldBe true
                // Fail closed: the connection was never acquired and nothing executed.
                verify(exactly = 0) { p.acquire(any()) }
                executed.isEmpty() shouldBe true
            }
        }

        "a non-UUID tenant_id is rejected as tenant_id_required" {
            runBlocking {
                val executed = mutableListOf<String>()
                val conn = fakeConn(executed)
                val pipeline = ExecutePipeline(pool(conn), translator("SELECT n FROM positions"), limits)

                val out = pipeline.execute(request("not-a-uuid")).toList()

                out.size shouldBe 1
                out[0].messagesList[0].code shouldBe "tenant_id_required"
                executed.isEmpty() shouldBe true
            }
        }

        "a SET LOCAL failure surfaces rls_set_failed and rolls back" {
            runBlocking {
                val tenant = UUID.fromString("22222222-2222-2222-2222-222222222222")
                val executed = mutableListOf<String>()
                val conn = fakeConn(executed, setLocalThrows = true)
                every { conn.rollback() } just Runs
                val pipeline = ExecutePipeline(pool(conn), translator("SELECT n FROM positions"), limits)

                val out = pipeline.execute(request(tenant.toString())).toList()

                out.size shouldBe 1
                out[0].messagesList[0].code shouldBe "rls_set_failed"
                // The query never ran — only the failed SET LOCAL was attempted — and we rolled back.
                executed shouldContainExactly listOf("SET LOCAL app.tenant_id = '$tenant'")
                verify(exactly = 1) { conn.rollback() }
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
