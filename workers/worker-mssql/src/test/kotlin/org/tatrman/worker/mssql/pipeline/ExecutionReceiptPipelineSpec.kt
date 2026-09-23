// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.mssql.pipeline

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.Value
import org.tatrman.translate.v1.UnparseRequest
import org.tatrman.translate.v1.UnparseResponse
import org.tatrman.worker.mssql.client.TranslatorClient
import org.tatrman.worker.mssql.connection.ConnectionPoolManager
import org.tatrman.worker.receipt.ExecutionReceiptBuilder
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.ExecutionOptions
import org.tatrman.worker.v1.ResultBatch
import org.tatrman.worker.v1.RlsOutcome
import org.tatrman.worker.v1.StatementKind
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Types

/**
 * **ES-P0·S0.2 — the MSSQL worker's half, which is the Postgres one minus RLS.**
 *
 * Same contract, same four `is_last` paths, one difference stated rather than implied: `rls` is
 * always `RLS_NOT_REQUIRED` because this worker has no tenant envelope. A receipt that left the
 * field at `RLS_OUTCOME_UNSPECIFIED` would read as "we did not look"; `RLS_NOT_REQUIRED` is the
 * true statement, and the document's Security section depends on the difference.
 *
 * The real-MSSQL assertions live in `MssqlMssqlComponentSpec` and run on CI only (amd64 image).
 */
class ExecutionReceiptPipelineSpec :
    StringSpec({

        "the tail marker of a successful run carries the statement half" {
            val out = run(sql = SELECT, parameters = listOf(binding("year_from", "int", 2025)))

            val tail = out.last()
            tail.isLast shouldBe true
            tail.hasReceipt() shouldBe true

            val receipt = tail.receipt
            receipt.statementKind shouldBe StatementKind.SQL_EXECUTED
            receipt.dialect shouldBe "MSSQL"
            receipt.statement shouldBe SELECT
            receipt.connectionId shouldBe CONN
            receipt.engineLabel shouldBe "worker-mssql@$CONN"
            receipt.rowsTotal shouldBe 2L
            receipt.durationMs shouldBeGreaterThanOrEqual 0L
            receipt.rls shouldBe RlsOutcome.RLS_NOT_REQUIRED

            // ⚑ES-2 — the roster travels, the value does not.
            receipt.parametersCount shouldBe 1
            receipt.getParameters(0).name shouldBe "year_from"
            receipt.getParameters(0).type shouldBe "int"
            receipt.getParameters(0).valueMasked shouldBe ExecutionReceiptBuilder.MASKED
            receipt.getParameters(0).masked shouldBe true
        }

        "an unknown connection_id fails before unparse — statement_kind = NONE, no statement" {
            val out =
                runBlocking {
                    ExecutePipeline(ConnectionPoolManager(emptyMap()), translator(SELECT), LIMITS)
                        .execute(request("nope"))
                        .toList()
                }

            val only = out.single()
            only.messagesList.single().code shouldBe "connection_not_supported"
            only.receipt.statementKind shouldBe StatementKind.NONE
            only.receipt.statement shouldBe ""
            only.receipt.engineLabel shouldBe "worker-mssql@nope"
        }

        "a failure AT execution carries SQL_EXECUTED and the statement that failed" {
            val out = run(sql = SELECT, executeThrows = SQLException("Invalid object name 'dbo.nope'."))

            val only = out.single()
            only.messagesList.single().code shouldBe "worker_execution_failed"
            only.receipt.statementKind shouldBe StatementKind.SQL_EXECUTED
            only.receipt.statement shouldBe SELECT
        }

        "the row-cap exit is a tail marker too, and carries the receipt" {
            val out = run(sql = SELECT, rowLimit = 1)

            val lastBatches = out.filter { it.isLast }
            lastBatches.size shouldBe 2
            lastBatches.forEach {
                it.hasReceipt() shouldBe true
                it.receipt.statement shouldBe SELECT
                it.receipt.rowsTotal shouldBe 2L
            }
        }
    })

private const val CONN = "df-test"
private const val SELECT = "SELECT id FROM dbo.sample_orders"

private val LIMITS =
    ExecutePipeline.ExecutionLimits(
        defaultBatchSizeRows = 100,
        maxBatchSizeRows = 1_000,
        defaultTimeoutSeconds = 30,
        maxTimeoutSeconds = 300,
        maxBlobBytesPerCell = 8 * 1024 * 1024,
    )

private fun binding(
    name: String,
    type: String,
    value: Long,
): ParameterBinding =
    ParameterBinding
        .newBuilder()
        .setName(name)
        .setType(type)
        .setValue(Value.newBuilder().setIntValue(value))
        .build()

private fun translator(
    sql: String,
    parameters: List<ParameterBinding> = emptyList(),
): TranslatorClient =
    object : TranslatorClient {
        override suspend fun unparse(request: UnparseRequest): UnparseResponse =
            UnparseResponse
                .newBuilder()
                .setOutput(sql)
                .setContext(request.context.toBuilder().addAllParameters(parameters))
                .build()

        override suspend fun probe() = Unit
    }

private fun request(
    connectionId: String,
    rowLimit: Long = 0,
): ExecuteRequest =
    ExecuteRequest
        .newBuilder()
        .setContext(PipelineContext.newBuilder().setCorrelationId("corr-es-1"))
        .setConnectionId(connectionId)
        .setOptions(ExecutionOptions.newBuilder().setRowLimit(rowLimit))
        .build()

/** Two-row fake ResultSet (`id BIGINT`), the smallest thing the Arrow path accepts. */
private fun fakeResultSet(): ResultSet {
    val meta =
        mockk<ResultSetMetaData>().also {
            every { it.columnCount } returns 1
            every { it.getColumnLabel(1) } returns "id"
            every { it.getColumnName(1) } returns "id"
            every { it.getColumnTypeName(1) } returns "bigint"
            every { it.getColumnType(1) } returns Types.BIGINT
            every { it.getPrecision(1) } returns 0
            every { it.getScale(1) } returns 0
            every { it.isNullable(1) } returns ResultSetMetaData.columnNullable
        }
    return mockk<ResultSet>(relaxed = true).also {
        every { it.metaData } returns meta
        every { it.next() } returnsMany listOf(true, true, false)
        every { it.wasNull() } returns false
        every { it.getLong(1) } returnsMany listOf(1001L, 1002L)
    }
}

private fun run(
    sql: String,
    parameters: List<ParameterBinding> = emptyList(),
    rowLimit: Long = 0,
    executeThrows: Throwable? = null,
): List<ResultBatch> {
    val statement =
        mockk<PreparedStatement>(relaxed = true).also {
            if (executeThrows != null) {
                every { it.executeQuery() } throws executeThrows
            } else {
                every { it.executeQuery() } returns fakeResultSet()
            }
        }
    val connection = mockk<Connection>(relaxed = true).also { every { it.prepareStatement(any()) } returns statement }
    val pool =
        mockk<ConnectionPoolManager>().also {
            every { it.supportedConnections } returns setOf(CONN)
            every { it.acquire(CONN) } returns connection
        }

    return runBlocking {
        ExecutePipeline(pool, translator(sql, parameters), LIMITS).execute(request(CONN, rowLimit)).toList()
    }
}
