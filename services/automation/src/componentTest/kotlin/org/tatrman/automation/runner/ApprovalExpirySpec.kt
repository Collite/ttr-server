// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.spyk
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.api.installAutomationErrors
import org.tatrman.automation.api.runRoutes
import org.tatrman.automation.bundle.actions
import org.tatrman.automation.engine.HttpEngineHistory
import org.tatrman.automation.identity.AUTOMATION_AUTH
import org.tatrman.automation.identity.AutomationBearerProvider
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.CallerVerifier
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.automation.identity.TestRealm
import org.tatrman.automation.identity.TokenKind
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.writer.RowWritesQueryFake
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val DEPLOYMENT = "release_native_event"
private const val TIMER = "s41_release_confirmed_lapsed"
private const val PERFORM = "/automation/deployments/$DEPLOYMENT/actions/hold/perform"

class ApprovalExpirySpec :
    StringSpec({
        beforeEach { LedgerTestDb.reset() }
        afterEach { dropWetRuntime() }

        "verified boundary timer expires one original key, links the Sales hold and replays without another write" {
            expiryFixture { fixture ->
                val (f, runtime, engine, history, fake) = fixture
                val original = waiting(f, runtime, engine)
                history.original = original.toString()
                val request = expiryRequest(original)
                val before = fake.writes.size
                val response = perform(engine.token, request)
                response.status shouldBe HttpStatusCode.OK
                val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val hold = result.getValue("runId").jsonPrimitive.content
                result["outcome"] shouldBe JsonPrimitive("applied")
                result.getValue("expiry").jsonObject["timerOccurrenceId"] shouldBe JsonPrimitive("timer-occur-1")
                salesState("4711") shouldBe listOf("held", "Sales")
                salesState("4712") shouldBe listOf("placed", "AR")
                fake.writes.size shouldBe before + 2

                val status = read("/automation/runs/$original", engine.token)
                status["status"] shouldBe JsonPrimitive("waiting")
                val actions = status.getValue("actions").jsonArray.map(JsonElement::jsonObject)
                val expired = actions.single { it["key"] == orderKey("4711") }.getValue("decided").jsonObject
                expired["decision"] shouldBe JsonPrimitive("expire")
                expired["holdRun"] shouldBe JsonPrimitive(hold)
                expired["by"] shouldBe null
                val other = actions.single { it["key"] == orderKey("4712") }
                other["decided"] shouldBe JsonNull
                val originalWrites = writes(original.toString(), engine.token)
                val entry = originalWrites.single { it["event"] == JsonPrimitive("held-expire") }
                entry["after_seal"] shouldBe JsonPrimitive(true)
                entry["holdRun"] shouldBe JsonPrimitive(hold)
                val holdProtocol = read("/automation/runs/$hold/protocol", engine.token).getValue("sections").jsonObject
                holdProtocol["people"]?.jsonArray.orEmpty() shouldBe emptyList()
                val opening =
                    holdProtocol
                        .getValue(
                            "automation",
                        ).jsonArray
                        .first()
                        .jsonObject
                        .getValue("payload")
                        .jsonObject
                opening.getValue("engine").jsonObject["instanceId"] shouldBe JsonPrimitive("pi-1")
                opening.getValue("expiry").jsonObject["originalRunId"] shouldBe JsonPrimitive(original.toString())

                history.processState = "COMPLETED"
                val retry = perform(engine.token, expiryRequest(original, idempotencyKey = "different"))
                retry.status shouldBe HttpStatusCode.OK
                val replay = Json.parseToJsonElement(retry.bodyAsText()).jsonObject
                replay["runId"] shouldBe JsonPrimitive(hold)
                replay["replayed"] shouldBe JsonPrimitive(true)
                fake.writes.size shouldBe before + 2
                writes(original.toString(), engine.token).count { it["event"] == JsonPrimitive("held-expire") } shouldBe
                    1
            }
        }

        "missing or mismatched trusted evidence refuses before changing the original or dispatching a hold" {
            expiryFixture { (f, runtime, engine, history, fake, realm) ->
                val original = waiting(f, runtime, engine)
                history.original = original.toString()
                val before = fake.writes.size
                val cases =
                    listOf(
                        realm.person() to expiryRequest(original),
                        engine.token to expiryRequest(original, target = "4712"),
                        engine.token to expiryRequest(original, taskId = "foreign-task"),
                        engine.token to expiryRequest(original, timer = "other-timer"),
                    )
                for ((token, request) in cases) {
                    perform(token, request).status shouldBe HttpStatusCode.Forbidden
                }
                history.timerCanceled = true
                perform(engine.token, expiryRequest(original)).status shouldBe HttpStatusCode.Forbidden
                history.timerCanceled = false
                history.deleteReason = "completed"
                perform(engine.token, expiryRequest(original)).status shouldBe HttpStatusCode.Forbidden
                history.deleteReason = "deleted"
                history.original = UUID.randomUUID().toString()
                perform(engine.token, expiryRequest(original)).status shouldBe HttpStatusCode.Forbidden
                history.original = original.toString()
                history.processState = "COMPLETED"
                val refused = perform(engine.token, expiryRequest(original))
                refused.status shouldBe HttpStatusCode.Forbidden
                val error =
                    Json
                        .parseToJsonElement(refused.bodyAsText())
                        .jsonObject
                        .getValue("error")
                        .jsonObject
                error["code"] shouldBe JsonPrimitive("identity")
                error.getValue("detail").jsonObject["reason"] shouldBe JsonPrimitive("unverified-expiry")
                fake.writes.size shouldBe before
                f.ledger
                    .heldGroup(original, orderKey("4711"))
                    .statuses
                    .all { it.decided == null } shouldBe true
                salesState("4711") shouldBe listOf("placed", "AR")
            }
        }

        "a human decision committed during the engine read wins and the fallback leaves the order released" {
            expiryFixture { (f, runtime, engine, history, fake) ->
                val original = waiting(f, runtime, engine)
                history.original = original.toString()
                val proving = CountDownLatch(1)
                val release = CountDownLatch(1)
                history.proving = proving to release
                kotlinx.coroutines.coroutineScope {
                    val pending = asyncPerform(this@expiryFixture, engine.token, expiryRequest(original))
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        proving.await(5, TimeUnit.SECONDS) shouldBe true
                    }
                    try {
                        val operator =
                            Caller(
                                "operator",
                                "operator",
                                emptySet(),
                                TokenKind.PERSON,
                                "token",
                                groups = setOf("hartland-ops"),
                            )
                        runtime.held.commitHeld(original, orderKey("4711"), operator).decision shouldBe "commit"
                    } finally {
                        release.countDown()
                    }
                    val before = fake.writes.size
                    pending.await() shouldBe HttpStatusCode.Conflict
                    fake.writes.size shouldBe before
                    salesState("4711").first() shouldBe "released"
                    writes(
                        original.toString(),
                        engine.token,
                    ).any { it["event"] == JsonPrimitive("held-expire") } shouldBe
                        false
                }
            }
        }

        "retry after the hold committed but before sealing reuses the linked run and journaled writes" {
            var failSeal = true
            expiryFixture(keys = listOf("4711"), ledgerFor = { ledger ->
                spyk(ledger).also { spy ->
                    every { spy.finishAdHocRun(any(), any(), any()) } answers {
                        if (failSeal) {
                            failSeal = false
                            throw AutomationException(ErrorCode.EXPIRY_HOLD_PENDING, "injected seal failure")
                        }
                        callOriginal()
                    }
                }
            }) { (f, runtime, engine, history, fake) ->
                val original = waiting(f, runtime, engine, keys = listOf("4711"))
                history.original = original.toString()
                val before = fake.writes.size
                val request = expiryRequest(original)
                perform(engine.token, request).status shouldBe HttpStatusCode.ServiceUnavailable
                salesState("4711") shouldBe listOf("held", "Sales")
                fake.writes.size shouldBe before + 2

                val status = read("/automation/runs/$original", engine.token)
                status["status"] shouldBe JsonPrimitive("closed")
                val decision =
                    status
                        .getValue("actions")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("decided")
                        .jsonObject
                val hold = decision.getValue("holdRun").jsonPrimitive.content
                read("/automation/runs/$hold", engine.token)["status"] shouldBe JsonPrimitive("open")
                writes(hold, engine.token).none { it["event"] == JsonPrimitive("performed") } shouldBe true
                f.base.now = f.base.now.plusSeconds(3600)
                f.ledger.abandonExpiredRuns() shouldBe emptyList()

                val retry = perform(engine.token, request)
                retry.status shouldBe HttpStatusCode.OK
                val result = Json.parseToJsonElement(retry.bodyAsText()).jsonObject
                result["runId"] shouldBe JsonPrimitive(hold)
                result["outcome"] shouldBe JsonPrimitive("applied")
                read("/automation/runs/$hold", engine.token)["status"] shouldBe JsonPrimitive("closed")
                fake.writes.size shouldBe before + 2
                writes(hold, engine.token).count { it["event"] == JsonPrimitive("performed") } shouldBe 1
            }
        }

        "retry stays pending when the hold committed but the Door response was lost" {
            expiryFixture { (f, runtime, engine, history, fake) ->
                val original = waiting(f, runtime, engine)
                history.original = original.toString()
                val before = fake.writes.size
                fake.loseCommit = { true }
                val request = expiryRequest(original)
                val pending = perform(engine.token, request)
                pending.status shouldBe HttpStatusCode.ServiceUnavailable
                val envelope = Json.parseToJsonElement(pending.bodyAsText()).jsonObject
                val error = envelope.getValue("error").jsonObject
                error["code"] shouldBe JsonPrimitive("expiry-hold-pending")
                val hold = error.getValue("detail").jsonObject.getValue("runId").jsonPrimitive.content
                fake.writes.size shouldBe before + 1
                salesState("4711") shouldBe listOf("held", "Sales")

                fake.loseCommit = { false }
                val retry = perform(engine.token, request)
                retry.status shouldBe HttpStatusCode.ServiceUnavailable
                Json.parseToJsonElement(retry.bodyAsText()).jsonObject["error"] shouldBe error
                read("/automation/runs/$hold", engine.token)["status"] shouldBe JsonPrimitive("open")
                writes(hold, engine.token).none { it["event"] == JsonPrimitive("performed") } shouldBe true
                fake.writes.size shouldBe before + 1
            }
        }

        "retry cannot attribute a separate Sales hold to the expiry run" {
            expiryFixture { (f, runtime, engine, history, fake) ->
                val original = waiting(f, runtime, engine)
                history.original = original.toString()
                val request = expiryRequest(original)
                val before = fake.writes.size
                fake.dropBeforeDispatch = { true }
                val pending = perform(engine.token, request)
                pending.status shouldBe HttpStatusCode.ServiceUnavailable
                val error = Json.parseToJsonElement(pending.bodyAsText()).jsonObject.getValue("error").jsonObject
                error["code"] shouldBe JsonPrimitive("expiry-hold-pending")
                val hold = error.getValue("detail").jsonObject.getValue("runId").jsonPrimitive.content
                salesState("4711") shouldBe listOf("placed", "AR")
                fake.writes.size shouldBe before + 1

                fake.dropBeforeDispatch = { false }
                val separate =
                    perform(
                        engine.token,
                        """{"key":{"order":"4711"},"params":{"department":"Sales"},"idempotencyKey":"separate"}""",
                    )
                separate.status shouldBe HttpStatusCode.OK
                val result = Json.parseToJsonElement(separate.bodyAsText()).jsonObject
                result["outcome"] shouldBe JsonPrimitive("applied")
                val separateRun = result.getValue("runId").jsonPrimitive.content
                salesState("4711") shouldBe listOf("held", "Sales")
                wetQuery("SELECT run::text FROM wet_runtime.state_history") shouldBe listOf(listOf(separateRun))
                fake.writes.size shouldBe before + 3

                val retry = perform(engine.token, request)
                retry.status shouldBe HttpStatusCode.ServiceUnavailable
                Json.parseToJsonElement(retry.bodyAsText()).jsonObject["error"] shouldBe error
                read("/automation/runs/$hold", engine.token)["status"] shouldBe JsonPrimitive("open")
                writes(hold, engine.token).none { it["event"] == JsonPrimitive("performed") } shouldBe true
                fake.writes.size shouldBe before + 3
            }
        }

        "legacy hold performs exactly as before and asserts no timer decision" {
            expiryFixture { (f, runtime, engine, _, fake) ->
                val original = waiting(f, runtime, engine)
                val before = fake.writes.size
                val request = """{"key":{"order":"4711"},"params":{"department":"Sales"},"idempotencyKey":"legacy"}"""
                val response = perform(engine.token, request)
                response.status shouldBe HttpStatusCode.OK
                val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                result["outcome"] shouldBe JsonPrimitive("applied")
                result["expiry"] shouldBe null
                perform(engine.token, request).status shouldBe HttpStatusCode.OK
                fake.writes.size shouldBe before + 2
                f.ledger
                    .heldGroup(original, orderKey("4711"))
                    .statuses
                    .all { it.decided == null } shouldBe true
            }
        }
    })

private data class ExpiryFixture(
    val f: DurableRuntimeFixture,
    val runtime: RunRuntime,
    val engine: Caller,
    val history: ExpiryHistoryStub,
    val fake: RowWritesQueryFake,
    val realm: TestRealm,
)

private suspend fun expiryFixture(
    ledgerFor: (Ledger) -> Ledger = { it },
    keys: List<String> = listOf("4711", "4712"),
    test: suspend ApplicationTestBuilder.(ExpiryFixture) -> Unit,
) {
    TestRealm().use { realm ->
        val bearer = realm.service("process-engine")
        ExpiryHistoryStub(bearer).use { history ->
            val engine =
                Caller("sub-process-engine", "process-engine", setOf("hartland-ops"), TokenKind.SERVICE, bearer)
            val root = Files.createTempDirectory("automation-expiry-")
            val (f, fake) =
                wetRuntimeFixture(
                    root,
                    initial = "placed",
                    keys = listOf("4711", "4712"),
                    wire = "release_confirmed",
                    candidates = { keys },
                    engineHistory = HttpEngineHistory(history.url),
                    tokens =
                        ServiceTokens(
                            mapOf("AUTOMATION_DECIDER" to "svc-hartland-automation:s"),
                            { realm.service("svc-hartland-automation") },
                        ),
                    runAsCaller = false,
                    ledgerFor = ledgerFor,
                    afterPrepare = { bundle ->
                        val recipe = bundle.recipes.getValue("release_order")
                        val coverage =
                            recipe.coverage.copy(
                                actions =
                                    recipe.coverage.actions + ("hold" to bundle.actions.getValue("hold")),
                            )
                        bundle.copy(recipes = bundle.recipes + ("release_order" to recipe.copy(coverage = coverage)))
                    },
                )
            wetSql("INSERT INTO public.sales_order VALUES ('4712', 'placed', 'AR')")
            val runtime = f.start()
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
                        routing { authenticate(AUTOMATION_AUTH) { runRoutes(runtime) } }
                    }
                    test(ExpiryFixture(f, runtime, engine, history, fake, realm))
                }
            } finally {
                runtime.shutdown()
                f.close()
                root.toFile().deleteRecursively()
            }
        }
    }
}

private suspend fun waiting(
    f: DurableRuntimeFixture,
    runtime: RunRuntime,
    engine: Caller,
    keys: List<String> = listOf("4711", "4712"),
): UUID {
    val request =
        f.request.copy(
            dryRun = null,
            port = buildJsonObject { put("keys", JsonArray(keys.map(::JsonPrimitive))) },
            engine = EngineRef("pi-1", "orders-0417", "dep-7"),
        )
    val id =
        UUID.fromString(
            runtime.sessions
                .open("release_order", request, engine)
                .response.runId,
        )
    runtime.sessions.evaluate(id, engine)
    runtime.closer.close(id, engine).status shouldBe "waiting"
    return id
}

private suspend fun ApplicationTestBuilder.perform(
    token: String,
    payload: String,
) = client.post(PERFORM) {
    bearerAuth(token)
    contentType(ContentType.Application.Json)
    setBody(payload)
}

private fun kotlinx.coroutines.CoroutineScope.asyncPerform(
    app: ApplicationTestBuilder,
    token: String,
    payload: String,
) = async { app.perform(token, payload).status }

private suspend fun ApplicationTestBuilder.read(
    path: String,
    token: String,
): JsonObject {
    val response = client.get(path) { bearerAuth(token) }
    response.status shouldBe HttpStatusCode.OK
    return Json.parseToJsonElement(response.bodyAsText()).jsonObject
}

private suspend fun ApplicationTestBuilder.writes(
    run: String,
    token: String,
) = read("/automation/runs/$run/protocol", token)
    .getValue(
        "sections",
    ).jsonObject
    .getValue("writes")
    .jsonArray
    .map { it.jsonObject.getValue("payload").jsonObject }

private fun orderKey(order: String) = buildJsonObject { put("order_number", order) }

private fun salesState(order: String) =
    wetQuery("SELECT state, held_for FROM public.sales_order WHERE order_number = '$order'").single()

private fun expiryRequest(
    original: UUID,
    target: String = "4711",
    taskId: String = "task-1",
    timer: String = TIMER,
    idempotencyKey: String = "pi-1:lapsed:4711",
): String =
    buildJsonObject {
        put("key", buildJsonObject { put("order", target) })
        put("params", buildJsonObject { put("department", "Sales") })
        put("idempotencyKey", idempotencyKey)
        put(
            "expiry",
            buildJsonObject {
                put("originalRunId", original.toString())
                put("action", "release_confirmed")
                put("key", orderKey(target))
                put("instanceId", "pi-1")
                put("taskId", taskId)
                put("timerActivityId", timer)
            },
        )
    }.toString()

private class ExpiryHistoryStub(
    bearer: String,
) : AutoCloseable {
    @Volatile var original = ""

    @Volatile var timerCanceled = false

    @Volatile var deleteReason = "deleted"

    @Volatile var processState = "ACTIVE"

    @Volatile var proving: Pair<CountDownLatch, CountDownLatch>? = null

    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/engine-rest/history/") { exchange ->
                val query = exchange.requestURI.query.orEmpty()
                val path = exchange.requestURI.path.substringAfterLast('/')
                val authorized = exchange.requestHeaders.getFirst("Authorization") == "Bearer $bearer"
                val body =
                    when {
                        !authorized -> "{}"
                        path == "task" && "taskId=task-1" in query ->
                            """[{"id":"task-1","processInstanceId":"pi-1","processDefinitionId":"definition-1",
                              "taskDefinitionKey":"approval-task","executionId":"task-execution","deleteReason":"$deleteReason","assignee":"maya",
                              "startTime":"2000-01-01T12:01:45.000+0000","due":"2000-01-01T12:02:00.000+0000","endTime":"2000-01-01T12:02:05.010+0000"}]"""
                        path == "activity-instance" && "activityId=$TIMER" in query -> {
                            proving?.let { (entered, release) ->
                                entered.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                            }
                            """[{"id":"timer-occur-1","activityId":"$TIMER","activityType":"boundaryTimer","processInstanceId":"pi-1",
                              "processDefinitionId":"definition-1","parentActivityInstanceId":"scope-1","canceled":$timerCanceled,
                              "startTime":"2000-01-01T12:02:05.015+0000","endTime":"2000-01-01T12:02:05.018+0000"}]"""
                        }
                        path == "process-instance" ->
                            """[{"id":"pi-1","processDefinitionId":"definition-1","businessKey":"orders-0417","state":"$processState"}]"""
                        path == "activity-instance" ->
                            """[{"id":"approval-occur-1","activityId":"approval-task","activityType":"userTask","taskId":"task-1",
                              "executionId":"task-execution","processInstanceId":"pi-1","processDefinitionId":"definition-1",
                              "parentActivityInstanceId":"scope-1","canceled":true,"endTime":"2000-01-01T12:02:05.008+0000"}]"""
                        path == "variable-instance" && "taskIdIn=task-1" in query ->
                            """[{"name":"ttr_action","type":"String","value":"release_confirmed","processInstanceId":"pi-1",
                              "taskId":"task-1","activityInstanceId":"approval-occur-1"},
                              {"name":"ttr_about","type":"String","value":"4711","processInstanceId":"pi-1",
                              "taskId":"task-1","activityInstanceId":"approval-occur-1"}]"""
                        path == "variable-instance" && "variableName=ttrRunId" in query ->
                            """[{"name":"ttrRunId","type":"String","value":"$original","processInstanceId":"pi-1"}]"""
                        else -> "[]"
                    }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(if (authorized) 200 else 401, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    val url = "http://127.0.0.1:${server.address.port}/engine-rest"

    override fun close() {
        server.stop(0)
    }
}
