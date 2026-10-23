// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

/**
 * The error codes of the run API (AU contracts §1, plus the legacy A-AG-10 three, §6's `connector-face`, §7's
 * 401 and the envelope's own `internal`). [stage] is the plan stage that first raises the code:
 * `ErrorEnvelopeSpec` holds every code of a delivered stage to at least one raising site and every other code
 * to none, so the catalogue and the code cannot drift apart as the phases land.
 */
enum class ErrorCode(
    val code: String,
    val status: HttpStatusCode,
    val stage: String,
) {
    // §1.1 — descriptor · refresh
    BRANCH_NOT_ALLOWED("branch-not-allowed", HttpStatusCode.Forbidden, "S0.2"),
    REFRESH_IN_PROGRESS("refresh-in-progress", HttpStatusCode.Conflict, "S0.2"),
    CHECKOUT_FAILED("checkout-failed", HttpStatusCode.BadGateway, "S0.2"),
    BUNDLE_INVALID("bundle-invalid", HttpStatusCode.UnprocessableEntity, "S0.2"),
    REFRESH_FORBIDDEN("refresh-forbidden", HttpStatusCode.Forbidden, "S0.2"),

    // §1.2 — runs
    PORT("port", HttpStatusCode.BadRequest, "S1.1"),
    AS_OF("as-of", HttpStatusCode.BadRequest, "S1.1"),
    BUNDLE("bundle", HttpStatusCode.BadRequest, "S4.2"),
    IDENTITY("identity", HttpStatusCode.Forbidden, "S0.3"),
    DISABLED("disabled", HttpStatusCode.Forbidden, "S1.1"),
    RECIPE("recipe", HttpStatusCode.NotFound, "S0.2"),
    DEPLOYMENT("deployment", HttpStatusCode.NotFound, "S0.2"),
    RECIPE_STALE("recipe-stale", HttpStatusCode.Conflict, "S1.1"),
    PROGRAM_STALE("program-stale", HttpStatusCode.Conflict, "S1.1"),
    DEPLOYMENT_STALE("deployment-stale", HttpStatusCode.Conflict, "S1.1"),
    PAUSED("paused", HttpStatusCode.ServiceUnavailable, "S3.3"),
    RUN_ABANDONED("run-abandoned", HttpStatusCode.ServiceUnavailable, "S1.3"),
    PROTOCOL_RENDERER_UNAVAILABLE("protocol-renderer-unavailable", HttpStatusCode.NotImplemented, "S1.3"),
    RESTRICTED_VIEW("restricted-view", HttpStatusCode.Conflict, "S1.2"),
    CANDIDATES_TRUNCATED("candidates-truncated", HttpStatusCode.Conflict, "S1.2"),
    OUT_OF_SCOPE_READ("out-of-scope-read", HttpStatusCode.UnprocessableEntity, "S1.2"),
    OUT_OF_SCOPE("out-of-scope", HttpStatusCode.UnprocessableEntity, "S1.2"),
    DUPLICATE_KEY("duplicate-key", HttpStatusCode.Conflict, "S1.2"),

    // A-AG-10 (the legacy automation API's codes, kept): the door refused a statement, a budget ran out, a
    // connector is down. `door-refused` keeps the legacy 502.
    DOOR_REFUSED("door-refused", HttpStatusCode.BadGateway, "S1.1"),
    BUDGET("budget", HttpStatusCode.GatewayTimeout, "S1.2"),
    CONNECTOR_UNAVAILABLE("connector-unavailable", HttpStatusCode.ServiceUnavailable, "S3.1"),

    // §6 — a deployment whose program needs a face its connector lacks (at /refresh).
    CONNECTOR_FACE("connector-face", HttpStatusCode.UnprocessableEntity, "S3.1"),

    // §7 — a missing, unsigned or tampered bearer, before any route.
    UNAUTHENTICATED("unauthenticated", HttpStatusCode.Unauthorized, "S0.3"),

    // The envelope's own: a request Ktor could not read (a malformed body, a content type no route takes), and
    // anything unexpected — each still answers in the envelope's shape.
    BAD_REQUEST("bad-request", HttpStatusCode.BadRequest, "S0.1"),
    UNSUPPORTED_MEDIA_TYPE("unsupported-media-type", HttpStatusCode.UnsupportedMediaType, "S0.1"),
    INTERNAL("internal", HttpStatusCode.InternalServerError, "S0.1"),
}

/** `{ error: { code, message, detail? } }` — every failure the API answers (AU contracts §1). */
@Serializable
data class ErrorEnvelope(
    val error: ErrorBody,
)

@Serializable
data class ErrorBody(
    val code: String,
    val message: String,
    val detail: JsonElement? = null,
)

/** A refusal the API answers as [ErrorCode.status] + [ErrorEnvelope]. */
class AutomationException(
    val code: ErrorCode,
    message: String,
    val detail: JsonElement? = null,
) : RuntimeException(message) {
    fun envelope(): ErrorEnvelope = ErrorEnvelope(ErrorBody(code.code, message ?: code.code, detail))
}

private val log = LoggerFactory.getLogger("org.tatrman.automation.api.Errors")

/**
 * One StatusPages handler, so every route renders a failure the same way. Ktor's own client errors stay client
 * errors in the envelope — a malformed body is 400, a content type no route reads 415 (Ktor's engine answers that
 * one itself, bare, before any handler sees the exception; the status handler dresses it) — never the catch-all's
 * 500, which a client would retry.
 */
fun StatusPagesConfig.installAutomationErrors() {
    exception<AutomationException> { call, e -> call.respond(e.code.status, e.envelope()) }
    exception<BadRequestException> { call, e ->
        val refusal = AutomationException(ErrorCode.BAD_REQUEST, e.message ?: "the request could not be read")
        call.respond(refusal.code.status, refusal.envelope())
    }
    exception<ContentTransformationException> { call, _ -> call.unsupportedMediaType() }
    status(HttpStatusCode.UnsupportedMediaType) { call, _ -> call.unsupportedMediaType() }
    exception<Throwable> { call, e ->
        log.error("unhandled failure on {} {}", call.request.local.method.value, call.request.local.uri, e)
        val internal = AutomationException(ErrorCode.INTERNAL, "the automation service failed; see its log")
        call.respond(internal.code.status, internal.envelope())
    }
}

private suspend fun ApplicationCall.unsupportedMediaType() {
    val refusal =
        AutomationException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "the request's content type cannot be read here")
    respond(refusal.code.status, refusal.envelope())
}
