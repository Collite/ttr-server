// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import kotlinx.serialization.Serializable

/** P1 polling projection. Batch/tasks/deliveries and durable restart/status recovery arrive with lifecycle work. */
@Serializable
data class RunProgress(
    val runId: String,
    val status: String,
    val verdict: VerdictSummary? = null,
    val guards: List<GuardResult> = emptyList(),
    val mode: String? = null,
    val protocolRef: String,
)

@Serializable
data class EvaluationPending(
    val runId: String,
    val status: String = "running",
    val location: String,
)
