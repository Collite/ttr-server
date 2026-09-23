// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.postgres.pipeline

import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.translate.v1.UnparseRequest
import org.tatrman.translate.v1.UnparseResponse
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.ExecutionOptions
import org.tatrman.worker.v1.ResultBatch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.tatrman.worker.postgres.client.TranslatorClient
import org.tatrman.worker.postgres.connection.ConnectionConfig
import org.tatrman.worker.postgres.connection.ConnectionPoolManager
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.UUID

/** One decoded `positions` row (the v1 must-pass types: bigint / numeric(20,4) / varchar). */
data class PositionRow(
    val accountId: Long,
    val amount: BigDecimal,
    val label: String,
)

/**
 * Drives the real Postgres worker pipeline against a live Postgres, exactly as the unit/component
 * Mssql spec does: a faked translator stands in for Translate (returns the SQL directly), the rest
 * of the path — pool → `SET LOCAL app.tenant_id` → JDBC → Arrow IPC → ResultBatch — is production code.
 */
object PostgresComponentSupport {
    private val limits =
        ExecutePipeline.ExecutionLimits(
            defaultBatchSizeRows = 100,
            maxBatchSizeRows = 1_000,
            defaultTimeoutSeconds = 30,
            maxTimeoutSeconds = 300,
            maxBlobBytesPerCell = 8 * 1024 * 1024,
        )

    /** The SQL the faked translator returns — and therefore the exact text the receipt must carry. */
    const val QUERY = "SELECT account_id, amount, label FROM positions ORDER BY account_id"

    /** A read-only, tenant-enforcing pool that connects as the non-owner [PostgresPgFixture.READONLY_ROLE]. */
    fun pool(
        jdbcUrl: String,
        database: String,
        requiresTenantId: Boolean = true,
    ): ConnectionPoolManager =
        ConnectionPoolManager(
            mapOf(
                "pg-midas" to
                    ConnectionConfig(
                        id = "pg-midas",
                        jdbcUrl = jdbcUrl,
                        username = PostgresPgFixture.READONLY_ROLE,
                        password = PostgresPgFixture.READONLY_PW,
                        database = database,
                        defaultSchema = "public",
                        requiresTenantId = requiresTenantId,
                        readOnly = true,
                    ),
            ),
        )

    private fun translator(): TranslatorClient =
        object : TranslatorClient {
            override suspend fun unparse(request: UnparseRequest): UnparseResponse =
                UnparseResponse
                    .newBuilder()
                    .setOutput(QUERY)
                    .setContext(request.context)
                    .build()

            override suspend fun probe() = Unit
        }

    /** Run the worker for [tenant] (null → omit tenant_id, exercising the fail-closed path). */
    fun execute(
        pool: ConnectionPoolManager,
        tenant: UUID?,
    ): List<ResultBatch> {
        val ctx =
            PipelineContext
                .newBuilder()
                .apply { if (tenant != null) tenantId = tenant.toString() }
                .build()
        val request =
            ExecuteRequest
                .newBuilder()
                .setPlan(
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
                        ).build(),
                ).setContext(ctx)
                .setConnectionId("pg-midas")
                .setOptions(ExecutionOptions.getDefaultInstance())
                .build()
        return runBlocking { ExecutePipeline(pool, translator(), limits).execute(request).toList() }
    }

    /** Decode every Arrow batch in [batches] into [PositionRow]s. */
    fun decode(batches: List<ResultBatch>): List<PositionRow> {
        val rows = mutableListOf<PositionRow>()
        RootAllocator(Long.MAX_VALUE).use { alloc ->
            batches.filter { !it.arrowIpc.isEmpty }.forEach { b ->
                ArrowStreamReader(ByteArrayInputStream(b.arrowIpc.toByteArray()), alloc).use { reader ->
                    while (reader.loadNextBatch()) {
                        val root = reader.vectorSchemaRoot
                        val acct = root.getVector("account_id") as BigIntVector
                        val amount = root.getVector("amount") as DecimalVector
                        val label = root.getVector("label") as VarCharVector
                        for (i in 0 until root.rowCount) {
                            rows.add(PositionRow(acct.get(i), amount.getObject(i), String(label.get(i))))
                        }
                    }
                }
            }
        }
        return rows
    }
}
