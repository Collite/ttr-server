// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.identity.caller
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.RunStatus
import org.tatrman.automation.query.QueryRequest
import org.tatrman.automation.query.QueryStep
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/** The template dialects `GET /automation/templates/{target}` knows (D-145); none is served before S3.1b. */
val TEMPLATE_TARGETS = setOf("camunda-7", "camunda-8", "n8n")

/**
 * S3.1 (D-146, D-128, D-145): the query step, the queries list, the runs list and the templates route. Install
 * inside bearer authentication.
 */
fun Route.queryRoutes(
    step: QueryStep,
    served: () -> Bundle?,
    ledger: Ledger,
) {
    post("/automation/query") {
        val outcome = step.run(call.receive<QueryRequest>(), call.caller())
        if (outcome.status == 202) {
            call.response.header(HttpHeaders.Location, "/automation/runs/${outcome.response.run.id}")
            call.response.header(HttpHeaders.RetryAfter, "5")
        }
        call.respond(HttpStatusCode.fromValue(outcome.status), outcome.response)
    }

    // The served bundle's `def query` objects (T4; n8n-nodes §2.4's loadOptions, the templates' query choices).
    get("/automation/queries") {
        val bundle = served() ?: throw AutomationException(ErrorCode.QUERY, "no bundle is served")
        val body =
            buildJsonObject {
                put("bundle", bundle.id)
                putJsonArray("queries") {
                    bundle.model.queries.values.sortedBy { it.qname.dotted() }.forEach { q ->
                        addJsonObject {
                            put("name", q.qname.name)
                            put("qname", q.qname.dotted())
                            put("description", q.description)
                            put("language", q.sourceLanguage)
                            putJsonArray("params") {
                                q.parameters.forEach { p ->
                                    addJsonObject {
                                        put("name", p.name)
                                        put("type", p.type)
                                        if (p.label.isNotEmpty()) put("label", p.label)
                                        put("default", p.defaultValue?.let(::JsonPrimitive) ?: JsonNull)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        call.respond(body)
    }

    // T4: the caller's own runs, newest first. `status` defaults to `closed`; `any` lists every status.
    get("/automation/runs") {
        val p = call.request.queryParameters
        val status =
            when (val s = p["status"] ?: RunStatus.CLOSED.wire) {
                "any" -> null
                else -> RunStatus.entries.firstOrNull { it.wire == s } ?: bad("unknown status $s")
            }
        val kind = p["kind"]?.also { if (it !in setOf("recipe", "query", "perform")) bad("unknown kind $it") }
        val since = p["since"]?.let { instant(it, "since") }
        val before = p["cursor"]?.let(::cursor)
        val limit =
            p["limit"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..500 } ?: bad("limit must be in 1..500") } ?: 100
        val caller = call.caller()
        val runs =
            withContext(Dispatchers.IO) {
                ledger.listRuns(caller.sub, p["deployment"], status, kind, since, before, limit + 1)
            }
        val page = runs.take(limit)
        val body =
            buildJsonObject {
                putJsonArray("runs") {
                    page.forEach { r ->
                        addJsonObject {
                            put("runId", r.id.toString())
                            put("kind", r.kind)
                            put("subject", r.subject)
                            r.program?.let { put("program", it) }
                            r.deployment?.let { put("deployment", it) }
                            put("status", r.status.wire)
                            r.origin?.let { put("origin", it) }
                            put("dryRun", r.dryRun)
                            put("startedAt", r.startedAt.toString())
                            r.endedAt?.let { put("endedAt", it.toString()) }
                            put("protocolRef", r.protocolRef)
                            r.error?.let { put("error", it) }
                        }
                    }
                }
                put(
                    "next",
                    if (runs.size > limit) JsonPrimitive(encode(page.last().startedAt, page.last().id)) else JsonNull,
                )
            }
        call.respond(body)
    }

    // T5 as re-cut (⚑AU-S31-1, 2026-11-14): the route exists; the rendering is S3.1b's (exporters S1.5.5 first).
    get("/automation/templates/{target}") {
        val target = call.parameters["target"].orEmpty()
        val reason = if (target in TEMPLATE_TARGETS) "templates-not-served" else "unknown-target"
        throw AutomationException(
            ErrorCode.TEMPLATES,
            if (target in
                TEMPLATE_TARGETS
            ) {
                "templates for $target are not served yet"
            } else {
                "no template target $target"
            },
            buildJsonObject {
                put("target", target)
                put("reason", reason)
            },
        )
    }
}

private fun instant(
    raw: String,
    name: String,
): Instant =
    try {
        Instant.parse(raw)
    } catch (_: DateTimeParseException) {
        bad("$name must be an ISO-8601 instant")
    }

private fun encode(
    at: Instant,
    id: UUID,
): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$at|$id".toByteArray(Charsets.UTF_8))

private fun cursor(raw: String): Pair<Instant, UUID> =
    try {
        val (at, id) = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8).split('|', limit = 2)
        Instant.parse(at) to UUID.fromString(id)
    } catch (_: Exception) {
        bad("cursor is not a runs-list cursor")
    }

private fun bad(message: String): Nothing = throw AutomationException(ErrorCode.BAD_REQUEST, message)
