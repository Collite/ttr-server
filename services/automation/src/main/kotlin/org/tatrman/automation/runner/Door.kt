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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.identity.RunIdentity
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.Value
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
)

data class ReadResult(
    val table: Table,
    val warnings: Set<String> = emptySet(),
)

fun interface Door {
    suspend fun read(read: DoorRead): ReadResult
}

/** Test seam at the gRPC stub boundary, not a second SQL execution road. */
fun interface QueryRun {
    fun run(request: RunRequest): Flow<ResultBatch>
}

/**
 * The governed door. Each `world/storage` must map to an explicit Query world alias (QUERY_WORLDS).
 * Never fall back to Query's default connection: that could read ERP instead of the ledger.
 */
class QueryDoor(
    private val query: QueryRun,
    private val routes: Map<String, String>,
    private val tokens: ServiceTokens,
) : Door {
    override suspend fun read(read: DoorRead): ReadResult {
        try {
            val world =
                routes["${read.world}/${read.storage}"]
                    ?: refuse("storage-unmapped", "no door route for ${read.world}/${read.storage}")
            val roles =
                if (read.identity.credential == null) {
                    read.identity.caller.roles
                } else {
                    // Claims of a token obtained from the configured IdP, never the initiating caller's roles.
                    val decoded = JWT.decode(tokens.token(read.identity.credential))
                    val principal =
                        org.tatrman.automation.identity.Caller
                            .of(decoded.token, decoded)
                            ?: refuse("identity-unmapped", "the run identity's token names nobody")
                    if (principal.principal !=
                        read.identity.principal
                    ) {
                        refuse("identity-mismatch", "the IdP returned another run identity")
                    }
                    principal.roles
                }
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
                    .build()
            val streams = mutableListOf<ByteArray>()
            val warnings = linkedSetOf<String>()
            var rows = 0L
            kotlinx.coroutines.withTimeout(read.timeout.toMillis()) {
                query.run(request).collect { batch ->
                    batch.messagesList.firstOrNull { it.severity == Severity.ERROR }?.let {
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

    override fun close() {
        channel.shutdownNow()
    }
}
