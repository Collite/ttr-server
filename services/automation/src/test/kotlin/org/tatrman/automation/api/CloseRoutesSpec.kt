// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
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
import org.tatrman.automation.apply.*
import org.tatrman.automation.identity.*
import org.tatrman.automation.ledger.*
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import java.util.UUID

class CloseRoutesSpec :
    StringSpec({
        val realm = TestRealm()
        afterSpec { realm.close() }
        val ledger = mockk<Ledger>()
        val id = UUID.randomUUID()
        val token = realm.person()
        val result =
            org.tatrman.automation.runner.CloseResult(
                "closed",
                org.tatrman.automation.runner
                    .RunBatch(UUID.randomUUID().toString(), "preview", 0),
                "/automation/runs/$id/protocol",
            )
        val released = mutableListOf<UUID>()
        beforeEach {
            clearMocks(ledger)
            released.clear()
            every { ledger.closePreview(any(), any()) } answers {
                if (firstArg<UUID>() == id && secondArg<String>() == "sub-maya") result else throw NoSuchRun(firstArg())
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
                    routing {
                        authenticate(AUTOMATION_AUTH) {
                            closeRoutes(
                                org.tatrman.automation.runner
                                    .RunCloser(ledger) { released.add(it) },
                            )
                        }
                    }
                }
                block()
            }

        "close returns the durable preview and retries through fresh handlers without executing" {
            repeat(2) {
                api {
                    val response = client.post("/automation/runs/$id/close") { bearerAuth(token) }
                    response.status shouldBe HttpStatusCode.OK
                    Json.decodeFromString<org.tatrman.automation.runner.CloseResult>(response.bodyAsText()) shouldBe
                        result
                }
            }
            released shouldBe listOf(id, id)
            verify(exactly = 2) { ledger.closePreview(id, "sub-maya") }
            confirmVerified(ledger)
        }

        "close validates bearer and UUID before ledger access and keeps subject ownership on rename" {
            api {
                client.post("/automation/runs/$id/close").status shouldBe HttpStatusCode.Unauthorized
                client.post("/automation/runs/bad/close") { bearerAuth(token) }.status shouldBe
                    HttpStatusCode.BadRequest
                verify { ledger wasNot Called }
                client
                    .post("/automation/runs/$id/close") {
                        bearerAuth(realm.person(username = "renamed", subject = "sub-maya"))
                    }.status shouldBe HttpStatusCode.OK
                val hidden =
                    listOf(id, UUID.randomUUID()).map { handle ->
                        val response =
                            client.post("/automation/runs/$handle/close") {
                                bearerAuth(realm.person(username = "maya", subject = "other"))
                            }
                        response.status shouldBe HttpStatusCode.Forbidden
                        response.bodyAsText()
                    }
                hidden.distinct().size shouldBe 1
            }
            released shouldBe listOf(id)
        }

        "pending evaluation or missing batch answers 409 and never releases the executor" {
            for (refusal in listOf(IllegalRunTransition(id, RunStatus.RUNNING, RunStatus.CLOSED), NoRunBatch(id))) {
                every { ledger.closePreview(id, "sub-maya") } throws refusal
                api {
                    val response = client.post("/automation/runs/$id/close") { bearerAuth(token) }
                    response.status shouldBe HttpStatusCode.Conflict
                    Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject["code"] shouldBe
                        JsonPrimitive("close-not-ready")
                }
            }
            released shouldBe emptyList()
        }

        "a preexisting seal answers 409 and a refused close budget stays 504 without releasing" {
            for ((refusal, status, code) in listOf(
                Triple(ProtocolSealed(id), HttpStatusCode.Conflict, "run-closed"),
                Triple(
                    AutomationException(ErrorCode.BUDGET, "the run exceeded its batch budget"),
                    HttpStatusCode.GatewayTimeout,
                    "budget",
                ),
            )) {
                every { ledger.closePreview(id, "sub-maya") } throws refusal
                api {
                    val response = client.post("/automation/runs/$id/close") { bearerAuth(token) }
                    response.status shouldBe status
                    Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject["code"] shouldBe
                        JsonPrimitive(code)
                }
            }
            released shouldBe emptyList()
        }
    })
