// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.tatrman.mcp.identity.IdentityPolicy
import org.tatrman.mcp.identity.JwksBearerVerifier
import org.tatrman.mcp.identity.RsaKeySource
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date

private const val ISSUER = "https://keycloak.example/realms/kantheon"

private fun pair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

private fun token(
    signer: KeyPair,
    expiresAt: Instant = Instant.now().plusSeconds(300),
): String =
    JWT
        .create()
        .withKeyId("k1")
        .withIssuer(ISSUER)
        .withClaim("preferred_username", "petr")
        .withClaim("realm_access", mapOf("roles" to listOf("kantheon-area-hartland", "kantheon-scope-dc-5")))
        .withExpiresAt(Date.from(expiresAt))
        .sign(Algorithm.RSA256(signer.public as RSAPublicKey, signer.private as RSAPrivateKey))

/**
 * LR G2b (⚑LR-14) — query-mcp verifies the bearer it is handed. Before this, the door decoded the token's
 * payload and trusted it: a token signed with anyone's key, claiming `kantheon-scope-dc-5` or
 * `query-platform-admin`, was that user with those roles. The drill's third leg is this 401.
 */
class BearerAuthSpec :
    StringSpec({
        val realm = pair()
        val verifier =
            JwksBearerVerifier(
                RsaKeySource {
                    (realm.public as RSAPublicKey).takeIf { _ ->
                        it == "k1"
                    }
                },
                ISSUER,
            )

        fun door(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    installBearerVerification(verifier)
                    routing { get("/mcp") { call.respondText("reached the transport") } }
                }
                block()
            }

        "a forged bearer is answered 401 invalid_token before the transport sees it" {
            door {
                val response = client.get("/mcp") { header(HttpHeaders.Authorization, "Bearer ${token(pair())}") }
                response.status shouldBe HttpStatusCode.Unauthorized
                response.headers[HttpHeaders.WWWAuthenticate] shouldBe """Bearer error="invalid_token""""
                response.bodyAsText() shouldContain "signature"
            }
        }

        "an expired bearer is answered 401" {
            door {
                val stale = token(realm, expiresAt = Instant.now().minusSeconds(600))
                client.get("/mcp") { header(HttpHeaders.Authorization, "Bearer $stale") }.status shouldBe
                    HttpStatusCode.Unauthorized
            }
        }

        "the realm's own token passes through, as today" {
            door {
                val response = client.get("/mcp") { header(HttpHeaders.Authorization, "Bearer ${token(realm)}") }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "reached the transport"
            }
        }

        "no bearer passes the interceptor — the identity gate decides that one, as before" {
            door { client.get("/mcp").status shouldBe HttpStatusCode.OK }
        }

        "off by default: no verifier, and the permissive policy the door always had" {
            val off = QueryMcpConfig.Security(requireIdentity = true)
            bearerVerifierOf(off).shouldBeNull()
            identityPolicyOf(null) shouldBe IdentityPolicy.PERMISSIVE
        }

        "on: a verifier, and the door trusts the token alone" {
            val on = QueryMcpConfig.Security(requireIdentity = true, verifySignature = true, issuer = ISSUER)
            val built = bearerVerifierOf(on).shouldNotBeNull()
            identityPolicyOf(built) shouldBe IdentityPolicy.TOKEN_ONLY
        }

        "on without an issuer, the door refuses to boot rather than verify nothing" {
            shouldThrow<IllegalStateException> {
                bearerVerifierOf(QueryMcpConfig.Security(requireIdentity = true, verifySignature = true))
            }.message shouldContain "QUERY_MCP_AUTH_ISSUER"
        }
    })
