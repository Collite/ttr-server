// SPDX-License-Identifier: Apache-2.0
package org.tatrman.grounding.mcp.client

import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingServiceGrpcKt
import org.tatrman.grounding.v1.ScanRequest
import org.tatrman.grounding.v1.ScanResponse
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import shared.logging.OutgoingCallLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * gRPC [GroundingClient] over a single grounding service. Transport failures propagate as exceptions
 * — the tool callback maps them to `isError = true` (AWAITING_CLARIFICATION / UNGROUNDABLE are normal
 * OK-transport outcomes, not MCP errors; contracts §2).
 */
class GroundingGrpcClient(
    override val serviceName: String,
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 20,
) : GroundingClient {
    private val channel: ManagedChannel =
        ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .intercept(OutgoingCallLoggingInterceptor())
            .build()

    private val stub = GroundingServiceGrpcKt.GroundingServiceCoroutineStub(channel)

    override suspend fun ground(request: GroundRequest): GroundResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).ground(request)

    override suspend fun scan(request: ScanRequest): ScanResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).scan(request)

    override fun close() {
        channel.shutdown()
    }
}
