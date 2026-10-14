// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import org.slf4j.LoggerFactory
import shared.otel.withSpan
import org.tatrman.resolver.pipeline.KernelPickException
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.token.ResumeTokenException
import org.tatrman.resolver.v1.GateRequest
import org.tatrman.resolver.v1.GateResponse
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.ResolverServiceGrpcKt

/**
 * The gRPC surface for the deterministic resolver core (RG-P5). Delegates to the
 * [ResolverPipeline] (parse → extractUniversal → proposeDomainSpans → gateSpans →
 * assemble). This class holds no logic beyond the wire boundary.
 *
 * INVARIANT (RS-23): ZERO LLM. This class — and the whole module — has no
 * llm-gateway client on the classpath; `NoLlmDependencyTest` asserts it.
 */
class ResolverGrpcService(
    private val pipeline: ResolverPipeline,
    // RV-P2.4.T5 — defaulted to the noop SDK so every existing construction site (and every test)
    // keeps working and pays nothing.
    //
    // ⚠ This comment used to end "; the service passes the real one." It did not. `Application.kt`
    // constructed `ResolverGrpcService(pipeline)` and the default won, so the `resolve.gate` span
    // documented below was never emitted from any cluster — for three months, while the comment
    // said otherwise. Fixed 2026-08-14 (TG-P0-F1); the wiring now lives in
    // `telemetry/ResolverTelemetry.kt`.
    //
    // ⚑ Note what is and is not guarded. `ResolverTelemetryTest` covers the factory — that the
    // flag is honoured and that an enabled SDK really is non-noop. **No test asserts that
    // `Application.kt` passes it**, because the argument is a default and dropping it compiles.
    // That is precisely how this survived. If you touch that construction site, check it by hand
    // — and prefer a comment that admits a gap to one that closes it in prose.
    openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
) : ResolverServiceGrpcKt.ResolverServiceCoroutineImplBase() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tracer: Tracer = openTelemetry.getTracer("org.tatrman.resolver")

    /**
     * TG-P0-F1 — a SERVER span, so the resolve hop is *visible* and not merely correlated.
     *
     * `Gate` has had one since RV-P2.4.T5 and `Resolve` never did, which mattered little while
     * neither was emitted at all. Now that the caller's context arrives (the
     * `OtelContextServerInterceptor` on the server), this span is what makes the turn's trace show
     * where its time went — without it a reader sees golem's CLIENT span, a gap, and then whatever
     * the resolver logged.
     */
    override suspend fun resolve(request: ResolveRequest): ResolveResponse =
        tracer.withSpan("resolve.resolve", kind = SpanKind.SERVER) {
            log.info("resolve conversation_id={}", request.conversationId)
            // A rejected/expired/mismatched resume token is a caller error, not a server
            // fault — map it to UNAUTHENTICATED carrying the RG-RES-002 reason instead of
            // letting the throw surface as an opaque UNKNOWN (RG-P6 review J).
            try {
                pipeline.resolve(request)
            } catch (e: ResumeTokenException) {
                log.info("resume token rejected conversation_id={}: {}", request.conversationId, e.reason)
                throw StatusException(Status.UNAUTHENTICATED.withDescription(e.message).withCause(e))
            } catch (e: KernelPickException) {
                // ttr-server#231 — a pick of a kernel's option the kernel did not ground. Not a caller
                // error and not an opaque UNKNOWN: UNAVAILABLE when the kernel could not be reached (a
                // retry may work), FAILED_PRECONDITION when it answered and will not ground it.
                log.warn("resume pick not grounded conversation_id={}: {}", request.conversationId, e.reason)
                val status = if (e.retryable) Status.UNAVAILABLE else Status.FAILED_PRECONDITION
                throw StatusException(status.withDescription(e.reason).withCause(e))
            }
        }

    /**
     * RV-P2.4 — the re-gate sibling (Q-13 = B). Stateless: the caller carries the lattice.
     *
     * T5 — one span per gate call, carrying hypotheses IN and bindings OUT. Those two numbers are
     * the whole health signal for the ladder: a rung whose hypotheses stop surviving the gate is a
     * rung that has started guessing, and it is invisible in a latency graph.
     */
    override suspend fun gate(request: GateRequest): GateResponse =
        tracer.withSpan(
            "resolve.gate",
            attributes = mapOf("rv.hypotheses.in" to request.hypothesesCount.toString()),
        ) {
            val response = pipeline.gate(request)
            log.info(
                "gate hypotheses_in={} bindings_out={} gaps_open={}",
                request.hypothesesCount,
                response.gatedBindingsCount,
                response.updatedGapsCount,
            )
            response
        }
}
