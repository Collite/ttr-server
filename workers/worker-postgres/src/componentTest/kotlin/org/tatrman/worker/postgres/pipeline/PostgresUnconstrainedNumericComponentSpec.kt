// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.postgres.pipeline

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.postgresql.ds.PGSimpleDataSource
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.testkit.containers.Containers
import org.tatrman.translate.v1.UnparseRequest
import org.tatrman.translate.v1.UnparseResponse
import org.tatrman.worker.postgres.client.TranslatorClient
import org.tatrman.worker.postgres.connection.ConnectionConfig
import org.tatrman.worker.postgres.connection.ConnectionPoolManager
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.ExecutionOptions
import org.tatrman.worker.v1.ResultBatch
import java.io.ByteArrayInputStream
import java.math.BigDecimal

/**
 * #155 / #83 against a real PostgreSQL: the statements that make PostgreSQL type a result column as an
 * UNCONSTRAINED numeric (no typmod — the driver reports precision 0, scale 0) come back with their
 * decimals. Before, every such value was rescaled to 0 decimals: `24.335` → `24`, `2.103100` → `2`,
 * a share of 0.244 → `0`, a sum of money lost its cents.
 */
@Tags("component")
class PostgresUnconstrainedNumericComponentSpec :
    StringSpec({

        "computed numerics keep their decimals; a declared numeric(7,2) is unchanged" {
            Containers.postgres().use { pg ->
                pg.start()
                PGSimpleDataSource()
                    .apply {
                        setURL(pg.jdbcUrl)
                        user = pg.username
                        password = pg.password
                    }.connection
                    .use { su ->
                        su.createStatement().use { st ->
                            // #83's reproduction, verbatim.
                            st.execute("CREATE TABLE t (id int, p numeric(18,6))")
                            st.execute("INSERT INTO t VALUES (1, 2.103100)")
                            st.execute("CREATE TABLE sales (amount numeric(7,2))")
                            st.execute("INSERT INTO sales VALUES (1234.56), (7.50)")
                        }
                    }
                val pool =
                    ConnectionPoolManager(
                        mapOf(
                            "pg" to
                                ConnectionConfig(
                                    id = "pg",
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
                try {
                    // #155's reproduction: a numeric(18,8) divided by an integer.
                    val computed =
                        run(
                            pool,
                            "SELECT CAST(24.335 AS NUMERIC(18, 8)) / 1 AS r, 504::numeric / 2064 AS share, " +
                                "CAST(1.5 AS NUMERIC(7, 2)) AS declared",
                        )
                    computed.getValue("r").single()!!.compareTo(BigDecimal("24.335")) shouldBe 0
                    // PostgreSQL answers 0.24418604651162790698; 18 decimals is the cap.
                    computed.getValue("share").single() shouldBe BigDecimal("0.244186046511627907")
                    computed.getValue("declared").single() shouldBe BigDecimal("1.50")

                    // #83: a UNION branch with an untyped NULL makes the column unconstrained.
                    val union = run(pool, "SELECT p FROM t UNION ALL SELECT NULL")
                    union.getValue("p") shouldBe listOf(BigDecimal("2.103100"), null)

                    // SUM of a numeric(7,2) is unconstrained too: the cents survive, no zeros are added.
                    val sum = run(pool, "SELECT SUM(amount) AS total, AVG(amount) AS mean FROM sales")
                    sum.getValue("total").single() shouldBe BigDecimal("1242.06")
                    sum.getValue("mean").single()!!.compareTo(BigDecimal("621.03")) shouldBe 0
                } finally {
                    pool.close()
                }
            }
        }
    })

private val limits =
    ExecutePipeline.ExecutionLimits(
        defaultBatchSizeRows = 100,
        maxBatchSizeRows = 1_000,
        defaultTimeoutSeconds = 30,
        maxTimeoutSeconds = 300,
        maxBlobBytesPerCell = 8 * 1024 * 1024,
    )

/** Run [sql] through the real pipeline (Translate faked to return it) and decode every decimal column. */
private fun run(
    pool: ConnectionPoolManager,
    sql: String,
): Map<String, List<BigDecimal?>> {
    val translator =
        object : TranslatorClient {
            override suspend fun unparse(request: UnparseRequest): UnparseResponse =
                UnparseResponse
                    .newBuilder()
                    .setOutput(sql)
                    .setContext(request.context)
                    .build()

            override suspend fun probe() = Unit
        }
    val request =
        ExecuteRequest
            .newBuilder()
            .setPlan(
                PlanNode.newBuilder().setTableScan(
                    TableScanNode.newBuilder().setTable(
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(SchemaCode.DB)
                            .setNamespace("public")
                            .setName("t"),
                    ),
                ),
            ).setContext(PipelineContext.getDefaultInstance())
            .setConnectionId("pg")
            .setOptions(ExecutionOptions.getDefaultInstance())
            .build()
    val batches: List<ResultBatch> = runBlocking { ExecutePipeline(pool, translator, limits).execute(request).toList() }
    check(batches.flatMap { it.messagesList }.none { it.severity == Severity.ERROR }) {
        "the pipeline reported an error: ${batches.flatMap { it.messagesList }}"
    }
    val columns = linkedMapOf<String, MutableList<BigDecimal?>>()
    RootAllocator(Long.MAX_VALUE).use { alloc ->
        batches.filter { !it.arrowIpc.isEmpty }.forEach { b ->
            ArrowStreamReader(ByteArrayInputStream(b.arrowIpc.toByteArray()), alloc).use { reader ->
                while (reader.loadNextBatch()) {
                    val root = reader.vectorSchemaRoot
                    root.fieldVectors.filterIsInstance<DecimalVector>().forEach { v ->
                        val out = columns.getOrPut(v.name) { mutableListOf() }
                        for (i in 0 until root.rowCount) out.add(if (v.isNull(i)) null else v.getObject(i))
                    }
                }
            }
        }
    }
    return columns
}
