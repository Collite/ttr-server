// SPDX-License-Identifier: Apache-2.0
package org.tatrman.mcp.identity

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date

private const val ISSUER = "https://keycloak.example/realms/kantheon"

/** An RSA key pair, as a realm holds one. */
internal fun rsaPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

/** A realm-shaped access token signed with [pair] under [kid]. */
internal fun signedToken(
    pair: KeyPair,
    kid: String = "k1",
    issuer: String = ISSUER,
    audience: String? = null,
    expiresAt: Instant? = Instant.now().plusSeconds(300),
    roles: List<String> = listOf("kantheon-area-hartland"),
): String =
    JWT
        .create()
        .withKeyId(kid)
        .withIssuer(issuer)
        .withSubject("f1d2")
        .withClaim("preferred_username", "petr")
        .withClaim("realm_access", mapOf("roles" to roles))
        .apply { audience?.let { withAudience(it) } }
        .apply { expiresAt?.let { withExpiresAt(Date.from(it)) } }
        .sign(Algorithm.RSA256(pair.public as RSAPublicKey, pair.private as RSAPrivateKey))

/**
 * LR G2b — the verifier a door runs before it trusts a bearer's claims. Each refusal below is a way to
 * forge `realm_access.roles` that a decode-only door accepted: a key of your own, no signature at all, an
 * HS256 token keyed with the realm's PUBLIC key, someone else's issuer, a token that has expired.
 */
class JwksBearerVerifierSpec :
    StringSpec({
        val realm = rsaPair()
        val keys = RsaKeySource { kid -> (realm.public as RSAPublicKey).takeIf { kid == "k1" } }
        val verifier = JwksBearerVerifier(keys, issuer = ISSUER)

        "a token the realm signed, unexpired, from the configured issuer, is valid" {
            verifier.verify(signedToken(realm)) shouldBe Verification.Valid
        }

        "a token signed with another key under the realm's kid is refused (signature)" {
            verifier.verify(signedToken(rsaPair(), kid = "k1")) shouldBe Verification.Invalid("signature")
        }

        "an expired token is refused, beyond the clock-skew leeway" {
            verifier.verify(signedToken(realm, expiresAt = Instant.now().minusSeconds(120))) shouldBe
                Verification.Invalid("expired")
        }

        "a token inside the leeway still passes — the issuer's clock may run ahead" {
            verifier.verify(signedToken(realm, expiresAt = Instant.now().minusSeconds(5))) shouldBe Verification.Valid
        }

        "a token without exp is refused: a realm always sets it, so its absence is not the realm" {
            verifier.verify(signedToken(realm, expiresAt = null)) shouldBe Verification.Invalid("missing_exp")
        }

        "another issuer's token is refused even when its key happens to verify" {
            verifier.verify(signedToken(realm, issuer = "https://evil.example/realms/kantheon")) shouldBe
                Verification.Invalid("issuer")
        }

        "an unknown kid is refused without trying any key" {
            verifier.verify(signedToken(realm, kid = "k-forged")) shouldBe Verification.Invalid("unknown_kid")
        }

        "alg none is refused" {
            val enc = Base64.getUrlEncoder().withoutPadding()
            val header = enc.encodeToString("""{"alg":"none","kid":"k1"}""".toByteArray())
            val payload =
                enc.encodeToString(
                    """{"iss":"$ISSUER","preferred_username":"petr","exp":${Instant.now().epochSecond + 300}}"""
                        .toByteArray(),
                )
            verifier.verify("$header.$payload.") shouldBe Verification.Invalid("algorithm")
        }

        "HS256 keyed with the realm's public key (algorithm confusion) is refused" {
            val forged =
                JWT
                    .create()
                    .withKeyId("k1")
                    .withIssuer(ISSUER)
                    .withClaim("preferred_username", "petr")
                    .withExpiresAt(Date.from(Instant.now().plusSeconds(300)))
                    .sign(Algorithm.HMAC256(realm.public.encoded))
            verifier.verify(forged) shouldBe Verification.Invalid("algorithm")
        }

        "garbage is malformed, not an exception" {
            verifier.verify("not-a-jwt") shouldBe Verification.Invalid("malformed")
        }

        "with an audience configured, a token for another audience is refused" {
            val strict = JwksBearerVerifier(keys, issuer = ISSUER, audience = "query-mcp")
            strict.verify(signedToken(realm, audience = "query-mcp")) shouldBe Verification.Valid
            strict.verify(signedToken(realm, audience = "studio")) shouldBe Verification.Invalid("audience")
        }

        "a key source that cannot answer refuses, it does not accept on doubt" {
            val down = JwksBearerVerifier({ null }, issuer = ISSUER)
            down.verify(signedToken(realm)) shouldBe Verification.Invalid("unknown_kid")
        }
    })
