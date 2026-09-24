// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llm.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** Where the LLM gateway lives (host/port + request timeout). Lib-local so the
 *  client doesn't couple to any one agent's config type. */
data class LlmGatewayEndpoint(
    val host: String,
    val port: Int,
    val timeoutMs: Long,
    // Gateway 2.0 requires a per-consumer `Bearer ttrk-…` key on every route (KeyValidator.requireKey).
    // Optional + null-default so existing callers are unaffected; when set, `complete` sends it.
    val apiKey: String? = null,
)

/**
 * Client for the LLM gateway LLM gateway's OpenAI-shaped `/v1/chat/completions`
 * (LLM gateway's ChatController serves this as an alias of `/api/v1/chat/completions`).
 * Shared across the constellation (Themis nodes, Golem's PlanComposer). `model`
 * is a flat tier key (`"haiku"` CHEAP / `"sonnet"` FAST / `"opus"`), mapped to a
 * LLM gateway tag downstream. Failures return a [Result.failure] — callers decide
 * fallback (never throws out of [complete]). A failure is an unreachable gateway, an undecodable
 * body, or ANY non-2xx answer: the gateway's error envelope is never read as an empty completion
 * (review-100 F2, ruled 2026-09-24 — this changed `complete()` too).
 *
 * **Attribution (LC).** A call made inside an [LlmCallContext] carries that context as request
 * headers (`X-Turn-Ref` · `X-Call-Purpose` · `X-End-User-Subject` · `X-Agent-Id`), whichever method
 * is used; [completeWithMeta] additionally returns what the gateway said about the call — above all
 * the prompt-log row id ([LlmCompletion.callRef]). With [propagateTrace] (⚑LC-5, opt-in) the caller's
 * current OTel span is written as a W3C `traceparent`, so the gateway's spans join the caller's trace.
 */
class LlmGatewayClient(
    private val endpoint: LlmGatewayEndpoint,
    private val propagateTrace: Boolean = false,
) {
    private val logger = LoggerFactory.getLogger(LlmGatewayClient::class.java)

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val httpClient =
        HttpClient(ClientCIO) {
            install(ContentNegotiation) {
                json(this@LlmGatewayClient.json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = endpoint.timeoutMs
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = endpoint.timeoutMs
            }
        }

    suspend fun complete(
        prompt: String,
        systemPrompt: String = "",
        model: String = "sonnet",
        temperature: Double = 0.0,
        maxTokens: Int = 2000,
    ): Result<String> = completeWithMeta(prompt, systemPrompt, model, temperature, maxTokens).map { it.content }

    /**
     * [complete], plus what the gateway said about the call — the prompt-log row id, the served
     * route, usage and cost (see [LlmCompletion] for where each field is read). Same request, same
     * failure contract: a [Result.failure] carrying [LlmGatewayException], never a throw.
     */
    suspend fun completeWithMeta(
        prompt: String,
        systemPrompt: String = "",
        model: String = "sonnet",
        temperature: Double = 0.0,
        maxTokens: Int = 2000,
    ): Result<LlmCompletion> =
        try {
            val request =
                ChatCompletionRequest(
                    model = model,
                    messages =
                        buildList {
                            if (systemPrompt.isNotBlank()) {
                                add(ChatMessage(role = "system", content = systemPrompt))
                            }
                            add(ChatMessage(role = "user", content = prompt))
                        },
                    temperature = temperature,
                    maxTokens = maxTokens,
                )
            val attribution =
                currentCoroutineContext()[LlmCallContext]?.headers { rejected ->
                    logger.warn(
                        "attribution header {} not sent — its value is not printable ASCII; the call goes on without it",
                        rejected,
                    )
                }

            val startedNs = System.nanoTime()
            val httpResponse: HttpResponse =
                httpClient
                    .post("http://${endpoint.host}:${endpoint.port}/v1/chat/completions") {
                        contentType(ContentType.Application.Json)
                        endpoint.apiKey?.takeIf { it.isNotBlank() }?.let {
                            header(HttpHeaders.Authorization, "Bearer $it")
                        }
                        // Belt and braces behind headers()' own filter: a header the HTTP client still
                        // refuses is dropped, never allowed to fail the call it only labels.
                        attribution?.forEach { (name, value) ->
                            runCatching { header(name, value) }
                                .onFailure {
                                    logger.warn(
                                        "attribution header {} refused by the HTTP client — not sent",
                                        name,
                                    )
                                }
                        }
                        if (propagateTrace) injectTraceParent(this)
                        setBody(request)
                    }
            val durationMs = (System.nanoTime() - startedNs) / 1_000_000

            if (!httpResponse.status.isSuccess()) {
                // A gateway error is NOT a completion (review-100 F2). Before LC this decoded the OpenAI
                // error envelope as a response with no choices and answered success("") — so a budget
                // 429 or an exhausted chain read as an empty reply, and (from LC on) as a call made.
                val detail =
                    runCatching {
                        json
                            .decodeFromString(
                                GatewayErrorEnvelope.serializer(),
                                httpResponse.bodyAsText(),
                            ).error
                            ?.message
                    }.getOrNull()
                val message =
                    "LLM gateway answered ${httpResponse.status.value}" +
                        (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                logger.warn(message)
                Result.failure(LlmGatewayException(message, httpStatus = httpResponse.status.value))
            } else {
                val response: ChatCompletionResponse = httpResponse.body()
                val content =
                    response.choices
                        ?.firstOrNull()
                        ?.message
                        ?.content
                        ?: ""
                logger.debug("LLM response: {}", content.take(200))
                Result.success(
                    LlmCompletion(
                        content = content,
                        callRef = httpResponse.headers[LlmCompletion.CALL_REF_HEADER]?.takeIf { it.isNotBlank() },
                        requestedModel = model,
                        servedModel = httpResponse.headers[SERVED_MODEL_HEADER],
                        servedProvider = httpResponse.headers[SERVED_PROVIDER_HEADER],
                        fallbackFrom = null, // on the prompt-log row only — see LlmCompletion
                        cached = response.cached ?: false,
                        tokensPrompt = response.usage?.promptTokens,
                        tokensCompletion = response.usage?.completionTokens,
                        costUsd = response.usage?.cost,
                        durationMs = durationMs,
                    ),
                )
            }
        } catch (e: Exception) {
            logger.error("Error calling LLM gateway: {}", e.message, e)
            Result.failure(LlmGatewayException("LLM gateway unavailable: ${e.message}"))
        }

    /**
     * ⚑LC-5 — the caller's current span as W3C `traceparent`, straight from [Context.current] through
     * the W3C propagator, so the client needs no `OpenTelemetry` instance (it has none to ask).
     * Outside a valid span the propagator writes nothing.
     *
     * [Context.current] is a THREAD-LOCAL. Inside a coroutine it is the caller's span only when the
     * caller carries that span as a coroutine context element (`span.asContextElement()`, or a
     * `Context` element) — a span made current with `makeCurrent()` does not survive a suspension,
     * and the header would name whatever the resuming thread holds.
     */
    private fun injectTraceParent(builder: HttpRequestBuilder) {
        W3CTraceContextPropagator.getInstance().inject(Context.current(), builder, HeaderSetter)
    }

    private object HeaderSetter : TextMapSetter<HttpRequestBuilder> {
        override fun set(
            carrier: HttpRequestBuilder?,
            key: String,
            value: String,
        ) {
            carrier?.headers?.set(key, value)
        }
    }

    fun close() {
        httpClient.close()
    }
}

private const val SERVED_PROVIDER_HEADER = "X-Gateway-Provider"
private const val SERVED_MODEL_HEADER = "X-Gateway-Model"

class LlmGatewayException
    @JvmOverloads
    constructor(
        message: String,
        /** The gateway's HTTP status when it answered with an error; null when it could not be reached. */
        val httpStatus: Int? = null,
    ) : Exception(message)

/** The gateway's OpenAI-shaped error body (`{"error":{"message":…,"type":…,"code":…}}`). */
@Serializable
internal data class GatewayErrorEnvelope(
    val error: GatewayErrorBody? = null,
)

@Serializable
internal data class GatewayErrorBody(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.0,
    @SerialName("max_tokens")
    val maxTokens: Int = 2000,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val `object`: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<Choice>? = null,
    val usage: Usage? = null,
    // The gateway's top-level cache flag (contracts §1.3); absent from a plain OpenAI upstream.
    val cached: Boolean? = null,
)

@Serializable
data class Choice(
    val index: Int? = null,
    val message: ChatMessage? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null,
    // The gateway's §1.3 usage extension — USD for this call (the saved cost, echoed, on a cache hit).
    val cost: Double? = null,
)
