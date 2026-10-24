// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
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
import org.tatrman.automation.ledger.RunStatus
import org.tatrman.automation.runner.*
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import java.math.BigDecimal
import java.util.UUID

class OutputRoutesSpec :
    StringSpec({
        val realm = TestRealm()
        afterSpec { realm.close() }

        "all output formats share verified snapshots and enforce ownership and pagination before execution" {
            val columns =
                listOf(
                    ManifestColumn("order_number", "text"),
                    ManifestColumn("note", "text"),
                    ManifestColumn("amount", "decimal"),
                    ManifestColumn("truncated", "bool"),
                )
            val amount = BigDecimal("12345678901234567890.1234567890")
            val table =
                Table(
                    columns,
                    listOf(
                        listOf("4711", "=SUM(1,2)", amount, false),
                        listOf("4711", "quoted \"note\",\r\nnext line", null, false),
                        listOf("4711", null, null, true),
                    ),
                )
            val fixture =
                RunSessionFixture(
                    columns = columns,
                    door =
                        Door { read ->
                            ReadResult(if (read.island == "placed") table.copy(rows = table.rows.take(1)) else table)
                        },
                )
            try {
                testApplication {
                    application {
                        outputTestApi(fixture, realm)
                    }
                    val token = realm.person("maya", listOf("sales_manager"))
                    val opened = fixture.open(by = Caller.of(token)!!)
                    val id = UUID.fromString(opened.response.runId)
                    val base = "/automation/runs/$id/outputs"

                    suspend fun get(suffix: String) = client.get("$base/$suffix") { bearerAuth(token) }
                    client.get("$base/shortfall.rows").status shouldBe HttpStatusCode.Unauthorized
                    client
                        .get("$base/shortfall.rows?limit=0") {
                            bearerAuth(realm.person("other", listOf("sales_manager")))
                        }.status shouldBe HttpStatusCode.ServiceUnavailable
                    fixture.statusReads.get() shouldBe 0
                    for (suffix in listOf(
                        "unknown.rows",
                        "shortfall.nope",
                        "shortfall.rows?format=xml",
                        "shortfall.rows?format=json&format=csv",
                        "shortfall.rows?offset=-1",
                        "shortfall.rows?offset=2147483648",
                        "shortfall.rows?limit=0",
                        "shortfall.rows?limit=3",
                        "shortfall.rows?offset=0&offset=1",
                        "shortfall.rows?limit=1&limit=2",
                        "shortfall.rows?offset=x",
                        "shortfall.rows?format=arrow&offset=0",
                        "shortfall.rows?format=arrow&limit=1",
                    )) {
                        get(suffix).status shouldBe HttpStatusCode.BadRequest
                    }
                    fixture.outputs.size shouldBe 0
                    fixture.states[id] shouldBe RunStatus.OPEN
                    val json = get("shortfall.rows").bodyAsText()
                    val page = Json.parseToJsonElement(json).jsonObject
                    page["rowCount"] shouldBe JsonPrimitive(3)
                    page["ref"] shouldBe JsonPrimitive("$base/shortfall.rows")
                    page["class"] shouldBe JsonPrimitive("lookup")
                    page["keyed"] shouldBe JsonPrimitive(false)
                    page["truncated"] shouldBe JsonPrimitive(true)
                    page["rows"]!!.jsonArray.size shouldBe 2
                    json.contains("12345678901234567890.1234567890") shouldBe true
                    page["rows"]!!.jsonArray[1].jsonObject["amount"] shouldBe JsonNull
                    val tail =
                        get(
                            "shortfall.rows?offset=2&limit=1",
                        ).bodyAsText().let { Json.parseToJsonElement(it).jsonObject }
                    tail["rows"]!!.jsonArray.single().jsonObject["truncated"] shouldBe JsonPrimitive(true)
                    val empty =
                        get(
                            "shortfall.rows?offset=2147483647",
                        ).bodyAsText().let { Json.parseToJsonElement(it).jsonObject }
                    empty["rows"] shouldBe JsonArray(emptyList())
                    empty["rowCount"] shouldBe JsonPrimitive(3)
                    empty["truncated"] shouldBe JsonPrimitive(true)
                    val csv = get("shortfall.rows?format=csv&limit=1")
                    csv.contentType() shouldBe ContentType.Text.CSV.withCharset(Charsets.UTF_8)
                    csv.headers["X-Row-Count"] shouldBe "3"
                    csv.headers["X-Truncated"] shouldBe "true"
                    csv.headers[HttpHeaders.ContentDisposition] shouldBe "attachment; filename=$id.csv"
                    csv.bodyAsText() shouldBe "\uFEFFsep=,\r\n\"order_number\",\"note\",\"amount\",\"truncated\"\r\n" +
                        "\"4711\",\"'=SUM(1,2)\",\"$amount\",\"false\"\r\n# truncated\r\n"
                    val arrow = get("shortfall.rows?format=arrow")
                    arrow.contentType() shouldBe ContentType("application", "vnd.apache.arrow.stream")
                    arrow.headers["X-Row-Count"] shouldBe "3"
                    arrow.headers["X-Truncated"] shouldBe "true"
                    val bytes = arrow.body<ByteArray>()
                    bytes.contentEquals(ArrowTables.encode(table)) shouldBe true
                    ArrowTables.decode(listOf(bytes)) shouldBe table
                    fixture.outputs.size shouldBe 2 // Candidate and context, never re-staged by a page/download.
                    fixture.evaluations.size shouldBe 0
                    val candidate = get("placed.orders").bodyAsText().let { Json.parseToJsonElement(it).jsonObject }
                    candidate["truncated"] shouldBe JsonPrimitive(false)
                    candidate["keyed"] shouldBe JsonPrimitive(true)
                    get("placed.orders?format=csv").bodyAsText().endsWith("# truncated\r\n") shouldBe false
                    for (status in listOf(RunStatus.CLOSED, RunStatus.FAILED, RunStatus.WAITING)) {
                        fixture.states[id] = status
                        get("shortfall.rows?format=arrow").status shouldBe HttpStatusCode.ServiceUnavailable
                    }
                    fixture.runs.release(id)
                    get("shortfall.rows").status shouldBe HttpStatusCode.ServiceUnavailable
                    client
                        .get("/automation/runs/${UUID.randomUUID()}/outputs/shortfall.rows") { bearerAuth(token) }
                        .status shouldBe HttpStatusCode.ServiceUnavailable
                }
            } finally {
                fixture.close()
            }
        }

        "corrupt snapshots and invalid decisions fail before Arrow headers or data are sent" {
            for (corrupt in listOf(true, false)) {
                val root =
                    java.nio.file.Files
                        .createTempDirectory("au-output-corrupt")
                val fixture =
                    RunSessionFixture(
                        stagingStore =
                            org.tatrman.automation.staging
                                .LocalStagingStore(root),
                    )
                try {
                    testApplication {
                        application { outputTestApi(fixture, realm) }
                        val token = realm.person("maya", listOf("sales_manager"))
                        val opened = fixture.open(by = Caller.of(token)!!)
                        val id = UUID.fromString(opened.response.runId)
                        val base = "/automation/runs/$id/outputs"
                        if (corrupt) {
                            client.get("$base/placed.orders") { bearerAuth(token) }.status shouldBe HttpStatusCode.OK
                            val output = fixture.outputs.single()
                            val file = root.resolve(output.path!!)
                            val bytes =
                                java.nio.file.Files
                                    .readAllBytes(file)
                            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                            java.nio.file.Files
                                .write(file, bytes)
                        }
                        // The fixture's decision is deliberately invalid: missing verdict_reason and duplicate keys.
                        val handle = if (corrupt) "placed.orders" else "way.release"
                        val response = client.get("$base/$handle?format=arrow") { bearerAuth(token) }
                        response.status shouldBe HttpStatusCode.InternalServerError
                        response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                        response.headers[HttpHeaders.ContentDisposition] shouldBe null
                        response.headers["X-Row-Count"] shouldBe null
                        fixture.states[id] shouldBe RunStatus.FAILED
                        fixture.evaluations.size shouldBe 0
                    }
                } finally {
                    fixture.close()
                }
            }
        }

        "ambiguous dotted handles refuse before reading or failing the run" {
            val fixture = RunSessionFixture()
            val recipe = fixture.bundle.recipes.getValue("release_order")
            val source = recipe.manifest.islands.single { it.name == "shortfall" }
            val extra = source.outputs.single().copy(port = "rows.extra")
            val other = source.copy(name = "shortfall.rows", outputs = listOf(extra.copy(port = "extra")))
            val updated =
                recipe.copy(
                    manifest =
                        recipe.manifest.copy(
                            islands =
                                recipe.manifest.islands.map {
                                    if (it.name ==
                                        source.name
                                    ) {
                                        it.copy(outputs = it.outputs + extra)
                                    } else {
                                        it
                                    }
                                } +
                                    other,
                            waves = recipe.manifest.waves + listOf(listOf(other.name)),
                        ),
                )
            fixture.bundle = fixture.bundle.copy(recipes = fixture.bundle.recipes + ("release_order" to updated))
            try {
                testApplication {
                    application { outputTestApi(fixture, realm) }
                    val token = realm.person("maya", listOf("sales_manager"))
                    val opened = fixture.open(by = Caller.of(token)!!)
                    val id = UUID.fromString(opened.response.runId)
                    client
                        .get("/automation/runs/$id/outputs/shortfall.rows.extra") { bearerAuth(token) }
                        .status shouldBe HttpStatusCode.BadRequest
                    fixture.outputs.size shouldBe 0
                    fixture.reads.get() shouldBe 0
                    fixture.states[id] shouldBe RunStatus.OPEN
                }
            } finally {
                fixture.close()
            }
        }

        "empty outputs preserve schema and full counts in every format" {
            val fixture = RunSessionFixture(door = Door { read -> ReadResult(Table(read.columns, emptyList())) })
            fixture.bundle =
                fixture.bundle.copy(programs = fixture.bundle.programs.mapValues { (_, p) -> p.copy(port = "none") })
            try {
                testApplication {
                    application { outputTestApi(fixture, realm) }
                    val token = realm.person("maya", listOf("sales_manager"))
                    val opened =
                        fixture.runs.open(
                            "release_order",
                            OpenRequest(
                                "release_native_event",
                                buildJsonObject { put("none", true) },
                            ),
                            Caller.of(token)!!,
                        )
                    val base = "/automation/runs/${opened.response.runId}/outputs/placed.orders"
                    val json =
                        client
                            .get(
                                base,
                            ) { bearerAuth(token) }
                            .bodyAsText()
                            .let { Json.parseToJsonElement(it).jsonObject }
                    json["rows"] shouldBe JsonArray(emptyList())
                    json["rowCount"] shouldBe JsonPrimitive(0)
                    json["truncated"] shouldBe JsonPrimitive(false)
                    client.get("$base?format=csv") { bearerAuth(token) }.bodyAsText() shouldBe
                        "\uFEFFsep=,\r\n\"order_number\",\"note\"\r\n"
                    val bytes = client.get("$base?format=arrow") { bearerAuth(token) }.body<ByteArray>()
                    val table = ArrowTables.decode(listOf(bytes))
                    table.columns.map { it.name } shouldBe listOf("order_number", "note")
                    table.rows shouldBe emptyList()
                    fixture.outputs.size shouldBe 1
                }
            } finally {
                fixture.close()
            }
        }

        "CSV quotes headers and multiline fields, neutralizes text formulas and retains typed numeric values" {
            val table =
                Table(
                    listOf(ManifestColumn("=header", "text"), ManifestColumn("value", "text")),
                    listOf(
                        listOf("=1", "+1"),
                        listOf("-1", "@SUM(1,2)"),
                        listOf("\tvalue", "\rvalue"),
                        listOf("\nvalue", "  =1"),
                        listOf("\uFEFF=1", "normal"),
                        listOf(-12L, BigDecimal("-1.2300")),
                        listOf(null, "a,\"b\"\r\nc"),
                    ),
                )
            outputCsv(table, false) shouldBe "\uFEFFsep=,\r\n\"'=header\",\"value\"\r\n" +
                "\"'=1\",\"'+1\"\r\n\"'-1\",\"'@SUM(1,2)\"\r\n\"'\tvalue\",\"'\rvalue\"\r\n" +
                "\"'\nvalue\",\"'  =1\"\r\n\"'\uFEFF=1\",\"normal\"\r\n\"-12\",\"-1.2300\"\r\n" +
                "\"\",\"a,\"\"b\"\"\r\nc\"\r\n"
        }
        "empty CSV retains BOM, separator and schema without a false truncation notice" {
            outputCsv(Table(listOf(ManifestColumn("id", "int")), emptyList()), false) shouldBe
                "\uFEFFsep=,\r\n\"id\"\r\n"
        }
    })

private fun io.ktor.server.application.Application.outputTestApi(
    fixture: RunSessionFixture,
    realm: TestRealm,
) {
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
    routing { authenticate(AUTOMATION_AUTH) { outputRoutes(fixture.runs, 2) } }
}
