// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.health

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.metadata.read.templateCatalogs
import org.tatrman.metadata.read.servedLexicons
import org.tatrman.metadata.refresh.diagnostic
import org.tatrman.metadata.refresh.GuardedRefresher
import org.tatrman.metadata.refresh.putOps

/** `/status` `error` when no model source is configured at all. */
const val NO_MODEL_SOURCE = "no_model_source_configured"

/** Shared operator help for startup and an empty board's readiness diagnostic. */
const val NO_MODEL_SOURCE_HELP =
    "configure METADATA_GIT_REMOTE_URI, METADATA_GIT_BRANCH and METADATA_GIT_SUBDIR; " +
        "for a filesystem model, set METADATA_GIT_REMOTE_URI= and METADATA_GIT_FALLBACK=local-model " +
        "with METADATA_TTR_ROOT=<model dir>, " +
        "or use -Dmetadata.sources.local-model.enabled=true with METADATA_TTR_ROOT=<model dir>"

/**
 * `/ready` and `/status` (ttr-server#180).
 *
 * `/ready` answers 200 only when every configured source has loaded at least once and the model
 * built from those loads is swapped in ([SourceLoadBoard.isReady]); until then it answers 503 with
 * a one-line reason.
 * `/status` always answers 200 and carries the per-source detail: what each source is, whether it
 * has loaded, when it last tried and succeeded, and the last error. `strict_swap` advertises the
 * configured publication policy. `issues` includes rejected model diagnostics as well as optional
 * content that was left out of the served model: a layout file that does not parse or repeats a taken name,
 * `layout <path>: <reason>` (VL V-38). Neither makes the model any less loaded.
 */
fun Route.modelHealthRoutes(
    registry: MetadataRegistry,
    board: SourceLoadBoard,
    refresher: GuardedRefresher? = null,
) {
    get("/ready") {
        val (snap, ready) = synchronized(registry) { registry.read().let { it to board.isReady(it?.swappedAt) } }
        if (snap != null && ready) {
            call.respondJson(
                buildJsonObject {
                    put("status", "UP")
                    put("model_version", snap.model.version.value)
                },
            )
        } else {
            call.respondJson(
                buildJsonObject {
                    put("status", "NOT_READY")
                    put("reason", notReadyReason(board))
                },
                HttpStatusCode.ServiceUnavailable,
            )
        }
    }
    get("/status") {
        val outcome = refresher?.status()
        val (snap, sourceStates, ready) =
            if (outcome != null) {
                Triple(
                    outcome.snapshot,
                    outcome.sources,
                    outcome.ready,
                )
            } else {
                synchronized(registry) {
                    val snapshot = registry.read()
                    Triple(snapshot, board.states(), board.isReady(snapshot?.swappedAt))
                }
            }
        call.respondJson(
            buildJsonObject {
                outcome?.let { putOps(it) }
                put("allow_non_default_branch", refresher?.allowNonDefaultBranch ?: false)
                put(
                    "objects_by_kind",
                    buildJsonObject {
                        (
                            outcome?.objectsByKind ?: snap
                                ?.model
                                ?.objectByQname()
                                ?.values
                                .orEmpty()
                                .groupingBy { it.kind }
                                .eachCount()
                                .toSortedMap()
                        ).forEach { (kind, count) ->
                            put(kind, count)
                        }
                    },
                )
                putJsonArray("tags") {
                    (
                        outcome?.tags ?: snap
                            ?.model
                            ?.objectByQname()
                            ?.values
                            .orEmpty()
                            .flatMap { it.tags }
                            .toSortedSet()
                            .toList()
                    ).forEach { add(it) }
                }
                putJsonArray("ignoredFiles") {
                    outcome
                        ?.lastAttempt
                        ?.ignoredFiles
                        .orEmpty()
                        .forEach { add(it) }
                }
                put("service", "metadata")
                put("strict_swap", board.strictSwap)
                put("ready", ready)
                put("model_loaded", snap != null)
                put("model_version", snap?.model?.version?.value ?: "")
                put("object_count", snap?.graph?.size() ?: 0)
                put("queries_total", snap?.model?.queries?.size ?: 0)
                if (sourceStates.isEmpty()) put("error", NO_MODEL_SOURCE)
                putJsonArray("issues") {
                    sourceStates
                        .flatMap { it.issues }
                        .distinct()
                        .forEach { add(it) }
                    snap?.warnings?.forEach { add(it.diagnostic()) }
                    snap?.layouts?.issues?.forEach { add(it.toString()) }
                    snap?.templateCatalogs()?.flatMap { it.issues }?.forEach { add(it) }
                    snap?.servedLexicons()?.mapNotNull { it.issue }?.forEach { add(it) }
                }
                putJsonArray("sources") { sourceStates.forEach { addJsonObject { putState(it) } } }
            },
        )
    }
}

// respondText, not respond: like the read routes, these need no ContentNegotiation plugin.
private suspend fun ApplicationCall.respondJson(
    body: JsonObject,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(body.toString(), ContentType.Application.Json, status)

private fun notReadyReason(board: SourceLoadBoard): String {
    val pending = board.states().filter { !it.loadedOnce }
    return when {
        board.states().isEmpty() ->
            "$NO_MODEL_SOURCE: $NO_MODEL_SOURCE_HELP"
        pending.isEmpty() -> "model not swapped in yet"
        else ->
            pending.joinToString("; ") { s ->
                val why = s.lastError.ifEmpty { "not loaded yet" }
                "source '${s.id}' has not loaded: $why"
            }
    }
}

private fun JsonObjectBuilder.putState(s: SourceLoadBoard.SourceLoadState) {
    put("id", s.id)
    put("branch", s.origin.branch)
    put("commit", s.origin.commit)
    put("source_type", s.origin.source)
    put("source", s.description)
    put("loaded", s.loadedOnce)
    put("serving_fallback", s.servingFallback)
    put("version", s.version)
    put("object_count", s.objectCount)
    put("last_attempt_at", s.lastAttemptAt?.toString() ?: "")
    put("last_success_at", s.lastSuccessAt?.toString() ?: "")
    put("last_error", s.lastError)
    put("consecutive_failures", s.consecutiveFailures)
    put("failures_total", s.failuresTotal)
}
