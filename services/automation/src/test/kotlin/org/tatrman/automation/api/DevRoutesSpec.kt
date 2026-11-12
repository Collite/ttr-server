// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.identity.AUTOMATION_AUTH
import org.tatrman.automation.identity.AutomationBearerProvider
import org.tatrman.automation.identity.CallerVerifier
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import org.tatrman.automation.identity.TestRealm
import org.tatrman.automation.ledger.HeldGroup
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.RunNames
import org.tatrman.automation.ledger.RunStatus
import org.tatrman.automation.runner.RunFingerprints
import org.tatrman.automation.writer.Apply
import org.tatrman.automation.writer.DeferredContexts
import org.tatrman.automation.writer.Held
import java.net.URLEncoder
import java.time.Duration
import java.util.UUID

/** S2.3 T3 — the dev face: 404 unless AUTOMATION_DEV_ROUTES=true; 403 identity without role.ops (D-138, the world's). */
class DevRoutesSpec :
    StringSpec({
        val realm = TestRealm()
        afterSpec { realm.close() }
        val bundle = BundleFixture.load()
        val id = UUID.randomUUID()
        val ledger = mockk<Ledger>()
        every { ledger.heldGroup(any(), any()) } answers {
            HeldGroup(
                id,
                UUID.randomUUID(),
                RunStatus.WAITING,
                "held",
                RunNames("release_order", "release_on_placement", "release_native_event", bundle.id, "maya"),
                RunFingerprints(null, null, null, null, null),
                emptyList(),
                emptyList(),
                emptyList(),
                Duration.ofMinutes(30),
            )
        }
        val held =
            Held(
                ledger,
                Apply(mockk(), BundleFixture.clock),
                DeferredContexts { _, _, _ -> error("no apply before the role check") },
                { bundle },
                BundleFixture.clock,
            )
        val group = URLEncoder.encode("""{"order_number":"4711"}""", Charsets.UTF_8)

        suspend fun post(
            enabled: Boolean,
            token: String,
        ): Pair<HttpStatusCode, String> {
            var answer: Pair<HttpStatusCode, String>? = null
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installAutomationErrors() }
                    install(Authentication) {
                        AutomationBearerProvider.register(
                            this,
                            CallerVerifier(
                                JwksBearerVerifier(
                                    HttpJwksKeySource(realm.jwksUrl),
                                    TestRealm.ISSUER,
                                    TestRealm.AUDIENCE,
                                ),
                            ),
                        )
                    }
                    routing { authenticate(AUTOMATION_AUTH) { devRoutes(enabled, held) } }
                }
                val response = client.post("/automation/dev/runs/$id/held/$group/commit") { bearerAuth(token) }
                answer = response.status to response.bodyAsText()
            }
            return answer!!
        }

        "the dev routes answer 404 unless AUTOMATION_DEV_ROUTES=true" {
            post(enabled = false, realm.person()).first shouldBe HttpStatusCode.NotFound
        }

        "a caller outside the world's role.ops is 403 identity, before any apply" {
            val (status, body) = post(enabled = true, realm.person(roles = listOf("hartland-sales-managers")))
            status shouldBe HttpStatusCode.Forbidden
            Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["code"] shouldBe JsonPrimitive("identity")
        }
    })
