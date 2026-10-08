// SPDX-License-Identifier: Apache-2.0
package org.tatrman.mcp.identity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.util.Base64

/**
 * A realm's signing keys, fetched from its JWKS endpoint (Keycloak: `<issuer>/protocol/openid-connect/certs`)
 * and cached by `kid`. An unknown `kid` re-fetches the set — that is how a key rotation is picked up — but
 * at most once per [minRefreshInterval]: every forged token can carry a fresh random `kid`, and without the
 * floor each one would cost a blocking round trip to the issuer.
 *
 * Only RSA signing keys are kept: Keycloak also publishes an `RSA-OAEP` key with `use = enc`, which signs
 * nothing. A failed fetch keeps the keys already held (and logs); construction does no I/O.
 */
class HttpJwksKeySource(
    private val jwksUri: String,
    private val minRefreshInterval: Duration = Duration.ofSeconds(30),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : RsaKeySource {
    private val log = LoggerFactory.getLogger(HttpJwksKeySource::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var keys: Map<String, RSAPublicKey> = emptyMap()

    private var lastAttempt: Long? = null

    override fun key(kid: String): RSAPublicKey? = keys[kid] ?: refreshIfDue()[kid]

    @Synchronized
    private fun refreshIfDue(): Map<String, RSAPublicKey> {
        val now = nowMillis()
        lastAttempt?.let { if (now - it < minRefreshInterval.toMillis()) return keys }
        lastAttempt = now
        runCatching {
            val request =
                HttpRequest
                    .newBuilder(URI.create(jwksUri))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
            parse(response.body())
        }.onSuccess { keys = it }
            .onFailure {
                log.warn(
                    "JWKS fetch from {} failed ({}) — keeping {} key(s)",
                    jwksUri,
                    it.message,
                    keys.size,
                )
            }
        return keys
    }

    internal fun parse(body: String): Map<String, RSAPublicKey> =
        json
            .parseToJsonElement(body)
            .jsonObject["keys"]
            ?.jsonArray
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.str("kty") == "RSA" && it.str("use").let { use -> use == null || use == "sig" } }
            .mapNotNull { jwk ->
                val kid = jwk.str("kid") ?: return@mapNotNull null
                val n = jwk.str("n") ?: return@mapNotNull null
                val e = jwk.str("e") ?: return@mapNotNull null
                runCatching { kid to rsaKey(n, e) }.getOrNull()
            }.toMap()

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun rsaKey(
        n: String,
        e: String,
    ): RSAPublicKey {
        val decoder = Base64.getUrlDecoder()
        val spec = RSAPublicKeySpec(BigInteger(1, decoder.decode(n)), BigInteger(1, decoder.decode(e)))
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }
}
