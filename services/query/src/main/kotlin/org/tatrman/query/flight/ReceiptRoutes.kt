// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import org.tatrman.mcp.identity.BearerVerifier
import org.tatrman.mcp.identity.IdentityPolicy
import org.tatrman.mcp.identity.IdentityResolver
import org.tatrman.mcp.identity.Verification
import java.time.Instant
import java.time.format.DateTimeParseException

private val log = LoggerFactory.getLogger("org.tatrman.query.flight.ReceiptRoutes")

/** The realm role that may read receipts (C-6) — the door's own admin role. */
const val RECEIPTS_ROLE = "query-platform-admin"

/**
 * C-6 — `GET /receipts?correlation_id=…` or `?user_id=…[&since=<ISO-8601>][&limit=…]` on the query HTTP port: the
 * edge's receipts, newest first. Only a caller whose verified bearer carries [RECEIPTS_ROLE]: no or a bad bearer is
 * 401, any other caller 403. One of `correlation_id` / `user_id` is required — the route answers questions about a
 * statement or a person, it does not dump the table.
 */
fun Route.receiptRoutes(
    store: ReceiptStore,
    verifier: BearerVerifier,
) {
    get("/receipts") {
        val token = IdentityResolver.bearerTokenOf(call.request.header(HttpHeaders.Authorization))
        val verdict = token?.let { withContext(Dispatchers.IO) { verifier.verify(it) } }
        if (token == null || verdict is Verification.Invalid) {
            call.response.header(HttpHeaders.WWWAuthenticate, """Bearer error="invalid_token"""")
            val reason = (verdict as? Verification.Invalid)?.reason ?: "missing"
            return@get call.respond(HttpStatusCode.Unauthorized, error("invalid_token", "bearer $reason"))
        }
        val identity = IdentityResolver.resolve("Bearer $token", null, null, IdentityPolicy.TOKEN_ONLY)
        if (identity == null || RECEIPTS_ROLE !in identity.roles) {
            log.info("GET /receipts refused for {} (no {})", identity?.id, RECEIPTS_ROLE)
            return@get call.respond(HttpStatusCode.Forbidden, error("forbidden", "receipts need $RECEIPTS_ROLE"))
        }
        val params = call.request.queryParameters
        val correlationId = params["correlation_id"]?.takeIf { it.isNotBlank() }
        val userId = params["user_id"]?.takeIf { it.isNotBlank() }
        if (correlationId == null && userId == null) {
            return@get call.respond(HttpStatusCode.BadRequest, error("bad_request", "give correlation_id or user_id"))
        }
        val since =
            try {
                params["since"]?.takeIf { it.isNotBlank() }?.let(Instant::parse)
            } catch (_: DateTimeParseException) {
                return@get call.respond(HttpStatusCode.BadRequest, error("bad_request", "since is an ISO-8601 instant"))
            }
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT
        val receipts =
            try {
                withContext(Dispatchers.IO) { store.find(ReceiptQuery(correlationId, userId, since, limit)) }
            } catch (e: Exception) {
                log.warn("GET /receipts: the receipt store did not answer: {}", e.message)
                return@get call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    error("receipts_unavailable", "${e.message}"),
                )
            }
        call.respond(
            buildJsonObject {
                putJsonArray("receipts") { receipts.forEach { add(it.toJson()) } }
            },
        )
    }
}

private const val DEFAULT_LIMIT = 100
private const val MAX_LIMIT = 1000

private fun error(
    code: String,
    message: String,
): JsonObject =
    buildJsonObject {
        put("error", code)
        put("message", message)
    }

internal fun Receipt.toJson(): JsonObject =
    buildJsonObject {
        put("correlation_id", correlationId)
        put("ts", ts.toString())
        put("user_id", userId)
        putJsonArray("roles") { roles.forEach { add(it) } }
        put("world", world)
        put("connection_id", connectionId)
        put("client", client)
        put("statement", statement)
        put("executed_sql", executedSql)
        put("security_applied", securityApplied)
        put("rows_total", rowsTotal)
        put("top_n_applied", topNApplied)
        put("status", status)
        put("error_code", errorCode)
    }
