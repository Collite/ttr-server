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
import org.tatrman.automation.identity.*
import org.tatrman.automation.ledger.*
import org.tatrman.automation.runner.*
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import java.util.UUID

class RunStatusRoutesSpec :
    StringSpec({
        val realm = TestRealm()
        afterSpec { realm.close() }
        val ledger = mockk<Ledger>()
        val sessions = mockk<RunSessions>()
        val id = UUID.randomUUID()
        val token = realm.person()
        var progress = RunProgress(id.toString(), "open", protocolRef = "/automation/runs/$id/protocol")
        beforeEach {
            clearMocks(ledger, sessions)
            progress = RunProgress(id.toString(), "open", protocolRef = "/automation/runs/$id/protocol")
            every { ledger.readRunProgress(any(), any()) } answers {
                if (firstArg<UUID>() == id &&
                    secondArg<String>() == "sub-maya"
                ) {
                    progress
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
                    routing {
                        authenticate(
                            AUTOMATION_AUTH,
                        ) { runStatusRoutes(RunStatusReader(ledger)::read) }
                    }
                }
                block()
            }

        "fresh HTTP readers return pinned run metadata and the committed verdict without execution resources" {
            progress =
                progress.copy(
                    status = "evaluated",
                    recipe = "release_order",
                    program = "morning_release",
                    deployment = "native",
                    runAs = "automation_decider",
                    author = "po",
                    asOf = "2026-10-23T12:00:00Z",
                    bundle = "pinned-bundle",
                    fingerprints = RunFingerprints("text", "values", "program", "deployment", "model"),
                    dryRun = true,
                    replayOf = UUID.randomUUID().toString(),
                    startedAt = "2026-10-23T12:00:01Z",
                    verdict = VerdictSummary(mapOf("hold" to RouteSummary(3, "arrow:held")), 1),
                    guards = listOf(GuardResult("out-of-scope-read", "orders", "passed")),
                    mode = "exclusive",
                    batch = RunBatch(UUID.randomUUID().toString(), "preview", 6),
                )
            repeat(2) {
                api {
                    val response = client.get("/automation/runs/$id") { bearerAuth(token) }
                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.RetryAfter] shouldBe null
                    Json.decodeFromString<RunProgress>(response.bodyAsText()) shouldBe progress
                    response.bodyAsText().contains(token) shouldBe false
                    response.bodyAsText().contains("sub-maya") shouldBe false
                }
            }
            verify(exactly = 2) { ledger.readRunProgress(id, "sub-maya") }
            verify { sessions wasNot Called }
            confirmVerified(ledger)
        }

        "authentication and UUID validation run before reading the ledger" {
            api {
                client.get("/automation/runs/$id").status shouldBe HttpStatusCode.Unauthorized
                client.get("/automation/runs/not-a-uuid") { bearerAuth(token) }.status shouldBe
                    HttpStatusCode.BadRequest
            }
            verify {
                ledger wasNot Called
                sessions wasNot Called
            }
        }

        "the verified subject survives rename and hides unknown and other subjects' handles identically" {
            api {
                client
                    .get("/automation/runs/$id") {
                        bearerAuth(realm.person(username = "renamed", subject = "sub-maya"))
                    }.status shouldBe HttpStatusCode.OK
                val hidden =
                    listOf(id, UUID.randomUUID()).map { handle ->
                        val response =
                            client.get("/automation/runs/$handle") {
                                bearerAuth(realm.person(username = "maya", subject = "reused-principal"))
                            }
                        response.status shouldBe HttpStatusCode.Forbidden
                        response.bodyAsText()
                    }
                hidden.distinct().size shouldBe 1
            }
            verify { sessions wasNot Called }
        }

        "all persisted statuses are observable and only open and running carry a retry hint" {
            api {
                for (state in RunStatus.entries) {
                    progress =
                        progress.copy(
                            status = state.wire,
                            error = if (state == RunStatus.FAILED) buildJsonObject { put("code", "budget") } else null,
                        )
                    val response = client.get("/automation/runs/$id") { bearerAuth(token) }
                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.RetryAfter] shouldBe
                        if (state in setOf(RunStatus.OPEN, RunStatus.RUNNING)) "15" else null
                    Json.decodeFromString<RunProgress>(response.bodyAsText()) shouldBe progress
                }
            }
            verify { sessions wasNot Called }
        }
    })
