// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.identity

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sun.net.httpserver.HttpServer
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * A Keycloak-shaped realm for the identity specs: an RSA key under a `kid`, its JWKS served over real HTTP (the
 * service's [org.tatrman.mcp.identity.HttpJwksKeySource] fetches it as it would the realm's), and tokens signed
 * with it. [close] stops the server.
 */
class TestRealm(
    val kid: String = "realm-k1",
) : AutoCloseable {
    val pair: KeyPair = rsaPair()
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/realms/hartland/protocol/openid-connect/certs") { exchange ->
                val body = jwks().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

    val jwksUrl = "http://127.0.0.1:${server.address.port}/realms/hartland/protocol/openid-connect/certs"

    /** A person's access token, as the realm issues one. */
    fun person(
        username: String = "maya",
        roles: List<String> = listOf("hartland-sales-managers"),
        audience: String? = AUDIENCE,
        expiresAt: Instant? = Instant.now().plusSeconds(300),
        issuer: String = ISSUER,
        signWith: KeyPair = pair,
        kid: String = this.kid,
        subject: String = "sub-$username",
    ): String =
        JWT
            .create()
            .withKeyId(kid)
            .withIssuer(issuer)
            .withSubject(subject)
            .withClaim("preferred_username", username)
            .withClaim("realm_access", mapOf("roles" to roles))
            .apply { audience?.let { withAudience(it) } }
            .apply { expiresAt?.let { withExpiresAt(Date.from(it)) } }
            .sign(Algorithm.RSA256(signWith.public as RSAPublicKey, signWith.private as RSAPrivateKey))

    /** A service account's client-credentials token (Keycloak: user `service-account-<client>`, claim `client_id`). */
    fun service(
        clientId: String = "svc-hartland-automation",
        roles: List<String> = listOf("hartland-ops"),
        expiresAt: Instant = Instant.now().plusSeconds(300),
    ): String =
        JWT
            .create()
            .withKeyId(kid)
            .withIssuer(ISSUER)
            .withSubject("sub-$clientId")
            .withAudience(AUDIENCE)
            .withClaim("preferred_username", "service-account-$clientId")
            .withClaim("client_id", clientId)
            .withClaim("azp", clientId)
            .withClaim("realm_access", mapOf("roles" to roles))
            .withExpiresAt(Date.from(expiresAt))
            .sign(Algorithm.RSA256(pair.public as RSAPublicKey, pair.private as RSAPrivateKey))

    private fun jwks(): String {
        val key = pair.public as RSAPublicKey
        val enc = Base64.getUrlEncoder().withoutPadding()

        fun unsigned(v: BigInteger) =
            v.toByteArray().let {
                if (it[0] ==
                    0.toByte()
                ) {
                    it.copyOfRange(1, it.size)
                } else {
                    it
                }
            }
        // Keycloak also publishes an encryption key; the source must keep only the signing one.
        return """{"keys":[
            {"kid":"enc-1","kty":"RSA","use":"enc","alg":"RSA-OAEP","n":"${enc.encodeToString(
            unsigned(key.modulus),
        )}","e":"AQAB"},
            {"kid":"$kid","kty":"RSA","use":"sig","alg":"RS256",
             "n":"${enc.encodeToString(
            unsigned(key.modulus),
        )}","e":"${enc.encodeToString(unsigned(key.publicExponent))}"}
        ]}"""
    }

    override fun close() = server.stop(0)

    companion object {
        const val ISSUER = "https://keycloak.hartland.example/realms/hartland"
        const val AUDIENCE = "automation"

        fun rsaPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        /** [token] with its payload swapped for [payloadJson], the original signature kept. */
        fun withPayload(
            token: String,
            payloadJson: String,
        ): String {
            val (header, _, signature) = token.split(".")
            val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray())
            return "$header.$payload.$signature"
        }

        fun payloadOf(token: String): String = String(Base64.getUrlDecoder().decode(token.split(".")[1]))
    }
}

/** A caller as the bearer check would hand it over, for the pure rule specs. */
fun caller(
    principal: String,
    roles: Set<String> = emptySet(),
    kind: TokenKind = TokenKind.PERSON,
) = Caller(sub = "sub-$principal", principal = principal, roles = roles, kind = kind, token = "t-$principal")
