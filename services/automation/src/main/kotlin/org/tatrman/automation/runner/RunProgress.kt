// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Observation of committed state. The runtime reader needs neither an execution session nor a current bundle. */
@Serializable
data class RunProgress(
    val runId: String,
    val status: String,
    val verdict: VerdictSummary? = null,
    val guards: List<GuardResult> = emptyList(),
    val mode: String? = null,
    val protocolRef: String,
    val recipe: String? = null,
    val program: String? = null,
    val deployment: String? = null,
    val runAs: String? = null,
    val author: String? = null,
    val asOf: String? = null,
    val bundle: String? = null,
    val fingerprints: RunFingerprints? = null,
    val dryRun: Boolean? = null,
    val replayOf: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val error: JsonElement? = null,
    val batch: RunBatch? = null,
)

@Serializable
data class RunFingerprints(
    val text: String?,
    val values: String?,
    val program: String?,
    val deployment: String?,
    val model: String?,
)

@Serializable
data class RunBatch(
    val id: String,
    val state: String,
    val rows: Int,
)

@Serializable
data class EvaluationPending(
    val runId: String,
    val status: String = "running",
    val location: String,
)
