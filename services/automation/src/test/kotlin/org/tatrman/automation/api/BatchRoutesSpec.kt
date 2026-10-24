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
import org.tatrman.automation.runner.RunStatusReader
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import java.util.UUID

class BatchRoutesSpec :
    StringSpec({
        val realm = TestRealm()
        afterSpec { realm.close() }
        val ledger = mockk<Ledger>()
        val id = UUID.randomUUID()
        val token = realm.person()
        val batch =
            RunBatchPlan(
                UUID.randomUUID().toString(),
                id.toString(),
                listOf(
                    ApplyRow(
                        1,
                        "er.entity.order",
                        "update",
                        key = buildJsonObject { put("order_number", "4711") },
                        set = buildJsonObject { put("held_for", "Sales") },
                    ),
                ),
                "preview",
            )
        beforeEach {
            clearMocks(ledger)
            every { ledger.readRunBatch(any(), any()) } answers {
                if (firstArg<UUID>() == id && secondArg<String>() == "sub-maya") batch else throw NoSuchRun(firstArg())
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
                    routing { authenticate(AUTOMATION_AUTH) { batchRoutes(RunStatusReader(ledger)) } }
                }
                block()
            }

        "authenticated batch reads survive fresh readers and return the full ordered apply subset without an executor" {
            repeat(2) {
                api {
                    val response = client.get("/automation/runs/$id/batch") { bearerAuth(token) }
                    response.status shouldBe HttpStatusCode.OK
                    Json.decodeFromString<RunBatchPlan>(response.bodyAsText()) shouldBe batch
                    response.bodyAsText().contains(token) shouldBe false
                }
            }
            verify(exactly = 2) { ledger.readRunBatch(id, "sub-maya") }
            confirmVerified(ledger)
        }

        "batch authorization and UUID validation hide unknown and other subjects' handles identically" {
            api {
                client.get("/automation/runs/$id/batch").status shouldBe HttpStatusCode.Unauthorized
                client.get("/automation/runs/invalid/batch") { bearerAuth(token) }.status shouldBe
                    HttpStatusCode.BadRequest
                verify { ledger wasNot Called }
                client
                    .get("/automation/runs/$id/batch") {
                        bearerAuth(realm.person(username = "renamed", subject = "sub-maya"))
                    }.status shouldBe HttpStatusCode.OK
                val hidden =
                    listOf(id, UUID.randomUUID()).map { handle ->
                        val response =
                            client.get("/automation/runs/$handle/batch") {
                                bearerAuth(realm.person(username = "maya", subject = "other"))
                            }
                        response.status shouldBe HttpStatusCode.Forbidden
                        response.bodyAsText()
                    }
                hidden.distinct().size shouldBe 1
            }
        }

        "an owned run without a preview returns 409 without attempting evaluation" {
            every { ledger.readRunBatch(id, "sub-maya") } throws NoRunBatch(id)
            api {
                val response = client.get("/automation/runs/$id/batch") { bearerAuth(token) }
                response.status shouldBe HttpStatusCode.Conflict
                Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject["code"] shouldBe
                    JsonPrimitive("batch-not-ready")
            }
            verify(exactly = 1) { ledger.readRunBatch(id, "sub-maya") }
            confirmVerified(ledger)
        }
    })
