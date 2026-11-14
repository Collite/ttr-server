// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.obs

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import java.time.Duration

/**
 * The service's OTel names (AU contracts §10), registered in one place. The spans are named here and opened
 * by the stages that run them; the metrics are instruments. D-142 (S3.2) removed the two ledger gauges
 * (`automation_tasks_open`, `automation_queue_depth`) with the task API and the queues.
 */
class AutomationTelemetry(
    openTelemetry: OpenTelemetry,
) {
    val tracer: Tracer = openTelemetry.getTracer(SCOPE)
    private val meter = openTelemetry.getMeter(SCOPE)

    private val runsTotal = meter.counterBuilder(RUNS_TOTAL).setDescription("Runs ended, by recipe and status").build()
    private val runDuration =
        meter
            .histogramBuilder(RUN_DURATION)
            .setUnit("s")
            .setDescription("Run wall time, open to close")
            .build()
    private val refreshTotal = meter.counterBuilder(REFRESH_TOTAL).setDescription("/refresh calls, by outcome").build()
    private val abandonedTotal =
        meter.counterBuilder(ABANDONED_TOTAL).setDescription("Runs the clock abandoned (D-46)").build()

    fun runEnded(
        recipe: String,
        status: String,
        duration: Duration,
    ) {
        val attributes = Attributes.of(RECIPE, recipe, STATUS, status)
        runsTotal.add(1, attributes)
        runDuration.record(duration.toMillis() / 1000.0, attributes)
    }

    fun refreshed(outcome: String) = refreshTotal.add(1, Attributes.of(OUTCOME, outcome))

    fun abandoned(recipe: String) = abandonedTotal.add(1, Attributes.of(RECIPE, recipe))

    companion object {
        const val SCOPE = "automation"

        const val SPAN_RUN = "automation.run"
        const val SPAN_ISLAND = "automation.island"
        const val SPAN_WRITE = "automation.write"
        const val SPAN_TASK = "automation.task"

        fun connectorSpan(face: String): String = "automation.connector.$face"

        const val RUNS_TOTAL = "automation_runs_total"
        const val RUN_DURATION = "automation_run_duration_seconds"
        const val REFRESH_TOTAL = "automation_refresh_total"
        const val ABANDONED_TOTAL = "automation_abandoned_total"

        /** The span attributes of `automation.run` (§10) and the metrics' labels. */
        val RECIPE: AttributeKey<String> = AttributeKey.stringKey("recipe")
        val PROGRAM: AttributeKey<String> = AttributeKey.stringKey("program")
        val DEPLOYMENT: AttributeKey<String> = AttributeKey.stringKey("deployment")
        val RUN_AS: AttributeKey<String> = AttributeKey.stringKey("run_as")
        val STATUS: AttributeKey<String> = AttributeKey.stringKey("status")
        val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")
    }
}
