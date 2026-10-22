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
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.identity.*
import org.tatrman.automation.runner.*
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import java.math.BigDecimal

class RunReadRoutesSpec :
    StringSpec({
        val realm = TestRealm()
        afterSpec { realm.close() }

        "authenticated previews are bounded and journalled with manifest metadata" {
            val fixture = RunSessionFixture()
            try {
                testApplication {
                    application {
                        install(ContentNegotiation) { json(Json { explicitNulls = true }) }
                        install(StatusPages) { installAutomationErrors() }
                        val verifier =
                            CallerVerifier(
                                JwksBearerVerifier(
                                    HttpJwksKeySource(realm.jwksUrl),
                                    TestRealm.ISSUER,
                                    TestRealm.AUDIENCE,
                                ),
                            )
                        install(Authentication) { AutomationBearerProvider.register(this, verifier) }
                        routing {
                            authenticate(AUTOMATION_AUTH) {
                                openRoutes(fixture.runs)
                                runReadRoutes(fixture.runs, 2)
                            }
                        }
                    }
                    val token = realm.person("maya", listOf("sales_manager"))

                    suspend fun post(
                        path: String,
                        authorized: Boolean = true,
                    ) = client.post(path) {
                        if (authorized) bearerAuth(token)
                    }
                    val opening =
                        client.post("/automation/recipes/release_order/runs") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"deployment":"release_native_event","port":{"key":"4711"},
                                   "idempotencyKey":"http:1"}""",
                            )
                        }
                    opening.status shouldBe HttpStatusCode.Created
                    val id = opening.json()["runId"]!!.jsonPrimitive.content
                    val base = "/automation/runs/$id"
                    post("$base/parts/candidates", false).status shouldBe HttpStatusCode.Unauthorized
                    fixture.reads.get() shouldBe 0
                    val candidates = post("$base/parts/candidates")
                    candidates.status shouldBe HttpStatusCode.OK
                    candidates.json().let { json ->
                        json["rows"]!!.jsonArray shouldBe
                            Json.parseToJsonElement(
                                """[{"order_number":"4711","note":"a"},{"order_number":"4711","note":null}]""",
                            )
                        json["rowCount"] shouldBe JsonPrimitive(3)
                        json["truncated"] shouldBe JsonPrimitive(true)
                        json["keyed"] shouldBe JsonPrimitive(true)
                        json["ref"] shouldBe JsonPrimitive("$base/outputs/placed.orders")
                    }
                    fixture.outputs.single().rows shouldBe 3L
                    fixture.outputs
                        .single()
                        .sha256!!
                        .startsWith("sha256:") shouldBe true
                    val context = post("$base/parts/shortfall").json()
                    context["class"] shouldBe JsonPrimitive("lookup")
                    context["keyed"] shouldBe JsonPrimitive(false)
                    post("$base/islands/placed").json() shouldBe candidates.json()
                    fixture.reads.get() shouldBe 2 // Shared candidate output is not read twice.
                    val decision = post("$base/parts/decision").json()["outputs"]!!.jsonObject
                    decision.keys shouldBe setOf("release", "hold")
                    decision.values.sumOf { it.jsonObject["rows"]!!.jsonArray.size } shouldBe 2 // Bound across ports.
                    decision["release"]!!.jsonObject["rowCount"] shouldBe JsonPrimitive(3)
                    decision["hold"]!!.jsonObject["rowCount"] shouldBe JsonPrimitive(3)
                    decision["hold"]!!.jsonObject["rows"]!!.jsonArray.size shouldBe 0
                    decision["hold"]!!.jsonObject["truncated"] shouldBe JsonPrimitive(true)
                    fixture.outputs.size shouldBe 4
                    fixture.reads.get() shouldBe 4
                    post("$base/islands/way").json()["outputs"] shouldBe JsonObject(decision)
                }
            } finally {
                fixture.close()
            }
        }

        "decision runs missing inputs in order; invalid names and ids never execute" {
            val order = java.util.Collections.synchronizedList(mutableListOf<String>())
            val fixture =
                RunSessionFixture(
                    door =
                        Door { read ->
                            order += read.island
                            ReadResult(Table(read.columns, emptyList()))
                        },
                )
            try {
                testApplication {
                    application {
                        install(ContentNegotiation) { json() }
                        install(StatusPages) { installAutomationErrors() }
                        val verifier =
                            CallerVerifier(
                                JwksBearerVerifier(
                                    HttpJwksKeySource(realm.jwksUrl),
                                    TestRealm.ISSUER,
                                    TestRealm.AUDIENCE,
                                ),
                            )
                        install(Authentication) { AutomationBearerProvider.register(this, verifier) }
                        routing {
                            authenticate(AUTOMATION_AUTH) {
                                openRoutes(fixture.runs)
                                runReadRoutes(fixture.runs, 2)
                            }
                        }
                    }
                    val token = realm.person("maya", listOf("sales_manager"))
                    val caller = Caller.of(token)!!
                    val id = fixture.open(by = caller).response.runId

                    suspend fun post(suffix: String) = client.post("/automation/runs/$id/$suffix") { bearerAuth(token) }
                    post("parts/not_a_context").status shouldBe HttpStatusCode.BadRequest
                    // Candidate role is named candidates.
                    post("parts/placed").status shouldBe HttpStatusCode.BadRequest
                    post("islands/not_an_island").status shouldBe HttpStatusCode.BadRequest
                    client.post("/automation/runs/1-1-1-1-1/parts/candidates") { bearerAuth(token) }.status shouldBe
                        HttpStatusCode.BadRequest
                    order.size shouldBe 0
                    fixture.states.values
                        .single()
                        .wire shouldBe "open"
                    post("parts/decision").status shouldBe HttpStatusCode.OK
                    order.toList().take(2) shouldBe listOf("placed", "shortfall")
                    order.toList().drop(2) shouldBe listOf("way", "way")
                    val unknown =
                        client.post("/automation/runs/00000000-0000-0000-0000-000000000000/parts/candidates") {
                            bearerAuth(token)
                        }
                    unknown.status shouldBe HttpStatusCode.ServiceUnavailable
                    unknown.json()["error"]!!.jsonObject["code"] shouldBe JsonPrimitive("run-abandoned")
                    val stranger =
                        client.post("/automation/runs/$id/parts/candidates") {
                            bearerAuth(realm.person("other", listOf("ops")))
                        }
                    stranger.status shouldBe HttpStatusCode.ServiceUnavailable
                    stranger.json() shouldBe unknown.json() // No run existence/ownership disclosure, including to ops.
                    order.size shouldBe 4
                }
            } finally {
                fixture.close()
            }
        }

        "decimal output stays exact JSON with its full reference and count" {
            val fixture =
                RunSessionFixture(
                    door =
                        Door { read ->
                            ReadResult(Table(read.columns, listOf(listOf("4711", BigDecimal("1234567890.123456789")))))
                        },
                    columns = listOf(ManifestColumn("order_number", "text"), ManifestColumn("note", "decimal")),
                )
            try {
                testApplication {
                    application {
                        install(ContentNegotiation) { json() }
                        install(StatusPages) { installAutomationErrors() }
                        val verifier =
                            CallerVerifier(
                                JwksBearerVerifier(
                                    HttpJwksKeySource(realm.jwksUrl),
                                    TestRealm.ISSUER,
                                    TestRealm.AUDIENCE,
                                ),
                            )
                        install(Authentication) { AutomationBearerProvider.register(this, verifier) }
                        routing { authenticate(AUTOMATION_AUTH) { runReadRoutes(fixture.runs, 2) } }
                    }
                    val token = realm.person("maya", listOf("sales_manager"))
                    val id = fixture.open(by = Caller.of(token)!!).response.runId
                    val response = client.post("/automation/runs/$id/parts/candidates") { bearerAuth(token) }
                    response.status shouldBe HttpStatusCode.OK
                    val value =
                        response
                            .json()["rows"]!!
                            .jsonArray
                            .single()
                            .jsonObject["note"]!!
                            .jsonPrimitive
                    value.isString shouldBe false
                    value.content shouldBe "1234567890.123456789"
                    response.json()["truncated"] shouldBe JsonPrimitive(false)
                }
            } finally {
                fixture.close()
            }
        }
    })

private suspend fun io.ktor.client.statement.HttpResponse.json(): JsonObject =
    Json.parseToJsonElement(bodyAsText()).jsonObject
