// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.cn

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.tatrman.concept.candidate.CnCandidates
import org.tatrman.ttr.metadata.model.VocabularyVersion

/** The candidate API is on with a store and a verifier, or off — and every route says why. */
sealed interface CnSurface {
    data class On(
        val service: CandidateService,
        val callers: CnCallers,
    ) : CnSurface

    data class Off(
        val reason: String,
    ) : CnSurface
}

private val log = LoggerFactory.getLogger("org.tatrman.veles.cn.CandidateRoutes")

/**
 * CN-P5 S5.3 T3 — the candidate store over HTTP (cn contracts §6; A-CN-90…95). JSON in is `cn-candidate.v1`; JSON out
 * wraps it: `{"candidate": <cn-candidate.v1>, "state", "content_hash", "submitted_by", "submitted_at", "promotion"?}`.
 * Errors are Veles's `{"error": …}` with a `code` (and `violations` / `findings` where there are some). Every route
 * needs a verified bearer (401 without); what each caller may do is [CnPolicy]'s.
 *
 * - `POST /v1/cn/candidates` — submit: 201 new, 200 a duplicate (the first candidate, `"duplicate": true`).
 * - `GET /v1/cn/candidates?status=&producer=&stereotype=&sort=newest|evidence&limit=` — the queue.
 * - `GET /v1/cn/candidates/{id}` — one, with its audit `history`.
 * - `POST /v1/cn/candidates/{id}/reject` `{"note"?}` · `POST /v1/cn/candidates/{id}/promote` — the steward's.
 * - `POST /v1/vocabularies/{id}/version` `{"version", "bundle"?}` (an importer's) · `GET /v1/vocabularies[/{id}/version]`.
 */
fun Route.candidateRoutes(
    surface: CnSurface,
    onVocabularyRegistered: suspend () -> Unit = {},
) {
    route("/v1/cn/candidates") {
        post { cn(surface) { s, caller -> submit(s, caller) } }
        get { cn(surface) { s, caller -> list(s, caller) } }
        get("/{id}") {
            cn(surface) { s, caller ->
                val (c, history) = withContext(Dispatchers.IO) { s.service.get(caller, id()) }
                respondJson(HttpStatusCode.OK, stored(c, history))
            }
        }
        post("/{id}/reject") {
            cn(surface) { s, caller ->
                val note = bodyObject()["note"]?.let { (it as? JsonPrimitive)?.content }?.trim()?.ifEmpty { null }
                val c = withContext(Dispatchers.IO) { s.service.reject(caller, id(), note) }
                respondJson(HttpStatusCode.OK, stored(c))
            }
        }
        post("/{id}/promote") {
            cn(surface) { s, caller ->
                val c = withContext(Dispatchers.IO) { s.service.promote(caller, id()) }
                respondJson(HttpStatusCode.OK, stored(c))
            }
        }
    }
    route("/v1/vocabularies") {
        get { cn(surface) { s, _ -> respondJson(HttpStatusCode.OK, vocabularies(s.service.vocabularyVersions(null))) } }
        get("/{id}/version") {
            cn(surface) { s, _ ->
                respondJson(HttpStatusCode.OK, vocabularies(s.service.vocabularyVersions(parameters["id"])))
            }
        }
        post("/{id}/version") {
            cn(surface) { s, caller ->
                val body = bodyObject()
                val version = (body["version"] as? JsonPrimitive)?.content.orEmpty()
                val bundle = (body["bundle"] as? JsonPrimitive)?.content
                val (v, registered) =
                    withContext(Dispatchers.IO) {
                        s.service.registerVocabulary(caller, parameters["id"].orEmpty(), version, bundle)
                    }
                if (registered) onVocabularyRegistered()
                respondJson(
                    if (registered) HttpStatusCode.Created else HttpStatusCode.OK,
                    buildJsonObject {
                        put("vocabulary", vocabulary(v))
                        put("registered", registered)
                    },
                )
            }
        }
    }
}

private suspend fun ApplicationCall.submit(
    s: CnSurface.On,
    caller: CnCaller,
) {
    val body = receiveText()
    val r = withContext(Dispatchers.IO) { s.service.submit(caller, body) }
    val json = stored(r.candidate)
    respondJson(
        if (r.created) HttpStatusCode.Created else HttpStatusCode.OK,
        if (r.created) json else JsonObject(json + ("duplicate" to JsonPrimitive(true))),
    )
}

private suspend fun ApplicationCall.list(
    s: CnSurface.On,
    caller: CnCaller,
) {
    val p = request.queryParameters
    val state =
        p["status"]?.takeIf { it.isNotBlank() }?.let {
            CandidateState.of(it)
                ?: throw badQuery("status is one of ${CandidateState.entries.joinToString { s -> s.word }}")
        }
    val sort =
        when (p["sort"]?.takeIf { it.isNotBlank() } ?: "newest") {
            "newest" -> CandidateSort.NEWEST
            "evidence" -> CandidateSort.EVIDENCE
            else -> throw badQuery("sort is newest or evidence")
        }
    val limit =
        p["limit"]?.let {
            it.toIntOrNull()?.coerceIn(
                1,
                CandidateQuery.MAX_LIMIT,
            ) ?: throw badQuery("limit is a number")
        }
            ?: CandidateQuery.DEFAULT_LIMIT
    val q =
        CandidateQuery(
            state = state,
            producer = p["producer"]?.takeIf { it.isNotBlank() },
            kind = p["stereotype"]?.takeIf { it.isNotBlank() },
            sort = sort,
            limit = limit,
        )
    val found = withContext(Dispatchers.IO) { s.service.list(caller, q) }
    respondJson(HttpStatusCode.OK, buildJsonObject { put("candidates", JsonArray(found.map { stored(it) })) })
}

/** Runs [block] for a verified caller on an [CnSurface.On] surface; answers every [CnRefused] in the error shape. */
private suspend fun io.ktor.server.routing.RoutingContext.cn(
    surface: CnSurface,
    block: suspend ApplicationCall.(CnSurface.On, CnCaller) -> Unit,
) {
    val on =
        when (surface) {
            is CnSurface.Off -> return call.respondRefusal(
                CnRefused(HttpStatusCode.ServiceUnavailable, CN_STORE_OFF, surface.reason),
            )
            is CnSurface.On -> surface
        }
    val caller =
        when (
            val check =
                withContext(
                    Dispatchers.IO,
                ) { on.callers.check(call.request.header(HttpHeaders.Authorization)) }
        ) {
            is CallerCheck.Refused -> {
                call.response.header(HttpHeaders.WWWAuthenticate, """Bearer error="invalid_token"""")
                return call.respondRefusal(
                    CnRefused(
                        HttpStatusCode.Unauthorized,
                        CN_UNAUTHENTICATED,
                        "a verified bearer token is required (${check.reason})",
                    ),
                )
            }
            is CallerCheck.Verified -> check.caller
        }
    try {
        call.block(on, caller)
    } catch (e: CnRefused) {
        call.respondRefusal(e)
    } catch (e: Exception) {
        log.warn("cn: {} {} failed", call.request.local.method.value, call.request.local.uri, e)
        call.respondRefusal(
            CnRefused(
                HttpStatusCode.ServiceUnavailable,
                CN_STORE_UNAVAILABLE,
                "the candidate store did not answer: ${e.message}",
            ),
        )
    }
}

private fun ApplicationCall.id(): String = parameters["id"].orEmpty()

private suspend fun ApplicationCall.bodyObject(): JsonObject {
    val text = receiveText()
    if (text.isBlank()) return JsonObject(emptyMap())
    return runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: throw CnRefused(HttpStatusCode.BadRequest, CN_BAD_REQUEST, "the body is a JSON object")
}

private fun badQuery(message: String) = CnRefused(HttpStatusCode.BadRequest, CN_BAD_REQUEST, message)

private suspend fun ApplicationCall.respondJson(
    status: HttpStatusCode,
    json: JsonObject,
) = respondText(json.toString(), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondRefusal(r: CnRefused) =
    respondJson(
        r.status,
        buildJsonObject {
            put("error", r.message.orEmpty())
            put("code", r.code)
            r.detail?.forEach { (k, v) -> put(k, v) }
        },
    )

/** The envelope: the document, then what the store knows. */
internal fun stored(
    c: StoredCandidate,
    history: List<CandidateEvent>? = null,
): JsonObject =
    buildJsonObject {
        put("candidate", CnCandidates.toJson(c.candidate))
        put("state", c.state.word)
        put("content_hash", c.contentHash)
        put("submitted_by", c.submittedBy)
        put("submitted_at", c.submittedAt.toString())
        put("updated_at", c.updatedAt.toString())
        c.promotion?.let { p ->
            putJsonObject("promotion") {
                put("qname", p.qname)
                put("base", p.base)
                put("by", p.by)
                put("at", p.at.toString())
                p.pullRequest?.let { put("pull_request_url", it) }
                p.inbox?.let { put("inbox", it) }
                putJsonObject("pull_request") {
                    put("title", p.title)
                    put("body", p.body)
                }
                put("diff", p.diff)
                putJsonArray("findings") { p.findings.forEach { add(it) } }
            }
        }
        history?.let { h ->
            putJsonArray("history") {
                for (e in h) {
                    add(
                        buildJsonObject {
                            put("action", e.action)
                            put("actor", e.actor)
                            put("at", e.at.toString())
                            if (e.detail.isNotEmpty()) put("detail", e.detail)
                        },
                    )
                }
            }
        }
    }

private fun vocabulary(v: VocabularyVersion): JsonObject =
    buildJsonObject {
        put("vocabulary", v.vocabulary)
        put("version", v.version)
        v.bundle?.let { put("bundle", it) }
        put("registered_at", v.registeredAt.toString())
    }

private fun vocabularies(vs: List<VocabularyVersion>): JsonObject =
    buildJsonObject { put("versions", JsonArray(vs.map { vocabulary(it) })) }

const val CN_STORE_OFF = "CN_STORE_OFF"
const val CN_STORE_UNAVAILABLE = "CN_STORE_UNAVAILABLE"
const val CN_UNAUTHENTICATED = "CN_UNAUTHENTICATED"
const val CN_BAD_REQUEST = "CN_BAD_REQUEST"
