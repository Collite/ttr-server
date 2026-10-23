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
import org.tatrman.automation.bundle.*
import org.tatrman.automation.identity.*
import org.tatrman.automation.ledger.RunStatus
import org.tatrman.automation.runner.*
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class RouteRowsSpec :
    StringSpec({
        val realm = TestRealm()
        afterSpec { realm.close() }

        "route paging preserves SQL reasons and resolves bounded detail refs for the exact key" {
            val reads = AtomicInteger()
            val columns = listOf(ManifestColumn("order_number", "text"), ManifestColumn("verdict_reason", "text"))
            val fixture =
                RunSessionFixture(
                    detailLimit = 2,
                    columns = columns,
                    door =
                        Door { read ->
                            reads.incrementAndGet()
                            val rows: List<List<Any?>> =
                                when (read.sql) {
                                    "release" -> listOf(listOf("4711", "SQL's reason"), listOf("4712", null))
                                    "hold", "shortfall" -> emptyList()
                                    "detail" ->
                                        listOf(
                                            listOf("4711", "first", "release", "shortfall"),
                                            listOf("4712", "other key", "release", "shortfall"),
                                            listOf("4711", "second", "release", "shortfall"),
                                            listOf("4711", "must become marker", "release", "shortfall"),
                                            listOf("4711", "must disappear", "release", "shortfall"),
                                        )
                                    else -> listOf(listOf("4711", "candidate"), listOf("4712", "candidate"))
                                }
                            ReadResult(Table(read.columns, rows))
                        },
                )
            val recipe = fixture.bundle.recipes.getValue("release_order")
            val metadata =
                recipe.recipe.copy(
                    roles =
                        recipe.recipe.roles!!.let { roles ->
                            roles.copy(
                                routes =
                                    roles.routes.map {
                                        if (it.name ==
                                            "release"
                                        ) {
                                            it.copy(detail = listOf("shortfall"))
                                        } else {
                                            it
                                        }
                                    },
                            )
                        },
                )
            val updated =
                recipe.copy(
                    recipe = metadata,
                    manifest =
                        recipe.manifest.copy(
                            recipe = metadata,
                            islands =
                                recipe.manifest.islands.map { island ->
                                    if (island.name != "way") {
                                        island
                                    } else {
                                        island.copy(
                                            outputs =
                                                island.outputs +
                                                    ManifestOutput(
                                                        "release.shortfall",
                                                        "detail.sql",
                                                        keyed = true,
                                                        storage = "erp_db",
                                                        columns =
                                                            listOf(
                                                                ManifestColumn("order_number", "text"),
                                                                ManifestColumn("note", "text"),
                                                                ManifestColumn("route", "text"),
                                                                ManifestColumn("context", "text"),
                                                            ),
                                                    ),
                                        )
                                    }
                                },
                        ),
                    statements = recipe.statements + ("detail.sql" to "detail"),
                )
            fixture.bundle =
                fixture.bundle.copy(
                    recipes = fixture.bundle.recipes + ("release_order" to updated),
                    programs = fixture.bundle.programs.mapValues { (_, program) -> program.copy(port = "none") },
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
                        routing { authenticate(AUTOMATION_AUTH) { routeRows(fixture.runs, 3) } }
                    }
                    val token = realm.person("maya", listOf("sales_manager"))
                    val result =
                        fixture.runs.open(
                            "release_order",
                            OpenRequest(
                                "release_native_event",
                                buildJsonObject { put("none", true) },
                            ),
                            Caller.of(token)!!,
                        )
                    val id = result.response.runId
                    val base = "/automation/runs/$id/routes"

                    suspend fun get(path: String) = client.get(path) { bearerAuth(token) }

                    client.get("$base/release").status shouldBe HttpStatusCode.Unauthorized
                    client
                        .get("$base/release") { bearerAuth(realm.person("other", listOf("sales_manager"))) }
                        .status shouldBe HttpStatusCode.ServiceUnavailable
                    for (path in listOf(
                        "unknown",
                        "release?offset=-1",
                        "release?offset=2147483648",
                        "release?limit=0",
                        "release?limit=4",
                        "release?limit=1&limit=2",
                        "release?offset=x",
                        "release/details/unknown",
                        "release/details/shortfall?row=-1",
                    )) {
                        get("$base/$path").status shouldBe HttpStatusCode.BadRequest
                    }
                    fixture.statusReads.get() shouldBe 9 // Unauthenticated/other-owner never reach the ledger.
                    fixture.states[UUID.fromString(id)] shouldBe RunStatus.OPEN
                    reads.get() shouldBe 0

                    val first = get("$base/release?limit=1").bodyAsText().let { Json.parseToJsonElement(it).jsonObject }
                    first["rowCount"] shouldBe JsonPrimitive(2)
                    first["truncated"] shouldBe JsonPrimitive(true)
                    val row = first["rows"]!!.jsonArray.single().jsonObject
                    row["order_number"] shouldBe JsonPrimitive("4711")
                    row["verdict_reason"] shouldBe JsonPrimitive("SQL's reason")
                    val ref = row["detail"]!!.jsonObject["shortfall"]!!.jsonPrimitive.content
                    ref shouldBe "$base/release/details/shortfall?row=0"
                    val details = get(ref).bodyAsText().let { Json.parseToJsonElement(it).jsonObject }
                    details["rowCount"] shouldBe JsonPrimitive(3)
                    details["ref"] shouldBe JsonPrimitive(ref)
                    details["truncated"] shouldBe JsonPrimitive(true)
                    val detailRows = details["rows"]!!.jsonArray
                    detailRows.map { it.jsonObject["note"] } shouldBe
                        listOf(JsonPrimitive("first"), JsonPrimitive("second"), JsonNull)
                    detailRows.last().jsonObject["truncated"] shouldBe JsonPrimitive(true)
                    detailRows.all { it.jsonObject["order_number"] == JsonPrimitive("4711") } shouldBe true

                    val second =
                        get(
                            "$base/release?offset=1&limit=1",
                        ).bodyAsText().let { Json.parseToJsonElement(it).jsonObject }
                    val secondRow = second["rows"]!!.jsonArray.single().jsonObject
                    secondRow["verdict_reason"] shouldBe JsonNull
                    val secondRef = secondRow["detail"]!!.jsonObject["shortfall"]!!.jsonPrimitive.content
                    val secondDetails = get(secondRef).bodyAsText().let { Json.parseToJsonElement(it).jsonObject }
                    secondDetails["rowCount"] shouldBe JsonPrimitive(1)
                    secondDetails["truncated"] shouldBe JsonPrimitive(false)
                    secondDetails["rows"]!!.jsonArray.single().jsonObject["note"] shouldBe JsonPrimitive("other key")

                    val empty =
                        get(
                            "$base/release?offset=2147483647",
                        ).bodyAsText().let { Json.parseToJsonElement(it).jsonObject }
                    empty["rows"] shouldBe JsonArray(emptyList())
                    empty["rowCount"] shouldBe JsonPrimitive(2)
                    get("$base/hold").status shouldBe HttpStatusCode.OK
                    get("$base/release/details/shortfall?offset=3&limit=1").status shouldBe HttpStatusCode.OK
                    get("$base/release/details/shortfall?row=2147483647").status shouldBe HttpStatusCode.OK
                    fixture.states[UUID.fromString(id)] shouldBe RunStatus.EVALUATED
                    fixture.evaluations.size shouldBe 1
                    reads.get() shouldBe 5 // Every route/detail read reuses the pinned evaluation.
                    fixture.outputs.single { it.port == "release.shortfall" }.rows shouldBe 4
                    fixture.states[UUID.fromString(id)] = RunStatus.CLOSED
                    get(ref).status shouldBe HttpStatusCode.ServiceUnavailable
                }
            } finally {
                fixture.close()
            }
        }
    })
