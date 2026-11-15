// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.obs

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader

/** AU contracts §10: the metric names are registered, carry their labels, and a failing ledger read never throws. */
class AutomationTelemetrySpec :
    StringSpec({
        fun sdk(reader: InMemoryMetricReader) =
            OpenTelemetrySdk
                .builder()
                .setMeterProvider(
                    SdkMeterProvider.builder().registerMetricReader(reader).build(),
                ).build()

        "the four metrics of §10 are emitted under their contract names, with their labels" {
            val reader = InMemoryMetricReader.create()
            val telemetry = AutomationTelemetry(sdk(reader))
            telemetry.runEnded("release_order", "closed", java.time.Duration.ofMillis(1500))
            telemetry.refreshed("ok")
            telemetry.abandoned("release_order")

            val metrics = reader.collectAllMetrics().associateBy { it.name }
            metrics.keys shouldContainAll
                listOf(
                    AutomationTelemetry.RUNS_TOTAL,
                    AutomationTelemetry.RUN_DURATION,
                    AutomationTelemetry.REFRESH_TOTAL,
                    AutomationTelemetry.ABANDONED_TOTAL,
                )
            val run =
                metrics
                    .getValue(AutomationTelemetry.RUNS_TOTAL)
                    .longSumData.points
                    .single()
            run.value shouldBe 1L
            run.attributes.get(AutomationTelemetry.RECIPE) shouldBe "release_order"
            run.attributes.get(AutomationTelemetry.STATUS) shouldBe "closed"
            metrics
                .getValue(AutomationTelemetry.RUN_DURATION)
                .histogramData.points
                .single()
                .sum shouldBe 1.5
        }

        "D-142: the task and queue gauges are gone" {
            val reader = InMemoryMetricReader.create()
            AutomationTelemetry(sdk(reader)).refreshed("ok")
            reader.collectAllMetrics().map { it.name } shouldNotContain "automation_queue_depth"
            reader.collectAllMetrics().map { it.name } shouldNotContain "automation_tasks_open"
        }

        "the span names are the contract's" {
            AutomationTelemetry.SPAN_RUN shouldBe "automation.run"
            AutomationTelemetry.SPAN_ISLAND shouldBe "automation.island"
            AutomationTelemetry.SPAN_WRITE shouldBe "automation.write"
            AutomationTelemetry.SPAN_TASK shouldBe "automation.task"
            AutomationTelemetry.connectorSpan("ask") shouldBe "automation.connector.ask"
        }
    })
