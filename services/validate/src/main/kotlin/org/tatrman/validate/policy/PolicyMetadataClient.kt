// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

import org.tatrman.meta.v1.GetObjectRequest
import org.tatrman.meta.v1.GetStatusRequest
import org.tatrman.meta.v1.MetadataServiceGrpcKt
import org.tatrman.plan.v1.QualifiedName
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Minimal contract the security service needs from the metadata service for
 * metadata-aware policy evaluation (DF-S02): does a referenced object exist,
 * and what's the current model version (so a stale plan can be flagged).
 *
 * Column-grain checks (does column X exist on table T) need RESOLVE-stage
 * column qualification that v1 doesn't have yet — out of scope here; table-grain
 * existence + version match is the robust subset.
 */
interface PolicyMetadataClient {
    suspend fun objectExists(qname: QualifiedName): Boolean

    /** The metadata model version (`ModelVersion.value`), or empty if unavailable. */
    suspend fun currentVersion(): String
}

class GrpcPolicyMetadataClient(
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 10,
) : PolicyMetadataClient,
    AutoCloseable {
    private val logger = LoggerFactory.getLogger(GrpcPolicyMetadataClient::class.java)
    private val channel: ManagedChannel =
        ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()
    private val stub = MetadataServiceGrpcKt.MetadataServiceCoroutineStub(channel)

    override suspend fun objectExists(qname: QualifiedName): Boolean =
        try {
            val resp =
                stub
                    .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
                    .getObject(GetObjectRequest.newBuilder().setQualifiedName(qname).build())
            // On not-found the metadata service leaves the descriptor unset and adds an
            // `object_not_found` message; on metadata_not_ready, same (no descriptor).
            resp.hasObjectDescriptor() && resp.messagesList.none { it.code == "object_not_found" }
        } catch (e: Exception) {
            // Don't fail policy evaluation on a transient metadata outage — treat as "exists"
            // (the caller may add a soft warning separately).
            logger.warn(
                "metadata GetObject({}.{}.{}) failed: {}",
                qname.schemaCode,
                qname.namespace,
                qname.name,
                e.message,
            )
            true
        }

    override suspend fun currentVersion(): String =
        try {
            stub
                .withDeadlineAfter(
                    deadlineSeconds,
                    TimeUnit.SECONDS,
                ).getStatus(GetStatusRequest.getDefaultInstance())
                .modelVersion
        } catch (e: Exception) {
            logger.warn("metadata GetStatus failed: {}", e.message)
            ""
        }

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}

/**
 * Static [MetadataClient] for tests and fixture / no-metadata boots. Reports a configured set of
 * known qnames as existing (default: everything), and a fixed version.
 */
class StaticPolicyMetadataClient(
    private val version: String = "static",
    private val knownQnames: Set<QualifiedName>? = null,
) : PolicyMetadataClient {
    override suspend fun objectExists(qname: QualifiedName): Boolean = knownQnames?.contains(qname) ?: true

    override suspend fun currentVersion(): String = version

    companion object {
        /** A client that says every object exists and returns an empty version (no version-mismatch check). */
        fun permissive(): StaticPolicyMetadataClient = StaticPolicyMetadataClient(version = "")
    }
}
