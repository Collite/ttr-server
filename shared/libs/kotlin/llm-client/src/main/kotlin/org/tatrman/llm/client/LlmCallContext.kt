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
    }

    /** The request headers this context maps to — non-blank fields only, in a stable order. */
    fun headers(): Map<String, String> =
        buildMap {
            turnRef?.takeIf { it.isNotBlank() }?.let { put(TURN_REF_HEADER, it) }
            purpose?.takeIf { it.isNotBlank() }?.let { put(PURPOSE_HEADER, it) }
            endUserSubject?.takeIf { it.isNotBlank() }?.let { put(END_USER_SUBJECT_HEADER, it) }
            agentId?.takeIf { it.isNotBlank() }?.let { put(AGENT_ID_HEADER, it) }
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
