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

/** `/status` `error` when no model source is configured at all. */
const val NO_MODEL_SOURCE = "no_model_source_configured"

/**
 * `/ready` and `/status` (ttr-server#180).
 *
 * `/ready` answers 200 only when every configured source has loaded at least once and the model
 * built from those loads is swapped in ([SourceLoadBoard.isReady]); until then it answers 503 with
 * a one-line reason.
 * `/status` always answers 200 and carries the per-source detail: what each source is, whether it
 * has loaded, when it last tried and succeeded, and the last error — and `issues`, the files of the served
 * model that were left out: a layout file that does not parse or repeats a taken name,
 * `layout <path>: <reason>` (VL V-38). Neither makes the model any less loaded.
 */
fun Route.modelHealthRoutes(
    registry: MetadataRegistry,
    board: SourceLoadBoard,
) {
    get("/ready") {
        val snap = registry.read()
        if (snap != null && board.isReady(snap.swappedAt)) {
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
        val snap = registry.read()
        call.respondJson(
            buildJsonObject {
                put("service", "metadata")
                put("ready", board.isReady(snap?.swappedAt))
                put("model_loaded", snap != null)
                put("model_version", snap?.model?.version?.value ?: "")
                put("object_count", snap?.graph?.size() ?: 0)
                put("queries_total", snap?.model?.queries?.size ?: 0)
                if (board.states().isEmpty()) put("error", NO_MODEL_SOURCE)
                putJsonArray("issues") {
                    snap?.layouts?.issues?.forEach { add(it.toString()) }
                    snap?.templateCatalogs()?.flatMap { it.issues }?.forEach { add(it) }
                    snap?.servedLexicons()?.mapNotNull { it.issue }?.forEach { add(it) }
                }
                putJsonArray("sources") { board.states().forEach { addJsonObject { putState(it) } } }
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
            "$NO_MODEL_SOURCE: set METADATA_GIT_REMOTE_URI (with METADATA_GIT_BRANCH and METADATA_GIT_SUBDIR)"
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
