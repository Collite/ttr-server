// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.client

import io.grpc.Server
import io.grpc.ServerBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.meta.v1.GetSnapshotRequest
import org.tatrman.meta.v1.GetSnapshotResponse
import org.tatrman.meta.v1.GetStatusRequest
import org.tatrman.meta.v1.GetStatusResponse
import org.tatrman.meta.v1.VelesServiceGrpcKt
import java.util.concurrent.atomic.AtomicInteger

/**
 * GH #112 — the version `validate` checks a plan against is `GetStatus.model_version`, not the
 * GetSnapshot ETag.
 *
 * The ETag is a cache validator: while Veles parses queries it is `<version>.q<n>`, so reading it
 * as the version would report every plan as compiled against a different model. The old read was
 * also an unconditional GetSnapshot — the whole model over the wire per Validate for one string.
 */
class GrpcMetadataClientVersionSpec :
    StringSpec({

        val snapshotCalls = AtomicInteger()
        val veles =
            object : VelesServiceGrpcKt.VelesServiceCoroutineImplBase() {
                override suspend fun getStatus(request: GetStatusRequest): GetStatusResponse =
                    GetStatusResponse
                        .newBuilder()
                        .setModelLoaded(true)
                        .setModelVersion("v7")
                        .build()

                override suspend fun getSnapshot(request: GetSnapshotRequest): GetSnapshotResponse {
                    snapshotCalls.incrementAndGet()
                    return GetSnapshotResponse.newBuilder().setEtag("v7.q3").build()
                }
            }
        lateinit var server: Server

        beforeSpec {
            server =
                ServerBuilder
                    .forPort(0)
                    .addService(veles)
                    .build()
                    .start()
        }
        afterSpec { server.shutdownNow() }

        "currentVersion is the model version from GetStatus, and fetches no snapshot" {
            GrpcMetadataClient("localhost", server.port).use { client ->
                client.currentVersion() shouldBe "v7"
            }
            snapshotCalls.get() shouldBe 0
        }
    })
