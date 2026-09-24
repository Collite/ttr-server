// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.admin

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Keycloak-JWT gate for the admin key API (D-5, contracts §1.8). Verifies an RS256 bearer against the
 * realm's public key (base64 X.509 SubjectPublicKeyInfo — what Keycloak's *Realm Settings → Keys* shows),
 * checks issuer/audience, and requires the realm role [requiredRole] (`llm-gateway-admin`, created in
 * Keycloak by Bora — LGQ-2). **In-service verification is the ONLY gate**: like the data plane (D-1), any
 * Envoy-injected identity header is ignored here — defense in depth (DQ-1).
 *
 * ⚑ The realm key is configured statically (`admin.realmPublicKey`); JWKS auto-rotation is a follow-up —
 * on a Keycloak key roll the config value must be updated + redeployed.
 */
class AdminAuth(
    issuer: String?,
    audience: String?,
    realmPublicKeyBase64: String,
    val requiredRole: String = "llm-gateway-admin",
) {
    sealed interface Result {
        data class Ok(
            val subject: String,
        ) : Result

        data object NoToken : Result // 401

        data object Invalid : Result // 401 — bad signature / issuer / audience / expiry

        data object Forbidden : Result // 403 — valid token, missing the admin role
    }

    /**
     * A verified realm JWT, read but NOT judged: who it names and which realm roles it carries (LC-1).
     * The admin plane judges it with [authenticate] (one role, unchanged); the prompt-log inspect surface
     * judges it per row (`PromptLogAccess`). Same verifier, same issuer/audience — one more acceptance
     * rule on the tokens the gateway already parses, no new issuer.
     */
    sealed interface Identity {
        data object NoToken : Identity // 401

        data object Invalid : Identity // 401 — bad signature / issuer / audience / expiry / not a JWT

        data class Verified(
            val subject: String?,
            val roles: Set<String>,
        ) : Identity
    }

    private val verifier: JWTVerifier =
        run {
            val keyBytes = Base64.getDecoder().decode(realmPublicKeyBase64.trim())
            val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes)) as RSAPublicKey
            var v = JWT.require(Algorithm.RSA256(pub, null))
            if (!issuer.isNullOrBlank()) v = v.withIssuer(issuer)
            if (!audience.isNullOrBlank()) v = v.withAudience(audience)
            v.acceptLeeway(30).build()
        }

    fun authenticate(bearerToken: String?): Result =
        when (val id = identify(bearerToken)) {
            Identity.NoToken -> Result.NoToken
            Identity.Invalid -> Result.Invalid
            is Identity.Verified -> if (requiredRole in id.roles) Result.Ok(id.subject ?: "?") else Result.Forbidden
        }

    fun identify(bearerToken: String?): Identity {
        val token = bearerToken ?: return Identity.NoToken
        val decoded =
            try {
                verifier.verify(token)
            } catch (e: JWTVerificationException) {
                return Identity.Invalid
            }
        return Identity.Verified(
            subject = decoded.subject?.takeIf { it.isNotBlank() },
            roles = realmRoles(decoded).toSet(),
        )
    }

    private fun realmRoles(jwt: DecodedJWT): List<String> {
        val realmAccess = jwt.getClaim("realm_access").asMap() ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return (realmAccess["roles"] as? List<String>) ?: emptyList()
    }
}
