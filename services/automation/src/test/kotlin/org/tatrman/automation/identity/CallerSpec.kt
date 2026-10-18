// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.identity

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.automation.api.HealthSources
import org.tatrman.automation.api.SchedulerHealth
import org.tatrman.automation.api.ServedBundle
import org.tatrman.automation.api.descriptorRoutes
import org.tatrman.automation.api.healthRoutes
import org.tatrman.automation.api.installAutomationErrors
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import java.time.Instant
import java.util.Base64

/**
 * AU contracts §7 — the caller is the verified bearer. **This spec is the LR-14 verification for the automation
 * service** (S0.3 T1): the routes run behind the real provider, which fetches the realm's JWKS over HTTP and
 * verifies RS256, issuer, expiry and audience with `mcp-identity`'s verifier. Every forged shape below — a
 * tampered payload, a foreign key, no signature, an expired token, someone else's audience — is a 401 before any
 * route runs, in the run API's envelope.
 */
class CallerSpec :
    StringSpec({
        val realm = TestRealm()
        afterSpec { realm.close() }

        val health =
            object : HealthSources {
                override fun dbReachable() = true

                override fun queueDepths() = emptyMap<String, Long>()

                override fun abandoned() = 0L

                override fun served(): ServedBundle? = null

                override fun scheduler(): SchedulerHealth? = null

                override fun refreshFailed() = false

                override fun paused() = false
            }

        fun ApplicationTestBuilder.service() {
            application {
                install(ContentNegotiation) { json() }
                install(StatusPages) { installAutomationErrors() }
                val callers =
                    CallerVerifier(
                        JwksBearerVerifier(HttpJwksKeySource(realm.jwksUrl), TestRealm.ISSUER, TestRealm.AUDIENCE),
                    )
                install(Authentication) { AutomationBearerProvider.register(this, callers) }
                routing {
                    healthRoutes(health)
                    authenticate(AUTOMATION_AUTH) {
                        descriptorRoutes(paused = { false })
                        get("/automation/whoami") {
                            val c = call.caller()
                            call.respondText("${c.principal}|${c.kind}|${c.roles.sorted().joinToString(",")}|${c.sub}")
                        }
                    }
                }
            }
        }

        suspend fun ApplicationTestBuilder.get(
            path: String,
            token: String?,
        ): HttpResponse = client.get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

        suspend fun HttpResponse.refusalReason(): String {
            status shouldBe HttpStatusCode.Unauthorized
            headers[HttpHeaders.WWWAuthenticate] shouldBe "Bearer"
            val error = Json.parseToJsonElement(bodyAsText()).jsonObject["error"]!!.jsonObject
            error["code"] shouldBe JsonPrimitive("unauthenticated")
            return error["detail"]!!.jsonObject["reason"]!!.jsonPrimitive.content
        }

        "a valid RS256 bearer from the realm resolves the caller: principal, roles, person" {
            testApplication {
                service()
                val response =
                    get("/automation/whoami", realm.person("maya", listOf("sales_manager", "offline_access")))
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "maya|PERSON|offline_access,sales_manager|sub-maya"
            }
        }

        "a service account's token is a SERVICE caller named by its client id" {
            testApplication {
                service()
                get("/automation/whoami", realm.service("svc-hartland-automation")).bodyAsText() shouldBe
                    "svc-hartland-automation|SERVICE|ops|sub-svc-hartland-automation"
            }
        }

        "the descriptor answers a verified caller (nothing served before the first refresh)" {
            testApplication {
                service()
                val response = get("/automation/", realm.person())
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["served"] shouldBe kotlinx.serialization.json.JsonNull
                body["version"] shouldBe JsonPrimitive("v1")
            }
        }

        "no bearer → 401 missing, on every /automation route" {
            testApplication {
                service()
                get("/automation/", null).refusalReason() shouldBe "missing"
                client
                    .get(
                        "/automation/",
                    ) { header(HttpHeaders.Authorization, "Basic bWF5YTpwdw==") }
                    .refusalReason() shouldBe
                    "missing"
            }
        }

        "a tampered payload — roles added, the realm's signature kept — → 401 signature" {
            testApplication {
                service()
                val genuine = realm.person("maya", listOf("sales_manager"))
                val forged =
                    TestRealm.withPayload(
                        genuine,
                        TestRealm.payloadOf(genuine).replace("\"sales_manager\"", "\"sales_manager\",\"ops\""),
                    )
                TestRealm.payloadOf(forged) shouldContain "\"ops\""
                get("/automation/whoami", forged).refusalReason() shouldBe "signature"
            }
        }

        "a token signed by a foreign key under the realm's kid → 401 signature" {
            testApplication {
                service()
                get("/automation/", realm.person(signWith = TestRealm.rsaPair())).refusalReason() shouldBe "signature"
            }
        }

        "an unsigned token (alg none) → 401 algorithm" {
            testApplication {
                service()
                val enc = Base64.getUrlEncoder().withoutPadding()
                val header = enc.encodeToString("""{"alg":"none","kid":"${realm.kid}"}""".toByteArray())
                val unsigned = "$header.${realm.person().split(".")[1]}."
                get("/automation/", unsigned).refusalReason() shouldBe "algorithm"
            }
        }

        "an expired token → 401 expired" {
            testApplication {
                service()
                get("/automation/", realm.person(expiresAt = Instant.now().minusSeconds(300))).refusalReason() shouldBe
                    "expired"
            }
        }

        "a token for another audience → 401 audience" {
            testApplication {
                service()
                get("/automation/", realm.person(audience = "account")).refusalReason() shouldBe "audience"
            }
        }

        "another issuer's token → 401 issuer; an unknown kid → 401 unknown_kid; garbage → 401 malformed" {
            testApplication {
                service()
                get(
                    "/automation/",
                    realm.person(issuer = "https://evil.example/realms/hartland"),
                ).refusalReason() shouldBe
                    "issuer"
                get("/automation/", realm.person(kid = "forged-kid")).refusalReason() shouldBe "unknown_kid"
                get("/automation/", "not-a-jwt").refusalReason() shouldBe "malformed"
            }
        }

        "the probes and /automation/health stay public" {
            testApplication {
                service()
                get("/health", null).status shouldBe HttpStatusCode.OK
                get("/ready", null).status shouldBe HttpStatusCode.OK
                get("/automation/health", null).status shouldBe HttpStatusCode.OK
            }
        }

        "the bearer never reaches a log line: Caller.toString leaves the token out" {
            val token = realm.person()
            val c = Caller.of(token)!!
            c.toString().contains(token) shouldBe false
        }
    })
