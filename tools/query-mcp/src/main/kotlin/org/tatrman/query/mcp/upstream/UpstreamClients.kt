// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.upstream

import org.tatrman.meta.v1.GetSnapshotRequest
import org.tatrman.meta.v1.GetSnapshotResponse
import org.tatrman.meta.v1.MetadataServiceGrpcKt
import org.tatrman.meta.v1.ObjectEntry
import org.tatrman.query.v1.CompileResponse
import org.tatrman.query.v1.QueryServiceGrpcKt
import org.tatrman.query.v1.RunRequest
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.translate.v1.ParseResponse
import org.tatrman.translate.v1.TranslateRequest
import org.tatrman.translate.v1.TranslateResponse
import org.tatrman.translate.v1.TranslateServiceGrpcKt
import org.tatrman.translate.v1.UnparseRequest
import org.tatrman.translate.v1.UnparseResponse
import org.tatrman.validate.v1.ValidateRequest
import org.tatrman.validate.v1.ValidateResponse
import org.tatrman.validate.v1.ValidateServiceGrpcKt
import org.tatrman.worker.v1.ResultBatch
import io.grpc.ConnectivityState
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory
import shared.formatter.core.ColumnDecoration
import shared.formatter.core.LocalizedString
import org.tatrman.query.mcp.QueryMcpConfig
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Upstream client interfaces. Kept as `interface` (not `fun interface`) so
 * test fakes can implement them with named constructors and trivially set
 * canned responses. Production implementations are the Grpc...Client
 * classes below.
 */
interface QueryRunnerClient {
    /** Server-streaming Run; cancellation propagates via the calling coroutine. */
    fun run(request: RunRequest): Flow<ResultBatch>

    suspend fun compile(request: RunRequest): CompileResponse
}

interface TranslatorClient {
    suspend fun parseToRelNode(request: ParseRequest): ParseResponse

    suspend fun translate(request: TranslateRequest): TranslateResponse

    suspend fun unparseFromRelNode(request: UnparseRequest): UnparseResponse
}

interface ValidatorClient {
    suspend fun validate(request: ValidateRequest): ValidateResponse
}

/**
 * Phase 2.2 — read-only metadata access for the side-channel decorator.
 *
 * The single high-level method `attributeDecorationsByLocalName()` returns
 * a flat `Map<columnName, ColumnDecoration>` built by walking every
 * `er.attribute.*` (and `db.column.*` mapped back via `er2db_attribute`)
 * in the model snapshot. Empty when the metadata service is unreachable
 * or the snapshot has no decoratable attributes — caller treats absence
 * as "no decoration".
 *
 * v1 ambiguity rule: when two attributes share a local name (different
 * entities), the first wins. Documented in the PR description as a known
 * limitation; queries that pull mixed-entity columns can disambiguate via
 * column aliases in the source query.
 */
interface MetadataServiceClient {
    suspend fun attributeDecorationsByLocalName(): Map<String, ColumnDecoration>
}

class GrpcQueryRunnerClient(
    private val cfg: QueryMcpConfig.GrpcEndpoint,
    private val maxMessageBytes: Int,
) : QueryRunnerClient,
    AutoCloseable {
    private val channel: ManagedChannel = openChannel(cfg.host, cfg.port, maxMessageBytes)
    private val stub = QueryServiceGrpcKt.QueryServiceCoroutineStub(channel)

    /**
     * Server-streaming Run. We do *not* set a per-call deadline because the
     * Worker may legitimately stream for `cfg.deadlineSeconds`+ on long
     * scans; cancellation is via the Flow collector's coroutine cancel.
     */
    override fun run(request: RunRequest): Flow<ResultBatch> = stub.run(request)

    override suspend fun compile(request: RunRequest): CompileResponse =
        stub.withDeadlineAfter(cfg.deadlineSeconds, TimeUnit.SECONDS).compile(request)

    fun connectivityState(): ConnectivityState = channel.getState(false)

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}

class GrpcTranslatorClient(
    private val cfg: QueryMcpConfig.GrpcEndpoint,
    private val maxMessageBytes: Int,
) : TranslatorClient,
    AutoCloseable {
    private val channel: ManagedChannel = openChannel(cfg.host, cfg.port, maxMessageBytes)
    private val stub = TranslateServiceGrpcKt.TranslateServiceCoroutineStub(channel)

    override suspend fun parseToRelNode(request: ParseRequest): ParseResponse =
        stub.withDeadlineAfter(cfg.deadlineSeconds, TimeUnit.SECONDS).parseToRelNode(request)

    override suspend fun translate(request: TranslateRequest): TranslateResponse =
        stub.withDeadlineAfter(cfg.deadlineSeconds, TimeUnit.SECONDS).translate(request)

    override suspend fun unparseFromRelNode(request: UnparseRequest): UnparseResponse =
        stub.withDeadlineAfter(cfg.deadlineSeconds, TimeUnit.SECONDS).unparseFromRelNode(request)

    fun connectivityState(): ConnectivityState = channel.getState(false)

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}

class GrpcValidatorClient(
    private val cfg: QueryMcpConfig.GrpcEndpoint,
    private val maxMessageBytes: Int,
) : ValidatorClient,
    AutoCloseable {
    private val channel: ManagedChannel = openChannel(cfg.host, cfg.port, maxMessageBytes)
    private val stub = ValidateServiceGrpcKt.ValidateServiceCoroutineStub(channel)

    override suspend fun validate(request: ValidateRequest): ValidateResponse =
        stub.withDeadlineAfter(cfg.deadlineSeconds, TimeUnit.SECONDS).validate(request)

    fun connectivityState(): ConnectivityState = channel.getState(false)

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}

class GrpcMetadataClient(
    private val cfg: QueryMcpConfig.GrpcEndpoint,
    private val maxMessageBytes: Int,
    cacheTtl: Duration = Duration.ofSeconds(30),
    clock: () -> Instant = Instant::now,
) : MetadataServiceClient,
    AutoCloseable {
    private val channel: ManagedChannel = openChannel(cfg.host, cfg.port, maxMessageBytes)
    private val stub = MetadataServiceGrpcKt.MetadataServiceCoroutineStub(channel)

    private val cache: MetadataDecorationCache =
        MetadataDecorationCache(
            ttl = cacheTtl,
            clock = clock,
            fetchSnapshot = { etag ->
                stub
                    .withDeadlineAfter(cfg.deadlineSeconds, TimeUnit.SECONDS)
                    .getSnapshot(
                        GetSnapshotRequest
                            .newBuilder()
                            .setIfNoneMatch(etag)
                            .build(),
                    )
            },
        )

    override suspend fun attributeDecorationsByLocalName(): Map<String, ColumnDecoration> = cache.decorations()

    fun connectivityState(): ConnectivityState = channel.getState(false)

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}

/**
 * DF-ME02-CACHE — two-level cache for the metadata-service decoration fetch.
 *
 *   - **TTL fast-path** (client-side): within [ttl] of the last successful fetch, skip the RPC
 *     entirely and return the in-memory decoration map. The hot path (`query` MCP tool) no longer
 *     pays a round-trip per query when the model is stable.
 *   - **Etag conditional GET** (after TTL elapses): we still issue `GetSnapshot(if_none_match = <last etag>)`;
 *     the server replies `not_modified = true` when the model hasn't changed, so we skip the
 *     full snapshot serialisation cost too. A 304-equivalent response keeps the cached decoration
 *     map but refreshes the `cachedAt` instant so the TTL window restarts.
 *
 * Failures (RPC errors / deadline exceeded) leave `cachedAt` unchanged so the next call retries
 * immediately rather than serving "TTL-fresh empty decorations". On a cold cache + failure the
 * returned map is empty (the side-channel decoration is best-effort by contract).
 *
 * [ttl] = `Duration.ZERO` disables the TTL fast-path; behaviour reduces to the prior etag-only
 * conditional GET — every call hits the wire, the server can still skip serialisation.
 */
internal class MetadataDecorationCache(
    private val ttl: Duration,
    private val clock: () -> Instant,
    private val fetchSnapshot: suspend (ifNoneMatch: String) -> GetSnapshotResponse,
) {
    private val logger = LoggerFactory.getLogger(MetadataDecorationCache::class.java)

    @Volatile
    private var cachedEtag: String = ""

    @Volatile
    private var cachedDecorations: Map<String, ColumnDecoration> = emptyMap()

    @Volatile
    private var cachedAt: Instant = Instant.EPOCH

    suspend fun decorations(): Map<String, ColumnDecoration> {
        if (cachedAt != Instant.EPOCH && !ttl.isZero) {
            val age = Duration.between(cachedAt, clock())
            if (!age.isNegative && age < ttl) return cachedDecorations
        }
        val resp =
            try {
                fetchSnapshot(cachedEtag)
            } catch (t: Throwable) {
                logger.debug("Metadata snapshot fetch failed: {}", t.message)
                return cachedDecorations
            }
        return if (resp.notModified) {
            cachedAt = clock()
            cachedDecorations
        } else {
            val fresh = buildDecorations(resp.snapshot.objectsList)
            cachedEtag = resp.etag
            cachedDecorations = fresh
            cachedAt = clock()
            fresh
        }
    }

    private fun buildDecorations(entries: List<ObjectEntry>): Map<String, ColumnDecoration> {
        val out = LinkedHashMap<String, ColumnDecoration>()
        for (entry in entries) {
            if (entry.contentCase != ObjectEntry.ContentCase.ATTRIBUTE) continue
            val attr = entry.attribute
            val hasDisplay = attr.displayLabel.byLanguageMap.isNotEmpty()
            val hasValueLabels = attr.valueLabelsMap.isNotEmpty()
            if (!hasDisplay && !hasValueLabels) continue
            val localName = entry.objectDescriptor.localName.substringAfterLast('.')
            // First entry wins on ambiguity — see contract on MetadataServiceClient.
            if (localName in out) continue
            out[localName] =
                ColumnDecoration(
                    displayLabel = LocalizedString(attr.displayLabel.byLanguageMap),
                    valueLabels =
                        attr.valueLabelsMap.mapValues { (_, v) ->
                            LocalizedString(v.byLanguageMap)
                        },
                )
        }
        return out
    }
}

private fun openChannel(
    host: String,
    port: Int,
    maxMessageBytes: Int,
): ManagedChannel =
    ManagedChannelBuilder
        .forAddress(host, port)
        .usePlaintext()
        .maxInboundMessageSize(maxMessageBytes)
        .keepAliveTime(30, TimeUnit.SECONDS)
        .keepAliveTimeout(10, TimeUnit.SECONDS)
        .keepAliveWithoutCalls(true)
        .enableRetry()
        .build()
