// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.tatrman.mcp.identity.BearerVerifier
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.IdentityPolicy
import org.tatrman.mcp.identity.IdentityResolver
import org.tatrman.mcp.identity.JwksBearerVerifier
import org.tatrman.mcp.identity.Verification

private val log = LoggerFactory.getLogger("query-mcp.auth")

/**
 * LR G2b — the door's bearer verifier, or null when `security.verify-signature` is off (decode-only, as
 * before). On, the issuer is required: without it nothing could be checked, and a door that says it
 * verifies must not boot into one that cannot. The JWKS URI defaults to Keycloak's
 * `<issuer>/protocol/openid-connect/certs`.
 */
fun bearerVerifierOf(security: QueryMcpConfig.Security): BearerVerifier? {
    if (!security.verifySignature) return null
    val issuer =
        security.issuer.trim().ifBlank {
            error("query-mcp.security.verify-signature = true needs query-mcp.security.issuer (QUERY_MCP_AUTH_ISSUER)")
        }
    val jwks = security.jwksUri.trim().ifBlank { "${issuer.trimEnd('/')}/protocol/openid-connect/certs" }
    log.info("bearer signature verification ON (issuer={}, jwks={})", issuer, jwks)
    return JwksBearerVerifier(
        HttpJwksKeySource(jwks),
        issuer = issuer,
        audience =
            security.audience.trim().ifBlank {
                null
            },
    )
}

/**
 * Which identity sources the door trusts. A verifying door trusts the verified token ONLY: the
 * `X-User-Id` header, the `user_id` arg and the `admin:` id prefix are caller-controlled and unsigned,
 * and on a permissive door `user_id = "admin:x"` carries the validator's admin bypass role.
 */
fun identityPolicyOf(verifier: BearerVerifier?): IdentityPolicy =
    if (verifier != null) IdentityPolicy.TOKEN_ONLY else IdentityPolicy.PERMISSIVE

/**
 * LR G2b — a request presenting a bearer that does not verify is answered **401** before the MCP
 * transport sees it (RFC 6750 `invalid_token`). A request with no bearer passes: the identity gate
 * decides that one (`missing_user_identity` when identity is required), as before. The verification
 * may fetch the realm's keys, so it runs off the event loop.
 */
fun Application.installBearerVerification(verifier: BearerVerifier) {
    intercept(ApplicationCallPipeline.Plugins) {
        val token = IdentityResolver.bearerTokenOf(call.request.header(HttpHeaders.Authorization)) ?: return@intercept
        val verdict = withContext(Dispatchers.IO) { verifier.verify(token) }
        if (verdict is Verification.Invalid) {
            log.warn("bearer refused on {} ({})", call.request.path(), verdict.reason)
            call.response.header(HttpHeaders.WWWAuthenticate, """Bearer error="invalid_token"""")
            call.respondText(
                """{"error":"invalid_token","reason":"${verdict.reason}"}""",
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
            finish()
        }
    }
}
