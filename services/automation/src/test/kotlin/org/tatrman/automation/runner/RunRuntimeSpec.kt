// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import com.google.protobuf.ByteString
import com.typesafe.config.ConfigFactory
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.tatrman.automation.api.*
import org.tatrman.automation.bundle.ApprovalReader
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.config.AutomationConfig
import org.tatrman.automation.identity.*
import org.tatrman.automation.ledger.*
import org.tatrman.automation.queue.RunDelivery
import org.tatrman.automation.queue.RunWorkQueue
import org.tatrman.automation.staging.LocalStagingStore
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ValuesNode
import org.tatrman.query.v1.CompileResponse
import org.tatrman.query.v1.ErReadScope
import org.tatrman.query.v1.RunRequest
import org.tatrman.worker.v1.ResultBatch
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RunRuntimeSpec :
    StringSpec({
        val realm = TestRealm()
        afterSpec { realm.close() }
        val token = realm.person("maya", listOf("sales_manager"))

        "mounted runtime shares queued evaluation across routes, pins Query aliases and publishes verified outputs" {
            val f = RuntimeFixture()
            try {
                testApplication {
                    application { runtimeApi(f.runtime, realm) }
                    client.post("/automation/recipes/release_order/runs").status shouldBe HttpStatusCode.Unauthorized
                    val id = openRun(token)
                    val base = "/automation/runs/$id"
                    client.get(base) { bearerAuth(token) }.bodyAsText().json()["status"] shouldBe JsonPrimitive("open")
                    f.queue.enqueues.get() shouldBe 0
                    f.bundle = f.bundle.copy(recipes = emptyMap(), modelFp = "refreshed-model")
                    client.post("$base/parts/candidates") { bearerAuth(token) }.status shouldBe HttpStatusCode.OK
                    // A route read invokes PartRunner.evaluate rather than RunSessions.evaluate: it must dispatch too.
                    val route = client.get("$base/routes/release") { bearerAuth(token) }
                    route.status shouldBe HttpStatusCode.OK
                    route.bodyAsText().json()["rowCount"] shouldBe JsonPrimitive(1)
                    client.post("$base/evaluate") { bearerAuth(token) }.status shouldBe HttpStatusCode.OK
                    val download = client.get("$base/outputs/way.release") { bearerAuth(token) }
                    download.status shouldBe HttpStatusCode.OK
                    download
                        .bodyAsText()
                        .json()["rows"]!!
                        .jsonArray
                        .single()
                        .jsonObject["order_number"] shouldBe
                        JsonPrimitive("4711")
                    f.queue.enqueues.get() shouldBe 1
                    f.queue.claims.get() shouldBe 1
                    f.evaluations.size shouldBe 1
                    f.outputs.size shouldBe 4
                    f.requests.size shouldBe 4
                    f.requests.forEach {
                        it.world shouldBe "us-erp"
                        it.context.modelVersion shouldBe "pinned-model"
                        it.context.userId shouldBe "maya"
                        it.context.authRolesList shouldBe listOf("sales_manager")
                    }
                    val before = f.statusReads.get()
                    client.get(base) { bearerAuth(realm.person("other", listOf("sales_manager"))) }.status shouldBe
                        HttpStatusCode.Forbidden
                    for (url in listOf("$base/routes/release", "$base/outputs/way.release")) {
                        client.get(url) { bearerAuth(realm.person("other", listOf("sales_manager"))) }.status shouldBe
                            HttpStatusCode.Forbidden
                    }
                    f.statusReads.get() shouldBe before
                }
            } finally {
                f.close()
            }
        }

        "mounted close seals execution reads and retries while status and batch stay readable" {
            val f = RuntimeFixture()
            try {
                testApplication {
                    application { runtimeApi(f.runtime, realm) }
                    val id = openRun(token)
                    val base = "/automation/runs/$id"
                    client.post("$base/close") { bearerAuth(token) }.status shouldBe HttpStatusCode.Conflict
                    client.post("$base/evaluate") { bearerAuth(token) }.status shouldBe HttpStatusCode.OK
                    val count = f.requests.size
                    val batch = client.get("$base/batch") { bearerAuth(token) }.bodyAsText()
                    val closed = client.post("$base/close") { bearerAuth(token) }
                    closed.status shouldBe HttpStatusCode.OK
                    closed.bodyAsText().json()["status"] shouldBe JsonPrimitive("closed")
                    for (url in listOf("$base/evaluate", "$base/parts/candidates", "$base/islands/way")) {
                        val response = client.post(url) { bearerAuth(token) }
                        response.status shouldBe HttpStatusCode.Conflict
                        response.bodyAsText().json()["error"]!!.jsonObject["code"] shouldBe JsonPrimitive("run-closed")
                    }
                    for (url in listOf(
                        "$base/routes/release",
                        "$base/outputs/way.release",
                        "$base/routes/release/details/shortfall?row=0",
                    )) {
                        val response = client.get(url) { bearerAuth(token) }
                        response.status shouldBe HttpStatusCode.Conflict
                        response.bodyAsText().json()["error"]!!.jsonObject["code"] shouldBe JsonPrimitive("run-closed")
                    }
                    client.get(base) { bearerAuth(token) }.bodyAsText().json()["status"] shouldBe
                        JsonPrimitive("closed")
                    client.get("$base/batch") { bearerAuth(token) }.bodyAsText() shouldBe batch
                    client.post("$base/close") { bearerAuth(token) }.bodyAsText() shouldBe closed.bodyAsText()
                    f.requests.size shouldBe count
                }
            } finally {
                f.close()
            }
        }

        "mounted configured clock bounds queued work and records a stable typed budget refusal" {
            val entered = CompletableDeferred<Unit>()
            val f =
                RuntimeFixture(sync = "20ms", batch = "10s", skew = "90s", beforeRun = {
                    entered.complete(Unit)
                    awaitCancellation()
                })
            try {
                testApplication {
                    application { runtimeApi(f.runtime, realm) }
                    val id = UUID.fromString(openRun(token))
                    val base = "/automation/runs/$id"
                    f.records.getValue(id).asOf shouldBe f.baseClock.now.plusSeconds(90)
                    f.starts.getValue(id) shouldBe f.baseClock.now.plusSeconds(90)
                    client.post("$base/evaluate") { bearerAuth(token) }.status shouldBe HttpStatusCode.Accepted
                    withTimeout(5000) { entered.await() }
                    f.baseClock.now = f.baseClock.now.plusSeconds(10)
                    withTimeout(5000) { while (f.failures[id] == null) delay(10) }
                    (f.failures.getValue(id) as AutomationException).code shouldBe ErrorCode.BUDGET
                    f.baseClock.now = f.baseClock.now.minusSeconds(100)
                    val progress = client.get(base) { bearerAuth(token) }
                    progress.status shouldBe HttpStatusCode.OK
                    progress.headers[HttpHeaders.RetryAfter] shouldBe null
                    progress.bodyAsText().json()["status"] shouldBe JsonPrimitive("failed")
                    progress.bodyAsText().json()["error"]!!.jsonObject["code"] shouldBe JsonPrimitive("budget")
                    for (response in listOf(
                        client.post("$base/evaluate") { bearerAuth(token) },
                        client.post("$base/parts/candidates") { bearerAuth(token) },
                    )) {
                        response.status shouldBe HttpStatusCode.GatewayTimeout
                        response.headers[HttpHeaders.RetryAfter] shouldBe null
                        response.bodyAsText().json()["error"]!!.jsonObject["code"] shouldBe JsonPrimitive("budget")
                    }
                    f.queue.claims.get() shouldBe 1
                    f.requests.size shouldBe 1
                    f.outputs.size shouldBe 0
                    f.evaluations.size shouldBe 0
                }
            } finally {
                f.close()
            }
        }

        "a restarted runtime gets a fresh owner and refuses missing original contexts without claiming old triggers" {
            val f = RuntimeFixture(sync = "20ms")
            f.queue.deliver = false
            var restarted: RunRuntime? = null
            try {
                val caller = Caller.of(token)!!
                val opened = f.runtime.sessions.open("release_order", request(), caller)
                val id = UUID.fromString(opened.response.runId)
                f.runtime.sessions.evaluate(id, caller) shouldBe null
                f.queue.enqueues.get() shouldBe 1
                f.runtime.shutdown()
                restarted = f.createRuntime()
                (restarted.owner == f.runtime.owner) shouldBe false
                f.queue.deliver = true
                testApplication {
                    application { runtimeApi(restarted, realm) }
                    val replay =
                        client.post("/automation/recipes/release_order/runs") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(OPEN_BODY)
                        }
                    replay.status shouldBe HttpStatusCode.Accepted
                    replay.bodyAsText().json()["runId"] shouldBe JsonPrimitive(id.toString())
                    client.get("/automation/runs/$id") { bearerAuth(token) }.status shouldBe
                        HttpStatusCode.OK
                    client.post("/automation/runs/$id/evaluate") { bearerAuth(token) }.status shouldBe
                        HttpStatusCode.ServiceUnavailable
                    f.queue.claims.get() shouldBe 0
                    f.requests.size shouldBe 0
                    f.outputs.size shouldBe 0
                }
            } finally {
                restarted?.shutdown()
                f.close()
            }
        }

        "shutdown fences HTTP work and joins noncancellable Query cleanup before returning to the application" {
            val entered = CompletableDeferred<Unit>()
            val cancelling = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val f =
                RuntimeFixture(sync = "20ms", beforeRun = {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cancelling.complete(Unit)
                            finish.await()
                        }
                    }
                })
            try {
                testApplication {
                    application { runtimeApi(f.runtime, realm) }
                    val id = openRun(token)
                    client.post("/automation/runs/$id/evaluate") { bearerAuth(token) }.status shouldBe
                        HttpStatusCode.Accepted
                    withTimeout(5000) { entered.await() }
                    coroutineScope {
                        val stopping = async { f.runtime.shutdown() }
                        withTimeout(5000) { cancelling.await() }
                        val alsoStopping = async { f.runtime.shutdown() }
                        stopping.isCompleted shouldBe false
                        alsoStopping.isCompleted shouldBe false
                        client.get("/automation/runs/$id") { bearerAuth(token) }.status shouldBe
                            HttpStatusCode.OK
                        client
                            .post("/automation/recipes/release_order/runs") {
                                bearerAuth(token)
                                contentType(ContentType.Application.Json)
                                setBody(OPEN_BODY)
                            }.status shouldBe HttpStatusCode.ServiceUnavailable
                        finish.complete(Unit)
                        withTimeout(5000) {
                            stopping.await()
                            alsoStopping.await()
                        }
                    }
                    f.outputs.size shouldBe 0
                    f.evaluations.size shouldBe 0
                }
            } finally {
                finish.complete(Unit)
                f.close()
            }
        }

        "an unmapped configured storage refuses before Query and journals the original door refusal" {
            val f = RuntimeFixture(routes = "")
            try {
                testApplication {
                    application { runtimeApi(f.runtime, realm) }
                    val id = openRun(token)
                    repeat(2) {
                        val response = client.post("/automation/runs/$id/evaluate") { bearerAuth(token) }
                        response.status shouldBe HttpStatusCode.BadGateway
                        response.bodyAsText().json()["error"]!!.jsonObject["code"] shouldBe
                            JsonPrimitive("door-refused")
                    }
                    f.requests.size shouldBe 0
                    f.compiles.get() shouldBe 0
                    f.failures.size shouldBe 1
                    f.queue.claims.get() shouldBe 1
                }
            } finally {
                f.close()
            }
        }

        "released staging refuses execution with 410 while owner-scoped status remains readable" {
            val f = RuntimeFixture()
            try {
                testApplication {
                    application { runtimeApi(f.runtime, realm) }
                    val id = UUID.fromString(openRun(token))
                    val base = "/automation/runs/$id"
                    f.released.add(id)
                    for (url in listOf("$base/routes/release", "$base/outputs/way.release")) {
                        val response = client.get(url) { bearerAuth(token) }
                        response.status shouldBe HttpStatusCode.Gone
                        response.bodyAsText().json()["error"]!!.jsonObject["code"] shouldBe
                            JsonPrimitive("staging-released")
                        client.get(url) { bearerAuth(realm.person("other", listOf("sales_manager"))) }.status shouldBe
                            HttpStatusCode.Forbidden
                    }
                    client.post("$base/evaluate") { bearerAuth(token) }.status shouldBe HttpStatusCode.Gone
                    client.get(base) { bearerAuth(token) }.status shouldBe HttpStatusCode.OK
                    f.requests.size shouldBe 0
                }
            } finally {
                f.close()
            }
        }

        "maintenance reaps local abandoned sessions even when the sweep returns no newly abandoned ids" {
            val entered = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val f =
                RuntimeFixture(beforeSweep = {
                    entered.countDown()
                    check(finish.await(10, TimeUnit.SECONDS))
                })
            try {
                withContext(Dispatchers.IO) { check(entered.await(5, TimeUnit.SECONDS)) }
                testApplication {
                    application { runtimeApi(f.runtime, realm) }
                    val id = UUID.fromString(openRun(token))
                    f.states[id] = RunStatus.ABANDONED // Another process made the durable transition.
                    finish.countDown()
                    withTimeout(5000) { while (f.queue.cleanups.get() == 0) delay(10) }
                    f.states[id] = RunStatus.OPEN // Prove removal, independently of the persisted status guard.
                    client.get("/automation/runs/$id") { bearerAuth(token) }.status shouldBe
                        HttpStatusCode.OK
                    client.post("/automation/runs/$id/evaluate") { bearerAuth(token) }.status shouldBe
                        HttpStatusCode.ServiceUnavailable
                    f.requests.size shouldBe 0
                    f.queue.claims.get() shouldBe 0
                }
            } finally {
                finish.countDown()
                f.close()
            }
        }
    })

private const val OPEN_BODY =
    """{"deployment":"release_native_event","port":{"key":"4711"},"idempotencyKey":"runtime:1"}"""

private fun request() =
    OpenRequest(
        "release_native_event",
        buildJsonObject {
            put("key", "4711")
        },
        idempotencyKey = "runtime:1",
    )

private fun String.json() = Json.parseToJsonElement(this).jsonObject

private suspend fun io.ktor.server.testing.ApplicationTestBuilder.openRun(token: String): String {
    val response =
        client.post("/automation/recipes/release_order/runs") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(OPEN_BODY)
        }
    response.status shouldBe HttpStatusCode.Created
    return response
        .bodyAsText()
        .json()
        .getValue("runId")
        .jsonPrimitive.content
}

private fun Application.runtimeApi(
    runtime: RunRuntime,
    realm: TestRealm,
) {
    install(ContentNegotiation) { json() }
    install(StatusPages) { installAutomationErrors() }
    install(Authentication) {
        AutomationBearerProvider.register(
            this,
            CallerVerifier(JwksBearerVerifier(HttpJwksKeySource(realm.jwksUrl), TestRealm.ISSUER, TestRealm.AUDIENCE)),
        )
    }
    routing { authenticate(AUTOMATION_AUTH) { runRoutes(runtime) } }
}

/** Only persistence, queue transport and the Query stub are replaced; all runtime wiring is production code. */
private class RuntimeFixture(
    sync: String = "60s",
    batch: String = "30m",
    skew: String = "0s",
    routes: String = "demo/erp_db=us-erp",
    beforeSweep: () -> Unit = {},
    private val beforeRun: suspend () -> Unit = {},
) {
    val baseClock = BatchClock(Instant.parse("2026-10-23T12:00:00Z"))
    private val config =
        AutomationConfig.load(
            ConfigFactory
                .parseString(
                    """
                    automation.db.host = "unused"
                    automation.door.queryUrl = "unused"
                    automation.door.velesUrl = "unused"
                    automation.door.routes = "$routes"
                    automation.auth.jwksUrl = "http://unused/certs"
                    automation.auth.issuer = "unused"
                    automation.clock.skew = $skew
                    automation.budgets.syncDefault = $sync
                    automation.budgets.batchDefault = $batch
                    automation.budgets.displayRows = 1
                    automation.budgets.detailLimit = 2
                    automation.runner.workers = 1
                    """.trimIndent(),
                ).withFallback(ConfigFactory.parseResources("application.conf"))
                .resolve(),
            emptyMap(),
        )
    private val clock = Clock.offset(baseClock, config.clockSkew)
    private val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val columns = listOf(ManifestColumn("order_number", "text"), ManifestColumn("verdict_reason", "text"))
    var bundle = sessionBundle(columns)
    val queue = RuntimeQueue()
    val records = ConcurrentHashMap<UUID, RunOpen>()
    val starts = ConcurrentHashMap<UUID, Instant>()
    val states = ConcurrentHashMap<UUID, RunStatus>()
    val failures = ConcurrentHashMap<UUID, Exception>()
    val evaluations = ConcurrentHashMap<UUID, EvaluationResult>()
    val previews = ConcurrentHashMap<UUID, org.tatrman.automation.apply.PreviewPlan>()
    val outputs = ConcurrentLinkedQueue<RunOutput>()
    val requests = ConcurrentLinkedQueue<RunRequest>()
    val compiles = AtomicInteger()
    val statusReads = AtomicInteger()
    private val store = LocalStagingStore(Files.createTempDirectory("au-runtime"))
    val released = ConcurrentHashMap.newKeySet<UUID>()
    private val ledger = mockk<Ledger>()
    private val query =
        object : QueryRun {
            override suspend fun compile(request: RunRequest): CompileResponse {
                compiles.incrementAndGet()
                return CompileResponse
                    .newBuilder()
                    .setPlan(PlanNode.newBuilder().setValues(ValuesNode.getDefaultInstance()))
                    .setErReads(ErReadScope.getDefaultInstance())
                    .build()
            }

            override fun run(request: RunRequest) =
                flow {
                    requests.add(request)
                    beforeRun()
                    val table =
                        Table(
                            columns,
                            if (request.source ==
                                "hold"
                            ) {
                                emptyList()
                            } else {
                                listOf(listOf("4711", "SQL reason"))
                            },
                        )
                    emit(
                        ResultBatch
                            .newBuilder()
                            .setArrowIpc(ByteString.copyFrom(ArrowTables.encode(table)))
                            .setBatchRowCount(table.rows.size.toLong())
                            .build(),
                    )
                }
        }
    val runtime: RunRuntime

    init {
        every { ledger.closePreview(any(), any()) } answers {
            val id = firstArg<UUID>()
            if (records[id]?.callerSubject != secondArg<String>()) throw NoSuchRun(id)
            if (states[id] !in setOf(RunStatus.EVALUATED, RunStatus.CLOSED)) {
                throw IllegalRunTransition(id, states.getValue(id), RunStatus.CLOSED)
            }
            val preview = previews[id] ?: throw NoRunBatch(id)
            states[id] = RunStatus.CLOSED
            CloseResult(
                "closed",
                RunBatch(preview.batch, "preview", preview.rows.size),
                "/automation/runs/$id/protocol",
            )
        }
        every { ledger.checkRunExecution(any(), any()) } answers {
            val id = firstArg<UUID>()
            if (records[id]?.callerSubject != secondArg<String>()) throw NoSuchRun(id)
            if (states[id] == RunStatus.CLOSED) throw ProtocolSealed(id)
            if (id in released) throw AutomationException(ErrorCode.STAGING_RELEASED, "staging released")
        }
        every { ledger.readRunProgress(any(), any()) } answers {
            val id = firstArg<UUID>()
            val open = records[id]?.takeIf { it.callerSubject == secondArg<String>() } ?: throw NoSuchRun(id)
            val result = evaluations[id]
            RunProgress(
                id.toString(),
                states.getValue(id).wire,
                result?.verdict,
                result?.guards.orEmpty(),
                result?.mode,
                "/automation/runs/$id/protocol",
                recipe = open.recipe,
                batch = previews[id]?.let { RunBatch(it.batch, "preview", it.rows.size) },
                actions = previews[id]?.actions.orEmpty(),
                error =
                    failures[id]?.let {
                        buildJsonObject {
                            put(
                                "code",
                                (it as? AutomationException)?.code?.code ?: "internal",
                            )
                        }
                    },
            )
        }
        every { ledger.abandonExpiredRuns(any()) } answers {
            beforeSweep()
            emptyList()
        }
        every { ledger.findRun(any()) } answers {
            statusReads.incrementAndGet()
            val id = firstArg<UUID>()
            records[id]?.let { RunRecord(id, it, states.getValue(id), "/automation/runs/$id/protocol") }
        }
        every { ledger.claimRun(any()) } answers {
            val input = firstArg<RunOpen>()
            val existing = records.entries.firstOrNull { it.value.idempotencyKey == input.idempotencyKey }
            if (existing != null) {
                Claim.Running(existing.key, states.getValue(existing.key))
            } else {
                val id = UUID.randomUUID()
                records[id] = input
                states[id] = RunStatus.OPEN
                starts[id] = clock.instant()
                Claim.Claimed(id, startedAt = starts.getValue(id))
            }
        }
        every { ledger.startEvaluation(any()) } answers { states[firstArg()] = RunStatus.RUNNING }
        every { ledger.completeEvaluation(any(), any()) } answers {
            evaluations[firstArg()] = secondArg()
            states[firstArg()] = RunStatus.EVALUATED
        }
        every { ledger.completeEvaluation(any(), any(), any()) } answers {
            previews[firstArg()] = thirdArg()
            evaluations[firstArg()] = secondArg()
            states[firstArg()] = RunStatus.EVALUATED
        }
        every { ledger.readRunBatch(any(), any()) } answers {
            val id = firstArg<UUID>()
            records[id]?.takeIf { it.callerSubject == secondArg<String>() } ?: throw NoSuchRun(id)
            val p = previews[id] ?: throw NoRunBatch(id)
            org.tatrman.automation.apply
                .RunBatchPlan(p.batch, p.run, p.rows, p.mode)
        }
        every { ledger.putOutput(any(), any()) } answers {
            outputs.add(firstArg())
            Unit
        }
        every { ledger.failExecution(any(), any()) } answers {
            failures[firstArg()] = secondArg()
            states[firstArg()] = RunStatus.FAILED
        }
        runtime = createRuntime()
    }

    fun createRuntime() =
        RunRuntime(
            config,
            { bundle },
            ApprovalReader { _, _, _ -> true },
            ServiceTokens(emptyMap(), {
                error("caller execution needs no service token")
            }),
            ledger,
            parent,
            clock,
            query,
            store,
            queue,
            mockk<StagingRetention> { every { candidates(any()) } returns emptyList() },
        )

    suspend fun close() {
        runtime.shutdown()
        parent.cancel()
    }
}

private class RuntimeQueue : RunWorkQueue {
    private val tickets = ConcurrentHashMap<UUID, ConcurrentLinkedQueue<RunDelivery>>()
    private val sequence = AtomicLong()
    val enqueues = AtomicInteger()
    val claims = AtomicInteger()
    val cleanups = AtomicInteger()

    @Volatile var deliver = true

    override fun enqueue(
        run: UUID,
        owner: UUID,
    ) {
        enqueues.incrementAndGet()
        tickets
            .computeIfAbsent(
                owner,
            ) { ConcurrentLinkedQueue() }
            .add(RunDelivery(sequence.incrementAndGet(), run, owner))
    }

    override fun read(
        owner: UUID,
        visibilitySeconds: Int,
    ) = if (deliver) tickets[owner]?.poll() else null

    override fun claim(delivery: RunDelivery): Boolean {
        claims.incrementAndGet()
        return true
    }

    override fun archive(delivery: RunDelivery) = Unit

    override fun archiveFinished(limit: Int): Int {
        cleanups.incrementAndGet()
        return 0
    }
}
