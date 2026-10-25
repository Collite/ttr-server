// SPDX-License-Identifier: Apache-2.0
package org.tatrman.money.client

import io.grpc.Channel
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.tatrman.meta.v1.GetObjectRequest
import org.tatrman.meta.v1.GetStatusRequest
import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.PageRequest
import org.tatrman.meta.v1.MetadataServiceGrpcKt
import org.tatrman.money.discover.AmountColumns
import org.tatrman.money.discover.ColumnRef
import org.tatrman.money.discover.FxTable
import org.tatrman.money.discover.MoneyDiscovery
import org.tatrman.plan.v1.QualifiedName
import shared.logging.OutgoingCallLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * The **one** place that reads `org.tatrman.meta.v1` for money (RG-P3.S0 metadata seam / G2 closure).
 * Implements the domain-typed [MoneyDiscovery] port over Metadata: tatrman's `meta.v1` carries no
 * server-side semantic filters, so discovery is client-side — `list_objects` surfaces the attribute
 * descriptors, `get_object` reads each attribute's [org.tatrman.meta.v1.AttributeDetail.getSemantics]
 * role, and the fx-rate table is found via `ObjectDescriptor.semantics_kind == "fx_rate"`. Keeping
 * this behind the port means the recipe layer and every grounding test stay proto-free.
 */
class MetaV1MoneyDiscovery internal constructor(
    private val stub: MetadataServiceGrpcKt.MetadataServiceCoroutineStub,
    private val ownedChannel: ManagedChannel?,
    private val deadlineSeconds: Long = 30,
    private val probeDeadlineSeconds: Long = 5,
    private val pageSize: Int = 1000,
) : MoneyDiscovery,
    AutoCloseable {
    companion object {
        /** Production wiring — owns the channel to `host:port` (closed on [close]). */
        fun forAddress(
            host: String,
            port: Int,
        ): MetaV1MoneyDiscovery {
            val channel =
                ManagedChannelBuilder
                    .forAddress(host, port)
                    .usePlaintext()
                    .keepAliveTime(30, TimeUnit.SECONDS)
                    .keepAliveTimeout(10, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .intercept(OutgoingCallLoggingInterceptor())
                    .build()
            return MetaV1MoneyDiscovery(
                MetadataServiceGrpcKt.MetadataServiceCoroutineStub(channel),
                ownedChannel = channel,
            )
        }

        /** Test wiring — borrows an existing (e.g. in-process) channel; does not own it. */
        fun forChannel(channel: Channel): MetaV1MoneyDiscovery =
            MetaV1MoneyDiscovery(
                MetadataServiceGrpcKt.MetadataServiceCoroutineStub(channel),
                ownedChannel = null,
            )
    }

    override suspend fun amountColumns(pkg: String): AmountColumns {
        val byRole = columnsByRole(pkg)
        return AmountColumns(
            domestic = byRole["amount_domestic"]?.firstOrNull(),
            amount = byRole["amount"].orEmpty(),
            currencyCode = byRole["currency_code"]?.firstOrNull(),
        )
    }

    override suspend fun fxTable(pkg: String): FxTable? {
        val entity =
            listObjects("entity", pkg)
                .firstOrNull { it.semanticsKind == "fx_rate" } ?: return null
        val byRole = columnsByRole(pkg)
        val rate = byRole["fx_rate"]?.firstOrNull() ?: return null
        val from = byRole["fx_from_currency"]?.firstOrNull() ?: return null
        val to = byRole["fx_to_currency"]?.firstOrNull() ?: return null
        return FxTable(
            entity = entity.qualifiedName,
            entityName = entity.localName,
            rate = rate,
            fromCurrency = from,
            toCurrency = to,
            validFrom = byRole["valid_from"]?.firstOrNull(),
            validTo = byRole["valid_to"]?.firstOrNull(),
        )
    }

    override suspend fun eventDateColumn(pkg: String): ColumnRef? = columnsByRole(pkg)["event_date"]?.firstOrNull()

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

    override fun close() {
        ownedChannel?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
    }
}
