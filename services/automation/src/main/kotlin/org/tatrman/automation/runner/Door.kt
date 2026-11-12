// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import com.auth0.jwt.JWT
import io.grpc.ManagedChannelBuilder
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.identity.RunIdentity
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.RowWrites
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.Value
import org.tatrman.query.v1.CompileResponse
import org.tatrman.query.v1.ErReadScope
import org.tatrman.query.v1.QueryServiceGrpcKt
import org.tatrman.query.v1.RowWindow
import org.tatrman.query.v1.RunRequest
import org.tatrman.translate.v1.Language
import org.tatrman.worker.v1.ResultBatch
import java.time.Duration
import java.util.UUID

/** Storage is a logical name, never a connection URL or a secret. */
data class DoorRead(
    val run: UUID,
    val island: String,
    val world: String,
    val storage: String,
    val sql: String,
    val bindings: Map<String, BoundValue>,
    val identity: RunIdentity,
    val modelFp: String,
    val readCeiling: Int,
    val columns: List<ManifestColumn>,
    val timeout: Duration,
    val objectsRead: Set<String> = emptySet(),
)

data class ReadResult(
    val table: Table,
    val warnings: Set<String> = emptySet(),
)

/**
 * One `row_writes` request (D-163): the rows of one (storage, entity group), sent in one transaction. [rows]
 * targets ER entities; Query lowers them. [parameters] bind every value (never interpolated).
 */
data class DoorWrite(
    val run: UUID,
    val label: String,
    val world: String,
    val storage: String,
    val rows: RowWrites,
    val parameters: List<ParameterBinding>,
    val identity: RunIdentity,
    val modelFp: String,
    val timeout: Duration,
)

/** One answer per `RowWrite`, in order: `rows_affected` and `prior` (the `read_before` values; null = none read). */
data class WriteResult(
    val rowsAffected: List<Long>,
    val prior: List<JsonObject?>,
)

/**
 * Why a write request did not answer. [Refused]: nothing ran (identity, policy, route, a read-only connection).
 * [RowFailed]: a statement failed and the worker rolled the whole request back. [Unknown]: the request may have
 * committed (the commit or the connection was lost) — the writer must never resend it as if it had failed.
 */
sealed class DoorWriteFailure(
    val code: String,
    message: String,
) : Exception(message) {
    class Refused(
        code: String,
        message: String,
    ) : DoorWriteFailure(code, message)

    class RowFailed(
        val seq: Int,
        code: String,
        message: String,
    ) : DoorWriteFailure(code, message)

    class Unknown(
        code: String,
        message: String,
    ) : DoorWriteFailure(code, message)
}

fun interface Door {
    suspend fun read(read: DoorRead): ReadResult

    suspend fun write(write: DoorWrite): WriteResult =
        throw DoorWriteFailure.Refused("storage-unmapped", "this door has no write path")
}

/** Test seam at the gRPC stub boundary, not a second SQL execution road. */
fun interface QueryRun {
    fun run(request: RunRequest): Flow<ResultBatch>

    suspend fun compile(request: RunRequest): CompileResponse =
        error("Query Compile is required for ER scope preflight")
}

/**
 * The governed door. Each `world/storage` must map to an explicit Query world alias (QUERY_WORLDS).
 * Never fall back to Query's default connection: that could read ERP instead of the ledger.
 */
class QueryDoor(
    private val query: QueryRun,
    private val routes: Map<String, String>,
    private val tokens: ServiceTokens,
    /** AUTOMATION_DOOR_WRITE_ROUTES: never [routes], never a default (amendment 2026-11-03). */
    private val writeRoutes: Map<String, String> = emptyMap(),
) : Door {
    /** Claims of a token obtained from the configured IdP, never the initiating caller's roles. */
    private suspend fun roles(identity: RunIdentity): List<String> {
        if (identity.credential == null) return identity.caller.roles.toList()
        val decoded = JWT.decode(tokens.token(identity.credential))
        val principal =
            org.tatrman.automation.identity.Caller
                .of(decoded.token, decoded)
                ?: refuse("identity-unmapped", "the run identity's token names nobody")
        if (principal.principal !=
            identity.principal
        ) {
            refuse("identity-mismatch", "the IdP returned another run identity")
        }
        return principal.roles.toList()
    }

    override suspend fun read(read: DoorRead): ReadResult {
        try {
            val world =
                routes["${read.world}/${read.storage}"]
                    ?: refuse("storage-unmapped", "no door route for ${read.world}/${read.storage}")
            val roles = roles(read.identity)
            val context =
                PipelineContext
                    .newBuilder()
                    .setCorrelationId("${read.run}/${read.island}")
                    .setUserId(read.identity.principal)
                    .addAllAuthRoles(roles)
                    .setModelVersion(read.modelFp)
                    .addAllParameters(read.bindings.map { (name, binding) -> parameter(name, binding) })
                    .build()
            val sql =
                SqlParameters.rewrite(read.sql) { name ->
                    if (name !in read.bindings) refuse("parameter-unbound", "unbound recipe parameter $name")
                    "{$name}"
                }
            val request =
                RunRequest
                    .newBuilder()
                    .setSource(sql)
                    .setSourceLanguage(Language.SQL)
                    .setSourceSchema(SchemaCode.ER)
                    .setContext(context)
                    .setWorld(world)
                    .setRowWindow(RowWindow.newBuilder().setLimit(read.readCeiling.toLong()))
                    .setAllowedErReads(ErReadScope.newBuilder().addAllObjects(read.objectsRead.sorted()))
                    .build()
            val streams = mutableListOf<ByteArray>()
            val warnings = linkedSetOf<String>()
            var rows = 0L
            kotlinx.coroutines.withTimeout(read.timeout.toMillis()) {
                val compiled = query.compile(request)
                compiled.messagesList.firstOrNull { it.severity == Severity.ERROR }?.let {
                    if (it.code == "out-of-scope-read") scopeRefusal(read.island)
                    refuse(it.code, it.humanMessage)
                }
                if (!compiled.hasPlan() || !compiled.hasErReads()) {
                    refuse("er-read-scope-unavailable", "Query did not provide validated ER read evidence")
                }
                if (!read.objectsRead.containsAll(compiled.erReads.objectsList)) scopeRefusal(read.island)
                query.run(request).collect { batch ->
                    batch.messagesList.firstOrNull { it.severity == Severity.ERROR }?.let {
                        if (it.code == "out-of-scope-read") scopeRefusal(read.island)
                        refuse(
                            it.code,
                            it.humanMessage,
                        )
                    }
                    warnings += batch.context.warningsList.map { it.code }
                    warnings += batch.messagesList.filter { it.severity == Severity.WARNING }.map { it.code }
                    warnings += batch.noticesList.map { it.code }
                    rows += batch.batchRowCount
                    if (rows > read.readCeiling) refuse("read-ceiling", "door exceeded the requested read ceiling")
                    if (!batch.arrowIpc.isEmpty) streams += batch.arrowIpc.toByteArray()
                }
            }
            val table = withContext(Dispatchers.IO) { ArrowTables.decode(streams, read.columns) }
            if (table.rows.size > read.readCeiling) refuse("read-ceiling", "door exceeded the requested read ceiling")
            return ReadResult(table, warnings)
        } catch (e: AutomationException) {
            throw e
        } catch (
            e: kotlinx.coroutines.TimeoutCancellationException,
        ) {
            refuse("deadline-exceeded", "door read exceeded the run budget")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: StatusException) {
            refuse(e.status.code.name, e.status.description ?: "door refused")
        } catch (e: StatusRuntimeException) {
            refuse(e.status.code.name, e.status.description ?: "door refused")
        } catch (e: Exception) {
            refuse("invalid-result", "door result or identity could not be read")
        }
    }

    /**
     * D-163: `RunRequest { REL_NODE, PlanNode{row_writes} }` under `run_as`'s context, no read scope (a read
     * scope never grants a write, D-172). Refusals before the worker ran are [DoorWriteFailure.Refused]; the
     * worker's `row_writes seq N` is [DoorWriteFailure.RowFailed]; a lost commit, a broken stream or a timeout
     * after the request left is [DoorWriteFailure.Unknown].
     */
    override suspend fun write(write: DoorWrite): WriteResult {
        val world =
            writeRoutes["${write.world}/${write.storage}"]
                ?: throw DoorWriteFailure.Refused(
                    "storage-unmapped",
                    "no door write route for ${write.world}/${write.storage}",
                )
        val count = write.rows.rowsCount
        require(count > 0) { "an empty row_writes request" }
        val request =
            try {
                val context =
                    PipelineContext
                        .newBuilder()
                        .setCorrelationId("${write.run}/${write.label}")
                        .setUserId(write.identity.principal)
                        .addAllAuthRoles(roles(write.identity))
                        .setModelVersion(write.modelFp)
                        .addAllParameters(write.parameters)
                        .build()
                RunRequest
                    .newBuilder()
                    .setSource(
                        PlanNode
                            .newBuilder()
                            .setRowWrites(write.rows)
                            .build()
                            .toByteArray()
                            .toString(Charsets.ISO_8859_1),
                    ).setSourceLanguage(Language.REL_NODE)
                    .setSourceSchema(SchemaCode.ER)
                    .setContext(context)
                    .setWorld(world)
                    .build()
            } catch (e: AutomationException) {
                throw DoorWriteFailure.Refused("identity", e.message ?: "the run identity has no token")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw DoorWriteFailure.Refused("identity", "the run identity's token could not be read")
            }
        val streams = mutableListOf<ByteArray>()
        try {
            kotlinx.coroutines.withTimeout(write.timeout.toMillis()) {
                query.run(request).collect { batch ->
                    batch.messagesList.firstOrNull { it.severity == Severity.ERROR }?.let { throw failure(it) }
                    if (!batch.arrowIpc.isEmpty) streams += batch.arrowIpc.toByteArray()
                }
            }
        } catch (e: DoorWriteFailure) {
            throw e
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw DoorWriteFailure.Unknown("deadline-exceeded", "the door write exceeded the run budget")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: StatusException) {
            throw DoorWriteFailure.Unknown(e.status.code.name, e.status.description ?: "the door call failed")
        } catch (e: StatusRuntimeException) {
            throw DoorWriteFailure.Unknown(e.status.code.name, e.status.description ?: "the door call failed")
        } catch (e: Exception) {
            throw DoorWriteFailure.Unknown("door-transport", "the door call failed after the request was sent")
        }
        val answers =
            try {
                ArrowTables.decode(streams, WRITE_ANSWER).rows
            } catch (e: Exception) {
                throw DoorWriteFailure.Unknown("invalid-result", "the door answer could not be read")
            }
        if (answers.size != count || answers.withIndex().any { (i, row) -> (row[0] as? Number)?.toInt() != i }) {
            throw DoorWriteFailure.Unknown("invalid-result", "the door answered ${answers.size} rows for $count")
        }
        return WriteResult(
            answers.map { requireNotNull(it[1] as? Number).toLong() },
            answers.map { row ->
                (row[2] as? String)?.let {
                    runCatching { Json.parseToJsonElement(it).jsonObject }.getOrElse {
                        throw DoorWriteFailure.Unknown("invalid-result", "the door answered a prior that is not JSON")
                    }
                }
            },
        )
    }

    private fun failure(message: org.tatrman.common.v1.ResponseMessage): DoorWriteFailure {
        val text = message.humanMessage
        ROW_FAILURE.find(text)?.let {
            return DoorWriteFailure.RowFailed(it.groupValues[1].toInt(), message.code, text)
        }
        if (text.startsWith("row_writes commit")) return DoorWriteFailure.Unknown(message.code, text)
        return DoorWriteFailure.Refused(message.code, text)
    }

    private fun scopeRefusal(island: String): Nothing =
        throw AutomationException(
            ErrorCode.OUT_OF_SCOPE_READ,
            "a governed read exceeds the recipe's declared objects",
            buildJsonObject { put("island", island) },
        )

    private fun parameter(
        name: String,
        binding: BoundValue,
    ): ParameterBinding {
        val tag =
            when (binding.type.lowercase()) {
                "int", "integer", "bigint", "days", "working_days" -> "int"
                "float", "decimal", "numeric", "double" -> "float"
                "bool", "boolean" -> "bool"
                "date", "datetime", "timestamp", "timestamptz" -> "datetime"
                else -> "text"
            }
        val value =
            Value
                .newBuilder()
                .also { v ->
                    val raw = binding.value
                    when {
                        raw == null -> v.isNull = true
                        tag == "int" -> v.intValue = (raw as Number).toLong()
                        tag == "float" -> v.floatValue = (raw as Number).toDouble()
                        tag == "bool" -> v.boolValue = raw as Boolean
                        tag == "datetime" -> v.datetimeValue = raw.toString()
                        else -> v.stringValue = raw.toString()
                    }
                }.build()
        return ParameterBinding
            .newBuilder()
            .setName(name)
            .setType(tag)
            .setValue(value)
            .build()
    }

    private fun refuse(
        code: String,
        message: String,
    ): Nothing =
        throw AutomationException(
            ErrorCode.DOOR_REFUSED,
            message,
            buildJsonObject { put("code", code) },
        )
}

private val ROW_FAILURE = Regex("^row_writes seq (\\d+): ")
private val WRITE_ANSWER =
    listOf(ManifestColumn("seq", "int"), ManifestColumn("rows_affected", "bigint"), ManifestColumn("prior", "text"))

class GrpcQueryRun(
    target: String,
) : QueryRun,
    AutoCloseable {
    private val channel =
        ManagedChannelBuilder
            .forTarget(target.removePrefix("http://").removePrefix("https://"))
            .apply { if (target.startsWith("https://")) useTransportSecurity() else usePlaintext() }
            .build()
    private val stub = QueryServiceGrpcKt.QueryServiceCoroutineStub(channel)

    override fun run(request: RunRequest): Flow<ResultBatch> = stub.run(request)

    override suspend fun compile(request: RunRequest): CompileResponse = stub.compile(request)

    override fun close() {
        channel.shutdownNow()
    }
}
