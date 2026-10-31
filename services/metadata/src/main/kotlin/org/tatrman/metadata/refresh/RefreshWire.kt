// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal fun ServingModel.toProto(): org.tatrman.meta.v1.ServingModel =
    org.tatrman.meta.v1.ServingModel
        .newBuilder()
        .setSource(source)
        .setSourceId(sourceId)
        .setBranch(branch)
        .setCommit(
            commit,
        ).setModelVersion(modelVersion)
        .setLoadedAt(loadedAt)
        .setServingFallback(servingFallback)
        .build()

internal fun ModelLoadReport.toProto(): org.tatrman.meta.v1.ModelLoadReport =
    org.tatrman.meta.v1.ModelLoadReport
        .newBuilder()
        .setSourceId(sourceId)
        .setAt(at)
        .setOk(ok)
        .setStatus(status)
        .setBranch(branch)
        .setCommit(commit)
        .addAllErrors(errors)
        .addAllWarnings(warnings)
        .setModelFiles(modelFiles)
        .addAllIgnoredFiles(ignoredFiles)
        .build()

internal fun JsonObjectBuilder.putOps(outcome: RefreshOutcome) {
    val serving = outcome.serving
    put(
        "serving",
        serving?.let { s ->
            buildJsonObject {
                put("source", s.source)
                put("source_id", s.sourceId)
                put("branch", s.branch)
                put("commit", s.commit)
                put("model_version", s.modelVersion)
                put("loaded_at", s.loadedAt)
                put("serving_fallback", s.servingFallback)
            }
        } ?: JsonNull,
    )
    put("source", serving?.source.orEmpty())
    put("branch", serving?.branch.orEmpty())
    put("commit", serving?.commit.orEmpty())
    put("serving_fallback", serving?.servingFallback ?: false)
    put("kept_previous", outcome.keptPrevious)
    put("last_attempt", outcome.lastAttempt?.json() ?: JsonNull)
    put("last_success", outcome.lastSuccess?.json() ?: JsonNull)
}

private fun ModelLoadReport.json() =
    buildJsonObject {
        put("source_id", sourceId)
        put("at", at)
        put("ok", ok)
        put("status", status)
        put("branch", branch)
        put("commit", commit)
        putJsonArray("errors") { errors.forEach { add(it) } }
        putJsonArray("warnings") { warnings.forEach { add(it) } }
        put("model_files", modelFiles)
        putJsonArray("ignored_files") { ignoredFiles.forEach { add(it) } }
    }
