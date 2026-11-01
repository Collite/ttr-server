// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import org.tatrman.metadata.health.SourceLoadBoard
import org.tatrman.ttr.metadata.registry.RegistrySnapshot

/** Immutable reply captured before the refresh lock is released. */
data class RefreshOutcome(
    val results: List<GuardedRefresher.SourceResult>,
    val snapshot: RegistrySnapshot?,
    val sources: List<SourceLoadBoard.SourceLoadState>,
    val serving: ServingModel?,
    val lastAttempt: ModelLoadReport?,
    val lastSuccess: ModelLoadReport?,
    val keptPrevious: Boolean,
    val status: String,
    val httpStatus: Int = 200,
    val ready: Boolean = false,
    val objectsByKind: Map<String, Int> = emptyMap(),
    val tags: List<String> = emptyList(),
)

data class ServingModel(
    val source: String,
    val sourceId: String,
    val branch: String,
    val commit: String,
    val modelVersion: String,
    val loadedAt: String,
    val servingFallback: Boolean,
)

data class ModelLoadReport(
    val sourceId: String,
    val at: String,
    val ok: Boolean,
    val status: String,
    val branch: String,
    val commit: String,
    val errors: List<String>,
    val warnings: List<String>,
    val modelFiles: Int,
    val ignoredFiles: List<String> = emptyList(),
)
