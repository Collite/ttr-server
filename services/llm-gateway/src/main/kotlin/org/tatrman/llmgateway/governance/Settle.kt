// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

/** Token usage that drove a settle — the counters the budget/prompt-log/metrics sinks read (§5.5). */
data class Usage(
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long = 0,
)

/**
 * The settlement record (contracts §5.5): one record, three sinks — budget settle (skipped when
 * [cached]), the prompt log (LG-P5·S2), and metrics. Built once per request at the end of the engine
 * flow; [estimated] flags a tokenizer-estimated usage (D-4 last resort).
 */
data class Settle(
    val keyId: String,
    val teamId: String,
    val costCenter: String?,
    // The caller's attribution headers (turn ref · purpose · end-user subject · agent id — LC §2.1).
    // Trace-only: the prompt-log sink writes them; budget and metrics never read them (D-2).
    val attribution: CallAttribution = CallAttribution(),
    val requestedModel: String,
    val servedProvider: String,
    val servedModel: String,
    val fallbackFrom: String?,
    val strippedParams: List<String>,
    val usage: Usage,
    val costUsd: Double,
    val estimated: Boolean,
    val cached: Boolean,
    val ttfbMs: Long?,
    val durationMs: Long,
    val traceId: String?,
)
