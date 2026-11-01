// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.identity

import com.auth0.jwt.JWT
import com.auth0.jwt.interfaces.DecodedJWT
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.mcp.identity.BearerVerifier
import org.tatrman.mcp.identity.IdentityResolver
import org.tatrman.mcp.identity.Verification

/** A person's own token, or a service principal's client-credentials token (Keycloak's service account). */
enum class TokenKind { PERSON, SERVICE }

/**
 * The caller of the run API = the verified bearer (AU contracts §7, D-94) — never an asserted field. [principal]
 * is what grants and identities name: a person's `preferred_username`, a service account's client id (Keycloak
 * names its user `service-account-<client id>`). [roles] are the realm roles; [token] is the bearer itself, which
 * a run `as: caller` hands on to the door.
 */
data class Caller(
    val sub: String,
    val principal: String,
    val roles: Set<String>,
    val kind: TokenKind,
    val token: String,
    val groups: Set<String> = emptySet(),
) {
    /** The bearer stays out of logs and protocol records. */
    override fun toString(): String = "Caller(sub=$sub, principal=$principal, roles=$roles, kind=$kind)"

    companion object {
        private const val SERVICE_ACCOUNT_PREFIX = "service-account-"

        /** Reads a token the verifier has accepted; null when it names nobody (no `sub`). */
        fun of(
            token: String,
            decoded: DecodedJWT = JWT.decode(token),
        ): Caller? {
            val sub = decoded.subject?.takeIf { it.isNotBlank() } ?: return null
            val username = decoded.getClaim("preferred_username").asString()
            val clientId = decoded.getClaim("client_id").asString() ?: decoded.getClaim("clientId").asString()
            val service = clientId != null || username?.startsWith(SERVICE_ACCOUNT_PREFIX) == true
            val principal =
                if (service) {
                    clientId ?: username?.removePrefix(SERVICE_ACCOUNT_PREFIX) ?: decoded.getClaim("azp").asString()
                        ?: sub
                } else {
                    username ?: sub
                }
            val roles =
                decoded
                    .getClaim("realm_access")
                    .asMap()
                    ?.get("roles")
                    .let { it as? List<*> }
                    .orEmpty()
                    .filterIsInstance<String>()
                    .toSet()
            val groups =
                decoded
                    .getClaim("groups")
                    .asList(Any::class.java)
                    .orEmpty()
                    .filterIsInstance<String>()
                    .map { it.removePrefix("/") }
                    .toSet()
            return Caller(sub, principal, roles, if (service) TokenKind.SERVICE else TokenKind.PERSON, token, groups)
        }
    }
}

/** What the bearer check decided; [Refused.reason] is the verifier's category (`signature`, `expired`, …). */
sealed interface CallerCheck {
    data class Verified(
        val caller: Caller,
    ) : CallerCheck

    data class Refused(
        val reason: String,
    ) : CallerCheck
}

/**
 * The bearer → [Caller] step: an `Authorization: Bearer` header, the token verified by [verifier] (RS256 against
 * the realm's JWKS, issuer, expiry, audience — `mcp-identity`'s LR G2b verifier, the LR-14 precondition), then
 * its claims read. A missing header, another scheme, a token the verifier refuses or one naming nobody is
 * [CallerCheck.Refused] — there is no fallback to a header or an argument.
 */
class CallerVerifier(
    private val verifier: BearerVerifier,
) {
    fun check(authorization: String?): CallerCheck {
        val token = IdentityResolver.bearerTokenOf(authorization) ?: return CallerCheck.Refused("missing")
        return when (val verdict = verifier.verify(token)) {
            is Verification.Invalid -> CallerCheck.Refused(verdict.reason)
            Verification.Valid ->
                Caller.of(token)?.let { CallerCheck.Verified(it) }
                    ?: CallerCheck.Refused("missing_sub")
        }
    }
}

/** The Ktor provider name every route under `/automation` except `/automation/health` is authenticated under. */
const val AUTOMATION_AUTH = "automation-bearer"

/**
 * Ktor's [AuthenticationProvider] over [CallerVerifier]: a verified bearer becomes the call's [Caller]; anything
 * else answers `401 unauthenticated` in the run API's envelope (with `WWW-Authenticate: Bearer`) before any route
 * runs.
 */
class AutomationBearerProvider private constructor(
    config: Config,
) : AuthenticationProvider(config) {
    private val callers = config.callers

    class Config internal constructor(
        name: String?,
        val callers: CallerVerifier,
    ) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        when (val check = callers.check(context.call.request.headers[HttpHeaders.Authorization])) {
            is CallerCheck.Verified -> context.principal(name, check.caller)
            is CallerCheck.Refused ->
                context.challenge(
                    name ?: AUTOMATION_AUTH,
                    AuthenticationFailedCause.InvalidCredentials,
                ) { challenge, call ->
                    val refusal = unauthenticated(check.reason)
                    call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
                    call.respond(refusal.code.status, refusal.envelope())
                    challenge.complete()
                }
        }
    }

    companion object {
        fun register(
            auth: AuthenticationConfig,
            callers: CallerVerifier,
            name: String = AUTOMATION_AUTH,
        ) = auth.register(AutomationBearerProvider(Config(name, callers)))
    }
}

private fun unauthenticated(reason: String) =
    AutomationException(
        ErrorCode.UNAUTHENTICATED,
        "a verified bearer token is required",
        buildJsonObject { put("reason", reason) },
    )

/** The verified caller of an authenticated route. */
fun ApplicationCall.caller(): Caller =
    checkNotNull(principal<Caller>(AUTOMATION_AUTH)) {
        "route not under $AUTOMATION_AUTH"
    }
