// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.runner.ArrowTables
import org.tatrman.automation.runner.QueryRun
import org.tatrman.automation.runner.Table
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.Value
import org.tatrman.query.v1.CompileResponse
import org.tatrman.query.v1.RunRequest
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.codec.sql.RowWriteUnparseException
import org.tatrman.translator.codec.sql.RowWriteUnparser
import org.tatrman.translator.schema.MapToPhysical
import org.tatrman.translator.schema.MapToPhysicalResult
import org.tatrman.worker.v1.ResultBatch
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Collections
import javax.sql.DataSource

/**
 * Query's write seam on a real Postgres: the fixture bundle supplies the model and bindings to
 * production SnapshotModelHandle placement and the pinned MapToPhysical. Like Dispatch, fill only
 * empty target namespaces; preserve logical namespaces on mismatches. Unparse with model keys and
 * execute one transaction per request under the alias's search_path, as worker-postgres does.
 */
class RowWritesQueryFake(
    private val dataSource: DataSource,
    /** Query world alias → the schema its writer connection's `default-schema` names. */
    private val worlds: Map<String, String>,
    bundle: () -> Bundle,
    /** Every non-`row_writes` request (the door's reads). */
    private val reads: QueryRun? = null,
) : QueryRun {
    private val model by lazy { writerModelHandle(bundle()) }

    /** Every `row_writes` request, as sent (a dry run must leave this empty). */
    val writes: MutableList<RunRequest> = Collections.synchronizedList(mutableListOf())

    /** A Query/validate refusal before dispatch (`identity`, `write_under_row_policy`, …): its code, or null. */
    @Volatile var refuse: (RunRequest) -> String? = { null }

    /** Commit, then answer as if the connection dropped during the commit (an unknown outcome). */
    @Volatile var loseCommit: (RunRequest) -> Boolean = { false }

    override fun run(request: RunRequest): Flow<ResultBatch> =
        if (request.sourceLanguage == Language.REL_NODE) {
            flow { emit(write(request)) }
        } else {
            requireNotNull(reads) { "this fake answers row_writes only" }.run(request)
        }

    override suspend fun compile(request: RunRequest): CompileResponse =
        requireNotNull(reads) { "this fake answers row_writes only" }.compile(request)

    private fun write(request: RunRequest): ResultBatch {
        writes += request
        refuse(request)?.let { return error(request, it, "refused before dispatch") }
        val schema = worlds[request.world] ?: return error(request, "unknown_world", "no writer world ${request.world}")
        val plan = PlanNode.parseFrom(request.source.toByteArray(Charsets.ISO_8859_1))
        check(plan.nodeCase == PlanNode.NodeCase.ROW_WRITES) { "not a row_writes root" }
        val physical =
            when (val result = MapToPhysical.apply(plan, model)) {
                is MapToPhysicalResult.Success -> result.plan
                is MapToPhysicalResult.Failure -> return error(request, result.code, result.message)
            }
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            val statements =
                physical.rowWrites.rowsList.mapIndexed { seq, row ->
                    val lowered =
                        if (row.target.namespace.isEmpty()) {
                            row.toBuilder().setTarget(row.target.toBuilder().setNamespace(schema)).build()
                        } else {
                            row
                        }
                    try {
                        RowWriteUnparser.unparse(
                            lowered,
                            SqlDialect.POSTGRESQL,
                            request.context.parametersList,
                            model
                                .tables(
                                    lowered.target.schemaCode,
                                    lowered.target.namespace,
                                )[lowered.target]
                                ?.primaryKey
                                .orEmpty(),
                        )
                    } catch (e: RowWriteUnparseException) {
                        return error(request, e.code, "row_writes seq $seq: ${e.message}")
                    }
                }
            val answers = mutableListOf<List<Any?>>()
            var seq = 0
            try {
                connection.createStatement().use { it.execute("SET LOCAL search_path = \"$schema\", pg_catalog") }
                for ((index, statement) in statements.withIndex()) {
                    seq = index
                    connection.prepareStatement(statement.sql).use { ps ->
                        bind(ps, statement.parameters)
                        ps.executeQuery().use { rs ->
                            check(rs.next()) { "a row write answered no row" }
                            answers += listOf(index.toLong(), rs.getLong("rows_affected"), rs.getString("prior"))
                        }
                    }
                }
            } catch (e: Exception) {
                runCatching { connection.rollback() }
                return error(request, "worker_execution_failed", "row_writes seq $seq: ${e.message}")
            }
            connection.commit()
            if (loseCommit(
                    request,
                )
            ) {
                return error(request, "worker_execution_failed", "row_writes commit: connection lost")
            }
            val table =
                Table(
                    listOf(
                        ManifestColumn("seq", "int"),
                        ManifestColumn("rows_affected", "bigint"),
                        ManifestColumn("prior", "text"),
                    ),
                    answers,
                )
            return ResultBatch
                .newBuilder()
                .setIsFirst(true)
                .setIsLast(true)
                .setBatchRowCount(answers.size.toLong())
                .setArrowIpc(ByteString.copyFrom(ArrowTables.encode(table)))
                .setContext(request.context)
                .build()
        }
    }

    /** worker-postgres `ExecutePipeline.bindParameters`, verbatim in behaviour. */
    private fun bind(
        statement: PreparedStatement,
        bindings: List<ParameterBinding>,
    ) {
        bindings.forEachIndexed { index, binding ->
            val pos = index + 1
            val v = binding.value
            if (!binding.hasValue() || v.isNull) {
                statement.setNull(pos, jdbcType(binding.type))
                return@forEachIndexed
            }
            when (v.vCase) {
                Value.VCase.STRING_VALUE -> statement.setString(pos, v.stringValue)
                Value.VCase.INT_VALUE -> statement.setLong(pos, v.intValue)
                Value.VCase.FLOAT_VALUE -> statement.setDouble(pos, v.floatValue)
                Value.VCase.BOOL_VALUE -> statement.setBoolean(pos, v.boolValue)
                Value.VCase.DATETIME_VALUE -> statement.setTimestamp(pos, timestamp(v.datetimeValue))
                else -> statement.setNull(pos, jdbcType(binding.type))
            }
        }
    }

    private fun jdbcType(surface: String): Int =
        when (surface) {
            "int" -> Types.BIGINT
            "float" -> Types.DOUBLE
            "bool" -> Types.BOOLEAN
            "datetime" -> Types.TIMESTAMP
            else -> Types.VARCHAR
        }

    /** worker-postgres `parseTimestampParam`. */
    private fun timestamp(iso: String): Timestamp {
        val text = iso.trim()
        runCatching { Instant.parse(text) }.getOrNull()?.let { return Timestamp.from(it) }
        runCatching { LocalDateTime.parse(text) }.getOrNull()?.let { return Timestamp.valueOf(it) }
        runCatching { LocalDate.parse(text) }.getOrNull()?.let { return Timestamp.valueOf(it.atStartOfDay()) }
        throw IllegalArgumentException("cannot read '$iso' as a timestamp parameter")
    }

    private fun error(
        request: RunRequest,
        code: String,
        message: String,
    ): ResultBatch =
        ResultBatch
            .newBuilder()
            .setIsFirst(true)
            .setIsLast(true)
            .setContext(request.context)
            .addMessages(
                ResponseMessage
                    .newBuilder()
                    .setSeverity(Severity.ERROR)
                    .setCode(code)
                    .setHumanMessage(message),
            ).build()
}
