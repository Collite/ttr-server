// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.asFlow
import org.apache.arrow.flight.FlightClient
import org.apache.arrow.flight.FlightStream
import org.apache.arrow.flight.Location
import org.apache.arrow.flight.sql.FlightSqlClient
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.meta.v1.GetModelRequest
import org.tatrman.meta.v1.GetModelResponse
import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.meta.v1.ListObjectsResponse
import org.tatrman.query.v1.RunRequest
import org.tatrman.query.world.Worlds
import org.tatrman.worker.v1.ExecutionReceipt
import org.tatrman.worker.v1.Notice
import org.tatrman.worker.v1.ResultBatch
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.util.concurrent.CopyOnWriteArrayList

/** Shared fixtures for the Flight edge's specs: worker-shaped IPC batches, a scripted door, a running edge. */
object FlightTestKit {
    val SCHEMA: Schema =
        Schema(
            listOf(
                Field("id", FieldType.nullable(ArrowType.Int(32, true)), null),
                Field("name", FieldType.nullable(ArrowType.Utf8()), null),
            ),
        )

    val SESSION = EdgeSession(userId = "petr", roles = listOf("kantheon-area-hartland", "kantheon-scope-dc-5"))

    /** One worker payload: a self-contained IPC stream (schema + one batch + EOS), as `ArrowIpcSerializer` writes. */
    fun ipc(
        allocator: BufferAllocator,
        rows: List<Pair<Int, String?>>,
        schema: Schema = SCHEMA,
    ): ByteArray =
        VectorSchemaRoot.create(schema, allocator).use { root ->
            root.allocateNew()
            val ids = root.getVector("id") as IntVector
            val names = root.getVector("name") as VarCharVector
            rows.forEachIndexed { i, (id, name) ->
                ids.setSafe(i, id)
                if (name == null) names.setNull(i) else names.setSafe(i, name.toByteArray())
            }
            root.rowCount = rows.size
            val out = ByteArrayOutputStream()
            ArrowStreamWriter(root, null, Channels.newChannel(out)).use { w ->
                w.start()
                w.writeBatch()
                w.end()
            }
            out.toByteArray()
        }

    fun dataBatch(
        index: Int,
        payload: ByteArray,
        rows: Int,
        last: Boolean = false,
    ): ResultBatch =
        ResultBatch
            .newBuilder()
            .setBatchIndex(index)
            .setIsFirst(index == 0)
            .setIsLast(last)
            .setBatchRowCount(rows.toLong())
            .setArrowIpc(ByteString.copyFrom(payload))
            .build()

    /**
     * The door's tail: no rows, the receipt's statement half and the cap notice when the answer hit it — as the door
     * sends it for an edge statement (C-10: the edge's ask is the largest window, so `requested` is 0).
     */
    fun tail(
        index: Int,
        connectionId: String = "pg-hartland-cz",
        rowsTotal: Long = 0,
        topNApplied: Boolean = false,
    ): ResultBatch =
        ResultBatch
            .newBuilder()
            .setBatchIndex(index)
            .setIsLast(true)
            .setArrowIpc(ByteString.EMPTY)
            .setReceipt(
                ExecutionReceipt
                    .newBuilder()
                    .setConnectionId(connectionId)
                    .setRowsTotal(rowsTotal)
                    .setStatement("SELECT 1"),
            ).apply {
                if (topNApplied) {
                    addMessages(
                        ResponseMessage
                            .newBuilder()
                            .setSeverity(Severity.WARNING)
                            .setCode("top_n_applied")
                            .setHumanMessage(
                                "Answer limited to 200 rows by the row cap; the caller asked for as many rows as its " +
                                    "ceiling allows.",
                            ),
                    )
                    addNotices(
                        Notice
                            .newBuilder()
                            .setCode("top_n_applied")
                            .setRequested(0)
                            .setApplied(200),
                    )
                }
            }.build()

    fun errorBatch(
        code: String,
        message: String,
        index: Int = 0,
        line: Int = 0,
        column: Int = 0,
    ): ResultBatch =
        ResultBatch
            .newBuilder()
            .setBatchIndex(index)
            .setIsFirst(index == 0)
            .setIsLast(true)
            .setArrowIpc(ByteString.EMPTY)
            .addMessages(
                ResponseMessage
                    .newBuilder()
                    .setSeverity(Severity.ERROR)
                    .setCode(code)
                    .setHumanMessage(message)
                    .setLine(line)
                    .setColumn(column),
            ).build()

    /** A door that answers every `Run` with [script]'s batches, and remembers every request. */
    class ScriptedDoor(
        var script: (RunRequest) -> List<ResultBatch>,
    ) : StatementRunner {
        val requests = CopyOnWriteArrayList<RunRequest>()

        override fun run(request: RunRequest) =
            script(request)
                .also { requests += request }
                .asFlow()
    }

    /** A Veles that never answers — for specs that do not read the catalog. */
    object NoVeles : VelesCatalogClient {
        override suspend fun modelVersion(): String = error("no Veles in this spec")

        override suspend fun listObjects(request: ListObjectsRequest): ListObjectsResponse =
            error("no Veles in this spec")

        override suspend fun getModel(request: GetModelRequest): GetModelResponse = error("no Veles in this spec")
    }

    /**
     * A running edge on an OS-picked loopback port. Without [identity], in allow-all TEST mode (no such mode exists
     * outside tests: production always installs [IdentityWorldMiddleware]) and every call made as [session]; with
     * it, every call authenticated and placed in its world by the real middleware.
     */
    class RunningEdge(
        door: StatementRunner,
        veles: VelesCatalogClient = NoVeles,
        session: EdgeSession? = SESSION,
        val outcomes: MutableList<StatementOutcome> = CopyOnWriteArrayList(),
        config: FlightConfig = testConfig(),
        identity: IdentityWorldMiddleware.Factory? = null,
        worlds: Worlds = Worlds.NONE,
        listener: StatementListener? = null,
    ) : AutoCloseable {
        val allocator: BufferAllocator = RootAllocator()
        val catalog = CatalogSource(veles, RunnerSchemaProbe(door, allocator), ttlSeconds = 30)
        val producer =
            TtrFlightSqlProducer(
                runner = door,
                catalog = catalog,
                sessions = if (identity != null) IdentityWorldMiddleware.SESSIONS else SessionResolver { session },
                allocator = allocator,
                config = config,
                outcomes =
                    StatementListener {
                        outcomes += it
                        listener?.onStatementDone(it)
                    },
                worlds = worlds,
            )
        val edge =
            FlightEdge(
                config,
                producer,
                allocator,
                listOfNotNull(identity?.let { IdentityWorldMiddleware.binding(it) }),
            ).start()
        private val clientAllocator = RootAllocator()
        val client: FlightClient =
            FlightClient.builder(clientAllocator, Location.forGrpcInsecure("127.0.0.1", edge.port)).build()
        val sql = FlightSqlClient(client)

        override fun close() {
            client.close()
            clientAllocator.close()
            edge.close()
            // The edge swallows a leak at shutdown (it must not fail a pod's stop); a spec must not.
            check(allocator.allocatedMemory == 0L) { "the edge leaked ${allocator.allocatedMemory} bytes" }
        }
    }

    fun testConfig(): FlightConfig =
        FlightConfig(
            enabled = true,
            port = 0,
            bind = "127.0.0.1",
            loopbackHost = "127.0.0.1",
            loopbackPort = 0,
        )

    /** What a client read from one stream: rows as (id, name) batch by batch, and each batch's app metadata. */
    data class Read(
        val batches: List<List<Pair<Int, String?>>>,
        val metadata: List<String?>,
    ) {
        val rows: List<Pair<Int, String?>> get() = batches.flatten()

        /** The closing metadata: the last batch's. */
        val closing: String? get() = metadata.lastOrNull()
    }

    fun FlightStream.readAll(): Read {
        val batches = mutableListOf<List<Pair<Int, String?>>>()
        val metadata = mutableListOf<String?>()
        while (next()) {
            val ids = root.getVector("id") as IntVector
            val names = root.getVector("name") as VarCharVector
            batches += (0 until root.rowCount).map { i -> ids.get(i) to names.getObject(i)?.toString() }
            metadata +=
                latestMetadata?.let { buf ->
                    val bytes = ByteArray(buf.readableBytes().toInt())
                    buf.getBytes(buf.readerIndex(), bytes)
                    String(bytes)
                }
        }
        return Read(batches, metadata)
    }
}
