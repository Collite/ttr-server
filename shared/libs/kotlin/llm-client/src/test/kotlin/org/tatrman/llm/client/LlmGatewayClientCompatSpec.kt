// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llm.client

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * LC-P0·S0.1 — `complete()` pinned as it behaved BEFORE LC, so the additive surface (the call
 * context, `completeWithMeta`, `propagateTrace`) is proven not to have moved it. Written and run
 * green against the pre-LC client first; nothing in this file may change to make LC pass.
 *
 * The module had no specs at all before LC (baseline `test NO-SOURCE`), so "the old spec count
 * unchanged" is vacuous — this file is the baseline instead.
 */
class LlmGatewayClientCompatSpec :
    StringSpec({
        val wm = WireMockServer(WireMockConfiguration.options().dynamicPort())
        lateinit var client: LlmGatewayClient

        beforeSpec {
            wm.start()
            client = LlmGatewayClient(LlmGatewayEndpoint("localhost", wm.port(), 5_000, apiKey = "ttrk-test"))
        }
        afterSpec {
            client.close()
            wm.stop()
        }
        beforeTest { wm.resetAll() }

        "complete() returns the first choice's content and posts the OpenAI-shaped body with the bearer" {
            wm.stubFor(
                post(urlPathEqualTo("/v1/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(GatewayBodies.COMPLETION),
                    ),
            )

            client.complete("hi", systemPrompt = "be brief", model = "fast", temperature = 0.2, maxTokens = 64) shouldBe
                Result.success("It is Q3.")

            wm.verify(
                postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                    .withHeader("Authorization", equalTo("Bearer ttrk-test"))
                    .withRequestBody(matchingJsonPath("$.model", equalTo("fast")))
                    .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                    .withRequestBody(matchingJsonPath("$.messages[1].content", equalTo("hi")))
                    .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("64"))),
            )
        }

        "complete() with no systemPrompt sends the user message alone" {
            wm.stubFor(
                post(urlPathEqualTo("/v1/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(GatewayBodies.COMPLETION),
                    ),
            )
            client.complete("hi").getOrThrow() shouldBe "It is Q3."
            wm.verify(
                postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                    .withRequestBody(matchingJsonPath("$.messages.length()", equalTo("1"))),
            )
        }

        // ⚑ Characterisation, not endorsement. The client runs Ktor's default `expectSuccess = false`
        // and decodes the body with `ignoreUnknownKeys`, so the gateway's OpenAI error envelope
        // decodes into a response with no choices — and `complete()` answers SUCCESS with an empty
        // string. Pinned because LC must not change it; recorded in the LC notes as a follow-up.
        "an HTTP error carrying the gateway's JSON error envelope comes back as success(\"\") — the pre-LC behaviour" {
            wm.stubFor(
                post(urlPathEqualTo("/v1/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(503)
                            .withHeader("Content-Type", "application/json")
                            .withBody(GatewayBodies.ERROR_ENVELOPE),
                    ),
            )
            client.complete("hi") shouldBe Result.success("")
        }

        "an unreachable gateway is a Result.failure(LlmGatewayException), never a throw" {
            val dead = LlmGatewayClient(LlmGatewayEndpoint("localhost", 1, 2_000))
            val r = dead.complete("hi")
            r.isFailure shouldBe true
            r.exceptionOrNull().shouldBeInstanceOf<LlmGatewayException>()
            dead.close()
        }

        "a non-JSON error body is a Result.failure (the body cannot be decoded)" {
            wm.stubFor(
                post(urlPathEqualTo("/v1/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(502)
                            .withHeader("Content-Type", "text/html")
                            .withBody("<html>bad gateway</html>"),
                    ),
            )
            client.complete("hi").exceptionOrNull().shouldBeInstanceOf<LlmGatewayException>()
        }
    })

/**
 * Response bodies in the shape the gateway emits TODAY — `ResponseEnrichment.chat` (upstream body +
 * the §1.3 usage extension: dual names, `cost`, `estimated`; top-level `cached`) and
 * `openAiErrorBody` — not a tidier invention of them.
 */
internal object GatewayBodies {
    val COMPLETION =
        """
        {"id":"chatcmpl-CJ9x","object":"chat.completion","created":1758700000,"model":"gpt-5-mini-2025-08-07",
         "choices":[{"index":0,"message":{"role":"assistant","content":"It is Q3.","refusal":null,"annotations":[]},
                     "finish_reason":"stop"}],
         "usage":{"prompt_tokens":1834,"completion_tokens":212,"total_tokens":2046,
                  "prompt_tokens_details":{"cached_tokens":0,"audio_tokens":0},
                  "input_tokens":1834,"output_tokens":212,"cost":0.000883,"estimated":false},
         "system_fingerprint":null,"cached":false}
        """.trimIndent()

    val ERROR_ENVELOPE =
        """{"error":{"message":"all providers exhausted","type":"server_error","param":null,"code":"upstream_unavailable"}}"""
}
