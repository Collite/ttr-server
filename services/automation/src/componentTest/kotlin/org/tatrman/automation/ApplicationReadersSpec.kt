// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.apply.*
import org.tatrman.automation.identity.TestRealm
import org.tatrman.automation.ledger.*
import org.tatrman.automation.protocol.ProtocolDocument
import org.tatrman.automation.runner.*
import java.net.URI
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** The real production module, migrations, auth and ledger; no test-only routing or runtime assembly. */
class ApplicationReadersSpec :
    StringSpec({
        val db = LedgerTestDb.ready()
        val realm = TestRealm()
        val staging = Files.createTempDirectory("au-application-readers-")
        val clock = Clock.systemUTC()
        val ledger = Ledger(Duration.ofMinutes(30), clock)
        val token = realm.person()
        // A separate owned application pool on the fixture's server. Module shutdown closes that pool;
        // restore the fixture's Exposed default afterwards so other component specs keep their own pool.
        val jdbc = db.getConnection().use { URI(it.metaData.url.removePrefix("jdbc:")) }
        val config =
            ConfigFactory
                .parseString(
                    """
                    automation.db {
                        host = "${jdbc.host}"
                        port = "${jdbc.port}"
                        database = "automation"
                        user = "automation"
                        password = "automation"
                    }
                    automation.door.queryUrl = "127.0.0.1:1"
                    automation.door.metadataUrl = "127.0.0.1:1"
                    automation.auth.jwksUrl = "${realm.jwksUrl}"
                    automation.auth.issuer = "${TestRealm.ISSUER}"
                    automation.auth.audience = "${TestRealm.AUDIENCE}"
                    automation.staging.path = "${staging.toString().replace("\\", "\\\\")}"
                    telemetry.enabled = false
                    """.trimIndent(),
                ).withFallback(ConfigFactory.parseResources("application.conf"))
                .resolve()
        beforeEach { LedgerTestDb.reset() }
        afterSpec {
            realm.close()
            staging.toFile().deleteRecursively()
        }

        suspend fun api(block: suspend ApplicationTestBuilder.(Application) -> Unit) {
            try {
                testApplication {
                    lateinit var installed: Application
                    application {
                        module(config)
                        installed = this
                    }
                    startApplication()
                    block(installed)
                }
            } finally {
                Database.connect(db.getDataSource())
            }
        }

        fun open(subject: String? = "sub-maya"): UUID =
            ledger
                .claimRun(
                    RunOpen(
                        "release_order",
                        program = "morning_release",
                        deployment = "native",
                        bundle = "original-bundle",
                        caller = "maya",
                        callerSubject = subject,
                        dryRun = true,
                        asOf = clock.instant(),
                        openingProtocol = buildJsonObject { put("event", "opened") },
                    ),
                ).runId

        fun completed(): UUID {
            val id = open()
            val key = buildJsonObject { put("order_number", 9007199254740993L) }
            val preview =
                PreviewPlan(
                    UUID.randomUUID().toString(),
                    id.toString(),
                    listOf(
                        ApplyRow(
                            1,
                            "er.entity.release_order_verdict",
                            "insert",
                            key,
                            row = JsonObject(key + ("amount" to Json.parseToJsonElement("4.560000000000000001"))),
                        ),
                    ),
                    emptyList(),
                )
            ledger.completeEvaluation(id, EvaluationResult(VerdictSummary(emptyMap(), 0), emptyList()), preview)
            ledger.closePreview(id, "sub-maya")
            return id
        }

        "production readers serve closed pinned evidence and exact values after application restart" {
            val id = completed()
            repeat(2) {
                api { _ ->
                    val status = client.get("/automation/runs/$id") { bearerAuth(token) }
                    status.status shouldBe HttpStatusCode.OK
                    status.headers[HttpHeaders.RetryAfter] shouldBe null
                    val progress = Json.decodeFromString<RunProgress>(status.bodyAsText())
                    progress.status shouldBe "closed"
                    progress.recipe shouldBe "release_order"
                    progress.bundle shouldBe "original-bundle"
                    progress.dryRun shouldBe true
                    val protocol = client.get("/automation/runs/$id/protocol") { bearerAuth(token) }
                    protocol.status shouldBe HttpStatusCode.OK
                    val document = Json.decodeFromString<ProtocolDocument>(protocol.bodyAsText())
                    document.schema shouldBe "protocol/v1"
                    document.sections.keys.toList() shouldBe ProtocolSection.entries.map { it.wire }
                    val lifecycle = document.sections.getValue("automation")
                    lifecycle.map { it.seq } shouldBe listOf(1, 2, 3)
                    lifecycle[0].payload.jsonObject["event"] shouldBe JsonPrimitive("opened")
                    lifecycle[1].payload.jsonObject["event"] shouldBe JsonPrimitive("closed")
                    lifecycle[2].payload.jsonObject["sealed"] shouldBe JsonPrimitive(true)
                    val batch = client.get("/automation/runs/$id/batch") { bearerAuth(token) }
                    batch.status shouldBe HttpStatusCode.OK
                    val row = Json.decodeFromString<RunBatchPlan>(batch.bodyAsText()).rows.single()
                    row.key!!["order_number"]!!.jsonPrimitive.content shouldBe "9007199254740993"
                    row.row!!["amount"]!!.jsonPrimitive.content shouldBe "4.560000000000000001"
                    client.get("/automation/") { bearerAuth(token) }.status shouldBe HttpStatusCode.OK
                    client.get("/ready").status shouldBe HttpStatusCode.OK
                }
            }
        }

        "production reader authentication ownership and input validation precede data disclosure" {
            val id = completed()
            val legacy = open(null)
            api { _ ->
                for (suffix in listOf("", "/protocol", "/batch")) {
                    client.get("/automation/runs/$id$suffix").status shouldBe HttpStatusCode.Unauthorized
                    client
                        .get("/automation/runs/$id$suffix") {
                            bearerAuth(realm.person(signWith = TestRealm.rsaPair()))
                        }.status shouldBe HttpStatusCode.Unauthorized
                    client.get("/automation/runs/not-a-uuid$suffix") { bearerAuth(token) }.status shouldBe
                        HttpStatusCode.BadRequest
                    val hidden =
                        listOf(id, legacy, UUID.randomUUID()).map { handle ->
                            val response =
                                client.get("/automation/runs/$handle$suffix") {
                                    bearerAuth(realm.person(subject = "another-subject"))
                                }
                            response.status shouldBe HttpStatusCode.Forbidden
                            response.bodyAsText()
                        }
                    hidden.distinct().size shouldBe 1
                    client
                        .get("/automation/runs/$id$suffix") {
                            bearerAuth(realm.person(username = "renamed", subject = "sub-maya"))
                        }.status shouldBe HttpStatusCode.OK
                    client.get("/automation/runs/$legacy$suffix") { bearerAuth(token) }.status shouldBe
                        HttpStatusCode.Forbidden
                }
                client.get("/automation/runs/$id/protocol?render=pt") { bearerAuth(token) }.status shouldBe
                    HttpStatusCode.NotImplemented
                client.get("/automation/runs/$id/protocol?render=bad") { bearerAuth(token) }.status shouldBe
                    HttpStatusCode.BadRequest
                client
                    .get("/automation/runs/$id/protocol?render=pt") {
                        bearerAuth(realm.person(subject = "another-subject"))
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        "production polling is observation only even with the runtime installed" {
            val id = open()
            api { _ ->
                repeat(2) {
                    val response = client.get("/automation/runs/$id") { bearerAuth(token) }
                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.RetryAfter] shouldBe "15"
                    Json.decodeFromString<RunProgress>(response.bodyAsText()).status shouldBe "open"
                    client.get("/automation/runs/$id/batch") { bearerAuth(token) }.status shouldBe
                        HttpStatusCode.Conflict
                }
            }
            ledger.findRun(id)!!.status shouldBe RunStatus.OPEN
            transaction {
                RunProtocol.selectAll().count() shouldBe 1L
                RunEvaluations.selectAll().count() shouldBe 0L
                RunOutputs.selectAll().count() shouldBe 0L
                Batches.selectAll().count() shouldBe 0L
            }
        }

        "stopping notification keeps dependencies available until the HTTP drain completes" {
            val id = completed()
            api { installed ->
                // This notification starts shutdown, before the engine has drained its HTTP calls.
                // The former Stopping subscription closed the real application pool at this point.
                installed.monitor.raise(ApplicationStopping, installed)
                client.get("/ready").status shouldBe HttpStatusCode.OK
                for (suffix in listOf("", "/protocol", "/batch")) {
                    client.get("/automation/runs/$id$suffix") { bearerAuth(token) }.status shouldBe HttpStatusCode.OK
                }
            }
        }
    })
