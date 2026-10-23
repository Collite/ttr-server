// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.tatrman.automation.identity.*
import org.tatrman.automation.ledger.RunStatus
import org.tatrman.automation.runner.*
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class AsyncRoutesSpec :
    StringSpec({
        val realm = TestRealm()
        afterSpec { realm.close() }

        "batch expiry returns a stable 504 budget on active evaluate and subsequent polls without a retry hint" {
            val now = java.time.Instant.parse("2026-10-23T12:00:00Z")
            val clock = BatchClock(now)
            val entered = CompletableDeferred<Unit>()
            val reads = AtomicInteger()
            val fixture =
                RunSessionFixture(
                    batchDefault = Duration.ofSeconds(10),
                    clock = clock,
                    door =
                        Door {
                            reads.incrementAndGet()
                            entered.complete(Unit)
                            awaitCancellation()
                        },
                )
            try {
                testApplication {
                    application { asyncTestApi(fixture, realm) }
                    val token = realm.person("maya", listOf("sales_manager"))
                    val id = fixture.open(by = Caller.of(token)!!).response.runId
                    val base = "/automation/runs/$id"
                    coroutineScope {
                        val request = async { client.post("$base/evaluate") { bearerAuth(token) } }
                        withTimeout(5000) { entered.await() }
                        clock.now = now.plusSeconds(10)
                        val expired = withTimeout(5000) { request.await() }
                        for (response in listOf(
                            expired,
                            client.get(base) { bearerAuth(token) },
                            client.post("$base/evaluate") { bearerAuth(token) },
                        )) {
                            response.status shouldBe HttpStatusCode.GatewayTimeout
                            response.headers[HttpHeaders.RetryAfter] shouldBe null
                            Json
                                .parseToJsonElement(
                                    response.bodyAsText(),
                                ).jsonObject["error"]!!
                                .jsonObject["code"] shouldBe
                                JsonPrimitive("budget")
                        }
                    }
                    reads.get() shouldBe 1
                    fixture.outputs.size shouldBe 0
                    fixture.evaluations.size shouldBe 0
                }
            } finally {
                fixture.close()
            }
        }

        "slow evaluation returns 202 within the pinned sync budget and polls to one completed decision" {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val reads = AtomicInteger()
            val starts = AtomicInteger()
            val fixture =
                asyncFixture(
                    sync = Duration.ofSeconds(1),
                    beforeRunning = { starts.incrementAndGet() },
                    door =
                        Door { read ->
                            reads.incrementAndGet()
                            if (read.island == "placed") {
                                entered.complete(Unit)
                                finish.await()
                            }
                            asyncRows(read)
                        },
                )
            try {
                testApplication {
                    application { asyncTestApi(fixture, realm) }
                    val token = realm.person("maya", listOf("sales_manager"))
                    val opened = fixture.open(by = Caller.of(token)!!)
                    val id = UUID.fromString(opened.response.runId)
                    val base = "/automation/runs/$id"

                    suspend fun poll() = client.get(base) { bearerAuth(token) }
                    client.get(base).status shouldBe HttpStatusCode.Unauthorized
                    client
                        .get(base) { bearerAuth(realm.person("other", listOf("sales_manager"))) }
                        .status shouldBe HttpStatusCode.ServiceUnavailable
                    fixture.statusReads.get() shouldBe 0
                    val open = poll()
                    open.headers[HttpHeaders.RetryAfter] shouldBe "15"
                    Json.parseToJsonElement(open.bodyAsText()).jsonObject["status"] shouldBe JsonPrimitive("open")
                    reads.get() shouldBe 0
                    coroutineScope {
                        val request = async { client.post("$base/evaluate") { bearerAuth(token) } }
                        withTimeout(5000) { entered.await() }
                        val pending = withTimeout(5000) { request.await() }
                        pending.status shouldBe HttpStatusCode.Accepted
                        pending.headers[HttpHeaders.Location] shouldBe base
                        pending.headers[HttpHeaders.RetryAfter] shouldBe "15"
                        val body = Json.parseToJsonElement(pending.bodyAsText()).jsonObject
                        body["runId"] shouldBe JsonPrimitive(id.toString())
                        body["location"] shouldBe JsonPrimitive(base)
                        body["status"] shouldBe JsonPrimitive("running")
                        val running = withTimeout(1000) { poll() }
                        Json.parseToJsonElement(running.bodyAsText()).jsonObject["status"] shouldBe
                            JsonPrimitive("running")
                        val retry =
                            client.post("/automation/recipes/release_order/runs") {
                                bearerAuth(token)
                                contentType(ContentType.Application.Json)
                                setBody(
                                    """{"deployment":"release_native_event","port":{"key":"4711"},
                                    "idempotencyKey":"engine:1"}""",
                                )
                            }
                        retry.status shouldBe HttpStatusCode.Accepted
                        retry.headers[HttpHeaders.Location] shouldBe base
                        retry.headers[HttpHeaders.RetryAfter] shouldBe "15"
                        Json.parseToJsonElement(retry.bodyAsText()).jsonObject["runId"] shouldBe
                            JsonPrimitive(id.toString())
                        client.post("$base/evaluate") { bearerAuth(token) }.status shouldBe HttpStatusCode.Accepted
                        reads.get() shouldBe 1
                        starts.get() shouldBe 1
                        fixture.created.get() shouldBe 1
                        finish.complete(Unit)
                    }
                    val complete =
                        withTimeout(5000) {
                            var response = poll()
                            while (Json.parseToJsonElement(response.bodyAsText()).jsonObject["status"] !=
                                JsonPrimitive("evaluated")
                            ) {
                                delay(10)
                                response = poll()
                            }
                            response
                        }
                    complete.status shouldBe HttpStatusCode.OK
                    complete.headers[HttpHeaders.RetryAfter] shouldBe null
                    val progress = Json.parseToJsonElement(complete.bodyAsText()).jsonObject
                    progress["verdict"]!!.jsonObject["routes"]!!.jsonObject["release"]!!.jsonObject["rowCount"] shouldBe
                        JsonPrimitive(1)
                    progress["protocolRef"] shouldBe JsonPrimitive("$base/protocol")
                    val again = client.post("$base/evaluate") { bearerAuth(token) }
                    again.status shouldBe HttpStatusCode.OK
                    Json.parseToJsonElement(again.bodyAsText()).jsonObject["verdict"] shouldBe progress["verdict"]
                    reads.get() shouldBe 4
                    starts.get() shouldBe 1
                    fixture.evaluations.size shouldBe 1
                    fixture.runs.release(id)
                    poll().status shouldBe HttpStatusCode.ServiceUnavailable
                    client.get("/automation/runs/1-1-1-1-1") { bearerAuth(token) }.status shouldBe
                        HttpStatusCode.BadRequest
                    client.get("/automation/runs/${UUID.randomUUID()}") { bearerAuth(token) }.status shouldBe
                        HttpStatusCode.ServiceUnavailable
                }
            } finally {
                finish.complete(Unit)
                fixture.close()
            }
        }

        "an async refusal is returned by subsequent polls and evaluate without restarting the failed run" {
            val finish = CompletableDeferred<Unit>()
            val reads = AtomicInteger()
            val fixture =
                asyncFixture(
                    sync = Duration.ofMillis(20),
                    door =
                        Door {
                            reads.incrementAndGet()
                            finish.await()
                            throw AutomationException(ErrorCode.OUT_OF_SCOPE_READ, "declared scan refused")
                        },
                )
            try {
                testApplication {
                    application { asyncTestApi(fixture, realm) }
                    val token = realm.person("maya", listOf("sales_manager"))
                    val id = UUID.fromString(fixture.open(by = Caller.of(token)!!).response.runId)
                    val base = "/automation/runs/$id"
                    client.post("$base/evaluate") { bearerAuth(token) }.status shouldBe HttpStatusCode.Accepted
                    finish.complete(Unit)
                    withTimeout(5000) { while (fixture.states[id] != RunStatus.FAILED) delay(5) }
                    for (response in listOf(
                        client.get(base) { bearerAuth(token) },
                        client.post("$base/evaluate") { bearerAuth(token) },
                        client.get(base) { bearerAuth(token) },
                    )) {
                        response.status shouldBe HttpStatusCode.UnprocessableEntity
                        Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject["code"] shouldBe
                            JsonPrimitive("out-of-scope-read")
                        response.headers[HttpHeaders.RetryAfter] shouldBe null
                    }
                    reads.get() shouldBe 1
                    fixture.evaluations.size shouldBe 0
                }
            } finally {
                finish.complete(Unit)
                fixture.close()
            }
        }
    })

private fun Application.asyncTestApi(
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
    routing {
        authenticate(AUTOMATION_AUTH) {
            openRoutes(fixture.runs)
            evaluateRoutes(fixture.runs, fixture.runs::progress)
        }
    }
}
