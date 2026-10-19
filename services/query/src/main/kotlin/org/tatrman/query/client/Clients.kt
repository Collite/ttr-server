// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.client

import org.tatrman.dispatch.v1.DispatchRequest
import org.tatrman.dispatch.v1.DispatchServiceGrpcKt
import org.tatrman.meta.v1.GetQueryRequest
import org.tatrman.meta.v1.GetQueryResponse
import org.tatrman.meta.v1.VelesServiceGrpcKt
import org.tatrman.translate.v1.DetectSchemaRequest
import org.tatrman.translate.v1.DetectSchemaResponse
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.translate.v1.ParseResponse
import org.tatrman.translate.v1.TranslateRequest
import org.tatrman.translate.v1.TranslateResponse
import org.tatrman.translate.v1.TranslateServiceGrpcKt
import org.tatrman.validate.v1.ValidateRequest
import org.tatrman.validate.v1.ValidateResponse
import org.tatrman.validate.v1.ValidateServiceGrpcKt
import org.tatrman.worker.v1.ResultBatch
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/**
 * Internal client interfaces. Each one's a `fun interface` to keep tests
 * trivial — the production `Grpc*Client` impls live below.
 */

fun interface TranslatorClient {
    suspend fun parse(request: ParseRequest): ParseResponse
}

fun interface TranslatorDetectClient {
    suspend fun detect(request: DetectSchemaRequest): DetectSchemaResponse
}

fun interface TranslatorTranslateClient {
    suspend fun translate(request: TranslateRequest): TranslateResponse
}

fun interface ValidatorClient {
    suspend fun validate(request: ValidateRequest): ValidateResponse
}

fun interface DispatcherClient {
    fun dispatch(request: DispatchRequest): Flow<ResultBatch>
}

/** LQ (L-31) — the metadata service's `GetQuery`: a saved query's parse state and stored plan, for RunSavedQuery. */
fun interface SavedQueryClient {
    suspend fun getQuery(request: GetQueryRequest): GetQueryResponse
}

class GrpcTranslatorClient(
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 30,
) : TranslatorClient,
    TranslatorDetectClient,
    TranslatorTranslateClient,
    AutoCloseable {
    private val channel: ManagedChannel = openChannel(host, port)
    private val stub = TranslateServiceGrpcKt.TranslateServiceCoroutineStub(channel)

    override suspend fun parse(request: ParseRequest): ParseResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).parseToRelNode(request)

    override suspend fun detect(request: DetectSchemaRequest): DetectSchemaResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).detectSourceSchema(request)

    override suspend fun translate(request: TranslateRequest): TranslateResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).translate(request)

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}

class GrpcValidatorClient(
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 30,
) : ValidatorClient,
    AutoCloseable {
    private val channel: ManagedChannel = openChannel(host, port)
    private val stub = ValidateServiceGrpcKt.ValidateServiceCoroutineStub(channel)

    override suspend fun validate(request: ValidateRequest): ValidateResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).validate(request)

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}

class GrpcDispatcherClient(
    host: String,
    port: Int,
) : DispatcherClient,
    AutoCloseable {
    private val channel: ManagedChannel = openChannel(host, port)
    private val stub = DispatchServiceGrpcKt.DispatchServiceCoroutineStub(channel)

    override fun dispatch(request: DispatchRequest): Flow<ResultBatch> = stub.dispatch(request)

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}

class GrpcSavedQueryClient(
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 10,
) : SavedQueryClient,
    AutoCloseable {
    private val channel: ManagedChannel = openChannel(host, port)
    private val stub = VelesServiceGrpcKt.VelesServiceCoroutineStub(channel)

    override suspend fun getQuery(request: GetQueryRequest): GetQueryResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).getQuery(request)

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}

private fun openChannel(
    host: String,
    port: Int,
): ManagedChannel =
    ManagedChannelBuilder
        .forAddress(host, port)
        .usePlaintext()
        .keepAliveTime(30, TimeUnit.SECONDS)
        .keepAliveTimeout(10, TimeUnit.SECONDS)
        .keepAliveWithoutCalls(true)
        .build()
