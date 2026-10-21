// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sun.net.httpserver.HttpServer
import org.tatrman.mcp.identity.JwksBearerVerifier
import org.tatrman.mcp.identity.RsaKeySource
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

/** A test realm for the edge's authenticator: one signing key, tokens as Keycloak shapes them, a stub token endpoint. */
object AuthTestKit {
    const val ISSUER = "https://keycloak.example/realms/kantheon"

    val realm: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** Somebody else's key: a token it signs claims whatever it likes. */
    val otherRealm: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    val verifier =
        JwksBearerVerifier(
            RsaKeySource { kid -> (realm.public as RSAPublicKey).takeIf { kid == "k1" } },
            issuer = ISSUER,
        )

    /** An access token the realm signed, for [user] with [roles]. */
    fun token(
        user: String = "petr",
        roles: List<String> = listOf("kantheon-area-hartland", "kantheon-scope-dc-5"),
        expiresAt: Instant = Instant.now().plusSeconds(300),
        pair: KeyPair = realm,
    ): String =
        JWT
            .create()
            .withKeyId("k1")
            .withIssuer(ISSUER)
            .withSubject("8c1e-$user")
            .withClaim("preferred_username", user)
            .withClaim("realm_access", mapOf("roles" to roles))
            .withExpiresAt(Date.from(expiresAt))
            .sign(Algorithm.RSA256(pair.public as RSAPublicKey, pair.private as RSAPrivateKey))

    /**
     * Keycloak's token endpoint, as far as a password grant sees it: [users] (name → password) get a realm-signed
     * token, anyone else a 401 `invalid_grant`. Every form it received is kept.
     */
    class KeycloakStub(
        private val users: Map<String, String> = mapOf("petr" to "petr-secret"),
        private val roles: Map<String, List<String>> = mapOf("petr" to listOf("kantheon-area-hartland")),
    ) : AutoCloseable {
        val forms = CopyOnWriteArrayList<Map<String, String>>()
        private val server: HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/realms/kantheon/protocol/openid-connect/token") { exchange ->
                    val form =
                        exchange.requestBody
                            .readAllBytes()
                            .decodeToString()
                            .split('&')
                            .filter { it.contains('=') }
                            .associate {
                                URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                                    URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
                            }
                    forms += form
                    val user = form["username"].orEmpty()
                    val (status, body) =
                        if (form["grant_type"] == "password" &&
                            users[user] != null &&
                            users[user] == form["password"]
                        ) {
                            200 to """{"access_token":"${token(user, roles[user].orEmpty())}","expires_in":300}"""
                        } else {
                            401 to """{"error":"invalid_grant","error_description":"Invalid user credentials"}"""
                        }
                    val bytes = body.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }

        val tokenUrl: String
            get() = "http://127.0.0.1:${server.address.port}/realms/kantheon/protocol/openid-connect/token"

        override fun close() = server.stop(0)
    }
}
