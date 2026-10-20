// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.obs

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * The service's OTel names (AU contracts §10), registered in one place. The spans are named here and opened
 * by the stages that run them; the metrics are instruments now. The two gauges read the ledger when the
 * exporter scrapes them, through [queueDepths] and [openTasks] — a failing read drops that scrape's points,
 * it never throws into the SDK.
 */
class AutomationTelemetry(
    openTelemetry: OpenTelemetry,
    queueDepths: () -> Map<String, Long> = { emptyMap() },
    openTasks: () -> Long = { 0 },
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

    init {
        meter
            .gaugeBuilder(TASKS_OPEN)
            .ofLongs()
            .setDescription("Open tasks")
            .buildWithCallback { m -> guarded(TASKS_OPEN) { m.record(openTasks()) } }
        meter
            .gaugeBuilder(QUEUE_DEPTH)
            .ofLongs()
            .setDescription("Messages waiting per ledger queue")
            .buildWithCallback { m ->
                guarded(QUEUE_DEPTH) {
                    queueDepths().forEach { (queue, depth) ->
                        m.record(depth, Attributes.of(QUEUE, queue))
                    }
                }
            }
    }

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

    private inline fun guarded(
        instrument: String,
        read: () -> Unit,
    ) {
        try {
            read()
        } catch (e: Exception) {
            log.warn("{}: the ledger read failed; this scrape has no points", instrument, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AutomationTelemetry::class.java)

        const val SCOPE = "automation"

        const val SPAN_RUN = "automation.run"
        const val SPAN_ISLAND = "automation.island"
        const val SPAN_WRITE = "automation.write"
        const val SPAN_TASK = "automation.task"

        fun connectorSpan(face: String): String = "automation.connector.$face"

        const val RUNS_TOTAL = "automation_runs_total"
        const val RUN_DURATION = "automation_run_duration_seconds"
        const val TASKS_OPEN = "automation_tasks_open"
        const val QUEUE_DEPTH = "automation_queue_depth"
        const val REFRESH_TOTAL = "automation_refresh_total"
        const val ABANDONED_TOTAL = "automation_abandoned_total"

        /** The span attributes of `automation.run` (§10) and the metrics' labels. */
        val RECIPE: AttributeKey<String> = AttributeKey.stringKey("recipe")
        val PROGRAM: AttributeKey<String> = AttributeKey.stringKey("program")
        val DEPLOYMENT: AttributeKey<String> = AttributeKey.stringKey("deployment")
        val RUN_AS: AttributeKey<String> = AttributeKey.stringKey("run_as")
        val STATUS: AttributeKey<String> = AttributeKey.stringKey("status")
        val QUEUE: AttributeKey<String> = AttributeKey.stringKey("queue")
        val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")
    }
}
