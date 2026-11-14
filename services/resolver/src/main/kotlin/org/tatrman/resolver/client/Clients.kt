// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.client

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyServiceGrpcKt
import org.tatrman.fuzzy.v1.FuzzyStatusRequest
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.LookupResponse
import org.tatrman.grounding.v1.GetStatusRequest as GroundingStatusRequest
import org.tatrman.grounding.v1.GetStatusResponse as GroundingStatusResponse
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingContext
import org.tatrman.grounding.v1.GroundingServiceGrpcKt
import org.tatrman.grounding.v1.ScanRequest
import org.tatrman.grounding.v1.ScanResponse
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NlpServiceGrpcKt
import org.tatrman.nlp.v1.StatusRequest
import org.tatrman.nlp.v1.StatusResponse
import shared.logging.OutgoingCallLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * The upstream contracts the deterministic core consumes. Interfaces (not concrete stubs) so
 * the pipeline is testable with fakes — the Q-20 corpora feed the pipeline through these seams
 * without a live nlp/fuzzy. NONE is an LLM; NoLlmDependencyTest guards the module boundary.
 *
 * ⚑ **There are three now, and that is a deliberate change** (R1, ruled 2026-08-28). This KDoc
 * used to say "the only upstreams are nlp and lex-matcher" — an invariant worth updating
 * explicitly rather than quietly outgrowing. [GroundingClient] is the third, and it is the one
 * that carries the interesting caveat: a grounding kernel MAY be configured with an LLM
 * fallback of its own. The non-LLM guarantee this module makes is therefore about the resolver's
 * own dependencies, which `NoLlmDependencyTest` still checks; it is not a claim about what a
 * kernel does behind its wire. Estates that need the stronger guarantee run their kernels with
 * the fallback off (hartland does: `CHRONO_LLM_FALLBACK_ENABLED=false`), and that is a
 * DEPLOYMENT fact, not a code one — so it is stated here rather than asserted in a test that
 * could not see it anyway.
 */
interface NlpClient {
    suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse

    /** RS-7 capability matrix — read once at resolve time (branch points in S2). */
    suspend fun getStatus(): StatusResponse
}

interface FuzzyClient {
    /** The one BatchMatch per resolve (B-T1). */
    suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse

    /**
     * RV-P2.3 — one narrowing question (contracts §1 addendum, FROZEN at P1.4 T5).
     *
     * Deliberately NOT a batch, and the difference from [batchMatch] is the whole point of the
     * rung: `BatchMatch` asks the broad pass's question about every span at once and can express
     * nothing but term+categories+limit, while a lookup round asks a narrow question the planner
     * chose — scoped by target class, optionally overriding the authored method — about one span
     * it decided was worth asking about again.
     *
     * Defaulted to "no vocabulary answered" so a test double that never exercises rounds does not
     * have to implement it. [GrpcFuzzyClient] always overrides; a fake that returns this default
     * simply makes the loop a no-op, which is exactly what an estate with nothing to narrow does.
     */
    suspend fun lookup(request: LookupRequest): LookupResponse = LookupResponse.getDefaultInstance()

    /** Category discovery + staleness (S2: registry snapshot echo). */
    suspend fun getStatus(): FuzzyStatusResponse
}

/**
 * A deterministic grounding kernel (chrono | money | geo — they share one contract).
 *
 * The resolver asks exactly one question of it: *"what interval/point/amount is this span, and
 * which column would restrict by it?"* — [org.tatrman.grounding.v1.FilterRecipe.getAnchorColumn]
 * is the half the lattice cannot obtain any other way, because it is discovered from `meta.v1`
 * semantic roles and the resolver has no metadata client.
 *
 * Optional by construction: absent grounding, the lattice is exactly what it was before, which
 * is why the whole rung is off by default (`resolver.grounding.enabled`).
 */
interface GroundingClient {
    suspend fun scan(
        text: String,
        context: GroundingContext,
        pkg: String,
    ): ScanResponse

    suspend fun ground(request: GroundRequest): GroundResponse

    suspend fun getStatus(): GroundingStatusResponse
}

class GrpcGroundingClient(
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 10,
) : GroundingClient,
    AutoCloseable {
    private val channel: ManagedChannel = openChannel(host, port)
    private val stub = GroundingServiceGrpcKt.GroundingServiceCoroutineStub(channel)

    override suspend fun scan(
        text: String,
        context: GroundingContext,
        pkg: String,
    ): ScanResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).scan(
            ScanRequest
                .newBuilder()
                .setText(text)
                .setContext(context)
                .setPackage(pkg)
                .build(),
        )

    override suspend fun ground(request: GroundRequest): GroundResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).ground(request)

    override suspend fun getStatus(): GroundingStatusResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).getStatus(GroundingStatusRequest.getDefaultInstance())

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}

class GrpcNlpClient(
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 30,
) : NlpClient,
    AutoCloseable {
    private val channel: ManagedChannel = openChannel(host, port)
    private val stub = NlpServiceGrpcKt.NlpServiceCoroutineStub(channel)

    override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).analyze(request)

    override suspend fun getStatus(): StatusResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).getStatus(StatusRequest.getDefaultInstance())

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}

class GrpcFuzzyClient(
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 30,
) : FuzzyClient,
    AutoCloseable {
    private val channel: ManagedChannel = openChannel(host, port)
    private val stub = FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub(channel)

    override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).batchMatch(request)

    override suspend fun lookup(request: LookupRequest): LookupResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).lookup(request)

    override suspend fun getStatus(): FuzzyStatusResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).getStatus(FuzzyStatusRequest.getDefaultInstance())

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
        // TG-P0-F3 (2026-08-14) — log what the core asks nlp and lex-matcher, and what they
        // answer. DEBUG-only (`LOG_LEVEL=DEBUG`), payloads redacted and capped by the interceptor.
        //
        // ⚑ Why it matters more here than anywhere else it is already used (validate, chrono,
        // translate, dispatch, geo): the resolver's own gRPC surface logs the LATTICE it produced,
        // but a lattice with no operator bound is ambiguous — it does not distinguish "lex-matcher
        // returned nothing" from "it returned a match the core discarded". D2 sat on exactly that
        // ambiguity for a day. This one line separates the two.
        .intercept(OutgoingCallLoggingInterceptor())
        .keepAliveTime(30, TimeUnit.SECONDS)
        .keepAliveTimeout(10, TimeUnit.SECONDS)
        .keepAliveWithoutCalls(true)
        .build()
