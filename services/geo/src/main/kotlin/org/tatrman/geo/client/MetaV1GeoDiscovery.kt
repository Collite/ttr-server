// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.client

import io.grpc.Channel
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.tatrman.geo.discover.ColumnRef
import org.tatrman.geo.discover.GeoColumns
import org.tatrman.geo.discover.GeoDiscovery
import org.tatrman.geo.discover.PoiEntity
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
 * The **one** place that reads `org.tatrman.meta.v1` for geo (RG-P3.S0 metadata seam / G2 closure).
 * Implements the domain-typed [GeoDiscovery] port over Metadata: tatrman's `meta.v1` carries no
 * server-side semantic filters, so discovery is client-side — `list_objects` surfaces the attribute
 * descriptors, `get_object` reads each attribute's role, the POI entity is found via
 * `ObjectDescriptor.semantics_kind == "poi"`, and its display column comes from
 * `EntityDetail.name_attribute`. Keeping this behind the port means the recipe layer, the POI
 * resolver, and every grounding test stay proto-free.
 */
class MetaV1GeoDiscovery internal constructor(
    private val stub: MetadataServiceGrpcKt.MetadataServiceCoroutineStub,
    private val ownedChannel: ManagedChannel?,
    private val deadlineSeconds: Long = 30,
    private val probeDeadlineSeconds: Long = 5,
    private val pageSize: Int = 1000,
) : GeoDiscovery,
    AutoCloseable {
    companion object {
        /** Production wiring — owns the channel to `host:port` (closed on [close]). */
        fun forAddress(
            host: String,
            port: Int,
        ): MetaV1GeoDiscovery {
            val channel =
                ManagedChannelBuilder
                    .forAddress(host, port)
                    .usePlaintext()
                    .keepAliveTime(30, TimeUnit.SECONDS)
                    .keepAliveTimeout(10, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .intercept(OutgoingCallLoggingInterceptor())
                    .build()
            return MetaV1GeoDiscovery(
                MetadataServiceGrpcKt.MetadataServiceCoroutineStub(channel),
                ownedChannel = channel,
            )
        }

        /** Test wiring — borrows an existing (e.g. in-process) channel; does not own it. */
        fun forChannel(channel: Channel): MetaV1GeoDiscovery =
            MetaV1GeoDiscovery(
                MetadataServiceGrpcKt.MetadataServiceCoroutineStub(channel),
                ownedChannel = null,
            )
    }

    override suspend fun geoColumns(pkg: String): GeoColumns? {
        val byRole = columnsByRole(pkg)
        val lat = byRole["geo_lat"]?.firstOrNull() ?: return null
        val lon = byRole["geo_lon"]?.firstOrNull() ?: return null
        return GeoColumns(lat, lon)
    }

    override suspend fun poiEntity(pkg: String): PoiEntity? {
        val item =
            listObjects("entity", pkg)
                .firstOrNull { it.semanticsKind == "poi" } ?: return null
        val nameColumn =
            getEntity(item.qualifiedName)?.nameAttribute?.takeIf { it.isNotEmpty() } ?: return null
        val geo = geoColumns(pkg) ?: return null
        return PoiEntity(
            entity = item.qualifiedName,
            entityName = item.localName,
            nameColumn = nameColumn,
            latColumn = geo.lat.columnName,
            lonColumn = geo.lon.columnName,
        )
    }

    override suspend fun probeReady(): Boolean =
        runCatching {
            stub
                .withDeadlineAfter(probeDeadlineSeconds, TimeUnit.SECONDS)
                .getStatus(GetStatusRequest.getDefaultInstance())
                .modelLoaded
        }.getOrDefault(false)

    /** All attribute columns in the package grouped by their semantics `role` (client-side filter). */
    private suspend fun columnsByRole(pkg: String): Map<String, List<ColumnRef>> {
        val byRole = mutableMapOf<String, MutableList<ColumnRef>>()
        for (attr in listObjects("attribute", pkg)) {
            val detail = getAttribute(attr.qualifiedName) ?: continue
            val role = detail.semantics.role.takeIf { it.isNotEmpty() } ?: continue
            val entityName = detail.entity.name.ifEmpty { attr.qualifiedName.namespace }
            byRole
                .getOrPut(role) { mutableListOf() }
                .add(ColumnRef(qname = attr.qualifiedName, entityName = entityName, columnName = attr.localName))
        }
        return byRole
    }

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

    private suspend fun getAttribute(qname: QualifiedName) =
        stub
            .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
            .getObject(GetObjectRequest.newBuilder().setQualifiedName(qname).build())
            .takeIf { it.hasAttribute() }
            ?.attribute

    private suspend fun getEntity(qname: QualifiedName) =
        stub
            .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
            .getObject(GetObjectRequest.newBuilder().setQualifiedName(qname).build())
            .takeIf { it.hasEntity() }
            ?.entity

    override fun close() {
        ownedChannel?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
    }
}
