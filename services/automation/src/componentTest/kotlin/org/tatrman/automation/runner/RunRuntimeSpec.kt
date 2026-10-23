// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import com.google.protobuf.ByteString
import com.typesafe.config.ConfigFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.*
import org.tatrman.automation.config.AutomationConfig
import org.tatrman.automation.identity.*
import org.tatrman.automation.ledger.*
import org.tatrman.automation.staging.LocalStagingStore
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ValuesNode
import org.tatrman.query.v1.CompileResponse
import org.tatrman.query.v1.ErReadScope
import org.tatrman.query.v1.RunRequest
import org.tatrman.worker.v1.ResultBatch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.tatrman.ttr.metadata.model.RunAsRef as ModelRunAs

/** Real ledger + default pgmq adapter + workers + executor; only Query is a canned governed dependency. */
class RunRuntimeComponentSpec :
    StringSpec({
        LedgerTestDb.ready()
        beforeEach { LedgerTestDb.reset() }

        "the assembled runtime uses the configured clock through durable dispatch, staging and decision commit" {
            val f = DurableRuntimeFixture()
            val runtime = f.start()
            try {
                val opened = runtime.sessions.open("release_order", f.request, f.caller)
                val id = UUID.fromString(opened.response.runId)
                opened.context!!.startedAt shouldBe f.clock.instant()
                runtime.sessions
                    .evaluate(id, f.caller)!!
                    .verdict.routes
                    .getValue("release")
                    .rowCount shouldBe 1L
                f.ledger.findRun(id)!!.status shouldBe RunStatus.EVALUATED
                f.ledger
                    .findRun(id)!!
                    .open.asOf shouldBe f.clock.instant()
                transaction { RunOutputs.selectAll().where { RunOutputs.run eq id }.count() } shouldBe 2L
                transaction {
                    RunProtocol.selectAll().where { RunProtocol.run eq id }.count()
                } shouldBe 4L // Opening + two output entries + atomic decision.
                eventually {
                    transaction { RunEvaluations.selectAll().single()[RunEvaluations.archivedAt] != null }
                }
                transaction {
                    val ticket = RunEvaluations.selectAll().single()
                    ticket[RunEvaluations.owner] shouldBe runtime.owner
                    (ticket[RunEvaluations.claimedAt] != null) shouldBe true
                }
                f.reads.get() shouldBe 2
                runtime.sessions.open("release_order", f.request, f.caller).httpStatus shouldBe 202
                runtime.sessions
                    .evaluate(id, f.caller)!!
                    .verdict.none shouldBe 0L
                f.reads.get() shouldBe 2
                val document = runtime.protocol.read(id, f.caller)
                val progress = runtime.status.read(id, f.caller)
                progress.status shouldBe "evaluated"
                progress.verdict!!
                    .routes
                    .getValue("release")
                    .rowCount shouldBe 1L
                progress.bundle shouldBe
                    f.ledger
                        .findRun(id)!!
                        .open.bundle
                document.sections.getValue("islands").size shouldBe 2
                document.sections.getValue("islands").forEach {
                    it.payload.jsonObject["engine"] shouldBe JsonPrimitive("query")
                    it.payload.jsonObject["rows"] shouldBe JsonPrimitive(1)
                    (
                        it.payload.jsonObject["bytes"]!!
                            .jsonPrimitive.long > 0
                    ) shouldBe true
                }
                runtime.shutdown()
                val restarted = f.start()
                try {
                    restarted.protocol.read(id, f.caller) shouldBe document
                    restarted.status.read(id, f.caller) shouldBe progress
                    f.reads.get() shouldBe 2
                } finally {
                    restarted.shutdown()
                }
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        "the assembled deadline cancels actual queued execution and persists the typed public refusal" {
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val f =
                DurableRuntimeFixture(sync = "20ms", beforeRead = {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                })
            val runtime = f.start()
            try {
                val id =
                    UUID.fromString(
                        runtime.sessions
                            .open("release_order", f.request, f.caller)
                            .response.runId,
                    )
                runtime.sessions.evaluate(id, f.caller) shouldBe null
                withTimeout(5000) { entered.await() }
                f.base.now = f.base.now.plusSeconds(10)
                withTimeout(5000) { cancelled.await() }
                eventually { f.ledger.findRun(id)!!.status == RunStatus.FAILED }
                runtime.status.read(id, f.caller).status shouldBe "failed"
                runtime.status.read(id, f.caller).error shouldBe buildJsonObject { put("code", "budget") }
                shouldThrow<AutomationException> { runtime.sessions.progress(id, f.caller) }.code shouldBe
                    ErrorCode.BUDGET
                transaction {
                    Runs.selectAll().single()[Runs.error] shouldBe buildJsonObject { put("code", "budget") }
                    RunOutputs.selectAll().count() shouldBe 0L
                    RunProtocol.selectAll().count() shouldBe 1L
                }
                f.reads.get() shouldBe 1
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        "startup maintenance abandons and archives a lost owner's trigger without executing its context" {
            val f = DurableRuntimeFixture()
            val lost = UUID.randomUUID()
            val id = f.ledger.claimRun(RunOpen("lost", budgetBatch = f.config.budgets.batchDefault)).runId
            RunQueue(clock = f.clock, defaultBudget = f.config.budgets.batchDefault).enqueue(id, lost)
            f.base.now = f.base.now.plusSeconds(131) // Strict budget + two minute recovery grace.
            val runtime = f.start()
            try {
                eventually { f.ledger.findRun(id)!!.status == RunStatus.ABANDONED }
                eventually {
                    transaction { RunEvaluations.selectAll().single()[RunEvaluations.archivedAt] != null }
                }
                transaction {
                    val ticket = RunEvaluations.selectAll().single()
                    ticket[RunEvaluations.owner] shouldBe lost
                    ticket[RunEvaluations.claimedAt] shouldBe null
                }
                shouldThrow<AutomationException> { runtime.sessions.evaluate(id, f.caller) }.code shouldBe
                    ErrorCode.RUN_ABANDONED
                f.reads.get() shouldBe 0
            } finally {
                runtime.shutdown()
                f.close()
            }
        }
    })

private suspend fun eventually(predicate: () -> Boolean) {
    withTimeout(5000) { while (!withContext(Dispatchers.IO) { predicate() }) delay(10) }
}

private class RuntimeClock(
    @Volatile var now: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}

private class DurableRuntimeFixture(
    sync: String = "60s",
    private val beforeRead: suspend () -> Unit = {},
) {
    // Far from the system clock: a queue/session accidentally using wall time would immediately refuse.
    val base = RuntimeClock(Instant.parse("2000-01-01T12:00:00Z"))
    val config =
        AutomationConfig.load(
            ConfigFactory
                .parseString(
                    """
                    automation.db.host = "unused"
                    automation.door.queryUrl = "unused"
                    automation.door.velesUrl = "unused"
                    automation.door.routes = "demo/erp_db=us-erp"
                    automation.auth.jwksUrl = "http://unused/certs"
                    automation.auth.issuer = "unused"
                    automation.clock.skew = 90s
                    automation.budgets.syncDefault = $sync
                    automation.budgets.batchDefault = 10s
                    automation.runner.workers = 1
                    """.trimIndent(),
                ).withFallback(ConfigFactory.parseResources("application.conf"))
                .resolve(),
            emptyMap(),
        )
    val clock = Clock.offset(base, config.clockSkew)
    val ledger = Ledger(config.budgets.batchDefault, clock)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val caller = Caller("sub-maya", "maya", setOf("sales_manager"), TokenKind.PERSON, "not-stored")
    val request = OpenRequest("release_native_event", buildJsonObject { put("key", "4711") })
    val reads = AtomicInteger()
    private val columns = listOf(ManifestColumn("order_number", "text"), ManifestColumn("verdict_reason", "text"))
    private val source =
        BundleLoader(AuthorMap(emptyMap()), clock).load(
            Checkout(
                Path.of("src/test/resources/bundles/hartland"),
                "master",
                "0123456789abcdef0123456789abcdef01234567",
                "po",
                "po@example.org",
            ),
            BundleKind.SERVED,
        )
    private val recipe =
        source.recipes.getValue("release_order").let { r ->
            val metadata =
                r.recipe.copy(
                    parts = emptyList(),
                    roles =
                        ManifestRoles(
                            "placed",
                            emptyList(),
                            "way",
                            routes = listOf(ManifestRoute("release")),
                        ),
                )
            r.copy(
                recipe = metadata,
                manifest =
                    r.manifest.copy(
                        recipe = metadata,
                        waves = listOf(listOf("placed"), listOf("way")),
                        islands =
                            listOf(
                                ManifestIsland(
                                    "placed",
                                    outputs =
                                        listOf(
                                            ManifestOutput(
                                                "orders",
                                                "c.sql",
                                                columns = columns,
                                                keyed = true,
                                                storage = "erp_db",
                                            ),
                                        ),
                                ),
                                ManifestIsland(
                                    "way",
                                    outputs =
                                        listOf(
                                            ManifestOutput(
                                                "release",
                                                "r.sql",
                                                columns = columns,
                                                keyed = true,
                                                storage = "erp_db",
                                            ),
                                        ),
                                ),
                            ),
                    ),
                statements = mapOf("c.sql" to "candidates", "r.sql" to "route"),
            )
        }
    private val bundle =
        source.copy(
            recipes = source.recipes + ("release_order" to recipe),
            deployments =
                source.deployments +
                    (
                        "release_native_event" to
                            source.deployments.getValue("release_native_event").copy(runAs = ModelRunAs.Caller)
                    ),
        )
    private val query =
        object : QueryRun {
            override suspend fun compile(request: RunRequest) =
                CompileResponse
                    .newBuilder()
                    .setPlan(PlanNode.newBuilder().setValues(ValuesNode.getDefaultInstance()))
                    .setErReads(ErReadScope.getDefaultInstance())
                    .build()

            override fun run(request: RunRequest) =
                flow {
                    request.world shouldBe "us-erp"
                    reads.incrementAndGet()
                    beforeRead()
                    val table = Table(columns, listOf(listOf("4711", "SQL reason")))
                    emit(
                        ResultBatch
                            .newBuilder()
                            .setBatchRowCount(
                                1,
                            ).setArrowIpc(ByteString.copyFrom(ArrowTables.encode(table)))
                            .build(),
                    )
                }
        }

    fun start() =
        RunRuntime(
            config,
            { bundle },
            ApprovalReader {
                _,
                _,
                _,
                ->
                true
            },
            ServiceTokens(emptyMap(), { error("unused") }),
            ledger,
            scope,
            clock,
            query,
            LocalStagingStore(Files.createTempDirectory("au-runtime-pg")),
        )

    fun close() {
        scope.cancel()
    }
}
