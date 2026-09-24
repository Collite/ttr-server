// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.admin

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date

/**
 * LG-P4·S3·T1 — the Keycloak-JWT gate (contracts §1.8). RS256 keys are generated in-test (no live IdP);
 * only a token that is well-signed, in-issuer/audience, unexpired AND carries the `llm-gateway-admin`
 * realm role is `Ok`. Everything else maps to NoToken (401) / Invalid (401) / Forbidden (403).
 */
class AdminAuthSpec :
    StringSpec({

        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pubB64 = Base64.getEncoder().encodeToString(kp.public.encoded)
        val alg = Algorithm.RSA256(kp.public as RSAPublicKey, kp.private as RSAPrivateKey)
        val iss = "https://kc/realms/tatrman"
        val aud = "llm-gateway"

        fun token(
            roles: List<String>,
            issuer: String? = iss,
            audience: String? = aud,
            algorithm: Algorithm = alg,
            expiresAt: Date = Date(System.currentTimeMillis() + 3_600_000),
        ): String {
            var b =
                JWT
                    .create()
                    .withSubject("admin-user")
                    .withExpiresAt(expiresAt)
                    .withClaim("realm_access", mapOf<String, Any>("roles" to roles))
            if (issuer != null) b = b.withIssuer(issuer)
            if (audience != null) b = b.withAudience(audience)
            return b.sign(algorithm)
        }

        val auth = AdminAuth(iss, aud, pubB64, "llm-gateway-admin")

        "no token → NoToken (401)" {
            auth.authenticate(null) shouldBe AdminAuth.Result.NoToken
        }
        "valid token with the admin role → Ok" {
            auth
                .authenticate(
                    token(listOf("llm-gateway-admin", "default-roles")),
                ).shouldBeInstanceOf<AdminAuth.Result.Ok>()
        }
        "valid token WITHOUT the admin role → Forbidden (403)" {
            auth.authenticate(token(listOf("some-other-role"))) shouldBe AdminAuth.Result.Forbidden
        }
        "wrong issuer → Invalid (401)" {
            auth.authenticate(token(listOf("llm-gateway-admin"), issuer = "https://evil/realms/x")) shouldBe
                AdminAuth.Result.Invalid
        }
        "wrong audience → Invalid" {
            auth.authenticate(token(listOf("llm-gateway-admin"), audience = "some-other-aud")) shouldBe
                AdminAuth.Result.Invalid
        }
        "expired token → Invalid" {
            auth.authenticate(token(listOf("llm-gateway-admin"), expiresAt = Date(0))) shouldBe AdminAuth.Result.Invalid
        }
        "signature from a different key → Invalid" {
            val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val badAlg = Algorithm.RSA256(other.public as RSAPublicKey, other.private as RSAPrivateKey)
            auth.authenticate(token(listOf("llm-gateway-admin"), algorithm = badAlg)) shouldBe AdminAuth.Result.Invalid
        }
        "garbage token → Invalid (never throws)" {
            auth.authenticate("not.a.jwt") shouldBe AdminAuth.Result.Invalid
        }

        // ── LC-1: identify() reads a verified token without judging it ────────────────────────────

        "identify: a valid token WITHOUT the admin role is Verified (subject + roles), not Forbidden" {
            auth.identify(token(listOf("default-roles"))) shouldBe
                AdminAuth.Identity.Verified("admin-user", setOf("default-roles"))
        }
        "identify: the verifier is the same — wrong issuer, expiry, foreign key and garbage are all Invalid" {
            auth.identify(token(listOf("x"), issuer = "https://evil/realms/x")) shouldBe AdminAuth.Identity.Invalid
            auth.identify(token(listOf("x"), expiresAt = Date(0))) shouldBe AdminAuth.Identity.Invalid
            auth.identify("ttrk-0123456789abcdef") shouldBe AdminAuth.Identity.Invalid // a gateway API key
            auth.identify(null) shouldBe AdminAuth.Identity.NoToken
        }
        "identify: a token without `sub` is Verified with a null subject (never the admin gate's \"?\")" {
            val noSub =
                JWT
                    .create()
                    .withIssuer(iss)
                    .withAudience(aud)
                    .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000))
                    .withClaim("realm_access", mapOf<String, Any>("roles" to listOf("default-roles")))
                    .sign(alg)
            auth.identify(noSub) shouldBe AdminAuth.Identity.Verified(null, setOf("default-roles"))
        }
    })
