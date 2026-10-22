// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.flow.Flow
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.query.v1.QueryServiceGrpcKt
import org.tatrman.query.v1.RowWindow
import org.tatrman.query.v1.RunRequest
import org.tatrman.translate.v1.Language
import org.tatrman.worker.v1.ResultBatch
import java.util.concurrent.TimeUnit

/** How the edge runs one statement: the door's `Run`, as any caller sees it. A `fun interface` so tests stub it. */
fun interface StatementRunner {
    fun run(request: RunRequest): Flow<ResultBatch>
}

/**
 * C-10 — the rows an edge statement asks for: as many as the caller's ceiling allows. The validator grants
 * `min(ask, ceiling)`, so the caller gets its ceiling (`max-top-n`, raised per role by `max-top-n-by-role`), and a
 * statement's own `LIMIT` still bounds it below that. A BI client states its page in SQL, never in a row window,
 * so the edge has no smaller number to pass on. The largest window the door accepts.
 */
const val EDGE_ROW_ASK: Long = Int.MAX_VALUE.toLong()

/**
 * One edge statement as the door's `RunRequest`: SQL, the layer its qualified names are written in stated as
 * `source_schema` ([SourceSchema]: `er.entity.x` / `obj.query.x` → ER, `db.dbo.x` → DB, none → the door's
 * autodetection), the caller's identity exactly as the verified bearer stated it (C-2), the
 * connection's world (C-3, C-9), and the edge's row ask ([EDGE_ROW_ASK]) unless the caller says otherwise.
 */
internal fun edgeRunRequest(
    sql: String,
    session: EdgeSession,
    correlationId: String,
    window: RowWindow = RowWindow.newBuilder().setLimit(EDGE_ROW_ASK).build(),
): RunRequest =
    RunRequest
        .newBuilder()
        .setSource(sql)
        .setSourceLanguage(Language.SQL)
        .setSourceSchema(SourceSchema.of(sql))
        .setContext(
            PipelineContext
                .newBuilder()
                .setCorrelationId(correlationId)
                .setUserId(session.userId)
                .addAllAuthRoles(session.roles),
        ).setRowWindow(window)
        .setWorld(session.world)
        .build()

/**
 * The production [StatementRunner]: `QueryService.Run` over a plaintext channel to this pod's own gRPC port
 * (BI-0 memo §4 — simpler than wiring the service's nine collaborators twice, and the door logs the call as it
 * logs every other caller's).
 */
class LoopbackStatementRunner(
    host: String,
    port: Int,
    maxMessageSizeBytes: Int,
) : StatementRunner,
    AutoCloseable {
    private val channel: ManagedChannel =
        ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .maxInboundMessageSize(maxMessageSizeBytes)
            .build()
    private val stub = QueryServiceGrpcKt.QueryServiceCoroutineStub(channel)

    override fun run(request: RunRequest): Flow<ResultBatch> = stub.run(request)

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}
