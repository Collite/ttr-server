// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.tatrman.meta.v1.GetStatusRequest
import org.tatrman.meta.v1.MetadataServiceGrpcKt
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Metadata's model sources as `GetStatus` reports them: source id → the version it loaded (a git source: the commit). */
fun interface MetadataStatus {
    /** Null when Metadata cannot be asked. */
    suspend fun sourceVersions(): Map<String, String>?
}

/** `GetStatus` over gRPC at `door.metadataUrl` (`host:port`, a scheme tolerated). */
class GrpcMetadataStatus(
    target: String,
) : MetadataStatus,
    AutoCloseable {
    private val channel: ManagedChannel =
        target.substringAfter("://").trimEnd('/').let { hostPort ->
            ManagedChannelBuilder.forTarget(hostPort).usePlaintext().build()
        }
    private val stub = MetadataServiceGrpcKt.MetadataServiceCoroutineStub(channel)

    override suspend fun sourceVersions(): Map<String, String>? =
        try {
            stub
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .getStatus(GetStatusRequest.getDefaultInstance())
                .sourcesList
                .associate { it.id to it.version }
        } catch (e: Exception) {
            log.debug("automation: metadata GetStatus failed: {}", e.message)
            null
        }

    override fun close() {
        channel.shutdownNow()
    }

    private companion object {
        val log = LoggerFactory.getLogger(GrpcMetadataStatus::class.java)
    }
}

/**
 * The Metadata handshake (S0.2 T6): does Metadata serve the model this bundle was built against? Metadata's own
 * `model_version` carries a random suffix per reconcile, so the comparable stamp is a source's version — for a git
 * source, its commit. `modelDrift` = Metadata serves another commit than the served bundle's: [source] (the
 * `bundle.metadataSource` config) names Metadata's source of this repo; unset, any Metadata source at the bundle's commit
 * counts as in sync. Null = not known (Metadata unreachable, no such source, or no accepted version yet). A drift is a descriptor warning,
 * never a refusal — the model repo and the automation package may be one repo or two (with two, set no source and
 * read `modelDrift` as "Metadata serves none of this repo's commits"). The answer is cached for [ttl].
 */
class MetadataHandshake(
    private val metadata: MetadataStatus,
    private val source: String?,
    private val ttl: Duration = Duration.ofSeconds(30),
    private val clock: Clock = Clock.systemUTC(),
) {
    @Volatile
    private var cached: Pair<Instant, Map<String, String>?>? = null

    suspend fun drift(commit: String): Boolean? {
        val versions = versions() ?: return null
        return if (source != null) {
            versions[source]?.takeIf { it.isNotEmpty() }?.let { it != commit }
        } else {
            versions.values
                .filter { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() }
                ?.let { commit !in it }
        }
    }

    private suspend fun versions(): Map<String, String>? {
        val now = clock.instant()
        cached?.let { (at, v) -> if (Duration.between(at, now) < ttl) return v }
        val v = withTimeoutOrNull(6_000) { metadata.sourceVersions() }
        cached = now to v
        return v
    }
}
