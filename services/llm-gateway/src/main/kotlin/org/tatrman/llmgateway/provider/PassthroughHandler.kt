// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.plugins.timeout
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.tatrman.llmgateway.stream.SseFrame
import org.tatrman.llmgateway.stream.SseFramer
import org.tatrman.llmgateway.wire.ChatRequest
import org.tatrman.llmgateway.wire.GatewayError
import org.tatrman.llmgateway.wire.OpenAiWireErrorConverter
import org.tatrman.llmgateway.wire.parseRetryAfterMs

/**
 * Passthrough handler for OpenAI-wire upstreams (Azure / OpenAI / Gemini-compat, B-1/B-2). The request
 * is re-emitted with the model rewritten to the catalog `upstream` name (copy-with-patch, `model_tags`
 * stripped); the response is **not parsed for the client** — the caller receives the upstream body
 * bytes, with only a parse-lite read for `usage`/`finish_reason` (the invariant is untouched CLIENT
 * bytes, not that we never look). Azure adds the deployment/api-version URL rewrite via [UrlBuilder].
 */
class PassthroughHandler(
    private val client: HttpClient,
) : ProviderHandler {
    override suspend fun complete(
        req: ChatRequest,
        target: UpstreamTarget,
        key: Key,
    ): ProviderResult {
        // model → upstream, model_tags stripped, everything else byte-faithful (B-T2 + LG-D1)
        val upstreamBody = reasoningAware(req.withModel(target.upstream).toUpstreamJson(), target)
        return call(target, key, path = "chat/completions", body = upstreamBody)
    }

    override fun stream(
        req: ChatRequest,
        target: UpstreamTarget,
        key: Key,
    ): Flow<SseFrame> =
        channelFlow {
            // stream:true stays in the body; model → upstream, model_tags stripped (B-T2 + LG-D1).
            val upstreamBody = reasoningAware(req.withModel(target.upstream).toUpstreamJson(), target)
            client
                .preparePost(target.baseUrl + UrlBuilder.path(target, "chat/completions")) {
                    header(target.authHeader, target.authScheme?.let { "$it ${key.value}" } ?: key.value)
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Text.EventStream)
                    // A stream may outlive the non-stream wall-clock budget — the whole-request timeout must
                    // NOT apply (connect timeout still guards; TTFB/retry budgeting lands LG-P3).
                    timeout { requestTimeoutMillis = Long.MAX_VALUE }
                    setBody(upstreamBody.toString())
                }.execute { response ->
                    val status = response.status.value
                    if (status !in 200..299) {
                        // Before-first-token upstream error → typed throw so the LG-P3·S2 engine can retry/
                        // fallback and (if exhausted) commit a real HTTP status, since the SSE writer has not
                        // attached yet (the engine peeks the first frame before `respondBytesWriter`). This
                        // replaces the S2 interim (an in-band SSE error frame committed under a 200).
                        val errText = runCatching { response.bodyAsText() }.getOrDefault("")
                        val errBody = runCatching { Json.parseToJsonElement(errText).jsonObject }.getOrNull()
                        val retryAfter = parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis())
                        val error = OpenAiWireErrorConverter.convert(status, errBody)
                        throw UpstreamStreamException(
                            if (error is GatewayError.RateLimit &&
                                retryAfter != null
                            ) {
                                GatewayError.RateLimit(retryAfter)
                            } else {
                                error
                            },
                        )
                    }
                    // The execute block stays open for the whole collection (channelFlow structured concurrency).
                    SseFramer.frames(response.bodyAsChannel()).collect { send(it) }
                }
        }

    override suspend fun embed(
        rawBody: JsonObject,
        target: UpstreamTarget,
        key: Key,
    ): ProviderResult {
        val upstreamBody = JsonObject(rawBody.toMutableMap().apply { put("model", JsonPrimitive(target.upstream)) })
        return call(target, key, path = "embeddings", body = upstreamBody)
    }

    /**
     * Reasoning models (gpt-5 / o-series, `catalog.reasoning: true`) reject the sampling + legacy-token
     * params deterministic callers send: `temperature` (only the default 1 is allowed — a `0.0` 400s),
     * `top_p`, and `max_tokens` (must be `max_completion_tokens` — a `max_tokens` 400s). Normalize the
     * outbound body for those models so every caller (golem/themis/pythia) works unchanged.
     *
     * The mirror case, for a normal model: `reasoning_effort` is a reasoning-model param that a normal
     * model 400s on, so it is dropped — a caller configured for a reasoning model must not break when a
     * fallback chain lands on a normal one. Nothing else is touched (byte-faithful passthrough otherwise).
     */
    private fun reasoningAware(
        body: JsonObject,
        target: UpstreamTarget,
    ): JsonObject {
        if (!target.reasoning) {
            return if (REASONING_EFFORT in body) JsonObject(body - REASONING_EFFORT) else body
        }
        val m = body.toMutableMap()
        m.remove("temperature")
        m.remove("top_p")
        val maxTokens = m.remove("max_tokens")
        if (maxTokens != null && "max_completion_tokens" !in m) m["max_completion_tokens"] = maxTokens
        return JsonObject(m)
    }

    private suspend fun call(
        target: UpstreamTarget,
        key: Key,
        path: String,
        body: JsonObject,
    ): ProviderResult {
        val response =
            client.post(target.baseUrl + UrlBuilder.path(target, path)) {
                header(target.authHeader, target.authScheme?.let { "$it ${key.value}" } ?: key.value)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        val text = response.bodyAsText()
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        val retryAfter = parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis())
        return ProviderResult(
            response.status.value,
            json,
            parseLiteUsage(json),
            parseLiteFinish(json),
            retryAfterMs = retryAfter,
        )
    }

    private fun parseLiteUsage(body: JsonObject): UpstreamUsage? {
        val usage = body["usage"] as? JsonObject ?: return null
        val prompt = usage["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: return null
        val completion = usage["completion_tokens"]?.jsonPrimitive?.longOrNull ?: 0
        return UpstreamUsage(prompt, completion)
    }

    private fun parseLiteFinish(body: JsonObject): String? =
        (body["choices"] as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull()
            ?.jsonObject
            ?.get("finish_reason")
            ?.jsonPrimitive
            ?.contentOrNull
}

/** URL path templating shared by all OpenAI-wire flavors (Azure's deployment/api-version rewrite, B-T3-α). */
object UrlBuilder {
    fun path(
        target: UpstreamTarget,
        path: String,
    ): String =
        target.urlPattern
            .replace("{upstream}", target.upstream)
            .replace("{path}", path)
            .replace("{apiVersion}", target.apiVersion ?: "")
}

private const val REASONING_EFFORT = "reasoning_effort"
