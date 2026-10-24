// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.protocol

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
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
import io.mockk.*
import kotlinx.serialization.json.*
import org.tatrman.automation.api.installAutomationErrors
import org.tatrman.automation.api.protocolRoutes
import org.tatrman.automation.identity.*
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.NoSuchRun
import org.tatrman.automation.ledger.ProtocolSection
import org.tatrman.automation.runner.BoundValue
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class ProtocolRoutesSpec :
    StringSpec({
        val realm = TestRealm()
        afterSpec { realm.close() }
        val id = UUID.randomUUID()
        val token = realm.person()
        val document =
            ProtocolDocument(
                id.toString(),
                ProtocolSection.entries.associate { section ->
                    section.wire to
                        if (section == ProtocolSection.AUTOMATION) {
                            listOf(
                                ProtocolEntry(1, "2026-10-23T12:00:00Z", buildJsonObject { put("event", "opened") }),
                            )
                        } else {
                            emptyList()
                        }
                },
            )
        val ledger = mockk<Ledger>()
        beforeEach {
            clearMocks(ledger)
            every { ledger.readProtocol(any(), any()) } answers {
                if (firstArg<UUID>() == id &&
                    secondArg<String>() == "sub-maya"
                ) {
                    document
                } else {
                    throw NoSuchRun(firstArg())
                }
            }
        }

        suspend fun api(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
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
                    routing { authenticate(AUTOMATION_AUTH) { protocolRoutes(ProtocolRecorder(ledger)) } }
                }
                block()
            }

        "the authenticated reader serializes protocol/v1 and all nine ordered sections without an execution session" {
            repeat(2) {
                // A fresh Ktor application/recorder reads the same durable evidence after restart.
                api {
                    val response = client.get("/automation/runs/$id/protocol") { bearerAuth(token) }
                    response.status shouldBe HttpStatusCode.OK
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    body["schema"] shouldBe JsonPrimitive("protocol/v1")
                    body["sections"]!!.jsonObject.keys.toList() shouldBe ProtocolSection.entries.map { it.wire }
                    body["sections"]!!.jsonObject["receipts"] shouldBe JsonArray(emptyList())
                    response.bodyAsText().contains(token) shouldBe false
                }
            }
            verify(exactly = 2) { ledger.readProtocol(id, "sub-maya") }
            confirmVerified(ledger)
        }

        "verification and UUID validation happen before any ledger read" {
            api {
                client.get("/automation/runs/$id/protocol").status shouldBe HttpStatusCode.Unauthorized
                client.get("/automation/runs/not-a-uuid/protocol") { bearerAuth(token) }.status shouldBe
                    HttpStatusCode.BadRequest
            }
            verify { ledger wasNot Called }
        }

        "a renamed caller keeps access, and a reused display principal cannot read the original subject's protocol" {
            api {
                client
                    .get("/automation/runs/$id/protocol") {
                        bearerAuth(realm.person(username = "renamed", subject = "sub-maya"))
                    }.status shouldBe HttpStatusCode.OK
                val hidden = mutableListOf<String>()
                for (url in listOf(
                    "/automation/runs/$id/protocol",
                    "/automation/runs/$id/protocol?render=pt",
                    "/automation/runs/${UUID.randomUUID()}/protocol",
                )) {
                    val response =
                        client.get(
                            url,
                        ) { bearerAuth(realm.person(username = "maya", subject = "different-subject")) }
                    response.status shouldBe HttpStatusCode.Forbidden
                    hidden += response.bodyAsText()
                }
                hidden.distinct().size shouldBe 1
            }
        }

        "raw rendering works, PT refuses explicitly until configured and unknown rendering is a client error" {
            api {
                client.get("/automation/runs/$id/protocol?render=raw") { bearerAuth(token) }.status shouldBe
                    HttpStatusCode.OK
                val pt = client.get("/automation/runs/$id/protocol?render=pt") { bearerAuth(token) }
                pt.status shouldBe HttpStatusCode.NotImplemented
                Json.parseToJsonElement(pt.bodyAsText()).jsonObject["error"]!!.jsonObject["code"] shouldBe
                    JsonPrimitive("protocol-renderer-unavailable")
                client.get("/automation/runs/$id/protocol?render=unknown") { bearerAuth(token) }.status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        "declared parameter values retain nulls, exact decimals and canonical temporal values" {
            val params =
                ProtocolRecorder.parameters(
                    mapOf(
                        "amount" to BoundValue("decimal", BigDecimal("12345678901234567890.123456789")),
                        "as_of" to BoundValue("timestamptz", Instant.parse("2026-10-23T12:00:00Z")),
                        "optional" to BoundValue("text", null),
                    ),
                )
            Json
                .encodeToJsonElement(params)
                .jsonObject["amount"]!!
                .jsonObject["value"]
                .toString() shouldBe
                "12345678901234567890.123456789"
            params.getValue("as_of").value shouldBe JsonPrimitive("2026-10-23T12:00:00Z")
            params.getValue("optional").value shouldBe JsonNull
        }
    })
