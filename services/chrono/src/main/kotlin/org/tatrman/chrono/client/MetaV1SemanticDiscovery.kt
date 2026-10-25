// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.client

import io.grpc.Channel
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.tatrman.chrono.discover.ColumnRef
import org.tatrman.chrono.discover.PeriodTable
import org.tatrman.chrono.discover.SemanticDiscovery
import org.tatrman.chrono.recognize.DateTarget
import org.tatrman.meta.v1.AttributeDetail
import org.tatrman.meta.v1.GetObjectRequest
import org.tatrman.meta.v1.GetStatusRequest
import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.PageRequest
import org.tatrman.meta.v1.MetadataServiceGrpcKt
import org.tatrman.plan.v1.QualifiedName
import shared.logging.OutgoingCallLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * The **one** place that reads `org.tatrman.meta.v1` (RG-P3.S0 metadata seam / G2 closure).
 * Implements the domain-typed [SemanticDiscovery] port over Metadata: tatrman's `meta.v1` carries no
 * server-side semantic filters, so discovery is client-side — `list_objects` surfaces
 * `ObjectDescriptor.semantics_kind` to find the period table, then `get_object` reads each
 * attribute's [AttributeDetail.getSemantics] role. Keeping this behind the port means the recipe
 * layer and every grounding test stay proto-free.
 */
class MetaV1SemanticDiscovery internal constructor(
    private val stub: MetadataServiceGrpcKt.MetadataServiceCoroutineStub,
    private val ownedChannel: ManagedChannel?,
    private val deadlineSeconds: Long = 30,
    private val probeDeadlineSeconds: Long = 5,
    private val defaultCodeFormat: String = "yyyyMM",
    private val pageSize: Int = 1000,
) : SemanticDiscovery,
    AutoCloseable {
    companion object {
        /** Production wiring — owns the channel to `host:port` (closed on [close]). */
        fun forAddress(
            host: String,
            port: Int,
            defaultCodeFormat: String = "yyyyMM",
        ): MetaV1SemanticDiscovery {
            val channel =
                ManagedChannelBuilder
                    .forAddress(host, port)
                    .usePlaintext()
                    .keepAliveTime(30, TimeUnit.SECONDS)
                    .keepAliveTimeout(10, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .intercept(OutgoingCallLoggingInterceptor())
                    .build()
            return MetaV1SemanticDiscovery(
                MetadataServiceGrpcKt.MetadataServiceCoroutineStub(channel),
                ownedChannel = channel,
                defaultCodeFormat = defaultCodeFormat,
            )
        }

        /** Test wiring — borrows an existing (e.g. in-process) channel; does not own it. */
        fun forChannel(
            channel: Channel,
            defaultCodeFormat: String = "yyyyMM",
        ): MetaV1SemanticDiscovery =
            MetaV1SemanticDiscovery(
                MetadataServiceGrpcKt.MetadataServiceCoroutineStub(channel),
                ownedChannel = null,
                defaultCodeFormat = defaultCodeFormat,
            )
    }

    override suspend fun periodTable(pkg: String): PeriodTable? {
        val entity =
            listObjects("entity", pkg)
                .firstOrNull { it.semanticsKind == "period_table" } ?: return null
        // Role columns are the period entity's attributes; read each attribute's role via get_object.
        val roleColumns = mutableMapOf<String, ColumnRef>()
        var codeFormat = defaultCodeFormat
        for (attr in listObjects("attribute", pkg)) {
            val detail = getAttribute(attr.qualifiedName) ?: continue
            if (detail.entity != entity.qualifiedName) continue
            val role = detail.semantics.role.takeIf { it.isNotEmpty() } ?: continue
            roleColumns[role] = columnRef(attr, detail)
            if (role == "period_code") {
                detail.semantics.codeFormat
                    .takeIf { it.isNotEmpty() }
                    ?.let { codeFormat = it }
            }
        }
        return PeriodTable(
            entity = entity.qualifiedName,
            entityName = entity.localName,
            start = roleColumns["period_start"],
            end = roleColumns["period_end"],
            code = roleColumns["period_code"],
            codeFormat = codeFormat,
        )
    }

    override suspend fun anchorColumn(
        pkg: String,
        target: DateTarget?,
    ): ColumnRef? {
        val role =
            when (target) {
                DateTarget.DUE -> "due_date"
                DateTarget.POSTING -> "posting_date"
                DateTarget.DOCUMENT -> "document_date"
                DateTarget.EVENT, null -> "event_date"
            }
        return firstColumnWithRole(pkg, role)
            ?: firstColumnWithRole(pkg, "event_date").takeIf { target != null }
    }

    override suspend fun probeReady(): Boolean =
        runCatching {
            stub
                .withDeadlineAfter(probeDeadlineSeconds, TimeUnit.SECONDS)
                .getStatus(GetStatusRequest.getDefaultInstance())
                .modelLoaded
        }.getOrDefault(false)

    private suspend fun firstColumnWithRole(
        pkg: String,
        role: String,
    ): ColumnRef? {
        for (attr in listObjects("attribute", pkg)) {
            val detail = getAttribute(attr.qualifiedName) ?: continue
            if (detail.semantics.role == role) return columnRef(attr, detail)
        }
        return null
    }

    private fun columnRef(
        descriptor: ObjectDescriptor,
        detail: AttributeDetail,
    ): ColumnRef =
        ColumnRef(
            qname = descriptor.qualifiedName,
            entityName = detail.entity.name.ifEmpty { descriptor.qualifiedName.namespace },
            columnName = descriptor.localName,
        )

    private suspend fun listObjects(
        kind: String,
        pkg: String,
    ): List<ObjectDescriptor> =
        stub
            .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
            .listObjects(
                ListObjectsRequest
                    .newBuilder()
                    .setKind(kind)
                    .setPackage(pkg)
                    .setPage(PageRequest.newBuilder().setPageSize(pageSize))
                    .build(),
            ).itemsList

    private suspend fun getAttribute(qname: QualifiedName): AttributeDetail? {
        val resp =
            stub
                .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
                .getObject(GetObjectRequest.newBuilder().setQualifiedName(qname).build())
        return if (resp.hasAttribute()) resp.attribute else null
    }

    override fun close() {
        ownedChannel?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
    }
}
