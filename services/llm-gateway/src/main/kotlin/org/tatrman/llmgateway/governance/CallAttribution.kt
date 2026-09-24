// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import io.ktor.http.Headers

/**
 * Who a data-plane call belongs to, as the CALLER stated it (LC contracts §2.1): the four attribution
 * headers, read once per request by [from] so the completion and embeddings routes cannot drift apart
 * again — three hand-copied `headers["X-Turn-Ref"]` reads is how the turn ref drifted before LC.
 *
 * Trace-only facts: they land on the prompt-log row, never on a metric label (D-2) and never in a
 * routing or budget decision. The values are caller-supplied, so each is trimmed, a blank one becomes
 * `null` (no row ever carries an empty string), and each is capped at [MAX_LENGTH] characters to
 * guard the columns.
 *
 * **Trust boundary (review-100 F16; LC contracts §2.1).** These are ASSERTIONS by the key holder, not
 * facts the gateway verified. Any data-plane key can stamp any `X-End-User-Subject` (and, as ever, any
 * `X-Turn-Ref`) onto its rows, so a subject's read of their own turn can include rows another key holder
 * wrote in their name. That is an integrity limit, not a confidentiality one: the per-row READ rule
 * (⚑LC-1) only ever narrows what a reader sees, and a row stamped with someone else's subject is never
 * shown to anyone who could not already read that subject's rows. Keys are issued to estate services
 * only; a key that should not attribute on a user's behalf must not be issued.
 */
data class CallAttribution(
    val turnRef: String? = null,
    val purpose: String? = null,
    val endUserSubject: String? = null,
    val agentId: String? = null,
) {
    companion object {
        const val TURN_REF_HEADER: String = "X-Turn-Ref"
        const val PURPOSE_HEADER: String = "X-Call-Purpose"
        const val END_USER_SUBJECT_HEADER: String = "X-End-User-Subject"
        const val AGENT_ID_HEADER: String = "X-Agent-Id"

        /** Column guard: a caller-supplied value longer than this is truncated, never refused. */
        const val MAX_LENGTH: Int = 512

        fun from(headers: Headers): CallAttribution =
            CallAttribution(
                turnRef = headers[TURN_REF_HEADER].bounded(),
                purpose = headers[PURPOSE_HEADER].bounded(),
                endUserSubject = headers[END_USER_SUBJECT_HEADER].bounded(),
                agentId = headers[AGENT_ID_HEADER].bounded(),
            )

        private fun String?.bounded(): String? =
            this
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.take(MAX_LENGTH)
    }
}
