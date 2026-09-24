// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llm.client

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.sdk.trace.SdkTracerProvider
import kotlinx.coroutines.withContext

/**
 * LC-P0·S0.1 — the per-call context on the wire (contracts §1, ⚑LC-2/LC-5).
 *
 * Asserted on the request the stand-in gateway RECEIVED, never on the client's own state: the
 * four headers exist iff a [LlmCallContext] is in the calling coroutine, blank fields leave no
 * header behind, and `traceparent` appears only when the client was built with `propagateTrace`.
 */
class LlmGatewayClientSpec :
    StringSpec({
        val wm = WireMockServer(WireMockConfiguration.options().dynamicPort())
        lateinit var client: LlmGatewayClient
        val headers = listOf("X-Turn-Ref", "X-Call-Purpose", "X-End-User-Subject", "X-Agent-Id")

        fun stubCompletion(callRef: String? = "4711") {
            val response =
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("X-Gateway-Provider", "azure")
                    .withHeader("X-Gateway-Model", "gpt-5-mini")
                    .withBody(GatewayBodies.COMPLETION)
            if (callRef != null) response.withHeader("X-Prompt-Log-Id", callRef)
            wm.stubFor(post(urlPathEqualTo("/v1/chat/completions")).willReturn(response))
        }

        beforeSpec {
            wm.start()
            client = LlmGatewayClient(LlmGatewayEndpoint("localhost", wm.port(), 5_000))
        }
        afterSpec {
            client.close()
            wm.stop()
        }
        beforeTest { wm.resetAll() }

        "a call inside an LlmCallContext carries the four attribution headers" {
            stubCompletion()
            withContext(
                LlmCallContext(
                    turnRef = "t1",
                    purpose = "compose-plan",
                    endUserSubject = "sub-1",
                    agentId = "golem-hartland",
                ),
            ) { client.completeWithMeta("hi") }.getOrThrow()

            wm.verify(
                postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                    .withHeader("X-Turn-Ref", equalTo("t1"))
                    .withHeader("X-Call-Purpose", equalTo("compose-plan"))
                    .withHeader("X-End-User-Subject", equalTo("sub-1"))
                    .withHeader("X-Agent-Id", equalTo("golem-hartland")),
            )
        }

        "a call with no context carries none of the four headers" {
            stubCompletion()
            client.completeWithMeta("hi").getOrThrow()
            val sent = postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
            headers.forEach { sent.withHeader(it, absent()) }
            wm.verify(sent)
        }

        "a blank field leaves its header out entirely — never an empty header" {
            stubCompletion()
            withContext(LlmCallContext(turnRef = "t1", purpose = "  ", endUserSubject = "", agentId = null)) {
                client.completeWithMeta("hi")
            }.getOrThrow()
            wm.verify(
                postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                    .withHeader("X-Turn-Ref", equalTo("t1"))
                    .withHeader("X-Call-Purpose", absent())
                    .withHeader("X-End-User-Subject", absent())
                    .withHeader("X-Agent-Id", absent()),
            )
        }

        "the old complete() carries the context too — the headers ride the coroutine, not the method" {
            stubCompletion()
            withContext(LlmCallContext(turnRef = "t2", purpose = "chip-topup")) { client.complete("hi") }.getOrThrow()
            wm.verify(
                postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                    .withHeader("X-Turn-Ref", equalTo("t2"))
                    .withHeader("X-Call-Purpose", equalTo("chip-topup")),
            )
        }

        "completeWithMeta reads the row id, the served route, usage and cost off the gateway's response" {
            stubCompletion(callRef = "4711")
            val c = client.completeWithMeta("hi", model = "fast").getOrThrow()

            c.content shouldBe "It is Q3."
            c.callRef shouldBe "4711"
            c.requestedModel shouldBe "fast"
            c.servedProvider shouldBe "azure" // X-Gateway-Provider
            c.servedModel shouldBe "gpt-5-mini" // X-Gateway-Model (the catalog upstream, not the body's dated id)
            c.cached shouldBe false
            c.tokensPrompt shouldBe 1834
            c.tokensCompletion shouldBe 212
            c.costUsd shouldBe 0.000883
            // Not on the wire today: the gateway records fallback_from on the ROW only. The reader
            // gets it from GET /v1/prompt-logs; the client does not pretend to know it.
            c.fallbackFrom.shouldBeNull()
            (c.durationMs!! >= 0) shouldBe true
        }

        "an older gateway (no X-Prompt-Log-Id) still returns the content, with callRef = null" {
            stubCompletion(callRef = null)
            val c = client.completeWithMeta("hi").getOrThrow()
            c.content shouldBe "It is Q3."
            c.callRef.shouldBeNull()
        }

        "a cache hit is reported as cached" {
            wm.stubFor(
                post(urlPathEqualTo("/v1/chat/completions")).willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Gateway-Cache", "hit")
                        .withHeader("X-Prompt-Log-Id", "4712")
                        .withBody(GatewayBodies.COMPLETION.replace("\"cached\":false", "\"cached\":true")),
                ),
            )
            client.completeWithMeta("hi").getOrThrow().cached shouldBe true
        }

        "complete() is completeWithMeta().map { content } — including the failure" {
            stubCompletion()
            client.complete("hi") shouldBe client.completeWithMeta("hi").map { it.content }
            val dead = LlmGatewayClient(LlmGatewayEndpoint("localhost", 1, 2_000))
            dead.completeWithMeta("hi").exceptionOrNull().shouldBeInstanceOf<LlmGatewayException>()
            dead.close()
        }

        // ── ⚑LC-5 — traceparent, opt-in ──────────────────────────────────────────────────────

        "propagateTrace = true writes the caller's span onto the wire as traceparent" {
            stubCompletion()
            val tracer = SdkTracerProvider.builder().build().get("llm-client-spec")
            val span = tracer.spanBuilder("compose-plan").startSpan()
            val tracing = LlmGatewayClient(LlmGatewayEndpoint("localhost", wm.port(), 5_000), propagateTrace = true)
            try {
                withContext(span.asContextElement()) { tracing.completeWithMeta("hi") }.getOrThrow()
            } finally {
                span.end()
                tracing.close()
            }
            // The trace id on the wire IS the caller's — a header that merely exists would pass a
            // plugin that opened a fresh trace (the otel-wiring lesson: test the wire, not the config).
            val traceId = span.spanContext.traceId
            val spanId = span.spanContext.spanId
            wm.verify(
                postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                    .withHeader("traceparent", matching("00-$traceId-$spanId-0[01]")),
            )
        }

        "propagateTrace defaults to false — no traceparent even inside a span" {
            stubCompletion()
            val span =
                SdkTracerProvider
                    .builder()
                    .build()
                    .get("llm-client-spec")
                    .spanBuilder("x")
                    .startSpan()
            try {
                withContext(span.asContextElement()) { client.completeWithMeta("hi") }.getOrThrow()
            } finally {
                span.end()
            }
            wm.verify(postRequestedFor(urlPathEqualTo("/v1/chat/completions")).withHeader("traceparent", absent()))
        }

        "propagateTrace = true outside any span writes no traceparent (nothing valid to propagate)" {
            stubCompletion()
            val tracing = LlmGatewayClient(LlmGatewayEndpoint("localhost", wm.port(), 5_000), propagateTrace = true)
            try {
                tracing.completeWithMeta("hi").getOrThrow()
            } finally {
                tracing.close()
            }
            wm.verify(postRequestedFor(urlPathEqualTo("/v1/chat/completions")).withHeader("traceparent", absent()))
        }

        // ── the Koog bridge's side channel ─────────────────────────────────────────────────────

        "LlmGatewayPromptExecutor hands every completion to onCompletion, in the caller's coroutine" {
            stubCompletion(callRef = "4711")
            val seen = mutableListOf<Pair<String?, String?>>()
            val executor =
                LlmGatewayPromptExecutor(
                    client,
                    onCompletion = { c ->
                        // suspend: the collector reads the CALLING turn off its coroutine context.
                        seen += c.callRef to kotlin.coroutines.coroutineContext[LlmCallContext]?.turnRef
                    },
                )
            val model = LLModel(provider = LLMProvider.Anthropic, id = "claude-sonnet")
            val reply =
                withContext(LlmCallContext(turnRef = "t9", purpose = "compose-plan")) {
                    executor.execute(prompt("p") { user("hi") }, model, emptyList())
                }

            reply.single().shouldBeInstanceOf<Message.Assistant>().content shouldBe "It is Q3."
            seen.shouldContainExactly("4711" to "t9")
            wm.verify(
                postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                    .withHeader("X-Turn-Ref", equalTo("t9"))
                    .withHeader("X-Call-Purpose", equalTo("compose-plan")),
            )
        }

        "a failed completion never reaches onCompletion and still throws out of execute (unchanged)" {
            var calls = 0
            val dead = LlmGatewayClient(LlmGatewayEndpoint("localhost", 1, 2_000))
            val executor = LlmGatewayPromptExecutor(dead, onCompletion = { calls++ })
            val r =
                runCatching {
                    executor.execute(
                        prompt("p") { user("hi") },
                        LLModel(provider = LLMProvider.Anthropic, id = "claude-haiku"),
                        emptyList(),
                    )
                }
            r.exceptionOrNull().shouldBeInstanceOf<LlmGatewayException>()
            calls shouldBe 0
            dead.close()
        }
    })
