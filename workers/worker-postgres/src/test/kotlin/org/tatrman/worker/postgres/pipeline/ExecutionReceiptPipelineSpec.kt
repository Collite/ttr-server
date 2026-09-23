// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.postgres.pipeline

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
import org.tatrman.worker.postgres.client.TranslatorClient
import org.tatrman.worker.postgres.connection.ConnectionPoolManager
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
import java.sql.Statement
import java.sql.Types
import java.util.UUID

/**
 * **ES-P0·S0.2 — every path that emits `is_last = true` carries a receipt.**
 *
 * The worker emits a tail marker on FOUR paths — the normal end of the row loop, the row-cap
 * exit, and both error streams (before unparse and at execution) — and the document's SQL section
 * is only honest if each of them says what it knows. The two error cases are the ones worth being
 * explicit about (ES task list 1, "the tail marker on the error stream"):
 *
 *  - failed **before** unparse ⇒ `statement_kind = NONE`. There is no statement, and saying so is
 *    a fact; rendering an empty string as "executed" is the dishonesty ES exists to remove.
 *  - failed **at** execution ⇒ `SQL_EXECUTED` + the statement. The statement exists, and it is
 *    precisely the one whoever is reading the failed run wants.
 *
 * The real-Postgres half of these assertions (a live `SET LOCAL`, a real duration) is
 * `PostgresReceiptComponentSpec`; what is here needs no Docker.
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
            receipt.dialect shouldBe "POSTGRESQL"
            receipt.statement shouldBe SELECT
            receipt.statementTruncated shouldBe false
            receipt.connectionId shouldBe CONN
            receipt.engineLabel shouldBe "worker-postgres@$CONN"
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
            only.isLast shouldBe true
            only.messagesList.single().code shouldBe "connection_not_supported"
            only.hasReceipt() shouldBe true
            only.receipt.statementKind shouldBe StatementKind.NONE
            only.receipt.statement shouldBe ""
            only.receipt.connectionId shouldBe "nope"
            only.receipt.engineLabel shouldBe "worker-postgres@nope"
        }

        "a failure AT execution carries SQL_EXECUTED and the statement that failed" {
            val out = run(sql = SELECT, executeThrows = SQLException("relation \"positions\" does not exist"))

            val only = out.single()
            only.isLast shouldBe true
            only.messagesList.single().code shouldBe "worker_execution_failed"
            only.receipt.statementKind shouldBe StatementKind.SQL_EXECUTED
            only.receipt.statement shouldBe SELECT
            only.receipt.rowsTotal shouldBe 0L
        }

        "a translator error is also a failure before unparse — NONE" {
            val translator =
                object : TranslatorClient {
                    override suspend fun unparse(request: UnparseRequest): UnparseResponse =
                        UnparseResponse
                            .newBuilder()
                            .setContext(request.context)
                            .addMessages(
                                org.tatrman.common.v1.ResponseMessage
                                    .newBuilder()
                                    .setSeverity(org.tatrman.common.v1.Severity.ERROR)
                                    .setCode("language_not_supported")
                                    .setHumanMessage("nope"),
                            ).build()

                    override suspend fun probe() = Unit
                }
            val out =
                runBlocking {
                    ExecutePipeline(pool(), translator, LIMITS).execute(request(CONN)).toList()
                }

            val only = out.single()
            only.messagesList.single().code shouldBe "translator_failed"
            only.receipt.statementKind shouldBe StatementKind.NONE
        }

        "a tenant-enforcing connection with a bound tenant reports RLS_APPLIED" {
            val out = run(sql = SELECT, requiresTenant = true, tenant = UUID.randomUUID())

            out.last().receipt.rls shouldBe RlsOutcome.RLS_APPLIED
        }

        "a SET LOCAL that fails reports RLS_FAILED — with the statement, which exists" {
            val out =
                run(
                    sql = SELECT,
                    requiresTenant = true,
                    tenant = UUID.randomUUID(),
                    setLocalThrows = SQLException("permission denied to set parameter"),
                )

            val only = out.single()
            only.messagesList.single().code shouldBe "rls_set_failed"
            only.receipt.rls shouldBe RlsOutcome.RLS_FAILED
            only.receipt.statementKind shouldBe StatementKind.SQL_EXECUTED
            only.receipt.statement shouldBe SELECT
        }

        "a missing tenant on a tenant-enforcing connection reports RLS_FAILED and NONE" {
            val out =
                runBlocking {
                    ExecutePipeline(pool(requiresTenant = true), translator(SELECT), LIMITS)
                        .execute(request(CONN))
                        .toList()
                }

            val only = out.single()
            only.messagesList.single().code shouldBe "tenant_id_required"
            only.receipt.rls shouldBe RlsOutcome.RLS_FAILED
            only.receipt.statementKind shouldBe StatementKind.NONE
        }

        "the row-cap exit is a tail marker too, and carries the receipt" {
            val out = run(sql = SELECT, rowLimit = 1)

            // Both `is_last` batches on this path stand alone (the cap batch and the tail marker
            // the loop still emits after the break).
            val lastBatches = out.filter { it.isLast }
            lastBatches.size shouldBe 2
            lastBatches.forEach { batch ->
                batch.hasReceipt() shouldBe true
                batch.receipt.statementKind shouldBe StatementKind.SQL_EXECUTED
                batch.receipt.statement shouldBe SELECT
                batch.receipt.rowsTotal shouldBe 2L
            }
        }
    })

private const val CONN = "pg-midas"
private const val SELECT = "SELECT account_id FROM public.positions"

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

private fun pool(requiresTenant: Boolean = false): ConnectionPoolManager =
    mockk<ConnectionPoolManager>().also {
        every { it.supportedConnections } returns setOf(CONN)
        every { it.requiresTenantId(CONN) } returns requiresTenant
    }

private fun request(
    connectionId: String,
    tenant: UUID? = null,
    rowLimit: Long = 0,
): ExecuteRequest =
    ExecuteRequest
        .newBuilder()
        .setContext(
            PipelineContext
                .newBuilder()
                .setCorrelationId("corr-es-1")
                .apply { if (tenant != null) tenantId = tenant.toString() },
        ).setConnectionId(connectionId)
        .setOptions(ExecutionOptions.newBuilder().setRowLimit(rowLimit))
        .build()

/** Two-row fake ResultSet (`account_id BIGINT`), the smallest thing the Arrow path accepts. */
private fun fakeResultSet(): ResultSet {
    val meta =
        mockk<ResultSetMetaData>().also {
            every { it.columnCount } returns 1
            every { it.getColumnLabel(1) } returns "account_id"
            every { it.getColumnName(1) } returns "account_id"
            every { it.getColumnTypeName(1) } returns "int8"
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
    requiresTenant: Boolean = false,
    tenant: UUID? = null,
    rowLimit: Long = 0,
    executeThrows: Throwable? = null,
    setLocalThrows: Throwable? = null,
): List<ResultBatch> {
    val statement =
        mockk<PreparedStatement>(relaxed = true).also {
            if (executeThrows != null) {
                every { it.executeQuery() } throws executeThrows
            } else {
                every { it.executeQuery() } returns fakeResultSet()
            }
        }
    val setLocal =
        mockk<Statement>(relaxed = true).also {
            if (setLocalThrows != null) every { it.execute(any<String>()) } throws setLocalThrows
        }
    val connection =
        mockk<Connection>(relaxed = true).also {
            every { it.prepareStatement(any()) } returns statement
            every { it.createStatement() } returns setLocal
        }
    val pool = pool(requiresTenant).also { every { it.acquire(CONN) } returns connection }

    return runBlocking {
        ExecutePipeline(pool, translator(sql, parameters), LIMITS)
            .execute(request(CONN, tenant, rowLimit))
            .toList()
    }
}
