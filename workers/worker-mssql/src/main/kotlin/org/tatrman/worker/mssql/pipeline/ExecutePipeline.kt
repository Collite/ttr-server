// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.mssql.pipeline

import com.google.protobuf.kotlin.toByteString
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.Warning as PlanWarning
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.Value
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translate.v1.UnparseRequest
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.ResultBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.apache.arrow.memory.RootAllocator
import org.slf4j.LoggerFactory
import org.tatrman.worker.mssql.arrow.ArrowIpcSerializer
import org.tatrman.worker.mssql.arrow.MssqlArrowTypeMapper
import org.tatrman.worker.mssql.arrow.ResultSetToArrow
import org.tatrman.worker.mssql.client.TranslatorClient
import org.tatrman.worker.mssql.connection.ConnectionPoolManager
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * A `datetime` parameter as the wire format actually carries it.
 *
 * ⛔ The plan wire format has **no `date_value`** — `datetime_value` is the only temporal carrier —
 * so a slot a model declares `date` arrives here as a date-ONLY ISO string. That is the producer
 * behaving to contract, not a caller mistake: query-mcp maps `date | datetime | timestamp` onto the
 * single `datetime` tag, and its own spec pins the date-only form in `datetime_value`.
 * `Instant.parse` demands a full instant and accepts none of it, so a correct date parameter was
 * refused at run time with *"Text '2026-09-18' could not be parsed at index 10"* — index 10 being
 * one past the end of the string. The Python worker takes the same value happily through
 * `fromisoformat`, so the two Kotlin workers were the outliers, not the sender.
 *
 * ⚑ A date-only value becomes **local** midnight, deliberately: it is compared against a `date`
 * column, and routing it through UTC would move the day for any negative offset.
 */
internal fun parseTimestampParam(iso: String): Timestamp {
    val text = iso.trim()
    // `Instant.parse` covers a trailing `Z` and — since JDK 12 — an explicit offset, so those two
    // forms were never the problem. The two below are what it refuses outright.
    runCatching { Instant.parse(text) }.getOrNull()?.let { return Timestamp.from(it) }
    runCatching { LocalDateTime.parse(text) }.getOrNull()?.let { return Timestamp.valueOf(it) }
    runCatching { LocalDate.parse(text) }.getOrNull()?.let { return Timestamp.valueOf(it.atStartOfDay()) }
    throw IllegalArgumentException(
        "cannot read '$iso' as a timestamp parameter — expected an ISO instant, " +
            "offset date-time, local date-time, or date",
    )
}

/**
 * Orchestrates the seven-step Worker pipeline from Round 6.B:
 *
 *   1. validate `connection_id` ∈ supported_connections
 *   2. clamp `options` against engine limits
 *   3. translator.UnparseFromRelNode(plan, SQL, MSSQL) → SQL + parameter map
 *   4. acquire JDBC connection from pool
 *   5. prepareStatement, bind parameters, set fetchSize + queryTimeout
 *   6. execute → ResultSet
 *   7. ResultSetToArrow batches → Arrow IPC bytes → ResultBatch flow
 *
 * Cancellation: the surrounding gRPC context cancels the coroutine; the
 * pipeline catches CancellationException, calls `Statement.cancel()` (MS
 * SQL JDBC sends a TDS attention signal), and releases the connection.
 *
 * Errors travel as a single-element error stream (`is_first = is_last =
 * true` + empty `arrow_ipc` + populated `messages`) so the Dispatcher's
 * upstream consumer treats every failure mode uniformly.
 */
class ExecutePipeline(
    private val pool: ConnectionPoolManager,
    private val translator: TranslatorClient,
    private val limits: ExecutionLimits,
    private val allocator: RootAllocator = RootAllocator(Long.MAX_VALUE),
) {
    private val active = AtomicInteger(0)

    val activeQueries: Int
        get() = active.get()

    fun execute(request: ExecuteRequest): Flow<ResultBatch> {
        val context = request.context

        // Step 1 — connection_id validation.
        if (!pool.supportedConnections.contains(request.connectionId)) {
            return flowOf(
                errorBatch(
                    code = "connection_not_supported",
                    message =
                        "Worker does not advertise connection_id='${request.connectionId}'. " +
                            "Known: ${pool.supportedConnections.joinToString()}.",
                ),
            )
        }

        // Step 2 — clamp options.
        val opt = clamp(request.options)

        return flow {
            active.incrementAndGet()
            var connection: Connection? = null
            var statement: PreparedStatement? = null
            var resultSet: ResultSet? = null
            // Track SQL + bind count so error logging at the catch site can show what was
            // actually attempted. Stays null/0 when the failure is upstream of unparse.
            var sql: String? = null
            var bindCount = 0
            try {
                // Step 3 — translator unparse.
                val unparse =
                    translator.unparse(
                        UnparseRequest
                            .newBuilder()
                            .setPlan(request.plan)
                            .setTargetLanguage(Language.SQL)
                            .setTargetDialect(SqlDialect.MSSQL)
                            .setOptimize(true)
                            .setContext(context)
                            .build(),
                    )
                if (unparse.messagesList.any { it.severity == Severity.ERROR }) {
                    emit(errorBatch("translator_failed", unparse.messagesList.first().humanMessage, context))
                    return@flow
                }
                sql = unparse.output
                bindCount = unparse.context.parametersList.size

                // Step 4 — acquire connection.
                connection = pool.acquire(request.connectionId)

                // Step 5 — prepareStatement, bind, configure.
                statement = connection.prepareStatement(sql)
                bindParameters(statement, unparse.context.parametersList)
                runCatching { statement.fetchSize = opt.batchSizeRows }
                runCatching { statement.queryTimeout = opt.timeoutSeconds }

                // Step 6 — execute.
                resultSet = statement.executeQuery()

                // Step 7 — stream batches.
                val converter = ResultSetToArrow(allocator, opt.batchSizeRows, opt.maxBlobBytesPerCell)
                val schema = converter.schemaOf(resultSet.metaData)
                val fingerprint = ArrowIpcSerializer.fingerprintFor(schema)

                // DF-W03 / G7 — emit one `unsupported_type_as_binary` pipeline warning per column
                // mapped to opaque VARBINARY (rowversion / hierarchyid / geography / geometry /
                // sql_variant / unrecognised JDBC type). Attached to the FIRST batch's context so
                // it travels with the schema announcement and isn't duplicated across batches.
                val unsupportedFallbacks = MssqlArrowTypeMapper.unsupportedBinaryFallbacks(schema)
                val firstBatchContext =
                    if (unsupportedFallbacks.isEmpty()) {
                        unparse.context
                    } else {
                        val cb = unparse.context.toBuilder()
                        unsupportedFallbacks.forEach { (col, origin) ->
                            cb.addWarnings(
                                PlanWarning
                                    .newBuilder()
                                    .setCode("unsupported_type_as_binary")
                                    .setMessage(
                                        "Column '$col' has unsupported type '$origin'; emitted as opaque binary.",
                                    ).setSourceStage("execute")
                                    .setSourceService("workers/mssql")
                                    .build(),
                            )
                        }
                        cb.build()
                    }

                var index = 0
                var rowsTotal = 0L
                var firstEmitted = false
                for (batch in converter.convert(resultSet)) {
                    if (!currentCoroutineContext().isActive) break
                    val root = batch.root
                    val rowCount = batch.rowCount.toLong()
                    rowsTotal += rowCount
                    val ipcBytes = root?.let { ArrowIpcSerializer.serializeBatch(it) } ?: ByteArray(0)
                    val builder =
                        ResultBatch
                            .newBuilder()
                            .setArrowIpc(ipcBytes.toByteString())
                            .setBatchIndex(index)
                            .setBatchRowCount(rowCount)
                            .setIsFirst(!firstEmitted)
                            .setIsLast(false)
                            .setContext(if (!firstEmitted) firstBatchContext else unparse.context)
                    if (!firstEmitted) {
                        builder.setSchemaFingerprint(fingerprint)
                        firstEmitted = true
                    }
                    batch.rejections.forEach { rej ->
                        builder.addMessages(
                            ResponseMessage
                                .newBuilder()
                                .setSeverity(Severity.WARNING)
                                .setCode("blob_too_large")
                                .setHumanMessage(
                                    "Row dropped: column type '${rej.typeName}' value of ${rej.sizeBytes}B " +
                                        "exceeds max-blob-bytes-per-cell ${opt.maxBlobBytesPerCell}.",
                                ),
                        )
                    }
                    if (opt.rowLimit in 1..rowsTotal) builder.setIsLast(true)
                    emit(builder.build())
                    root?.close()
                    index++
                    if (opt.rowLimit in 1..rowsTotal) break
                }

                // Always emit a tail marker so callers can rely on `is_last`. If no batch was
                // emitted yet (empty result set), the tail IS the first batch — attach the
                // unsupported-type warnings here so they still reach the caller.
                emit(
                    ResultBatch
                        .newBuilder()
                        .setBatchIndex(index)
                        .setIsFirst(!firstEmitted)
                        .setIsLast(true)
                        .setContext(if (!firstEmitted) firstBatchContext else unparse.context)
                        .let { b -> if (!firstEmitted) b.setSchemaFingerprint(fingerprint) else b }
                        .setArrowIpc(ByteArray(0).toByteString())
                        .build(),
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    runCatching { statement?.cancel() }
                    throw t
                }
                if (t is ConnectionPoolManager.UnknownConnectionException) {
                    emit(errorBatch("connection_not_supported", t.message ?: "unknown connection_id", context))
                    return@flow
                }
                // Always log the SQL the worker was about to run (when unparse succeeded)
                // alongside connection_id and bind count. Operators were previously seeing
                // "Invalid object name 'db.dbo.X'" with no way to know the full SQL.
                if (sql != null) {
                    log.warn(
                        "Worker pipeline failed for connection_id={} (bindings={}) sql=[{}] : {}",
                        request.connectionId,
                        bindCount,
                        sql,
                        t.message,
                        t,
                    )
                } else {
                    log.warn(
                        "Worker pipeline failed before SQL was produced for connection_id={} : {}",
                        request.connectionId,
                        t.message,
                        t,
                    )
                }
                emit(errorBatch("worker_execution_failed", t.message ?: "Unhandled worker error.", context))
            } finally {
                runCatching { resultSet?.close() }
                runCatching { statement?.close() }
                runCatching { connection?.close() }
                active.decrementAndGet()
            }
        }.flowOn(Dispatchers.IO)
    }

    private fun clamp(options: org.tatrman.worker.v1.ExecutionOptions): ClampedOptions {
        val batch = options.batchSizeRows.takeIf { it > 0 } ?: limits.defaultBatchSizeRows
        val cappedBatch = minOf(batch, limits.maxBatchSizeRows)
        val timeout = options.timeoutSeconds.takeIf { it > 0L } ?: limits.defaultTimeoutSeconds
        val cappedTimeout = minOf(timeout, limits.maxTimeoutSeconds).toInt()
        return ClampedOptions(
            batchSizeRows = cappedBatch,
            timeoutSeconds = cappedTimeout,
            rowLimit = options.rowLimit,
            maxBlobBytesPerCell = limits.maxBlobBytesPerCell,
        )
    }

    private fun bindParameters(
        stmt: PreparedStatement,
        bindings: List<ParameterBinding>,
    ) {
        bindings.forEachIndexed { idx, binding ->
            val pos = idx + 1
            val v = binding.value
            if (!binding.hasValue() || v.isNull) {
                stmt.setNull(pos, jdbcTypeFor(binding.type))
                return@forEachIndexed
            }
            when (v.vCase) {
                Value.VCase.STRING_VALUE -> stmt.setString(pos, v.stringValue)
                Value.VCase.INT_VALUE -> stmt.setLong(pos, v.intValue)
                Value.VCase.FLOAT_VALUE -> stmt.setDouble(pos, v.floatValue)
                Value.VCase.BOOL_VALUE -> stmt.setBoolean(pos, v.boolValue)
                Value.VCase.DATETIME_VALUE -> stmt.setTimestamp(pos, parseTimestampParam(v.datetimeValue))
                Value.VCase.V_NOT_SET -> stmt.setNull(pos, jdbcTypeFor(binding.type))
            }
        }
    }

    private fun jdbcTypeFor(surface: String): Int =
        when (surface) {
            "int" -> Types.BIGINT
            "float" -> Types.DOUBLE
            "bool" -> Types.BOOLEAN
            "datetime" -> Types.TIMESTAMP
            else -> Types.NVARCHAR
        }

    private fun errorBatch(
        code: String,
        message: String,
        context: org.tatrman.plan.v1.PipelineContext =
            org.tatrman.plan.v1.PipelineContext
                .getDefaultInstance(),
    ): ResultBatch =
        ResultBatch
            .newBuilder()
            .setIsFirst(true)
            .setIsLast(true)
            .setArrowIpc(ByteArray(0).toByteString())
            .setContext(context)
            .addMessages(
                ResponseMessage
                    .newBuilder()
                    .setSeverity(Severity.ERROR)
                    .setCode(code)
                    .setHumanMessage(message),
            ).build()

    data class ExecutionLimits(
        val defaultBatchSizeRows: Int,
        val maxBatchSizeRows: Int,
        val defaultTimeoutSeconds: Long,
        val maxTimeoutSeconds: Long,
        val maxBlobBytesPerCell: Long,
    )

    private data class ClampedOptions(
        val batchSizeRows: Int,
        val timeoutSeconds: Int,
        val rowLimit: Long,
        val maxBlobBytesPerCell: Long,
    )

    companion object {
        private val log = LoggerFactory.getLogger(ExecutePipeline::class.java)
    }
}
