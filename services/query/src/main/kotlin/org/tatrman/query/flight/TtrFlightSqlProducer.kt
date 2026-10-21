// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import com.google.protobuf.Any
import com.google.protobuf.ByteString
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.apache.arrow.flight.BackpressureStrategy
import org.apache.arrow.flight.CloseSessionRequest
import org.apache.arrow.flight.CloseSessionResult
import org.apache.arrow.flight.GetSessionOptionsRequest
import org.apache.arrow.flight.GetSessionOptionsResult
import org.apache.arrow.flight.NoOpSessionOptionValueVisitor
import org.apache.arrow.flight.SessionOptionValueFactory
import org.apache.arrow.flight.SetSessionOptionsRequest
import org.apache.arrow.flight.SetSessionOptionsResult
import org.apache.arrow.flight.CallStatus
import org.apache.arrow.flight.ErrorFlightMetadata
import org.apache.arrow.flight.FlightDescriptor
import org.apache.arrow.flight.FlightEndpoint
import org.apache.arrow.flight.FlightInfo
import org.apache.arrow.flight.FlightProducer.CallContext
import org.apache.arrow.flight.FlightProducer.ServerStreamListener
import org.apache.arrow.flight.FlightProducer.StreamListener
import org.apache.arrow.flight.FlightRuntimeException
import org.apache.arrow.flight.Result
import org.apache.arrow.flight.Ticket
import org.apache.arrow.flight.sql.FlightSqlProducer.Schemas
import org.apache.arrow.flight.sql.NoOpFlightSqlProducer
import org.apache.arrow.flight.sql.SqlInfoBuilder
import org.apache.arrow.flight.sql.impl.FlightSql.ActionClosePreparedStatementRequest
import org.apache.arrow.flight.sql.impl.FlightSql.ActionCreatePreparedStatementRequest
import org.apache.arrow.flight.sql.impl.FlightSql.ActionCreatePreparedStatementResult
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetCatalogs
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetDbSchemas
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetExportedKeys
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetImportedKeys
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetPrimaryKeys
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetSqlInfo
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetTableTypes
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetTables
import org.apache.arrow.flight.sql.impl.FlightSql.CommandPreparedStatementQuery
import org.apache.arrow.flight.sql.impl.FlightSql.CommandStatementQuery
import org.apache.arrow.flight.sql.impl.FlightSql.SqlSupportedCaseSensitivity
import org.apache.arrow.flight.sql.impl.FlightSql.SqlSupportedTransaction
import org.apache.arrow.flight.sql.impl.FlightSql.TicketStatementQuery
import org.apache.arrow.memory.ArrowBuf
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.UInt1Vector
import org.apache.arrow.vector.VarBinaryVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.WriteChannel
import org.apache.arrow.vector.ipc.message.MessageSerializer
import org.apache.arrow.vector.types.pojo.Schema
import org.slf4j.LoggerFactory
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.query.world.Worlds
import org.tatrman.worker.v1.ExecutionReceipt
import org.tatrman.worker.v1.Notice
import org.tatrman.worker.v1.ResultBatch
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * BI (contracts C-1, C-4, C-5, C-8) — the query door as an Arrow Flight SQL server: DBeaver, IntelliJ, pandas (ADBC)
 * and the `tatrman://` Superset dialect read entities, saved logical queries and bound tables through it.
 *
 * Every statement is the door's own `Run` ([StatementRunner]), carrying the caller's verified identity from
 * [sessions] — the edge adds no query path of its own. **Execution is eager:** `GetFlightInfo` and
 * `CreatePreparedStatement` start the statement and read it up to its first data batch, because the Flight SQL
 * JDBC driver prepares even a plain `executeQuery` and a dataset's schema must be real, not guessed (BI-0 memo
 * §4). The started statement waits under its ticket for the `DoGet` that streams it — read once, cancelled
 * [FlightConfig.pendingTtlSeconds] after its `FlightInfo` went out if nobody comes.
 *
 * The stream's closing app metadata (JSON) rides the last data batch: `correlation_id`, `connection_id`,
 * `rows_total`, `top_n_applied` and the door's notices (C-5). An error batch — first or mid-stream — becomes a
 * Flight error `"<code>: <one sentence>"` with the code also in the `x-ttr-code` error metadata.
 *
 * Every statement runs in the connection's world (C-3): `RunRequest.world` is set from it, a statement naming another
 * catalog is refused `world_mismatch` before `Run` ([CatalogQualifier]), and a stream whose receipt names a connection
 * other than the world's is failed `world_mismatch` rather than delivered as the world's rows.
 */
class TtrFlightSqlProducer(
    private val runner: StatementRunner,
    private val catalog: CatalogSource,
    private val sessions: SessionResolver,
    private val allocator: BufferAllocator,
    private val config: FlightConfig,
    private val outcomes: StatementListener = StatementListener { },
    /** C-3/C-9 — the door's world table: which catalogs exist, and the connection each world's receipts must name. */
    private val worlds: Worlds = Worlds.NONE,
    openTelemetry: OpenTelemetry = GlobalOpenTelemetry.get(),
    serverVersion: String = TtrFlightSqlProducer::class.java.`package`?.implementationVersion ?: "dev",
    /** The clock statement ages are read from (tests move it). */
    private val nanoClock: () -> Long = System::nanoTime,
) : NoOpFlightSqlProducer() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reframer = BatchReframer(allocator)
    private val pending = ConcurrentHashMap<String, Execution>()
    private val prepared = ConcurrentHashMap<String, Prepared>()
    private val statements = AtomicLong(0)
    private val telemetry = EdgeTelemetry(openTelemetry)
    private val sweeper: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "flight-edge-sweeper").apply { isDaemon = true } }

    private val sqlInfo =
        SqlInfoBuilder()
            .withFlightSqlServerName(SERVER_NAME)
            .withFlightSqlServerVersion(serverVersion)
            .withFlightSqlServerArrowVersion(ARROW_VERSION)
            .withFlightSqlServerReadOnly(true)
            .withFlightSqlServerSql(true)
            .withFlightSqlServerSubstrait(false)
            .withFlightSqlServerTransaction(SqlSupportedTransaction.SQL_SUPPORTED_TRANSACTION_NONE)
            .withFlightSqlServerCancel(false)
            .withSqlTransactionsSupported(false)
            .withSqlDdlCatalog(false)
            .withSqlDdlSchema(false)
            .withSqlDdlTable(false)
            .withSqlIdentifierQuoteChar("\"")
            .withSqlIdentifierCase(SqlSupportedCaseSensitivity.SQL_CASE_SENSITIVITY_CASE_INSENSITIVE)
            .withSqlQuotedIdentifierCase(SqlSupportedCaseSensitivity.SQL_CASE_SENSITIVITY_CASE_INSENSITIVE)
            .withSqlKeywords(TRANSLATOR_RESERVED_WORDS.toTypedArray())

    init {
        val every = config.pendingTtlSeconds.coerceIn(1, SWEEP_SECONDS)
        sweeper.scheduleWithFixedDelay(::sweep, every, every, TimeUnit.SECONDS)
    }

    /** Statements started since boot (`/status`). */
    val statementCount: Long get() = statements.get()

    // ── statements ────────────────────────────────────────────────────────────────────────────────────────────

    override fun getFlightInfoStatement(
        command: CommandStatementQuery,
        context: CallContext,
        descriptor: FlightDescriptor,
    ): FlightInfo {
        val execution = begin(command.query, sessionOf(context))
        return flightInfo(execution, descriptor)
    }

    override fun getStreamStatement(
        ticket: TicketStatementQuery,
        context: CallContext,
        listener: ServerStreamListener,
    ) {
        val session = sessionOf(context)
        val id = ticket.statementHandle.toStringUtf8()
        val execution =
            pending.remove(id)
                ?: throw CallStatus.NOT_FOUND
                    .withDescription("statement not found: it was already read, or expired unread")
                    .toRuntimeException()
        if (execution.session.userId != session.userId) {
            execution.abandon(StatementOutcome.Status.CANCELLED, "ticket_owner_mismatch")
            throw CallStatus.UNAUTHORIZED
                .withDescription(
                    "this statement belongs to another caller",
                ).toRuntimeException()
        }
        stream(execution, listener)
    }

    override fun createPreparedStatement(
        request: ActionCreatePreparedStatementRequest,
        context: CallContext,
        listener: StreamListener<Result>,
    ) {
        try {
            val session = sessionOf(context)
            val execution = begin(request.query, session)
            val handle = UUID.randomUUID().toString()
            prepared[handle] =
                Prepared(request.query, session.userId, execution.schema, AtomicReference(execution), nanoClock())
            val result =
                ActionCreatePreparedStatementResult
                    .newBuilder()
                    .setPreparedStatementHandle(ByteString.copyFromUtf8(handle))
                    .setDatasetSchema(ByteString.copyFrom(serializeSchema(execution.schema)))
                    .build()
            listener.onNext(Result(Any.pack(result).toByteArray()))
            listener.onCompleted()
        } catch (e: FlightRuntimeException) {
            listener.onError(e)
        }
    }

    override fun getFlightInfoPreparedStatement(
        command: CommandPreparedStatementQuery,
        context: CallContext,
        descriptor: FlightDescriptor,
    ): FlightInfo {
        val session = sessionOf(context)
        val statement = preparedFor(command.preparedStatementHandle, session)
        // The execution `prepare` started is the first one run; every later execute starts afresh.
        val execution =
            statement.pending.getAndSet(null)?.also { it.readyNanos = nanoClock() } ?: begin(statement.sql, session)
        return flightInfo(execution, descriptor)
    }

    override fun getStreamPreparedStatement(
        command: CommandPreparedStatementQuery,
        context: CallContext,
        listener: ServerStreamListener,
    ) {
        val session = sessionOf(context)
        val statement = preparedFor(command.preparedStatementHandle, session)
        stream(statement.pending.getAndSet(null) ?: begin(statement.sql, session), listener)
    }

    override fun closePreparedStatement(
        request: ActionClosePreparedStatementRequest,
        context: CallContext,
        listener: StreamListener<Result>,
    ) {
        prepared.remove(request.preparedStatementHandle.toStringUtf8())?.let { statement ->
            statement.pending.getAndSet(null)?.abandon(StatementOutcome.Status.CANCELLED, "closed_unread")
        }
        listener.onCompleted()
    }

    // ── session options: the JDBC `catalog` property (C-3) ───────────────────────────────────────────────────

    /**
     * The Flight SQL JDBC driver sends its `catalog` property as `SetSessionOptions(catalog)` when it connects: the
     * world the connection keeps, as the `ttr_world` cookie ([IdentityWorldMiddleware]). A world this door does not
     * serve is refused with its reason, which the driver shows; any other option is not one this edge has.
     */
    override fun setSessionOptions(
        request: SetSessionOptionsRequest,
        context: CallContext,
        listener: StreamListener<SetSessionOptionsResult>,
    ) {
        try {
            sessionOf(context)
            val middleware = context.getMiddleware(IdentityWorldMiddleware.KEY)
            val errors = mutableMapOf<String, SetSessionOptionsResult.Error>()
            request.sessionOptions.forEach { (name, value) ->
                if (name != CATALOG_OPTION) {
                    errors[name] = SetSessionOptionsResult.Error(SetSessionOptionsResult.ErrorValue.INVALID_NAME)
                    return@forEach
                }
                val world = value.acceptVisitor(STRING_OPTION)
                val refusal =
                    when {
                        world == null -> "$INVALID_OPTION: the catalog option is a world's name, a string."
                        middleware == null -> "$INVALID_OPTION: this edge keeps no session options."
                        else -> middleware.keepWorld(world)
                    }
                if (refusal != null) throw flightError(refusal)
            }
            listener.onNext(SetSessionOptionsResult(errors))
            listener.onCompleted()
        } catch (e: FlightRuntimeException) {
            listener.onError(e)
        }
    }

    override fun getSessionOptions(
        request: GetSessionOptionsRequest,
        context: CallContext,
        listener: StreamListener<GetSessionOptionsResult>,
    ) {
        try {
            val world = sessionOf(context).world
            val options =
                if (world.isEmpty()) {
                    emptyMap()
                } else {
                    mapOf(
                        CATALOG_OPTION to SessionOptionValueFactory.makeSessionOptionValue(world),
                    )
                }
            listener.onNext(GetSessionOptionsResult(options))
            listener.onCompleted()
        } catch (e: FlightRuntimeException) {
            listener.onError(e)
        }
    }

    /** Closing the session forgets the kept world (the cookie is cleared); the edge holds nothing else. */
    override fun closeSession(
        request: CloseSessionRequest,
        context: CallContext,
        listener: StreamListener<CloseSessionResult>,
    ) {
        try {
            sessionOf(context)
            context.getMiddleware(IdentityWorldMiddleware.KEY)?.keepWorld("")
            listener.onNext(CloseSessionResult(CloseSessionResult.Status.CLOSED))
            listener.onCompleted()
        } catch (e: FlightRuntimeException) {
            listener.onError(e)
        }
    }

    // ── catalog (C-4) ─────────────────────────────────────────────────────────────────────────────────────────

    override fun getFlightInfoSqlInfo(
        request: CommandGetSqlInfo,
        context: CallContext,
        descriptor: FlightDescriptor,
    ): FlightInfo = commandInfo(Schemas.GET_SQL_INFO_SCHEMA, request, descriptor)

    override fun getStreamSqlInfo(
        command: CommandGetSqlInfo,
        context: CallContext,
        listener: ServerStreamListener,
    ) {
        sqlInfo.send(command.infoList, listener)
    }

    override fun getFlightInfoCatalogs(
        request: CommandGetCatalogs,
        context: CallContext,
        descriptor: FlightDescriptor,
    ): FlightInfo = commandInfo(Schemas.GET_CATALOGS_SCHEMA, request, descriptor)

    override fun getStreamCatalogs(
        context: CallContext,
        listener: ServerStreamListener,
    ) {
        val session = sessionOf(context)
        sendRows(Schemas.GET_CATALOGS_SCHEMA, CatalogSource.catalogsOf(session), listener) { root, i, catalogName ->
            (root.getVector("catalog_name") as VarCharVector).setSafe(i, catalogName.toByteArray())
        }
    }

    override fun getFlightInfoSchemas(
        request: CommandGetDbSchemas,
        context: CallContext,
        descriptor: FlightDescriptor,
    ): FlightInfo = commandInfo(Schemas.GET_SCHEMAS_SCHEMA, request, descriptor)

    override fun getStreamSchemas(
        command: CommandGetDbSchemas,
        context: CallContext,
        listener: ServerStreamListener,
    ) {
        val session = sessionOf(context)
        val schemas =
            catalogCall(listener) {
                catalog.schemas(
                    session = session,
                    catalog = if (command.hasCatalog()) command.catalog else null,
                    schemaPattern = if (command.hasDbSchemaFilterPattern()) command.dbSchemaFilterPattern else null,
                )
            } ?: return
        sendRows(Schemas.GET_SCHEMAS_SCHEMA, schemas, listener) { root, i, schema ->
            (root.getVector("catalog_name") as VarCharVector).setNullableUtf8(i, CatalogSource.catalogOf(session))
            (root.getVector("db_schema_name") as VarCharVector).setSafe(i, schema.toByteArray())
        }
    }

    override fun getFlightInfoTables(
        request: CommandGetTables,
        context: CallContext,
        descriptor: FlightDescriptor,
    ): FlightInfo =
        commandInfo(
            if (request.includeSchema) Schemas.GET_TABLES_SCHEMA else Schemas.GET_TABLES_SCHEMA_NO_SCHEMA,
            request,
            descriptor,
        )

    override fun getStreamTables(
        command: CommandGetTables,
        context: CallContext,
        listener: ServerStreamListener,
    ) {
        val session = sessionOf(context)
        val tables =
            catalogCall(listener) {
                catalog.tables(
                    session = session,
                    catalog = if (command.hasCatalog()) command.catalog else null,
                    schemaPattern = if (command.hasDbSchemaFilterPattern()) command.dbSchemaFilterPattern else null,
                    tablePattern = if (command.hasTableNameFilterPattern()) command.tableNameFilterPattern else null,
                    tableTypes = command.tableTypesList,
                    withFields = command.includeSchema,
                )
            } ?: return
        val schema = if (command.includeSchema) Schemas.GET_TABLES_SCHEMA else Schemas.GET_TABLES_SCHEMA_NO_SCHEMA
        sendRows(schema, tables, listener) { root, i, table ->
            (root.getVector("catalog_name") as VarCharVector).setNullableUtf8(i, CatalogSource.catalogOf(session))
            (root.getVector("db_schema_name") as VarCharVector).setSafe(i, table.schema.toByteArray())
            (root.getVector("table_name") as VarCharVector).setSafe(i, table.name.toByteArray())
            (root.getVector("table_type") as VarCharVector).setSafe(i, table.type.label.toByteArray())
            if (command.includeSchema) {
                (root.getVector("table_schema") as VarBinaryVector).setSafe(i, serializeSchema(table.arrowSchema))
            }
        }
    }

    override fun getFlightInfoTableTypes(
        request: CommandGetTableTypes,
        context: CallContext,
        descriptor: FlightDescriptor,
    ): FlightInfo = commandInfo(Schemas.GET_TABLE_TYPES_SCHEMA, request, descriptor)

    override fun getStreamTableTypes(
        context: CallContext,
        listener: ServerStreamListener,
    ) {
        sessionOf(context)
        sendRows(Schemas.GET_TABLE_TYPES_SCHEMA, CatalogTable.Type.entries, listener) { root, i, type ->
            (root.getVector("table_type") as VarCharVector).setSafe(i, type.label.toByteArray())
        }
    }

    override fun getFlightInfoPrimaryKeys(
        request: CommandGetPrimaryKeys,
        context: CallContext,
        descriptor: FlightDescriptor,
    ): FlightInfo = commandInfo(Schemas.GET_PRIMARY_KEYS_SCHEMA, request, descriptor)

    override fun getStreamPrimaryKeys(
        command: CommandGetPrimaryKeys,
        context: CallContext,
        listener: ServerStreamListener,
    ) {
        val session = sessionOf(context)
        val keys =
            catalogCall(listener) {
                catalog.primaryKeys(
                    session,
                    if (command.hasCatalog()) command.catalog else null,
                    if (command.hasDbSchema()) command.dbSchema else null,
                    command.table,
                )
            } ?: return
        sendRows(Schemas.GET_PRIMARY_KEYS_SCHEMA, keys, listener) { root, i, key ->
            (root.getVector("catalog_name") as VarCharVector).setNullableUtf8(i, CatalogSource.catalogOf(session))
            (root.getVector("db_schema_name") as VarCharVector).setSafe(i, key.schema.toByteArray())
            (root.getVector("table_name") as VarCharVector).setSafe(i, key.table.toByteArray())
            (root.getVector("column_name") as VarCharVector).setSafe(i, key.column.toByteArray())
            (root.getVector("key_name") as VarCharVector).setNull(i)
            (root.getVector("key_sequence") as IntVector).setSafe(i, key.keySequence)
        }
    }

    override fun getFlightInfoImportedKeys(
        request: CommandGetImportedKeys,
        context: CallContext,
        descriptor: FlightDescriptor,
    ): FlightInfo = commandInfo(Schemas.GET_IMPORTED_KEYS_SCHEMA, request, descriptor)

    override fun getStreamImportedKeys(
        command: CommandGetImportedKeys,
        context: CallContext,
        listener: ServerStreamListener,
    ) {
        val session = sessionOf(context)
        val keys =
            catalogCall(listener) {
                catalog.foreignKeys(session).filter {
                    it.fkTable == command.table && (!command.hasDbSchema() || it.fkSchema == command.dbSchema)
                }
            } ?: return
        sendForeignKeys(Schemas.GET_IMPORTED_KEYS_SCHEMA, keys, session, listener)
    }

    override fun getFlightInfoExportedKeys(
        request: CommandGetExportedKeys,
        context: CallContext,
        descriptor: FlightDescriptor,
    ): FlightInfo = commandInfo(Schemas.GET_EXPORTED_KEYS_SCHEMA, request, descriptor)

    override fun getStreamExportedKeys(
        command: CommandGetExportedKeys,
        context: CallContext,
        listener: ServerStreamListener,
    ) {
        val session = sessionOf(context)
        val keys =
            catalogCall(listener) {
                catalog.foreignKeys(session).filter {
                    it.pkTable == command.table && (!command.hasDbSchema() || it.pkSchema == command.dbSchema)
                }
            } ?: return
        sendForeignKeys(Schemas.GET_EXPORTED_KEYS_SCHEMA, keys, session, listener)
    }

    // ── execution ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Starts [sql] as [session] and reads it up to its first data batch, so the result's schema is known.
     * A statement the door refuses or fails is a Flight error here, before any ticket exists.
     */
    private fun begin(
        sql: String,
        session: EdgeSession,
    ): Execution {
        statements.incrementAndGet()
        val correlationId = UUID.randomUUID().toString()
        val span = telemetry.startStatement(session)
        val startedNanos = nanoClock()
        val runSql =
            when (val qualified = CatalogQualifier.resolve(sql, session.world)) {
                is CatalogQualifier.Outcome.Run -> qualified.sql
                is CatalogQualifier.Outcome.Mismatch -> {
                    val refusal = "$WORLD_MISMATCH: ${catalogMismatch(qualified.catalog, session.world)}"
                    finish(outcomeOf(correlationId, session, sql, startedNanos, refusal = refusal), span)
                    throw flightError(refusal)
                }
            }
        val batches = Channel<ResultBatch>(BUFFERED_BATCHES)
        val job =
            scope.launch {
                try {
                    runner.run(edgeRunRequest(runSql, session, correlationId)).collect { batches.send(it) }
                    batches.close()
                } catch (e: CancellationException) {
                    batches.close(e)
                    throw e
                } catch (t: Throwable) {
                    batches.close(t)
                }
            }
        val head = mutableListOf<ResultBatch>()
        val schema =
            try {
                runBlocking {
                    var found: Schema? = null
                    for (batch in batches) {
                        head += batch
                        batch.refusal()?.let { throw it }
                        found = reframer.schemaOf(batch.arrowIpc.toByteArray())
                        if (found != null || batch.isLast) break
                    }
                    // Every worker names an empty answer's columns (C-5); an answer with no schema at all is a door
                    // that sent none (an empty plan), and it reads as no columns.
                    found ?: Schema(emptyList()).also {
                        log.warn(
                            "Flight edge: statement {} answered without a schema; listed as no columns",
                            correlationId,
                        )
                    }
                }
            } catch (e: RefusedException) {
                job.cancel()
                val outcome = outcomeOf(correlationId, session, sql, startedNanos, refusal = e.message)
                finish(outcome, span)
                throw flightError(e.message, e.line, e.column)
            } catch (t: Throwable) {
                job.cancel()
                val outcome =
                    outcomeOf(
                        correlationId,
                        session,
                        sql,
                        startedNanos,
                        status = StatementOutcome.Status.FAILED,
                        errorCode = DOOR_UNREACHABLE,
                    )
                finish(outcome, span)
                throw CallStatus.UNAVAILABLE
                    .withDescription("$DOOR_UNREACHABLE: the query door did not answer (${t.message})")
                    .withCause(t)
                    .toRuntimeException()
            }
        return Execution(
            id = UUID.randomUUID().toString(),
            correlationId = correlationId,
            session = session,
            sql = sql,
            schema = schema,
            head = head,
            rest = batches,
            job = job,
            span = span,
            startedNanos = startedNanos,
        ).also {
            // The ticket's wait for its DoGet starts now, however long the first batch took.
            it.readyNanos = nanoClock()
            pending[it.id] = it
        }
    }

    private fun flightInfo(
        execution: Execution,
        descriptor: FlightDescriptor,
    ): FlightInfo {
        val ticket =
            TicketStatementQuery
                .newBuilder()
                .setStatementHandle(ByteString.copyFromUtf8(execution.id))
                .build()
        return FlightInfo(
            execution.schema,
            descriptor,
            listOf(FlightEndpoint(Ticket(Any.pack(ticket).toByteArray()))),
            -1,
            -1,
        )
    }

    /**
     * Streams [execution] to [out]: the batches read at `begin`, then the rest as the door sends them. Each worker
     * payload is held until the next one arrives, so the last one — and only it — carries the closing metadata.
     */
    private fun stream(
        execution: Execution,
        out: ServerStreamListener,
    ) {
        pending.remove(execution.id)
        val backpressure =
            object : BackpressureStrategy.CallbackBackpressureStrategy() {
                override fun cancelCallback() {
                    super.cancelCallback()
                    execution.job.cancel()
                }
            }
        backpressure.register(out)
        var rows = 0L
        var receipt: ExecutionReceipt? = null
        val notices = linkedMapOf<Pair<String, String>, ResponseMessage>()
        val typedNotices = mutableListOf<Notice>()
        VectorSchemaRoot.create(execution.schema, allocator).use { root ->
            try {
                out.start(root)

                fun send(
                    ipc: ByteArray,
                    closing: ByteArray?,
                ) {
                    rows +=
                        reframer.reframe(ipc, root) { lastInPayload ->
                            awaitReady(backpressure, execution)
                            if (closing != null && lastInPayload) out.putNext(metadataBuf(closing)) else out.putNext()
                        }
                }
                // The payload held back, and its rows (the worker's own count: it is decoded once, when sent).
                var held: Pair<ByteArray, Long>? = null
                runBlocking {
                    suspend fun consume(batch: ResultBatch): Boolean {
                        batch.refusal()?.let { throw it }
                        if (batch.hasReceipt()) receipt = batch.receipt
                        // C-5 — every batch's warnings: a worker puts one on the batch it concerns (a row it dropped),
                        // the door its own on the first. Typed notices come with the last batch.
                        noticesOf(batch).forEach { notices.putIfAbsent(it.code to it.humanMessage, it) }
                        if (batch.isLast) typedNotices += batch.noticesList
                        val ipc = batch.arrowIpc.toByteArray()
                        if (ipc.isNotEmpty()) {
                            held?.let { (payload, _) -> send(payload, null) }
                            held = ipc to batch.batchRowCount
                        }
                        return batch.isLast
                    }
                    var done = false
                    for (batch in execution.head) if (!done) done = consume(batch)
                    if (!done) {
                        for (batch in execution.rest) if (consume(batch)) break
                    }
                }
                receiptGuard(execution.session, receipt)?.let { throw RefusedException(it) }
                val said = notices.values.toList()
                val closing = closingMetadata(execution, receipt, said, typedNotices, rows + (held?.second ?: 0L))
                val last = held
                if (last != null) {
                    send(last.first, closing)
                } else {
                    root.rowCount = 0
                    out.putNext(metadataBuf(closing))
                }
                out.completed()
                finish(
                    outcomeOf(
                        execution.correlationId,
                        execution.session,
                        execution.sql,
                        execution.startedNanos,
                        rows = rows,
                        receipt = receipt,
                        notices = said,
                    ),
                    execution.span,
                )
            } catch (e: RefusedException) {
                // An ERROR batch after the stream started: the run failed under way (a refusal comes before any row,
                // and `begin` has already turned those into errors).
                execution.job.cancel()
                finish(
                    outcomeOf(
                        execution.correlationId,
                        execution.session,
                        execution.sql,
                        execution.startedNanos,
                        status = StatementOutcome.Status.FAILED,
                        errorCode = codeOf(e.message),
                        rows = rows,
                        receipt = receipt,
                    ),
                    execution.span,
                )
                out.error(flightError(e.message, e.line, e.column))
            } catch (e: ClientGoneException) {
                execution.job.cancel()
                finish(
                    outcomeOf(
                        execution.correlationId,
                        execution.session,
                        execution.sql,
                        execution.startedNanos,
                        status = StatementOutcome.Status.CANCELLED,
                        errorCode = if (e.stalled) CLIENT_STALLED else "client_cancelled",
                        rows = rows,
                    ),
                    execution.span,
                )
                // A cancelled call is over already; a stalled one is ended here, so every DoGet ends with a status.
                if (e.stalled) {
                    out.error(
                        CallStatus.TIMED_OUT
                            .withDescription(
                                "$CLIENT_STALLED: the client read nothing for ${config.readyWaitMillis / 1000} s, " +
                                    "so the statement was stopped.",
                            ).toRuntimeException(),
                    )
                }
            } catch (t: Throwable) {
                execution.job.cancel()
                log.warn("Flight edge: statement {} failed mid-stream: {}", execution.correlationId, t.message, t)
                val code = if (t is SchemaChangedException) "schema_changed" else "stream_failed"
                finish(
                    outcomeOf(
                        execution.correlationId,
                        execution.session,
                        execution.sql,
                        execution.startedNanos,
                        status = StatementOutcome.Status.FAILED,
                        errorCode = code,
                        rows = rows,
                    ),
                    execution.span,
                )
                out.error(
                    CallStatus.INTERNAL.withDescription("$code: ${oneSentence(t.message ?: "")}").toRuntimeException(),
                )
            }
        }
    }

    private fun awaitReady(
        backpressure: BackpressureStrategy,
        execution: Execution,
    ) {
        when (backpressure.waitForListener(config.readyWaitMillis)) {
            BackpressureStrategy.WaitResult.READY -> Unit
            BackpressureStrategy.WaitResult.CANCELLED -> throw ClientGoneException(stalled = false)
            else -> {
                log.warn(
                    "Flight edge: statement {} — client not reading for {} ms",
                    execution.correlationId,
                    config.readyWaitMillis,
                )
                throw ClientGoneException(stalled = true)
            }
        }
    }

    /**
     * C-3 — the receipt's connection against the connection's world: the door names where it ran, and rows from
     * another connection are not this world's. Null when they agree, or when there is nothing to compare.
     */
    private fun receiptGuard(
        session: EdgeSession,
        receipt: ExecutionReceipt?,
    ): String? {
        if (session.world.isEmpty()) return null
        val expected = worlds.connectionOf(session.world) ?: return null
        val ran = receipt?.connectionId.orEmpty()
        if (ran.isEmpty() || ran == expected) return null
        return "$WORLD_MISMATCH: the door ran this on connection '$ran', not on world " +
            "'${session.world}' ('$expected')."
    }

    /**
     * C-5 — the app metadata on the last batch. A notice the door also sent typed (C-10: `top_n_applied`) carries its
     * numbers, `requested` (0 = unstated) and `applied` (the cap).
     */
    private fun closingMetadata(
        execution: Execution,
        receipt: ExecutionReceipt?,
        notices: List<ResponseMessage>,
        typedNotices: List<Notice>,
        rows: Long,
    ): ByteArray =
        buildJsonObject {
            put("correlation_id", execution.correlationId)
            put("connection_id", receipt?.connectionId.orEmpty())
            put("rows_total", rows)
            put("top_n_applied", notices.any { it.code == StatementOutcome.TOP_N_APPLIED })
            putJsonArray("notices") {
                notices.forEach { notice ->
                    addJsonObject {
                        put("code", notice.code)
                        put("severity", notice.severity.name)
                        put("message", notice.humanMessage)
                        typedNotices.firstOrNull { it.code == notice.code }?.let { typed ->
                            put("requested", typed.requested)
                            put("applied", typed.applied)
                        }
                    }
                }
            }
        }.toString().toByteArray()

    private fun metadataBuf(bytes: ByteArray): ArrowBuf =
        allocator.buffer(bytes.size.toLong()).also {
            it.writeBytes(bytes)
        }

    private fun outcomeOf(
        correlationId: String,
        session: EdgeSession,
        sql: String,
        startedNanos: Long,
        status: StatementOutcome.Status = StatementOutcome.Status.OK,
        errorCode: String = "",
        refusal: String? = null,
        rows: Long = 0,
        receipt: ExecutionReceipt? = null,
        notices: List<ResponseMessage> = emptyList(),
    ): StatementOutcome =
        StatementOutcome(
            correlationId = correlationId,
            session = session,
            statement = sql,
            status = if (refusal != null) StatementOutcome.Status.REFUSED else status,
            errorCode = refusal?.let { codeOf(it) } ?: errorCode,
            rowsStreamed = rows,
            receipt = receipt,
            notices = notices,
            durationMs = (nanoClock() - startedNanos) / 1_000_000,
        )

    private fun finish(
        outcome: StatementOutcome,
        span: Span,
    ) {
        telemetry.endStatement(span, outcome)
        runCatching { outcomes.onStatementDone(outcome) }
            .onFailure {
                log.warn(
                    "Flight edge: statement listener failed for {}: {}",
                    outcome.correlationId,
                    it.message,
                )
            }
    }

    /**
     * A started statement nobody fetched within the TTL of its `FlightInfo` is cancelled, and reported `expired`.
     * The TTL counts from when the ticket went out, not from the statement's start: a slow first batch is not the
     * client's wait.
     */
    internal fun sweep() {
        val now = nanoClock()
        val cutoff = now - TimeUnit.SECONDS.toNanos(config.pendingTtlSeconds)
        pending.values.filter { it.readyNanos < cutoff }.forEach { execution ->
            if (pending.remove(execution.id, execution)) {
                execution.abandon(StatementOutcome.Status.EXPIRED, "expired_unread")
                prepared.values.forEach { it.pending.compareAndSet(execution, null) }
            }
        }
        val idleCutoff = now - TimeUnit.SECONDS.toNanos(PREPARED_IDLE_SECONDS)
        prepared.entries.removeIf { it.value.lastUsedNanos < idleCutoff }
    }

    private fun Execution.abandon(
        status: StatementOutcome.Status,
        code: String,
    ) {
        pending.remove(id)
        job.cancel()
        finish(outcomeOf(correlationId, session, sql, startedNanos, status = status, errorCode = code), span)
    }

    override fun close() {
        sweeper.shutdownNow()
        pending.values.toList().forEach { it.abandon(StatementOutcome.Status.CANCELLED, "edge_stopped") }
        scope.cancel()
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────

    private fun sessionOf(context: CallContext): EdgeSession =
        sessions.sessionOf(context)
            ?: throw CallStatus.UNAUTHENTICATED.withDescription("no verified caller on this call").toRuntimeException()

    private fun preparedFor(
        handle: ByteString,
        session: EdgeSession,
    ): Prepared {
        val statement =
            prepared[handle.toStringUtf8()]
                ?: throw CallStatus.NOT_FOUND
                    .withDescription(
                        "prepared statement not found (closed or expired)",
                    ).toRuntimeException()
        if (statement.userId != session.userId) {
            throw CallStatus.UNAUTHORIZED
                .withDescription(
                    "this prepared statement belongs to another caller",
                ).toRuntimeException()
        }
        statement.lastUsedNanos = nanoClock()
        return statement
    }

    private fun commandInfo(
        schema: Schema,
        command: com.google.protobuf.Message,
        descriptor: FlightDescriptor,
    ): FlightInfo =
        FlightInfo(schema, descriptor, listOf(FlightEndpoint(Ticket(Any.pack(command).toByteArray()))), -1, -1)

    /** A catalog read, or null after failing [listener] with why Veles could not answer. */
    private fun <T> catalogCall(
        listener: ServerStreamListener,
        read: suspend () -> T,
    ): T? =
        try {
            runBlocking { read() }
        } catch (e: FlightRuntimeException) {
            listener.error(e)
            null
        } catch (t: Throwable) {
            log.warn("Flight edge: catalog read failed: {}", t.message, t)
            listener.error(
                CallStatus.UNAVAILABLE
                    .withDescription("catalog_unavailable: the metadata service did not answer (${t.message})")
                    .toRuntimeException(),
            )
            null
        }

    private fun <T> sendRows(
        schema: Schema,
        rows: List<T>,
        listener: ServerStreamListener,
        fill: (VectorSchemaRoot, Int, T) -> Unit,
    ) {
        VectorSchemaRoot.create(schema, allocator).use { root ->
            root.allocateNew()
            rows.forEachIndexed { i, row -> fill(root, i, row) }
            root.rowCount = rows.size
            listener.start(root)
            listener.putNext()
            listener.completed()
        }
    }

    private fun sendForeignKeys(
        schema: Schema,
        keys: List<CatalogForeignKey>,
        session: EdgeSession,
        listener: ServerStreamListener,
    ) {
        sendRows(schema, keys, listener) { root, i, key ->
            val catalogName = CatalogSource.catalogOf(session)
            (root.getVector("pk_catalog_name") as VarCharVector).setNullableUtf8(i, catalogName)
            (root.getVector("pk_db_schema_name") as VarCharVector).setSafe(i, key.pkSchema.toByteArray())
            (root.getVector("pk_table_name") as VarCharVector).setSafe(i, key.pkTable.toByteArray())
            (root.getVector("pk_column_name") as VarCharVector).setSafe(i, key.pkColumn.toByteArray())
            (root.getVector("fk_catalog_name") as VarCharVector).setNullableUtf8(i, catalogName)
            (root.getVector("fk_db_schema_name") as VarCharVector).setSafe(i, key.fkSchema.toByteArray())
            (root.getVector("fk_table_name") as VarCharVector).setSafe(i, key.fkTable.toByteArray())
            (root.getVector("fk_column_name") as VarCharVector).setSafe(i, key.fkColumn.toByteArray())
            (root.getVector("key_sequence") as IntVector).setSafe(i, key.keySequence)
            (root.getVector("fk_key_name") as VarCharVector).setNull(i)
            (root.getVector("pk_key_name") as VarCharVector).setNull(i)
            (root.getVector("update_rule") as UInt1Vector).setSafe(i, NO_ACTION)
            (root.getVector("delete_rule") as UInt1Vector).setSafe(i, NO_ACTION)
        }
    }

    private fun VarCharVector.setNullableUtf8(
        i: Int,
        value: String?,
    ) {
        if (value == null) setNull(i) else setSafe(i, value.toByteArray())
    }

    private class Execution(
        val id: String,
        val correlationId: String,
        val session: EdgeSession,
        val sql: String,
        val schema: Schema,
        val head: List<ResultBatch>,
        val rest: ReceiveChannel<ResultBatch>,
        val job: Job,
        val span: Span,
        val startedNanos: Long,
    ) {
        /** When its ticket went out (its `FlightInfo`, or a prepared statement's execute): the TTL counts from here. */
        @Volatile var readyNanos: Long = startedNanos
    }

    private class Prepared(
        val sql: String,
        val userId: String,
        val schema: Schema,
        val pending: AtomicReference<Execution?>,
        @Volatile var lastUsedNanos: Long,
    )

    /**
     * An ERROR the door sent in-band (its error batch), as `"<code>: <message>"`, with the statement position it
     * points at when it names one (translate's refusals, C-11; 0 = none).
     */
    private class RefusedException(
        override val message: String,
        val line: Int = 0,
        val column: Int = 0,
    ) : RuntimeException(message)

    /** The client cancelled, or ([stalled]) stopped reading for [FlightConfig.readyWaitMillis]. */
    private class ClientGoneException(
        val stalled: Boolean,
    ) : RuntimeException()

    companion object {
        private val log = LoggerFactory.getLogger(TtrFlightSqlProducer::class.java)

        const val SERVER_NAME = "Tatrman"
        const val ARROW_VERSION = "18.3.0"

        /** The edge's own code when the door itself cannot be reached over the loopback. */
        const val DOOR_UNREACHABLE = "door_unavailable"

        /** Error metadata key carrying the door's code beside the description. */
        const val CODE_METADATA_KEY = "x-ttr-code"

        /** C-11 — error metadata keys for the position in the statement a refusal points at (1-based). */
        const val LINE_METADATA_KEY = "x-ttr-line"
        const val COLUMN_METADATA_KEY = "x-ttr-column"

        /** C-3 — a statement or a stream that is not the connection's world. */
        const val WORLD_MISMATCH = "world_mismatch"

        /** The session option the JDBC driver's `catalog` property becomes. */
        const val CATALOG_OPTION = "catalog"

        private const val INVALID_OPTION = "invalid_option"

        private val STRING_OPTION =
            object : NoOpSessionOptionValueVisitor<String?>() {
                override fun visit(value: String): String = value

                override fun visit(value: Void?): String = ""
            }

        internal fun catalogMismatch(
            catalog: String,
            world: String,
        ): String =
            if (world.isEmpty()) {
                "the statement reads catalog '$catalog', but this connection has no world (set world or catalog)."
            } else {
                "the statement reads catalog '$catalog', but this connection's world is '$world'."
            }

        private const val BUFFERED_BATCHES = 4
        private const val SWEEP_SECONDS = 10L
        private const val PREPARED_IDLE_SECONDS = 3600L

        /** A `DoGet` whose client stopped reading: ended `TIMED_OUT`, recorded with this code. */
        const val CLIENT_STALLED = "client_stalled"

        /**
         * The door's bookkeeping a BI client has no use for (compile time, the plan cache, how dispatch routed,
         * that the schema was autodetected — the edge never states one): left out of the closing metadata's notices.
         */
        private val QUIET_CODES =
            setOf(
                "compile_duration_ms",
                "cache_hit",
                "cache_miss",
                "schema_autodetected",
                "routing_decision",
                "sticky_session_match",
                "connection_id_overrides_derived",
            )

        /** A batch's non-ERROR messages and its context's warnings, as notices; the door's bookkeeping left out. */
        private fun noticesOf(batch: ResultBatch): List<ResponseMessage> =
            (
                batch.messagesList.filter { it.severity != Severity.ERROR } +
                    batch.context.warningsList.map {
                        ResponseMessage
                            .newBuilder()
                            .setSeverity(Severity.WARNING)
                            .setCode(it.code)
                            .setHumanMessage(it.message)
                            .build()
                    }
            ).filter { it.code !in QUIET_CODES }

        /** Flight SQL's `UpdateDeleteRules.NO_ACTION`. */
        private const val NO_ACTION = 3

        /**
         * Words the translator (Apache Calcite's SQL parser) reserves: an identifier spelled like one must be
         * quoted (`"count"`). Clients read the list from `GetSqlInfo(SQL_KEYWORDS)` to decide what to quote.
         */
        val TRANSLATOR_RESERVED_WORDS: List<String> =
            """
                abs all allocate allow alter and any are array as asensitive asymmetric at atomic authorization
                avg begin between bigint binary bit blob boolean both by call called cardinality cascaded case
                cast ceil ceiling char character check clob close coalesce collate collect column commit
                condition connect constraint convert corr corresponding count covar_pop covar_samp create cross
                cube cume_dist current current_date current_time current_timestamp current_user cursor cycle
                date day deallocate dec decimal declare default delete dense_rank deref describe deterministic
                disallow disconnect distinct double drop dynamic each element else end escape every except exec
                execute exists exp explain external extract false fetch filter first_value float floor for
                foreign free from full function fusion get global grant group grouping having hold hour identity
                import in indicator inner inout insensitive insert int integer intersect intersection interval
                into is join lag language large last_value lateral lead leading left like limit ln local
                localtime localtimestamp lower match max member merge method min minute mod modifies module
                month multiset national natural nchar nclob new next no none normalize not null nullif numeric
                octet_length of offset old on only open or order out outer over overlaps overlay parameter
                partition percent_rank percentile_cont percentile_disc position power precision prepare primary
                procedure range rank reads real recursive ref references referencing release reset result return
                returns revoke right rollback rollup row row_number rows savepoint scope scroll search second
                select sensitive session_user set similar smallint some specific specifictype sql sqlexception
                sqlstate sqlwarning sqrt start static stddev_pop stddev_samp stream submultiset substring sum
                symmetric system system_user table tablesample then time timestamp timezone_hour timezone_minute
                tinyint to trailing translate translation treat trigger trim true uescape union unique unknown
                unnest update upper upsert user using value values var_pop var_samp varbinary varchar varying
                when whenever where width_bucket window with within without year
            """.trim().split(Regex("\\s+"))

        /** The IPC-framed schema message Flight SQL carries in `dataset_schema` and `GetTables.table_schema`. */
        fun serializeSchema(schema: Schema): ByteArray {
            val out = ByteArrayOutputStream()
            MessageSerializer.serialize(WriteChannel(Channels.newChannel(out)), schema)
            return out.toByteArray()
        }

        /** The code half of an `"<code>: <message>"` refusal. */
        internal fun codeOf(refusal: String): String = refusal.substringBefore(':').trim()

        /** One readable line: the first line of [text], whitespace collapsed, capped. */
        internal fun oneSentence(text: String): String =
            text
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
                .replace(Regex("\\s+"), " ")
                .trim()
                .let { if (it.length > MAX_MESSAGE_CHARS) it.take(MAX_MESSAGE_CHARS - 1) + "…" else it }

        private const val MAX_MESSAGE_CHARS = 400

        /**
         * The door's error code as a Flight status. Unavailable dependencies are retryable; a refusal on access is
         * UNAUTHORIZED; the door's own failures are INTERNAL; everything else is the statement's fault.
         */
        internal fun statusFor(code: String): CallStatus =
            when {
                code.endsWith("_unavailable") -> CallStatus.UNAVAILABLE
                code in ACCESS_CODES -> CallStatus.UNAUTHORIZED
                code in INTERNAL_CODES -> CallStatus.INTERNAL
                else -> CallStatus.INVALID_ARGUMENT
            }

        private val ACCESS_CODES = setOf("access_denied", "column_denied", "permission_denied", "world_mismatch")
        private val INTERNAL_CODES = setOf("query_failed", "cache_corrupted", "translator_failed")

        private fun flightError(
            refusal: String,
            line: Int = 0,
            column: Int = 0,
        ): FlightRuntimeException {
            val code = codeOf(refusal)
            val metadata =
                ErrorFlightMetadata().apply {
                    insert(CODE_METADATA_KEY, code)
                    if (line > 0) {
                        insert(LINE_METADATA_KEY, line.toString())
                        insert(COLUMN_METADATA_KEY, column.toString())
                    }
                }
            return statusFor(code).withDescription(refusal).withMetadata(metadata).toRuntimeException()
        }

        /** The batch's first ERROR as `"<code>: <one sentence>"` (and its position), or null. */
        private fun ResultBatch.refusal(): RefusedException? =
            messagesList
                .firstOrNull { it.severity == Severity.ERROR }
                ?.let {
                    RefusedException(
                        "${it.code.ifEmpty { "query_error" }}: ${oneSentence(it.humanMessage)}",
                        it.line,
                        it.column,
                    )
                }
    }
}

/** C-8 — the `bi.statement` span and the two BI metrics. */
private class EdgeTelemetry(
    openTelemetry: OpenTelemetry,
) {
    private val tracer = openTelemetry.getTracer("query.flight")
    private val meter = openTelemetry.getMeter("query.flight")
    private val statements = meter.counterBuilder("bi_statements_total").setDescription("BI edge statements").build()
    private val seconds =
        meter
            .histogramBuilder("bi_statement_seconds")
            .setUnit("s")
            .setDescription("BI edge statement wall time, start to last batch")
            .build()

    fun startStatement(session: EdgeSession): Span =
        tracer
            .spanBuilder("bi.statement")
            .setAttribute(USER_ID, session.userId)
            .setAttribute(WORLD, session.world)
            .setAttribute(CLIENT, session.client)
            .startSpan()

    fun endStatement(
        span: Span,
        outcome: StatementOutcome,
    ) {
        span.setAttribute(ROWS, outcome.rowsStreamed)
        span.setAttribute(STATUS, outcome.status.label)
        if (outcome.status != StatementOutcome.Status.OK) span.setStatus(StatusCode.ERROR, outcome.errorCode)
        span.end()
        val labels = Attributes.of(CLIENT, outcome.session.client, STATUS, outcome.status.label)
        statements.add(1, labels)
        seconds.record(outcome.durationMs / 1000.0, labels)
    }

    companion object {
        val USER_ID: AttributeKey<String> = AttributeKey.stringKey("user_id")
        val WORLD: AttributeKey<String> = AttributeKey.stringKey("world")
        val CLIENT: AttributeKey<String> = AttributeKey.stringKey("client")
        val STATUS: AttributeKey<String> = AttributeKey.stringKey("status")
        val ROWS: AttributeKey<Long> = AttributeKey.longKey("rows")
    }
}
