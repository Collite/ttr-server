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
import kotlinx.serialization.json.*
import org.tatrman.automation.bundle.ApprovalReader
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.identity.*
import org.tatrman.automation.ledger.*
import org.tatrman.automation.runner.Open
import org.tatrman.automation.runner.RunContext
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import java.time.Duration
import java.util.UUID
import org.tatrman.ttr.metadata.model.RunAsRef as ModelRunAs

class OpenRoutesSpec :
    StringSpec({
        val realm = TestRealm()
        afterSpec { realm.close() }
        val original = BundleFixture.load()
        val d = "release_native_event"
        val bundle =
            original.copy(
                deployments =
                    original.deployments + (d to original.deployments.getValue(d).copy(runAs = ModelRunAs.Caller)),
            )
        val path = "/automation/recipes/release_order/runs"
        val body =
            """
            {"deployment":"release_native_event","port":{"key":"4711"},
             "dryRun":false,"idempotencyKey":"engine:1"}
            """.trimIndent()

        "authenticated open answers 201, live handle 202, replay 200; only a new claim creates an executor context" {
            val id = UUID.randomUUID()
            var claim: Claim = Claim.Claimed(id)
            var recorded: RunOpen? = null
            val contexts = mutableListOf<RunContext>()
            val open =
                Open(
                    { bundle },
                    ApprovalReader { _, _, _ -> true },
                    ServiceTokens(emptyMap(), { error("unused") }),
                    {
                        recorded = it
                        claim
                    },
                    { "/automation/runs/$it/protocol" },
                    { false },
                    Duration.ofSeconds(60),
                    Duration.ofMinutes(30),
                    BundleFixture.clock,
                )
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installAutomationErrors() }
                    val verifier =
                        CallerVerifier(
                            JwksBearerVerifier(HttpJwksKeySource(realm.jwksUrl), TestRealm.ISSUER, TestRealm.AUDIENCE),
                        )
                    install(Authentication) { AutomationBearerProvider.register(this, verifier) }
                    routing {
                        authenticate(AUTOMATION_AUTH) {
                            openRoutes { r, request, caller ->
                                open.open(r, request, caller).also { result -> result.context?.let { contexts += it } }
                            }
                        }
                    }
                }

                suspend fun post(
                    body: String,
                    authorized: Boolean = true,
                ) = client.post(path) {
                    contentType(ContentType.Application.Json)
                    if (authorized) bearerAuth(realm.person("maya", listOf("sales_manager")))
                    setBody(body)
                }
                post(body, false).status shouldBe HttpStatusCode.Unauthorized
                contexts.size shouldBe 0
                val created = post(body)
                created.status shouldBe HttpStatusCode.Created
                created.headers[HttpHeaders.Location] shouldBe "/automation/runs/$id"
                val json = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                json["replayed"] shouldBe JsonPrimitive(false)
                recorded!!.dryRun shouldBe true
                recorded!!.caller shouldBe "maya"
                contexts.size shouldBe 1
                claim = Claim.Running(id, RunStatus.RUNNING)
                val running = post(body)
                running.status shouldBe HttpStatusCode.Accepted
                running.headers[HttpHeaders.Location] shouldBe "/automation/runs/$id"
                claim = Claim.Replay(id)
                val replay = post(body)
                replay.status shouldBe HttpStatusCode.OK
                Json.parseToJsonElement(replay.bodyAsText()).jsonObject["protocolRef"] shouldBe
                    JsonPrimitive("/automation/runs/$id/protocol")
                contexts.size shouldBe 1
                post("{broken").status shouldBe HttpStatusCode.BadRequest
                val badPort = post("""{"deployment":"release_native_event","port":{"key":4711}}""")
                badPort.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(badPort.bodyAsText()).jsonObject["error"]!!.jsonObject["code"] shouldBe
                    JsonPrimitive("port")
            }
        }
    })
