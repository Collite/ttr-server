// SPDX-License-Identifier: Apache-2.0
package org.tatrman.dispatch.registry

import org.tatrman.dispatch.v1.WorkerHealthStatus
import org.tatrman.worker.v1.ConnectionInfo
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.GetCapabilitiesResponse
import org.tatrman.worker.v1.ResultBatch
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.tatrman.dispatch.client.WorkerClient

class WorkerRegistrySpec :
    StringSpec({
        val ep = "worker-a:9000"
        val caps =
            GetCapabilitiesResponse
                .newBuilder()
                .setEngineName("mssql")
                .addSupportedConnections("hartland-default")
                .build()

        "lookupForConnection returns only HEALTHY workers" {
            val r = WorkerRegistry()
            r.seed(listOf(seedEntry(ep)))
            r.lookupForConnection("hartland-default").size shouldBe 0
            r.recordSuccess(ep, caps)
            r.lookupForConnection("hartland-default").size shouldBe 1
            r.lookupForConnection("nope").size shouldBe 0
        }

        "DEGRADED transition after configured failures" {
            val r = WorkerRegistry(degradedAfter = 2, unreachableAfter = 5)
            r.seed(listOf(seedEntry(ep)))
            r.recordSuccess(ep, caps)
            r.recordFailure(ep, RuntimeException("down"))
            r.all().single().health shouldBe WorkerHealthStatus.HEALTHY
            r.recordFailure(ep, RuntimeException("down"))
            r.all().single().health shouldBe WorkerHealthStatus.DEGRADED
        }

        "UNREACHABLE transition triggers callback" {
            val r = WorkerRegistry(degradedAfter = 2, unreachableAfter = 3)
            r.seed(listOf(seedEntry(ep)))
            r.recordSuccess(ep, caps)
            var fired = false
            r.onUnreachable(ep) { fired = true }
            repeat(3) { r.recordFailure(ep, RuntimeException("down")) }
            r.all().single().health shouldBe WorkerHealthStatus.UNREACHABLE
            fired shouldBe true
        }

        "recovery resets consecutiveFailures and brings health back to HEALTHY" {
            val r = WorkerRegistry(degradedAfter = 2, unreachableAfter = 5)
            r.seed(listOf(seedEntry(ep)))
            repeat(3) { r.recordFailure(ep, RuntimeException("down")) }
            r.all().single().health shouldBe WorkerHealthStatus.DEGRADED
            r.recordSuccess(ep, caps)
            val entry = r.all().single()
            entry.health shouldBe WorkerHealthStatus.HEALTHY
            entry.consecutiveFailures shouldBe 0
        }

        "WorkerEntry.connectionInfo resolves the advertised database/schema by connection_id (issue #57 Phase C)" {
            val richCaps =
                GetCapabilitiesResponse
                    .newBuilder()
                    .setEngineName("mssql")
                    .addSupportedConnections("hartland-default")
                    .addConnections(
                        ConnectionInfo
                            .newBuilder()
                            .setConnectionId("hartland-default")
                            .setDatabase("tatrman")
                            .setDefaultSchema("dbo")
                            .build(),
                    ).build()
            val entry =
                WorkerEntry(
                    endpoint = ep,
                    roleHint = "mssql",
                    client = NoopClient(ep),
                    capabilities = richCaps,
                    health = WorkerHealthStatus.HEALTHY,
                    lastPolled = null,
                    consecutiveFailures = 0,
                )
            val info = entry.connectionInfo("hartland-default")
            info?.database shouldBe "tatrman"
            info?.defaultSchema shouldBe "dbo"
            entry.connectionInfo("unknown") shouldBe null
        }

        "WorkerEntry.connectionInfo returns null for legacy (pre-Phase-B) workers" {
            val legacyCaps =
                GetCapabilitiesResponse
                    .newBuilder()
                    .setEngineName("mssql")
                    .addSupportedConnections("hartland-default")
                    // No connections[] entries — represents a pre-Phase-B worker.
                    .build()
            val entry =
                WorkerEntry(
                    endpoint = ep,
                    roleHint = "mssql",
                    client = NoopClient(ep),
                    capabilities = legacyCaps,
                    health = WorkerHealthStatus.HEALTHY,
                    lastPolled = null,
                    consecutiveFailures = 0,
                )
            entry.connectionInfo("hartland-default") shouldBe null
        }
    })

private fun seedEntry(endpoint: String): WorkerEntry =
    WorkerEntry(
        endpoint = endpoint,
        roleHint = "mssql",
        client = NoopClient(endpoint),
        capabilities = null,
        health = WorkerHealthStatus.WORKER_HEALTH_STATUS_UNSPECIFIED,
        lastPolled = null,
        consecutiveFailures = 0,
    )

private class NoopClient(
    @Suppress("unused") val endpoint: String,
) : WorkerClient {
    override suspend fun getCapabilities(): GetCapabilitiesResponse = GetCapabilitiesResponse.getDefaultInstance()

    override fun execute(request: ExecuteRequest): Flow<ResultBatch> = emptyFlow()

    override fun close() = Unit
}
