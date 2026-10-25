// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.client

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.tatrman.meta.v1.VelesServiceGrpcKt
import org.tatrman.meta.v1.GetModelRequest
import org.tatrman.meta.v1.GetModelResponse
import org.tatrman.meta.v1.GetObjectRequest
import org.tatrman.meta.v1.GetObjectResponse
import org.tatrman.meta.v1.GetQueryRequest
import org.tatrman.meta.v1.GetQueryResponse
import org.tatrman.meta.v1.GetRolesForEntityRequest
import org.tatrman.meta.v1.GetRolesForEntityResponse
import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.meta.v1.ListObjectsResponse
import org.tatrman.meta.v1.ListQueriesRequest
import org.tatrman.meta.v1.ListQueriesResponse
import org.tatrman.meta.v1.ListRolesRequest
import org.tatrman.meta.v1.ListRolesResponse
import org.tatrman.meta.v1.PageRequest
import org.tatrman.meta.v1.ResolveAreaRequest
import org.tatrman.meta.v1.ResolveAreaResponse
import org.tatrman.plan.v1.QualifiedName
import java.util.concurrent.TimeUnit

/** Server-side maximum page size for list RPCs; requesting it minimises round-trips when draining. */
private const val PAGE_SIZE = 1000

/**
 * Suspend client over the Veles model-graph gRPC service. Shared across the
 * constellation (veles-mcp, Golem `PackageContext`/`PromptStore`, Pythia).
 * Callers do their own caching on top of the model `content_hash` / prompt
 * `tree_hash`; this client is a thin, stateless RPC bridge.
 */
interface MetadataGrpcClient : AutoCloseable {
    suspend fun listRoles(pageSize: Int = 100): ListRolesResponse

    suspend fun getRolesForEntity(qname: QualifiedName): GetRolesForEntityResponse

    suspend fun getModel(
        packages: List<String>,
        locale: String = "",
        includeSearchHints: Boolean = true,
        includeRoles: Boolean = true,
        includeDrillMap: Boolean = true,
    ): GetModelResponse

    suspend fun listObjects(
        kind: String = "",
        packageFilter: String = "",
    ): ListObjectsResponse

    suspend fun listQueries(
        kind: String = "",
        packageFilter: String = "",
    ): ListQueriesResponse

    suspend fun getObject(qname: QualifiedName): GetObjectResponse

    suspend fun getQuery(qname: QualifiedName): GetQueryResponse

    // Golem P4 S4.2 — resolve a subject area to its package set + metadata.
    suspend fun resolveArea(area: String): ResolveAreaResponse
}

class GrpcMetadataGrpcClient(
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 10,
) : MetadataGrpcClient {
    private val channel: ManagedChannel =
        ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()

    private val stub = VelesServiceGrpcKt.VelesServiceCoroutineStub(channel)

    override suspend fun listRoles(pageSize: Int): ListRolesResponse =
        stub
            .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
            .listRoles(
                ListRolesRequest
                    .newBuilder()
                    .setPage(PageRequest.newBuilder().setPageSize(pageSize))
                    .build(),
            )

    override suspend fun getRolesForEntity(qname: QualifiedName): GetRolesForEntityResponse =
        stub
            .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
            .getRolesForEntity(
                GetRolesForEntityRequest
                    .newBuilder()
                    .setEntity(qname)
                    .build(),
            )

    override suspend fun getModel(
        packages: List<String>,
        locale: String,
        includeSearchHints: Boolean,
        includeRoles: Boolean,
        includeDrillMap: Boolean,
    ): GetModelResponse =
        stub
            .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
            .getModel(
                GetModelRequest
                    .newBuilder()
                    .addAllPackages(packages)
                    .setLocale(locale)
                    .setIncludeSearchHints(includeSearchHints)
                    .setIncludeRoles(includeRoles)
                    .setIncludeDrillMap(includeDrillMap)
                    .build(),
            )

    // Drain every page so callers never see a silently-truncated list. The server caps page
    // size at 1000; with no `package` filter the full model can exceed a single 100-item page.
    override suspend fun listObjects(
        kind: String,
        packageFilter: String,
    ): ListObjectsResponse {
        val merged = ListObjectsResponse.newBuilder()
        var token = ""
        do {
            val resp =
                stub
                    .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
                    .listObjects(
                        ListObjectsRequest
                            .newBuilder()
                            .setKind(kind)
                            .setPackage(packageFilter)
                            .setPage(PageRequest.newBuilder().setPageSize(PAGE_SIZE).setPageToken(token))
                            .build(),
                    )
            merged.addAllItems(resp.itemsList)
            token = resp.pageInfo.nextPageToken
        } while (token.isNotEmpty())
        return merged.build()
    }

    override suspend fun listQueries(
        kind: String,
        packageFilter: String,
    ): ListQueriesResponse {
        val merged = ListQueriesResponse.newBuilder()
        var token = ""
        do {
            val resp =
                stub
                    .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
                    .listQueries(
                        ListQueriesRequest
                            .newBuilder()
                            .setPackage(packageFilter)
                            .setPage(PageRequest.newBuilder().setPageSize(PAGE_SIZE).setPageToken(token))
                            .build(),
                    )
            merged.addAllItems(resp.itemsList)
            token = resp.pageInfo.nextPageToken
        } while (token.isNotEmpty())
        return merged.build()
    }

    override suspend fun getObject(qname: QualifiedName): GetObjectResponse =
        stub
            .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
            .getObject(
                GetObjectRequest
                    .newBuilder()
                    .setQualifiedName(qname)
                    .build(),
            )

    override suspend fun getQuery(qname: QualifiedName): GetQueryResponse =
        stub
            .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
            .getQuery(
                GetQueryRequest
                    .newBuilder()
                    .setQualifiedName(qname)
                    .build(),
            )

    // Golem P4 S4.2 — resolve a subject area to its package set. Single gRPC call.
    override suspend fun resolveArea(area: String): ResolveAreaResponse =
        stub
            .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
            .resolveArea(
                ResolveAreaRequest
                    .newBuilder()
                    .setArea(area)
                    .build(),
            )

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}
