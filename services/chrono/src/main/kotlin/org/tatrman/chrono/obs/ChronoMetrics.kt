// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.obs

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter

/**
 * A8.7 observability for chrono grounding. One counter keyed by (outcome, source, application) and one
 * latency histogram keyed by (outcome, source), so a dashboard can slice OK/AWAITING_CLARIFICATION/
 * UNGROUNDABLE by RULES vs LLM vs the deterministic no-result paths, and see how often an OK result
 * came back as `values` because the model offered no date column (TS-1).
 *
 * `Scan` (TS-P0 S0.3) has its own counter, `chrono_scan_total{spans}`, and latency histogram.
 *
 * Latency is measured with `System.nanoTime()` (a monotonic duration timer) — NOT a wall clock, so
 * it does not violate the "chrono never reads a clock" rule (that guards `reference_datetime`).
 */
class ChronoMetrics(
    meter: Meter,
) {
    private val groundCounter =
        meter
            .counterBuilder("chrono_ground_total")
            .setDescription("Total chrono Ground calls, by outcome, source and application")
            .setUnit("requests")
            .build()

    private val latencyHistogram =
        meter
            .histogramBuilder("chrono_ground_latency_ms")
            .setDescription("chrono Ground wall-time latency, by outcome and source")
            .setUnit("ms")
            .build()

    private val scanCounter =
        meter
            .counterBuilder("chrono_scan_total")
            .setDescription("Total chrono Scan calls, by how many spans they found (0, 1, 2, 3+)")
            .setUnit("requests")
            .build()

    private val scanLatencyHistogram =
        meter
            .histogramBuilder("chrono_scan_latency_ms")
            .setDescription("chrono Scan wall-time latency")
            .setUnit("ms")
            .build()

    /** Record one Scan call (TS-P0 S0.3): [spans] found, bucketed `0` · `1` · `2` · `3+` to keep the label small. */
    fun recordScan(
        spans: Int,
        latencyMs: Double,
    ) {
        scanCounter.add(1, Attributes.of(SPANS, if (spans >= 3) "3+" else spans.toString()))
        scanLatencyHistogram.record(latencyMs)
    }

    /**
     * Record one Ground call. [outcome] = GroundResponse.Status name; [source] = RULES | LLM | NONE;
     * [application] = filter | join | values for an OK result, `none` otherwise.
     */
    fun recordGround(
        outcome: String,
        source: String,
        application: String,
        latencyMs: Double,
    ) {
        groundCounter.add(1, Attributes.of(OUTCOME, outcome, SOURCE, source, APPLICATION, application))
        latencyHistogram.record(latencyMs, Attributes.of(OUTCOME, outcome, SOURCE, source))
    }

    companion object {
        private val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")
        private val SOURCE: AttributeKey<String> = AttributeKey.stringKey("source")
        private val APPLICATION: AttributeKey<String> = AttributeKey.stringKey("application")
        private val SPANS: AttributeKey<String> = AttributeKey.stringKey("spans")

        /** No-op metrics for tests / fixture boots — records into the noop SDK. */
        fun noop(): ChronoMetrics = ChronoMetrics(OpenTelemetry.noop().getMeter("chrono"))
    }
}
