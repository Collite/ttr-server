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
                                """[{"order_number":"4711","note":"a"}]""",
                            )
                        json["rowCount"] shouldBe JsonPrimitive(1)
                        json["truncated"] shouldBe JsonPrimitive(false)
                        json["keyed"] shouldBe JsonPrimitive(true)
                        json["ref"] shouldBe JsonPrimitive("$base/outputs/placed.orders")
                    }
                    fixture.outputs.single().rows shouldBe 1L
                    fixture.outputs
                        .single()
                        .sha256!!
                        .startsWith("sha256:") shouldBe true
                    val context = post("$base/parts/shortfall").json()
                    context["class"] shouldBe JsonPrimitive("lookup")
                    context["keyed"] shouldBe JsonPrimitive(false)
                    context["rowCount"] shouldBe JsonPrimitive(3)
                    context["truncated"] shouldBe JsonPrimitive(true)
                    context["rows"]!!.jsonArray.size shouldBe 2
                    context["rows"]!!.jsonArray[1].jsonObject["note"] shouldBe JsonNull
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
                            ReadResult(
                                Table(
                                    read.columns,
                                    if (read.island ==
                                        "placed"
                                    ) {
                                        listOf(listOf("4711", "candidate"))
                                    } else {
                                        emptyList()
                                    },
                                ),
                            )
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

        "part, island and evaluate requests enforce guards and fail the ledger before returning data" {
            for ((code, warnings, rows, cap) in listOf(
                GuardHttpCase("restricted-view", setOf("security_predicate_applied"), listOf(listOf("4711", "a"))),
                GuardHttpCase("candidates-truncated", emptySet(), listOf(listOf("4711", "a")), 1),
                GuardHttpCase("duplicate-key", emptySet(), listOf(listOf("4711", "a"), listOf("4711", "b"))),
                GuardHttpCase("out-of-scope", emptySet(), emptyList()),
                GuardHttpCase("out-of-scope-read", emptySet(), emptyList()),
            )) {
                for (suffix in listOf("parts/candidates", "islands/placed", "evaluate")) {
                    val fixture =
                        RunSessionFixture(
                            door =
                                Door {
                                    if (code ==
                                        "out-of-scope-read"
                                    ) {
                                        throw AutomationException(
                                            ErrorCode.OUT_OF_SCOPE_READ,
                                            "scope refused",
                                            buildJsonObject { put("island", it.island) },
                                        )
                                    }
                                    ReadResult(Table(it.columns, rows), warnings)
                                },
                        )
                    try {
                        if (cap != null) {
                            val b = fixture.bundle
                            fixture.bundle =
                                b.copy(
                                    deployments =
                                        b.deployments + (
                                            "release_native_event" to
                                                b.deployments.getValue("release_native_event").copy(readCeiling = cap)
                                        ),
                                )
                        }
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
                                        runReadRoutes(fixture.runs, 2)
                                        evaluateRoutes(fixture.runs)
                                    }
                                }
                            }
                            val token = realm.person("maya", listOf("sales_manager"))
                            val id = fixture.open(by = Caller.of(token)!!).response.runId
                            val response = client.post("/automation/runs/$id/$suffix") { bearerAuth(token) }
                            response.status.value shouldBe
                                if (code in setOf("out-of-scope", "out-of-scope-read")) 422 else 409
                            response.json()["error"]!!.jsonObject.let {
                                it["code"] shouldBe JsonPrimitive(code)
                                it["detail"]!!.jsonObject["island"] shouldBe JsonPrimitive("placed")
                            }
                            fixture.states.values
                                .single()
                                .wire shouldBe "failed"
                            fixture.outputs.size shouldBe 0
                            fixture.evaluations.size shouldBe 0
                            client.post("/automation/runs/$id/$suffix") { bearerAuth(token) }.status shouldBe
                                HttpStatusCode.ServiceUnavailable
                        }
                    } finally {
                        fixture.close()
                    }
                }
            }
        }

        "evaluate authenticates the owner, publishes a staged summary and guards, and reuses the completed result" {
            val reads =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val fixture =
                RunSessionFixture(
                    door =
                        Door { read ->
                            reads.incrementAndGet()
                            ReadResult(
                                Table(
                                    read.columns,
                                    if (read.sql ==
                                        "hold"
                                    ) {
                                        emptyList()
                                    } else {
                                        listOf(listOf("4711", "from SQL"))
                                    },
                                ),
                            )
                        },
                    columns = listOf(ManifestColumn("order_number", "text"), ManifestColumn("verdict_reason", "text")),
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
                        routing { authenticate(AUTOMATION_AUTH) { evaluateRoutes(fixture.runs) } }
                    }
                    val token = realm.person("maya", listOf("sales_manager"))
                    val id = fixture.open(by = Caller.of(token)!!).response.runId
                    val path = "/automation/runs/$id/evaluate"
                    client.post(path).status shouldBe HttpStatusCode.Unauthorized
                    client.post(path) { bearerAuth(realm.person("other", listOf("sales_manager"))) }.status shouldBe
                        HttpStatusCode.ServiceUnavailable
                    client.post("/automation/runs/1-1-1-1-1/evaluate") { bearerAuth(token) }.status shouldBe
                        HttpStatusCode.BadRequest
                    reads.get() shouldBe 0
                    val first = client.post(path) { bearerAuth(token) }
                    first.status shouldBe HttpStatusCode.OK
                    val body = first.json()
                    body["verdict"]!!.jsonObject.let { verdict ->
                        verdict["none"] shouldBe JsonPrimitive(0)
                        verdict["routes"]!!.jsonObject shouldBe
                            Json.parseToJsonElement(
                                """{"release":{"rowCount":1,"ref":"/automation/runs/$id/outputs/way.release","detail":{}},
                                "hold":{"rowCount":0,"ref":"/automation/runs/$id/outputs/way.hold","detail":{}}}""",
                            )
                    }
                    body["guards"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }.toSet() shouldBe
                        setOf(
                            "restricted-view",
                            "candidates-truncated",
                            "out-of-scope-read",
                            "duplicate-key",
                            "out-of-scope",
                        )
                    fixture.states.values
                        .single()
                        .wire shouldBe "evaluated"
                    fixture.evaluations.size shouldBe 1
                    client.post(path) { bearerAuth(token) }.json() shouldBe body
                    reads.get() shouldBe 4
                    fixture.outputs.size shouldBe 4
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
                        routing {
                            authenticate(AUTOMATION_AUTH) {
                                runReadRoutes(fixture.runs, 2)
                                evaluateRoutes(fixture.runs)
                            }
                        }
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

private data class GuardHttpCase(
    val code: String,
    val warnings: Set<String>,
    val rows: List<List<Any?>>,
    val cap: Int? = null,
)
