// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llm.client

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Who a gateway call belongs to — carried as a **coroutine context element**, so the code that
 * knows the turn (an agent's graph) sets it once and every gateway call made underneath it is
 * attributed, with no call site naming a header (LC contracts §1, ⚑LC-6).
 *
 * Why an element and not a parameter: Koog's `PromptExecutor.execute(prompt, model, tools)` is not
 * ours to widen, and [LlmGatewayPromptExecutor] sits beneath it. [LlmGatewayClient] reads the element
 * off the calling coroutine and maps each non-blank field to one request header; a blank or null field
 * sends NO header (never an empty one). The gateway stores all four on its prompt-log row.
 *
 * All fields are optional: a caller that sets none behaves exactly as before LC.
 */
data class LlmCallContext(
    /** → `X-Turn-Ref` — the turn the call serves; the gateway's row key for the protocol read (PT A-7). */
    val turnRef: String? = null,
    /** → `X-Call-Purpose` — the node/step that made the call (⚑LC-2), e.g. `compose-plan`. */
    val purpose: String? = null,
    /** → `X-End-User-Subject` — the human the turn is for; per-row read authz keys on it (⚑LC-1). */
    val endUserSubject: String? = null,
    /** → `X-Agent-Id` — the agent pod that made the call; each delegated pod sets its own (⚑LC-4). */
    val agentId: String? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LlmCallContext> {
        const val TURN_REF_HEADER: String = "X-Turn-Ref"
        const val PURPOSE_HEADER: String = "X-Call-Purpose"
        const val END_USER_SUBJECT_HEADER: String = "X-End-User-Subject"
        const val AGENT_ID_HEADER: String = "X-Agent-Id"

        /** The gateway's `CallAttribution` cap — a longer value would be cut there anyway. */
        const val MAX_HEADER_VALUE_LENGTH: Int = 512
    }

    /**
     * The request headers this context maps to, in a stable order. Attribution must never fail the
     * call it attributes (review-100 F9): a field is trimmed and capped at [MAX_HEADER_VALUE_LENGTH];
     * a blank one sends no header; one carrying anything but printable ASCII (a CR/LF from a
     * caller-supplied turn id, a non-ASCII name) sends no header either — the HTTP client would refuse
     * it and fail the call — and is reported to [onRejected] by header name.
     */
    fun headers(onRejected: (header: String) -> Unit = {}): Map<String, String> =
        buildMap {
            fun add(
                header: String,
                raw: String?,
            ) {
                val value = raw?.trim()?.take(MAX_HEADER_VALUE_LENGTH)
                when {
                    value.isNullOrEmpty() -> Unit
                    value.all { it in ' '..'~' } -> put(header, value)
                    else -> onRejected(header)
                }
            }
            add(TURN_REF_HEADER, turnRef)
            add(PURPOSE_HEADER, purpose)
            add(END_USER_SUBJECT_HEADER, endUserSubject)
            add(AGENT_ID_HEADER, agentId)
        }
}

/**
 * One completion with what the gateway said about it (LC contracts §1). Read from the response the
 * gateway sends TODAY — its headers and its enriched body — never inferred:
 *
 *  - [callRef] ← `X-Prompt-Log-Id`, the id of the prompt-log row the gateway will write. It is
 *    allocated before the gateway's async writer enqueues the row, so it is true even when that row
 *    is later dropped — which is exactly what makes it useful to a reader (*"golem reported n calls;
 *    the gateway holds m"*). `null` from a gateway older than LC.
 *  - [servedProvider] / [servedModel] ← `X-Gateway-Provider` / `X-Gateway-Model`.
 *  - [cached] ← the body's top-level `cached`; [tokensPrompt] / [tokensCompletion] / [costUsd] ←
 *    its `usage` block (`cost` is the gateway's §1.3 extension).
 *  - [fallbackFrom] — **not on the wire**: the gateway records it on the row only. Always `null` here
 *    until the gateway echoes it; the protocol reads it from `GET /v1/prompt-logs`.
 *  - [durationMs] — measured by this client around the HTTP exchange (the gateway's own
 *    `duration_ms` is on the row).
 *
 * On a cache hit ([cached]) the gateway replays the stored body, so [tokensPrompt],
 * [tokensCompletion] and [costUsd] are the ORIGINAL call's — what the hit saved, not what it cost.
 * A per-turn spend must leave cached completions out.
 *
 * Only a 2xx answer is a completion: a gateway error (its OpenAI-shaped envelope on a 4xx/5xx) is a
 * `Result.failure`, never an empty completion (review-100 F2).
 */
data class LlmCompletion(
    val content: String,
    val callRef: String?,
    val requestedModel: String,
    val servedModel: String?,
    val servedProvider: String?,
    val fallbackFrom: String?,
    val cached: Boolean,
    val tokensPrompt: Int?,
    val tokensCompletion: Int?,
    val costUsd: Double?,
    val durationMs: Long?,
) {
    companion object {
        /** The response header carrying the prompt-log row id (LC contracts §2.2). */
        const val CALL_REF_HEADER: String = "X-Prompt-Log-Id"
    }
}
