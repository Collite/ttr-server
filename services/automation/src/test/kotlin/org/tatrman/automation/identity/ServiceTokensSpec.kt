// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.identity

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.tatrman.keycloak.auth.TokenProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** §7: a named identity's credential → its client-credentials token, fetched once and cached until its expiry. */
class ServiceTokensSpec :
    StringSpec({
        val realm = TestRealm()
        afterSpec { realm.close() }

        class MovableClock(
            var now: Instant,
        ) : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant() = now
        }

        val start = Instant.parse("2026-10-18T10:00:00Z")

        "credentials parse as id:secret; the secret may hold colons; anything else is not a credential" {
            ClientCredential.parse("svc-a:s3:cr:et") shouldBe ClientCredential("svc-a", "s3:cr:et")
            ClientCredential.parse("postgresql://u:p@pg:5432/db") shouldBe null
            ClientCredential.parse("no-colon") shouldBe null
            ClientCredential.parse(":secret") shouldBe null
            ClientCredential.parse("id:") shouldBe null
            ClientCredential("svc-a", "s3cret").toString().contains("s3cret") shouldBe false
        }

        "a credential is found by its env name or its bare name" {
            val tokens = ServiceTokens(mapOf("AUTOMATION" to "svc-hartland-automation:s"), fetch = { "" })
            tokens.credential("TTR_CONN_AUTOMATION") shouldBe ClientCredential("svc-hartland-automation", "s")
            tokens.credential("AUTOMATION") shouldBe ClientCredential("svc-hartland-automation", "s")
            tokens.credential("TTR_CONN_MAILER") shouldBe null
        }

        "the token is fetched once and served from the cache until 30 s before its exp" {
            val clock = MovableClock(start)
            val fetched = AtomicInteger()
            val tokens =
                ServiceTokens(
                    mapOf("AUTOMATION" to "svc-hartland-automation:s"),
                    fetch = { c ->
                        fetched.incrementAndGet()
                        realm.service(c.clientId, expiresAt = clock.now.plusSeconds(300))
                    },
                    clock = clock,
                )
            val first = tokens.token("TTR_CONN_AUTOMATION")
            tokens.token("AUTOMATION") shouldBe first
            fetched.get() shouldBe 1
            clock.now = start.plusSeconds(269)
            tokens.token("AUTOMATION") shouldBe first
            fetched.get() shouldBe 1
            clock.now = start.plusSeconds(270)
            tokens.token("AUTOMATION")
            fetched.get() shouldBe 2
        }

        "keycloak: one provider (one HTTP client) per credential, reused by every fetch" {
            val built = mutableListOf<String>()
            val fetched = AtomicInteger()
            // No `exp`, so nothing is cached and every call fetches.
            val unexpiring = JWT.create().withSubject("svc").sign(Algorithm.HMAC256("k"))
            val tokens =
                ServiceTokens.keycloak(
                    mapOf("AUTOMATION" to "svc-a:s", "MAILER" to "svc-m:s"),
                    tokenUrl = "http://keycloak.invalid/token",
                    provider = { c ->
                        built += c.clientId
                        object : TokenProvider {
                            override suspend fun getToken(): String = unexpiring.also { fetched.incrementAndGet() }
                        }
                    },
                )
            repeat(3) { tokens.token("AUTOMATION") }
            tokens.token("MAILER")
            fetched.get() shouldBe 4
            built shouldBe listOf("svc-a", "svc-m")
        }

        "concurrent first calls fetch once" {
            val fetched = AtomicInteger()
            val tokens =
                ServiceTokens(
                    mapOf("AUTOMATION" to "svc-hartland-automation:s"),
                    fetch = { c ->
                        fetched.incrementAndGet()
                        delay(50)
                        realm.service(c.clientId)
                    },
                )
            coroutineScope { (1..8).map { async { tokens.token("AUTOMATION") } }.awaitAll() }.toSet().size shouldBe 1
            fetched.get() shouldBe 1
        }
    })
