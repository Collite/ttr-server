// SPDX-License-Identifier: Apache-2.0
package org.tatrman.mcp.identity

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import java.security.interfaces.RSAPublicKey

/**
 * Checks a bearer JWT before any of its claims is trusted. Without one, [IdentityResolver] only DECODES
 * the token: whoever can reach the door can claim any user and any realm role, row-policy roles
 * included. A door that verifies passes one to [IdentityGate.decide], and a bearer that fails is
 * refused outright — it never falls through to the `X-User-Id` header or the `user_id` arg.
 */
fun interface BearerVerifier {
    fun verify(token: String): Verification
}

/** What [BearerVerifier.verify] decided. [Invalid.reason] is a short category, safe to log and to return. */
sealed interface Verification {
    data object Valid : Verification

    data class Invalid(
        val reason: String,
    ) : Verification
}

/** RSA public keys by JWT `kid` — a realm's JWKS, or a fixed set in a test. */
fun interface RsaKeySource {
    fun key(kid: String): RSAPublicKey?
}

/**
 * RS256 verification against a realm's keys (Keycloak signs access tokens with RS256 by default).
 *
 * In order: the header names `RS256` (so `none` and an HS256 token keyed with the public key are
 * refused before any key is looked up); a `kid` that [keys] resolves; the signature; `exp` present and
 * not past; `iss` equal to [issuer]; `aud` containing [audience] when one is configured. Any failure —
 * a key source that cannot answer included — is [Verification.Invalid]: refused, never accepted on doubt.
 *
 * [leewaySeconds] absorbs clock skew between the issuer and this pod on `exp` / `nbf` / `iat`.
 */
class JwksBearerVerifier(
    private val keys: RsaKeySource,
    private val issuer: String,
    private val audience: String? = null,
    private val leewaySeconds: Long = 30,
) : BearerVerifier {
    init {
        require(issuer.isNotBlank()) { "a verifying door must name the issuer it trusts" }
    }

    override fun verify(token: String): Verification {
        val decoded =
            try {
                JWT.decode(token)
            } catch (_: JWTDecodeException) {
                return Verification.Invalid("malformed")
            }
        if (decoded.algorithm != "RS256") return Verification.Invalid("algorithm")
        val kid = decoded.keyId ?: return Verification.Invalid("no_kid")
        val key = keys.key(kid) ?: return Verification.Invalid("unknown_kid")
        val verifier =
            JWT
                .require(Algorithm.RSA256(key, null))
                .withIssuer(issuer)
                .withClaimPresence("exp")
                .acceptLeeway(leewaySeconds)
                .apply { audience?.takeIf { it.isNotBlank() }?.let { withAudience(it) } }
                .build()
        return try {
            verifier.verify(decoded)
            Verification.Valid
        } catch (_: TokenExpiredException) {
            Verification.Invalid("expired")
        } catch (e: JWTVerificationException) {
            Verification.Invalid(category(e))
        }
    }

    private fun category(e: JWTVerificationException): String =
        when (e) {
            is com.auth0.jwt.exceptions.SignatureVerificationException -> "signature"
            is com.auth0.jwt.exceptions.IncorrectClaimException ->
                when (e.claimName) {
                    "iss" -> "issuer"
                    "aud" -> "audience"
                    else -> "claim"
                }
            is com.auth0.jwt.exceptions.MissingClaimException -> "missing_${e.claimName}"
            else -> "invalid"
        }
}
