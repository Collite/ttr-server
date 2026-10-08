// SPDX-License-Identifier: Apache-2.0
package org.tatrman.mcp.identity

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

private fun jwk(
    kid: String,
    pair: KeyPair,
    use: String = "sig",
): String {
    val pub = pair.public as RSAPublicKey
    val enc = Base64.getUrlEncoder().withoutPadding()

    fun b64(bytes: ByteArray) = enc.encodeToString(bytes.dropWhile { it == 0.toByte() }.toByteArray())
    return """{"kid":"$kid","kty":"RSA","alg":"RS256","use":"$use","n":"${b64(pub.modulus.toByteArray())}","e":"${b64(
        pub.publicExponent.toByteArray(),
    )}"}"""
}

/** A JWKS endpoint that serves whatever [body] says now and counts how often it was asked. */
private class FakeRealm : AutoCloseable {
    @Volatile var body: String = """{"keys":[]}"""
    val hits = AtomicInteger()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/certs") { ex ->
                hits.incrementAndGet()
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
    val uri = "http://127.0.0.1:${server.address.port}/certs"

    override fun close() = server.stop(0)
}

/**
 * LR G2b — the realm's keys, fetched and cached by `kid`. A rotation is picked up by re-fetching on an
 * unknown `kid`; a forged token's random `kid` must not buy a round trip to the issuer every time.
 */
class HttpJwksKeySourceSpec :
    StringSpec({
        val k1 = rsaPair()
        val k2 = rsaPair()

        "a signing key is found by kid, and fetched once" {
            FakeRealm().use { realm ->
                realm.body = """{"keys":[${jwk("k1", k1)}]}"""
                val source = HttpJwksKeySource(realm.uri)
                source.key("k1").shouldNotBeNull().modulus shouldBe (k1.public as RSAPublicKey).modulus
                source.key("k1").shouldNotBeNull()
                realm.hits.get() shouldBe 1
            }
        }

        "an encryption key (use = enc, Keycloak's RSA-OAEP) is not a signing key" {
            FakeRealm().use { realm ->
                realm.body = """{"keys":[${jwk("k-enc", k1, use = "enc")}]}"""
                HttpJwksKeySource(realm.uri).key("k-enc").shouldBeNull()
            }
        }

        "unknown kids re-fetch at most once per interval — forged kids cost the issuer nothing" {
            FakeRealm().use { realm ->
                realm.body = """{"keys":[${jwk("k1", k1)}]}"""
                var now = 0L
                val source = HttpJwksKeySource(realm.uri, Duration.ofSeconds(30), nowMillis = { now })
                repeat(50) { source.key("forged-$it").shouldBeNull() }
                realm.hits.get() shouldBe 1
                now += 31_000
                source.key("forged-x").shouldBeNull()
                realm.hits.get() shouldBe 2
            }
        }

        "a rotated key is picked up once the interval has passed, and the old one keeps working meanwhile" {
            FakeRealm().use { realm ->
                realm.body = """{"keys":[${jwk("k1", k1)}]}"""
                var now = 0L
                val source = HttpJwksKeySource(realm.uri, Duration.ofSeconds(30), nowMillis = { now })
                source.key("k1").shouldNotBeNull()
                realm.body = """{"keys":[${jwk("k1", k1)},${jwk("k2", k2)}]}"""
                now += 31_000
                source.key("k2").shouldNotBeNull().modulus shouldBe (k2.public as RSAPublicKey).modulus
                source.key("k1").shouldNotBeNull()
            }
        }

        "a realm that cannot be reached leaves the held keys in place" {
            val realm = FakeRealm()
            realm.body = """{"keys":[${jwk("k1", k1)}]}"""
            var now = 0L
            val source = HttpJwksKeySource(realm.uri, Duration.ofSeconds(30), nowMillis = { now })
            source.key("k1").shouldNotBeNull()
            realm.close()
            now += 31_000
            source.key("k2").shouldBeNull()
            source.key("k1").shouldNotBeNull()
        }

        "verified end to end: the verifier over the fetched keys accepts the realm's token" {
            FakeRealm().use { realm ->
                realm.body = """{"keys":[${jwk("k1", k1)}]}"""
                val verifier =
                    JwksBearerVerifier(
                        HttpJwksKeySource(realm.uri),
                        issuer = "https://keycloak.example/realms/kantheon",
                    )
                verifier.verify(signedToken(k1, kid = "k1")) shouldBe Verification.Valid
                verifier.verify(signedToken(k2, kid = "k1")) shouldBe Verification.Invalid("signature")
            }
        }
    })
