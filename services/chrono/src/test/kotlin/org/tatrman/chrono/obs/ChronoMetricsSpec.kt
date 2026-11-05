// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.obs

import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundingContext
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import org.tatrman.chrono.FakeMetadataClient
import org.tatrman.chrono.grpc.ChronoGroundingService

/**
 * A8.7 — the Ground counter/latency actually emit, keyed by (outcome, source). Uses a real
 * SdkMeterProvider + InMemoryMetricReader so we assert exported data points, not call counts.
 */
class ChronoMetricsSpec :
    StringSpec({
        val outcomeKey = AttributeKey.stringKey("outcome")
        val sourceKey = AttributeKey.stringKey("source")
        val applicationKey = AttributeKey.stringKey("application")

        fun request(span: String): GroundRequest =
            GroundRequest
                .newBuilder()
                .setSpanText(span)
                .setKind(EntityKind.DATE_TIME)
                .setPackage("cnc")
                .setContext(
                    GroundingContext
                        .newBuilder()
                        .setReferenceDatetime("2026-05-15T12:00:00+02:00")
                        .setTimezone("Europe/Prague"),
                ).build()

        "Ground records chrono_ground_total + latency by (outcome, source)" {
            val reader = InMemoryMetricReader.create()
            val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
            val service =
                ChronoGroundingService(
                    discovery = FakeMetadataClient.accounting("cnc"),
                    llmFallback = null,
                    metrics = ChronoMetrics(provider.get("chrono")),
                )

            service.ground(request("May 2026")) // OK / RULES
            service.ground(request("qwerty nonsense")) // UNGROUNDABLE / NONE

            val metrics = reader.collectAllMetrics()
            val counter = metrics.first { it.name == "chrono_ground_total" }
            val points = counter.longSumData.points

            points.any {
                it.attributes.get(outcomeKey) == "OK" && it.attributes.get(sourceKey) == "RULES"
            } shouldBe true
            points.any {
                it.attributes.get(outcomeKey) == "UNGROUNDABLE" && it.attributes.get(sourceKey) == "NONE"
            } shouldBe true

            metrics.any { it.name == "chrono_ground_latency_ms" } shouldBe true
        }

        "chrono_ground_total carries the application, so a dashboard sees how often the model offered no column" {
            val reader = InMemoryMetricReader.create()
            val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()

            fun service(discovery: FakeMetadataClient) =
                ChronoGroundingService(discovery, llmFallback = null, metrics = ChronoMetrics(provider.get("chrono")))

            service(FakeMetadataClient.accounting("cnc")).ground(request("May 2026")) // join
            service(FakeMetadataClient.accounting("cnc")).ground(request("yesterday")) // filter
            service(FakeMetadataClient(emptyList())).ground(request("yesterday")) // values: no date role
            service(FakeMetadataClient(emptyList())).ground(request("qwerty nonsense")) // UNGROUNDABLE

            val points =
                reader
                    .collectAllMetrics()
                    .first { it.name == "chrono_ground_total" }
                    .longSumData.points
            points.map { it.attributes.get(outcomeKey) to it.attributes.get(applicationKey) }.toSet() shouldBe
                setOf("OK" to "join", "OK" to "filter", "OK" to "values", "UNGROUNDABLE" to "none")
        }
    })
