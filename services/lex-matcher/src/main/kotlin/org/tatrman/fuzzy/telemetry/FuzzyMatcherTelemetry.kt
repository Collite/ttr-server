// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.telemetry

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk
import java.util.concurrent.atomic.AtomicInteger

class FuzzyTelemetry {
    val openTelemetry: OpenTelemetry
    val tracer: Tracer
    val meterRegistry: MeterRegistry

    private val categoriesLoaded = AtomicInteger(0)

    init {
        val protocol = System.getenv("FUZZY_MATCHER_OTEL_PROTOCOL") ?: "grpc"
        // Gate the OTel SDK on the same flag the chart derives from `telemetry.enabled`
        // (OFF by default). Without this, `createOpenTelemetrySdk` defaults to enabled=true and
        // always builds OTLP exporters that spam "Failed to export" against a non-existent
        // collector at localhost:4317. The other services pass `enabled` from config the same way.
        val enabled = System.getenv("OTEL_ENABLED_FUZZY")?.toBooleanStrictOrNull() ?: false
        val otelSdk =
            createOpenTelemetrySdk(
                OtelEndpointConfig(
                    serviceName = "fuzzy",
                    protocol = protocol,
                ),
                enabled = enabled,
            )
        openTelemetry = otelSdk
        tracer = otelSdk.getTracer("fuzzy")
        meterRegistry = io.micrometer.core.instrument.Metrics.globalRegistry
        registerGauges()
    }

    private fun registerGauges() {
        Gauge
            .builder("fuzzy_loader_categories_loaded") { categoriesLoaded.get().toDouble() }
            .description("Number of categories loaded in last refresh")
            .register(meterRegistry)
    }

    /**
     * Per-category candidate counts (design §9). Each refresh's [updateCategories]
     * call replaces the full row set via `MultiGauge.register(rows, overwrite=true)`,
     * so categories that disappear from the model (after H1's atomic swap) stop
     * being reported on the next tick instead of lingering as stale series.
     */
    private val candidatesPerCategory: MultiGauge =
        MultiGauge
            .builder("fuzzy_loader_candidates_total")
            .description("Number of candidates per category")
            .register(meterRegistry)

    private val refreshDurationTimer =
        Timer
            .builder("fuzzy_loader_refresh_duration_seconds")
            .description("Duration of cache refresh")
            .register(meterRegistry)

    private val metadataFailures =
        Counter
            .builder("fuzzy_loader_metadata_failures_total")
            .description("Metadata service call failures")
            .register(meterRegistry)

    fun recordRefreshDuration(durationSeconds: Double) {
        refreshDurationTimer.record(java.time.Duration.ofNanos((durationSeconds * 1_000_000_000).toLong()))
    }

    /**
     * MV-T2 — a member vocabulary the loader did not load, by reason: `no_key` (RG-FUZ-001),
     * `no_read_plan` (RG-FUZ-003), `wrong_source`, `sql_failed`, `alias_sql_failed`. Renamed from
     * `fuzzy_loader_skipped_total{no_pk|composite_pk|…}`: what is skipped is a vocabulary, and the
     * key rule is Veles' now, not a table's primary key.
     */
    fun recordSkipped(reason: String) {
        Counter
            .builder("member_vocabulary_skipped_total")
            .tag("reason", reason)
            .description("Member vocabularies the loader did not load, by reason")
            .register(meterRegistry)
            .increment()
    }

    fun recordMetadataFailure() {
        metadataFailures.increment()
    }

    fun updateCategories(candidatesByCategory: Map<String, Int>) {
        categoriesLoaded.set(candidatesByCategory.size)
        candidatesPerCategory.register(
            candidatesByCategory.map { (category, n) ->
                MultiGauge.Row.of(Tags.of("category", category), n.toDouble())
            },
            true,
        )
    }

    /** Read-only accessor for tests — distinct categories from the last refresh. */
    fun categoriesLoadedSnapshot(): Int = categoriesLoaded.get()
}
