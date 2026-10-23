// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.*
import org.tatrman.automation.identity.*
import org.tatrman.automation.ledger.*
import org.tatrman.automation.staging.LocalStagingStore
import org.tatrman.automation.staging.Staging
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.tatrman.ttr.metadata.model.RunAsRef as ModelRunAs

class RunSessionsSpec :
    StringSpec({
        "racing opens publish one pinned executor before either handle returns" {
            val fixture = RunSessionFixture()
            try {
                val results = coroutineScope { (1..12).map { async { fixture.open() } }.awaitAll() }
                results.count { it.httpStatus == 201 } shouldBe 1
                results.count { it.httpStatus == 202 } shouldBe 11
                results.map { it.response.runId }.distinct().size shouldBe 1
                fixture.created.get() shouldBe 1
                fixture.runs.read(
                    UUID.fromString(results.first().response.runId),
                    fixture.caller,
                    { it.id },
                ) { runner, id ->
                    runner.context.id shouldBe id
                }
            } finally {
                fixture.close()
            }
        }

        "a disconnect during claim cannot leave a handle without its executor" {
            val entered = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val fixture =
                RunSessionFixture(claimed = {
                    entered.countDown()
                    check(finish.await(10, TimeUnit.SECONDS))
                })
            try {
                coroutineScope {
                    val request = launch(Dispatchers.Default) { fixture.open() }
                    withContext(Dispatchers.IO) { check(entered.await(10, TimeUnit.SECONDS)) }
                    request.cancel()
                    finish.countDown()
                    request.join()
                }
                val id = fixture.states.keys.single()
                fixture.created.get() shouldBe 1
                fixture.runs.read(id, fixture.caller, { it.id }) { _, value -> value } shouldBe id
            } finally {
                finish.countDown()
                fixture.close()
            }
        }

        "refresh preserves the run's pinned recipe, model and caller roles" {
            val fixture = RunSessionFixture()
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                fixture.bundle = fixture.bundle.copy(modelFp = "new-model", recipes = emptyMap())
                val refreshed = fixture.caller.copy(roles = setOf("new-role"), token = "new-token")
                fixture.runs.read(id, refreshed, { it.modelFp }) { runner, model ->
                    model shouldBe "pinned-model"
                    runner.context.recipe.statements
                        .getValue("c.sql") shouldBe "candidates-original"
                    runner.context.identity.caller.roles shouldBe setOf("sales_manager")
                    runner.context.identity.caller.token shouldBe "original-token"
                    runner
                        .island("placed")
                        .single()
                        .table.rows.size
                } shouldBe 1
            } finally {
                fixture.close()
            }
        }

        "subject ownership precedes ledger access even for an identical display principal" {
            val fixture = RunSessionFixture()
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                fixture.statusReads.set(0)
                val refused =
                    shouldThrow<AutomationException> {
                        fixture.runs.read(
                            id,
                            fixture.caller.copy(sub = "other-sub"),
                            { it.id },
                        ) { runner, _ -> runner.all() }
                    }
                refused.code shouldBe ErrorCode.RUN_ABANDONED
                fixture.statusReads.get() shouldBe 0
                fixture.reads.get() shouldBe 0
                fixture.runs.read(id, fixture.caller, { it.id }) { runner, _ -> runner.island("placed") }
                fixture.reads.get() shouldBe 1
            } finally {
                fixture.close()
            }
        }

        "cancelling one read waiter preserves the shared read for another request" {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val reads = AtomicInteger()
            val fixture =
                RunSessionFixture(
                    door =
                        Door { r ->
                            reads.incrementAndGet()
                            entered.complete(Unit)
                            finish.await()
                            ReadResult(Table(r.columns, listOf(listOf("4711", "kept"))))
                        },
                )
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                coroutineScope {
                    val first =
                        launch {
                            fixture.runs.read(
                                id,
                                fixture.caller,
                                { "placed" },
                            ) { runner, name -> runner.island(name) }
                        }
                    entered.await()
                    first.cancelAndJoin()
                    val second =
                        async {
                            fixture.runs.read(
                                id,
                                fixture.caller,
                                { "placed" },
                            ) { runner, name -> runner.island(name) }
                        }
                    finish.complete(Unit)
                    second
                        .await()
                        .single()
                        .table.rows shouldBe listOf(listOf("4711", "kept"))
                }
                reads.get() shouldBe 1
            } finally {
                finish.complete(Unit)
                fixture.close()
            }
        }

        "a bad name preserves the run; executor failure frees the key without staging success" {
            val fixture =
                RunSessionFixture(door = Door { throw AutomationException(ErrorCode.DOOR_REFUSED, "refused") })
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                shouldThrow<IllegalArgumentException> {
                    fixture.runs.read(id, fixture.caller, { errorName() }) { _, _ -> Unit }
                }
                fixture.states.getValue(id) shouldBe RunStatus.OPEN
                shouldThrow<AutomationException> {
                    fixture.runs.read(id, fixture.caller, { "placed" }) { runner, name -> runner.island(name) }
                }.code shouldBe ErrorCode.DOOR_REFUSED
                fixture.states.getValue(id) shouldBe RunStatus.FAILED
                fixture.outputs.size shouldBe 0
                fixture.open().response.runId shouldBe
                    fixture.states.keys
                        .single { it != id }
                        .toString()
            } finally {
                fixture.close()
            }
        }

        "construction failure frees the claim; closed replay constructs nothing" {
            val fixture = RunSessionFixture()
            try {
                val valid = fixture.bundle
                val recipe = valid.recipes.getValue("release_order")
                fixture.bundle =
                    valid.copy(
                        recipes =
                            valid.recipes +
                                ("release_order" to recipe.copy(manifest = recipe.manifest.copy(waves = emptyList()))),
                    )
                shouldThrow<IllegalArgumentException> { fixture.open() }
                fixture.states.values.single() shouldBe RunStatus.FAILED
                fixture.bundle = valid
                val id = UUID.fromString(fixture.open().response.runId)
                fixture.states[id] = RunStatus.CLOSED
                fixture.open().let { result ->
                    result.httpStatus shouldBe 200
                    result.response.replayed shouldBe true
                    result.response.runId shouldBe id.toString()
                }
                fixture.created.get() shouldBe 2 // One failed construction, then one good executor.
            } finally {
                fixture.close()
            }
        }

        "a failed failure-journal write can retry without repeating the governed read" {
            val attempts = AtomicInteger()
            val reads = AtomicInteger()
            val fixture =
                RunSessionFixture(
                    door =
                        Door {
                            reads.incrementAndGet()
                            throw AutomationException(ErrorCode.DOOR_REFUSED, "refused")
                        },
                    beforeFailure = { if (attempts.incrementAndGet() == 1) error("ledger unavailable") },
                )
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                shouldThrow<IllegalStateException> {
                    fixture.runs.read(id, fixture.caller, { "placed" }) { runner, name -> runner.island(name) }
                }
                fixture.states.getValue(id) shouldBe RunStatus.OPEN
                shouldThrow<AutomationException> {
                    fixture.runs.read(id, fixture.caller, { "placed" }) { runner, name -> runner.island(name) }
                }.code shouldBe ErrorCode.DOOR_REFUSED
                fixture.states.getValue(id) shouldBe RunStatus.FAILED
                attempts.get() shouldBe 2
                reads.get() shouldBe 1
            } finally {
                fixture.close()
            }
        }

        "shutdown joins a concurrent release even after that release has started cancellation" {
            val entered = CompletableDeferred<Unit>()
            val cancelling = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val fixture =
                RunSessionFixture(
                    door =
                        Door {
                            entered.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                withContext(NonCancellable) {
                                    cancelling.complete(Unit)
                                    finish.await()
                                }
                            }
                        },
                )
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                coroutineScope {
                    val reading =
                        launch {
                            fixture.runs.read(id, fixture.caller, { "placed" }) { runner, name -> runner.island(name) }
                        }
                    entered.await()
                    val releasing = async { fixture.runs.release(id) }
                    cancelling.await()
                    val stopping = async(start = CoroutineStart.UNDISPATCHED) { fixture.runs.shutdown() }
                    stopping.isCompleted shouldBe false
                    finish.complete(Unit)
                    releasing.await()
                    stopping.await()
                    reading.join()
                }
            } finally {
                finish.complete(Unit)
                fixture.close()
            }
        }

        "released and terminal handles refuse without reconstructing an executor" {
            val fixture = RunSessionFixture()
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                fixture.states[id] = RunStatus.ABANDONED
                shouldThrow<AutomationException> {
                    fixture.runs.read(id, fixture.caller, { "placed" }) { runner, name -> runner.island(name) }
                }.code shouldBe ErrorCode.RUN_ABANDONED
                fixture.runs.release(id)
                fixture.states[id] = RunStatus.OPEN
                shouldThrow<AutomationException> {
                    fixture.runs.read(id, fixture.caller, { "placed" }) { runner, name -> runner.island(name) }
                }.code shouldBe ErrorCode.RUN_ABANDONED
                fixture.open().httpStatus shouldBe 202
                fixture.created.get() shouldBe 1
                fixture.reads.get() shouldBe 0
            } finally {
                fixture.close()
            }
        }

        "release joins cancelled reads before returning; cancelled work stages no rows" {
            val entered = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            val fixture =
                RunSessionFixture(
                    door =
                        Door {
                            entered.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                cleaned.complete(Unit)
                            }
                        },
                )
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                coroutineScope {
                    val reading = launch { fixture.runs.read(id, fixture.caller, { "placed" }) { r, n -> r.island(n) } }
                    entered.await()
                    fixture.runs.release(id)
                    cleaned.isCompleted shouldBe true
                    reading.join()
                }
                fixture.outputs.size shouldBe 0
                // Release is resource cleanup, not a ledger transition.
                fixture.states.getValue(id) shouldBe RunStatus.OPEN
            } finally {
                fixture.close()
            }
        }
    })

private fun errorName(): String = throw IllegalArgumentException("bad name")

/** Real Open policy + real PartRunner/Arrow staging, with only the ledger and governed door replaced. */
internal class RunSessionFixture(
    door: Door? = null,
    claimed: () -> Unit = {},
    beforeFailure: () -> Unit = {},
    detailLimit: Int = 200,
    stagingStore: org.tatrman.automation.staging.StagingStore? = null,
    syncDefault: Duration = Duration.ofSeconds(60),
    beforeRunning: () -> Unit = {},
    afterEvaluation: () -> Unit = {},
    beforeStatus: (UUID) -> Unit = {},
    workers: RunWorkers? = null,
    batchDefault: Duration = Duration.ofMinutes(30),
    clock: java.time.Clock = BundleFixture.clock,
    private val columns: List<ManifestColumn> =
        listOf(ManifestColumn("order_number", "text"), ManifestColumn("note", "text")),
) {
    val caller = Caller("subject-maya", "maya", setOf("sales_manager"), TokenKind.PERSON, "original-token")
    val states = ConcurrentHashMap<UUID, RunStatus>()
    val records = ConcurrentHashMap<UUID, RunOpen>()
    val evaluations = ConcurrentHashMap<UUID, EvaluationResult>()
    val failures = ConcurrentHashMap<UUID, Exception>()
    val created = AtomicInteger()
    val reads = AtomicInteger()
    val statusReads = AtomicInteger()
    val outputs = java.util.Collections.synchronizedList(mutableListOf<RunOutput>())
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val original = BundleFixture.load()
    private val islands =
        listOf(
            ManifestIsland(
                "placed",
                outputs =
                    listOf(
                        ManifestOutput("orders", "c.sql", columns = columns, keyed = true, storage = "erp_db"),
                    ),
            ),
            ManifestIsland(
                "shortfall",
                outputs = listOf(ManifestOutput("rows", "s.sql", columns = columns, keyed = false, storage = "erp_db")),
            ),
            ManifestIsland(
                "way",
                outputs =
                    listOf("release", "hold").map {
                        ManifestOutput(it, "$it.sql", columns = columns, keyed = true, storage = "erp_db")
                    },
            ),
        )
    private val recipe =
        original.recipes.getValue("release_order").let { r ->
            val metadata =
                r.recipe.copy(
                    roles =
                        ManifestRoles(
                            "placed",
                            listOf(ManifestContext("shortfall", "lookup", "sql")),
                            "way",
                            routes = listOf(ManifestRoute("release"), ManifestRoute("hold")),
                        ),
                    parts = emptyList(),
                )
            r.copy(
                recipe = metadata,
                manifest =
                    r.manifest.copy(
                        islands = islands,
                        waves = listOf(listOf("placed"), listOf("shortfall"), listOf("way")),
                        recipe = metadata,
                    ),
                statements =
                    mapOf(
                        "c.sql" to "candidates-original",
                        "s.sql" to "shortfall",
                        "release.sql" to "release",
                        "hold.sql" to "hold",
                    ),
            )
        }
    var bundle =
        original.copy(
            modelFp = "pinned-model",
            recipes = original.recipes + ("release_order" to recipe),
            deployments =
                original.deployments +
                    (
                        "release_native_event" to
                            original.deployments.getValue("release_native_event").copy(runAs = ModelRunAs.Caller)
                    ),
        )
    private val policy =
        Open(
            { bundle },
            ApprovalReader { _, _, _ -> true },
            ServiceTokens(emptyMap(), { error("unused") }),
            { input ->
                val existing =
                    records.entries.firstOrNull {
                        it.value.idempotencyKey == input.idempotencyKey &&
                            !states.getValue(it.key).freesKey
                    }
                if (existing != null) {
                    if (states.getValue(existing.key) == RunStatus.CLOSED) {
                        Claim.Replay(existing.key)
                    } else {
                        Claim.Running(existing.key, states.getValue(existing.key))
                    }
                } else {
                    val id = UUID.randomUUID()
                    states[id] = RunStatus.OPEN
                    records[id] = input
                    claimed()
                    Claim.Claimed(id, startedAt = clock.instant())
                }
            },
            { "/automation/runs/$it/protocol" },
            { false },
            syncDefault,
            batchDefault,
            clock,
            detailLimit = detailLimit,
        )
    val runs =
        RunSessions(
            policy,
            work,
            { context, scope ->
                created.incrementAndGet()
                PartRunner(
                    context,
                    door ?: Door { read ->
                        reads.incrementAndGet()
                        ReadResult(
                            Table(
                                read.columns,
                                if (read.island == "placed") {
                                    listOf(listOf("4711", "a"))
                                } else {
                                    listOf(listOf("4711", "a"), listOf("4711", null), listOf("4711", "c"))
                                },
                            ),
                        )
                    },
                    Staging(stagingStore ?: LocalStagingStore(Files.createTempDirectory("au-session")), outputs::add),
                    scope,
                    { id, result ->
                        evaluations[id] = result
                        states[id] = RunStatus.EVALUATED
                        afterEvaluation()
                    },
                    dispatch = workers?.let { it::evaluate },
                )
            },
            { id ->
                statusReads.incrementAndGet()
                beforeStatus(id)
                states[id]
            },
            { id, failure ->
                beforeFailure()
                failures[id] = failure
                states.computeIfPresent(id) { _, old ->
                    if (!old.terminal &&
                        old != RunStatus.WAITING
                    ) {
                        RunStatus.FAILED
                    } else {
                        old
                    }
                }
            },
            { id ->
                beforeRunning()
                states.compute(id) { _, old ->
                    check(old in setOf(RunStatus.OPEN, RunStatus.RUNNING, RunStatus.EVALUATED))
                    if (old == RunStatus.OPEN) RunStatus.RUNNING else old
                }
            },
            clock = clock,
        )

    suspend fun open(
        key: String = "engine:1",
        by: Caller = caller,
    ) = runs.open(
        "release_order",
        OpenRequest("release_native_event", buildJsonObject { put("key", "4711") }, idempotencyKey = key),
        by,
    )

    suspend fun close() {
        runs.shutdown()
        work.cancel()
    }
}
