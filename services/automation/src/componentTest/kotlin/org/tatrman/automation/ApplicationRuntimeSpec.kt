// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation

import com.google.protobuf.ByteString
import com.typesafe.config.ConfigFactory
import io.grpc.ServerBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.bundle.*
import org.tatrman.automation.identity.AuthorMap
import org.tatrman.automation.identity.TestRealm
import org.tatrman.automation.ledger.*
import org.tatrman.automation.runner.*
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ValuesNode
import org.tatrman.query.v1.*
import org.tatrman.worker.v1.ResultBatch
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Production module, verified JWT, git refresh, real pgmq/ledger and configured gRPC Query adapter. */
class ApplicationRuntimeSpec :
    StringSpec({
        val db = LedgerTestDb.ready()
        val realm = TestRealm()
        beforeEach { LedgerTestDb.reset() }
        afterSpec { realm.close() }

        fun fixture(beforeRead: suspend () -> Unit = {}) =
            ApplicationRuntimeFixture(tempdir(prefix = "application-runtime-").toPath(), realm, beforeRead)

        "production runtime preserves a governed preview through close and restart" {
            val f = fixture()
            try {
                lateinit var closed: String
                lateinit var lost: String
                f.api { _ ->
                    client.post("/automation/recipes/release_order/runs").status shouldBe HttpStatusCode.Unauthorized
                    val before = Instant.now().minus(Duration.ofDays(30)).minusSeconds(10)
                    closed = f.open(this)
                    val row = Ledger(Duration.ofMinutes(30)).findRun(UUID.fromString(closed))!!
                    val started = transaction { Runs.selectAll().single()[Runs.startedAt].toInstant() }
                    (started >= before && started <= before.plusSeconds(20)) shouldBe true
                    // A queue using wall time would refuse this -30d configured-clock run as expired.
                    row.open.bundle shouldBe f.bundle.id
                    row.open.callerSubject shouldBe "sub-maya"
                    row.open.dryRun shouldBe true
                    val base = "/automation/runs/$closed"
                    client.post("$base/parts/candidates") { bearerAuth(f.token) }.status shouldBe HttpStatusCode.OK
                    withTimeout(15_000) {
                        while (true) {
                            val evaluated = client.post("$base/evaluate") { bearerAuth(f.token) }
                            if (evaluated.status == HttpStatusCode.OK) break
                            evaluated.status shouldBe HttpStatusCode.Accepted
                            delay(20)
                        }
                    }
                    client.get("$base/routes/release") { bearerAuth(f.token) }.status shouldBe HttpStatusCode.OK
                    client.get("$base/outputs/verdict.release") { bearerAuth(f.token) }.status shouldBe
                        HttpStatusCode.OK
                    val batch = client.get("$base/batch") { bearerAuth(f.token) }
                    batch.status shouldBe HttpStatusCode.OK
                    batch.bodyAsText().contains("released") shouldBe true
                    client.post("$base/close") { bearerAuth(f.token) }.status shouldBe HttpStatusCode.OK
                    client.post("$base/close") { bearerAuth(f.token) }.status shouldBe HttpStatusCode.OK
                    client.get("$base/protocol") { bearerAuth(f.token) }.status shouldBe HttpStatusCode.OK
                    f.requests.size shouldBe 2
                    f.requests.forEach {
                        it.world shouldBe "fixture-erp"
                        it.context.modelVersion shouldBe f.bundle.modelFp
                        it.context.userId shouldBe "maya"
                        it.context.authRolesList shouldBe listOf("sales_manager")
                        it.allowedErReads.objectsList shouldBe listOf("er.entity.order")
                    }
                    transaction {
                        RunEvaluations.selectAll().count() shouldBe 1L
                        RunOutputs.selectAll().count() shouldBe 2L
                    }
                    // A second original context is intentionally lost at shutdown, never reconstructed on restart.
                    lost = f.open(this, "lost-context")
                    client.get(base) { bearerAuth(realm.person(subject = "another-subject")) }.status shouldBe
                        HttpStatusCode.Forbidden
                }
                f.api { _ ->
                    val reads = f.requests.size
                    for (suffix in listOf("", "/protocol", "/batch")) {
                        client.get("/automation/runs/$closed$suffix") { bearerAuth(f.token) }.status shouldBe
                            HttpStatusCode.OK
                    }
                    client.post("/automation/runs/$lost/evaluate") { bearerAuth(f.token) }.status shouldBe
                        HttpStatusCode.ServiceUnavailable
                    f.requests.size shouldBe reads
                    transaction { Runs.selectAll().count() shouldBe 2L }
                }
            } finally {
                f.close()
            }
        }

        "production shutdown joins an active gRPC read while durable readers survive HTTP drain" {
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val f =
                fixture {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            try {
                f.api { installed ->
                    val id = f.open(this)
                    val base = "/automation/runs/$id"
                    client.post("$base/evaluate") { bearerAuth(f.token) }.status shouldBe HttpStatusCode.Accepted
                    withTimeout(10_000) { entered.await() }
                    withContext(Dispatchers.IO) { installed.monitor.raise(ApplicationStopping, installed) }
                    withTimeout(10_000) { cancelled.await() }
                    client.get("/ready").status shouldBe HttpStatusCode.OK
                    client.get(base) { bearerAuth(f.token) }.status shouldBe HttpStatusCode.OK
                    client.get("$base/protocol") { bearerAuth(f.token) }.status shouldBe HttpStatusCode.OK
                    val rejected =
                        client.post("/automation/recipes/release_order/runs") {
                            bearerAuth(f.token)
                            contentType(ContentType.Application.Json)
                            setBody(f.request("after-stop"))
                        }
                    rejected.status shouldBe HttpStatusCode.ServiceUnavailable
                    transaction {
                        Runs.selectAll().count() shouldBe 1L
                        Batches.selectAll().count() shouldBe 0L
                    }
                }
            } finally {
                f.close()
            }
        }

        "Stopped cleanup fences execution even when the earlier Stopping notification was missed" {
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val f =
                fixture {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            try {
                f.api { installed ->
                    val id = f.open(this)
                    client.post("/automation/runs/$id/evaluate") { bearerAuth(f.token) }.status shouldBe
                        HttpStatusCode.Accepted
                    withTimeout(10_000) { entered.await() }
                    withContext(Dispatchers.IO) { installed.monitor.raise(ApplicationStopped, installed) }
                    withTimeout(10_000) { cancelled.await() }
                    val refused =
                        client.post("/automation/recipes/release_order/runs") {
                            bearerAuth(f.token)
                            contentType(ContentType.Application.Json)
                            setBody(f.request("after-stopped"))
                        }
                    refused.status shouldBe HttpStatusCode.ServiceUnavailable
                    client.get("/ready").status shouldBe HttpStatusCode.ServiceUnavailable
                }
            } finally {
                f.close()
            }
        }
    })

private class ApplicationRuntimeFixture(
    private val root: Path,
    private val realm: TestRealm,
    private val beforeRead: suspend () -> Unit,
) : AutoCloseable {
    val token = realm.person()
    val requests = CopyOnWriteArrayList<RunRequest>()
    private val source = ApprovalFixture.copyTo(root.resolve("source"))
    private val author = PersonIdent("Fixture Owner", "owner@example.org")
    private val commit: String
    val bundle: Bundle
    private val server =
        ServerBuilder
            .forPort(0)
            .addService(
                object : QueryServiceGrpcKt.QueryServiceCoroutineImplBase() {
                    override suspend fun compile(request: RunRequest): CompileResponse =
                        CompileResponse
                            .newBuilder()
                            .setPlan(PlanNode.newBuilder().setValues(ValuesNode.getDefaultInstance()))
                            .setErReads(ErReadScope.newBuilder().addObjects("er.entity.order"))
                            .build()

                    override fun run(request: RunRequest) =
                        flow {
                            requests += request
                            beforeRead()
                            val table =
                                if ("verdict_reason" in request.source) {
                                    Table(
                                        listOf(
                                            ManifestColumn("order_number", "text"),
                                            ManifestColumn("route", "text"),
                                            ManifestColumn("verdict_reason", "text"),
                                        ),
                                        listOf(listOf("4711", "release", "approved writer fixture")),
                                    )
                                } else {
                                    Table(listOf(ManifestColumn("order_number", "text")), listOf(listOf("4711")))
                                }
                            emit(
                                ResultBatch
                                    .newBuilder()
                                    .setContext(request.context)
                                    .setBatchRowCount(table.rows.size.toLong())
                                    .setArrowIpc(ByteString.copyFrom(ArrowTables.encode(table)))
                                    .build(),
                            )
                        }
                },
            ).build()
            .start()

    init {
        val world = source.resolve("model/worlds/demo.ttrm")
        world.writeText(world.readText().replace("run_as: identity.automation_decider", "run_as: caller"))
        commit =
            Git.init().setDirectory(source.toFile()).setInitialBranch("master").call().use { git ->
                git.add().addFilepattern(".").call()
                git
                    .commit()
                    .setMessage(
                        "released writer runtime fixture",
                    ).setAuthor(author)
                    .setCommitter(author)
                    .call()
                    .name
            }
        bundle =
            BundleLoader(
                AuthorMap(emptyMap()),
            ).load(Checkout(source, "master", commit, author.name, author.emailAddress), BundleKind.SERVED)
        val recipe = bundle.recipes.getValue("release_order").recipe
        approve("recipe", "release_order", recipe.fingerprints.text)
        approve("values", "release_order", recipe.fingerprints.values)
        approve("program", "release_on_placement", bundle.programFps.getValue("release_on_placement"))
        approve("deployment", "release_native_event", bundle.deploymentFps.getValue("release_native_event"))
    }

    private fun approve(
        kind: String,
        obj: String,
        fp: String,
    ) = transaction {
        Approvals.insert {
            it[id] = UUID.randomUUID()
            it[objectKind] = if (kind == "values") "recipe" else kind
            it[Approvals.obj] = obj
            when (kind) {
                "recipe" -> it[fpText] = fp
                "values" -> it[fpValues] = fp
                else -> it[Approvals.fp] = fp
            }
            it[Approvals.kind] = kind
            it[approver] = "fixture-owner"
            it[at] = Instant.now().atOffset(ZoneOffset.UTC)
            it[status] = "approved"
        }
    }

    fun request(key: String) = """{"deployment":"release_native_event","port":{"key":"4711"},"idempotencyKey":"$key"}"""

    suspend fun open(
        api: ApplicationTestBuilder,
        key: String = "preview",
    ): String {
        val response =
            api.client.post("/automation/recipes/release_order/runs") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request(key))
            }
        response.status shouldBe HttpStatusCode.Created
        return Json
            .parseToJsonElement(response.bodyAsText())
            .jsonObject
            .getValue("runId")
            .jsonPrimitive.content
    }

    suspend fun api(block: suspend ApplicationTestBuilder.(Application) -> Unit) {
        val db = LedgerTestDb.db
        val jdbc = db.getConnection().use { URI(it.metaData.url.removePrefix("jdbc:")) }
        val config =
            ConfigFactory
                .parseString(
                    """
            automation.db { host = "${jdbc.host}", port = "${jdbc.port}", database = "automation", user = "automation", password = "automation" }
            automation.door.queryUrl = "127.0.0.1:${server.port}"
            automation.door.metadataUrl = "127.0.0.1:1"
            automation.door.routes = "demo/erp_db=fixture-erp,demo/automation_db=fixture-automation"
            automation.auth.jwksUrl = "${realm.jwksUrl}"
            automation.auth.issuer = "${TestRealm.ISSUER}"
            automation.auth.audience = "${TestRealm.AUDIENCE}"
            automation.bundle.git.url = "${source.toUri()}"
            automation.bundle.git.checkoutDir = "${root.resolve("checkout")}"
            automation.staging.path = "${root.resolve("staging")}"
            automation.runner.workers = 1
            automation.clock.skew = -720h
            automation.budgets.syncDefault = 50ms
            """,
                ).withFallback(ConfigFactory.parseResources("application.conf"))
                .resolve()
        try {
            testApplication {
                lateinit var installed: Application
                application {
                    module(config)
                    installed = this
                }
                startApplication()
                withTimeout(15_000) {
                    while (true) {
                        val health = client.get("/automation/health").bodyAsText()
                        if (health.contains(commit)) break
                        delay(20)
                    }
                }
                block(installed)
            }
        } finally {
            Database.connect(db.getDataSource())
        }
    }

    override fun close() {
        server.shutdownNow()
        server.awaitTermination(10, TimeUnit.SECONDS)
    }
}
