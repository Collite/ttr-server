// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.spyk
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.engine.EngineHistory
import org.tatrman.automation.engine.HistoricTask
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.automation.identity.TokenKind
import org.tatrman.automation.ledger.*
import java.util.UUID

/**
 * S3.5 (D-230, ⚑AU-S35-1, D-233): an answer Open — a verified `engine.answeredBy` — decides the answered key's held rows
 * of the engine instance's waiting run at Open, and is closed at Open; it evaluates nothing. The original run is a wet
 * `release_confirmed` run over orders 4711 + 4712 (`approval: required`: one held group per key), opened by Maya under
 * an engine instance, run as `identity.automation_decider` so a decision after its close can re-pin its context (D-186).
 */
class AnswerRunSpec :
    StringSpec({
        LedgerTestDb.ready()
        beforeEach { LedgerTestDb.reset() }

        val engine = Caller("sub-engine", "process-engine", emptySet(), TokenKind.SERVICE, "engine-token")
        val k4711 = buildJsonObject { put("order_number", "4711") }
        val k4712 = buildJsonObject { put("order_number", "4712") }

        // The engine's task history: task → (process instance, assignee).
        val tasks =
            mapOf(
                "task-1" to ("pi-1" to "alice"),
                "task-2" to ("pi-1" to "bob"),
                "task-9" to ("pi-1" to "alice"),
                "task-3" to ("pi-1" to "alice"),
                "task-5" to ("pi-2" to "alice"),
            )
        val history =
            EngineHistory { taskId, _ ->
                tasks[taskId]
                    ?.let { (instance, assignee) ->
                        listOf(HistoricTask(taskId, instance, assignee, "2026-11-17T10:00:00Z", "completed"))
                    }.orEmpty()
            }
        // The door decodes what the IdP returns: an unsigned JWT stands for the decider's client-credentials token
        // (`client_id` = the identity's principal).
        val tokens = ServiceTokens(mapOf("AUTOMATION_DECIDER" to "svc-hartland-automation:s"), { DECIDER_TOKEN })

        fun fixture(
            keys: List<String> = listOf("4711", "4712"),
            candidates: () -> List<String> = { keys },
            ledgerFor: (Ledger) -> Ledger = { it },
        ): Pair<DurableRuntimeFixture, org.tatrman.automation.writer.RowWritesQueryFake> {
            val (f, fake, _) =
                wetRuntimeFixture(
                    tempdir(prefix = "automation-answer-").toPath(),
                    initial = "placed",
                    keys = keys,
                    wire = "release_confirmed",
                    engineHistory = history,
                    tokens = tokens,
                    runAsCaller = false,
                    candidates = candidates,
                    ledgerFor = ledgerFor,
                )
            for (order in keys - "4711") wetSql("INSERT INTO public.sales_order VALUES ('$order', 'placed', NULL)")
            return f to fake
        }

        /** An operator of the world's `def role ops` (hartland-ops): the dev held routes' decider. */
        fun ops(name: String) =
            Caller("sub-$name", name, emptySet(), TokenKind.PERSON, "t", groups = setOf("hartland-ops"))

        fun answer(
            key: JsonElement,
            task: String,
            by: String,
            instance: String = "pi-1",
            decision: String? = null,
            idempotencyKey: String? = null,
        ) = OpenRequest(
            "release_native_event",
            buildJsonObject { put("key", key) },
            idempotencyKey = idempotencyKey,
            engine = EngineRef(instance, "orders-0417", "dep-7", taskId = task, answeredBy = by, decision = decision),
        )

        /** The engine instance's original run: wet, both keys held per key, `waiting`. */
        suspend fun waiting(
            f: DurableRuntimeFixture,
            runtime: RunRuntime,
            keys: List<String> = listOf("4711", "4712"),
        ): UUID {
            val id =
                UUID.fromString(
                    runtime.sessions
                        .open(
                            "release_order",
                            f.request.copy(
                                dryRun = null,
                                port =
                                    buildJsonObject {
                                        put("keys", JsonArray(keys.map(::JsonPrimitive)))
                                    },
                                engine = EngineRef("pi-1", "orders-0417", "dep-7"),
                            ),
                            f.caller,
                        ).response.runId,
                )
            runtime.sessions.evaluate(id, f.caller)
            runtime.closer.close(id, f.caller).status shouldBe "waiting"
            return id
        }

        fun state(order: String) =
            wetQuery("SELECT state FROM public.sales_order WHERE order_number = '$order'").single().single()

        fun runs(): Long = transaction { Runs.selectAll().count() }

        fun refusal(block: suspend () -> Unit): AutomationException =
            shouldThrow<AutomationException> { kotlinx.coroutines.runBlocking { block() } }

        "(a)(b)(c) an answer decides its key's held rows at Open and closes; other keys stay held; a retry replays" {
            val (f, fake) = fixture()
            val runtime = f.start()
            try {
                val original = waiting(f, runtime)
                val first = runtime.sessions.open("release_order", answer(k4711, "task-1", "alice"), engine)
                first.httpStatus shouldBe 201
                first.context shouldBe null
                first.response.status shouldBe "closed"
                val answerId = UUID.fromString(first.response.runId)
                val outcome = first.response.answer!!
                outcome.original shouldBe original.toString()
                outcome.key shouldBe k4711
                outcome.decision shouldBe "commit"
                outcome.by shouldBe "alice"
                outcome.applied shouldBe 1
                outcome.skipped shouldBe 0
                outcome.unknown shouldBe 0
                outcome.replayed shouldBe false
                // (a) 4711's held transition applied; 4712's is still held; the original still waits.
                state("4711") shouldBe "released"
                state("4712") shouldBe "placed"
                f.ledger.findRun(original)!!.status shouldBe RunStatus.WAITING
                val batch = f.ledger.readRunBatch(original, f.caller.sub)
                outcome.batch shouldBe batch.batch
                val held4711 = batch.rows.single { it.group == k4711 }.seq
                val held4712 = batch.rows.single { it.group == k4712 }.seq
                batch.statuses.single { it.seq == held4711 }.let {
                    it.status shouldBe "applied"
                    it.decided!!["decision"] shouldBe JsonPrimitive("commit")
                    it.decided!!["by"] shouldBe JsonPrimitive("alice")
                    it.decided!!["answer"] shouldBe JsonPrimitive(answerId.toString())
                }
                batch.statuses.single { it.seq == held4712 }.let {
                    it.status shouldBe "staged"
                    it.decided shouldBe null
                }
                // The answer run: kind `answer`, closed, the verified engine columns, no batch of its own.
                f.ledger.findRun(answerId)!!.let {
                    it.status shouldBe RunStatus.CLOSED
                    it.open.kind shouldBe RUN_KIND_ANSWER
                    it.open.engine shouldBe EngineColumns("pi-1", "orders-0417", "dep-7", "task-1", "alice", true)
                }
                transaction { Runs.selectAll().where { Runs.id eq answerId }.single()[Runs.batchId] } shouldBe null
                val writes =
                    runtime.protocol
                        .read(answerId, engine)
                        .sections
                        .getValue("writes")
                        .single()
                        .payload.jsonObject
                writes["answer"] shouldBe JsonPrimitive(true)
                writes["original"] shouldBe JsonPrimitive(original.toString())
                writes["key"] shouldBe k4711
                writes["decision"] shouldBe JsonPrimitive("commit")
                writes["by"] shouldBe JsonPrimitive("alice")
                writes["applied"] shouldBe JsonPrimitive(1)
                // D-159: who answered — asserted and verified against the engine's task history.
                runtime.protocol
                    .read(answerId, engine)
                    .sections
                    .getValue("people")
                    .single()
                    .payload.jsonObject
                    .let {
                        it["asserted"] shouldBe JsonPrimitive("alice")
                        it["verified"] shouldBe JsonPrimitive(true)
                        it["taskId"] shouldBe JsonPrimitive("task-1")
                    }
                // The original's `held-commit` entry names the answer.
                runtime.protocol
                    .read(original, f.caller)
                    .sections
                    .getValue("writes")
                    .map { it.payload.jsonObject }
                    .single { it["event"] == JsonPrimitive("held-commit") }
                    .let {
                        it["by"] shouldBe JsonPrimitive("alice")
                        it["answer"] shouldBe JsonPrimitive(answerId.toString())
                        it["last"] shouldBe JsonPrimitive(false)
                    }

                // (c) The same answer again (the same task) replays the answer run; nothing is sent twice.
                val sent = fake.writes.size
                val again = runtime.sessions.open("release_order", answer(k4711, "task-1", "alice"), engine)
                again.httpStatus shouldBe 200
                again.response.replayed shouldBe true
                again.response.runId shouldBe first.response.runId
                again.response.status shouldBe "closed"
                again.response.answer shouldBe outcome
                fake.writes.size shouldBe sent
                // Contract 2: another task answering a key already decided — its own closed answer run, the recorded
                // decision replayed, nothing applied again.
                val late = runtime.sessions.open("release_order", answer(k4711, "task-9", "alice"), engine)
                late.httpStatus shouldBe 200
                late.response.runId shouldNotBe first.response.runId
                late.response.answer!!.replayed shouldBe true
                late.response.answer!!.applied shouldBe 1
                fake.writes.size shouldBe sent
                f.ledger.findRun(original)!!.status shouldBe RunStatus.WAITING

                // (b) The answer for 4712: the original's last held key — it leaves `waiting` (decideHeld's `last`).
                val second = runtime.sessions.open("release_order", answer(k4712, "task-2", "bob"), engine)
                second.httpStatus shouldBe 201
                second.response.answer!!.applied shouldBe 1
                state("4712") shouldBe "released"
                f.ledger.findRun(original)!!.status shouldBe RunStatus.CLOSED
                transaction {
                    Batches.selectAll().where { Batches.run eq original }.single()[Batches.state] shouldBe "committed"
                }
            } finally {
                runtime.shutdown()
                f.close()
                dropWetRuntime()
            }
        }

        "(d)(e) an unheld key, or an instance with no waiting run, is 409 answer-unmatched; nothing applies" {
            val (f, fake) = fixture()
            val runtime = f.start()
            try {
                val original = waiting(f, runtime)
                val sent = fake.writes.size
                val k9999 = buildJsonObject { put("order_number", "9999") }
                val unheld =
                    refusal { runtime.sessions.open("release_order", answer(k9999, "task-1", "alice"), engine) }
                unheld.code shouldBe ErrorCode.ANSWER_UNMATCHED
                unheld.detail!!.jsonObject.let {
                    it["reason"] shouldBe JsonPrimitive("no-held-key")
                    it["run"] shouldBe JsonPrimitive(original.toString())
                    it["key"] shouldBe k9999
                    it["held"] shouldBe JsonArray(listOf(k4711, k4712))
                }
                // review-354 F5 (✅R354-C): `port.key` is verbatim — a number for the text key held as "4711" matches
                // nothing, and the refusal lists the run's held keys so the type mismatch shows.
                val numeric = buildJsonObject { put("order_number", 4711) }
                val typed =
                    refusal { runtime.sessions.open("release_order", answer(numeric, "task-1", "alice"), engine) }
                typed.code shouldBe ErrorCode.ANSWER_UNMATCHED
                typed.detail!!.jsonObject["held"] shouldBe JsonArray(listOf(k4711, k4712))
                val other =
                    refusal {
                        runtime.sessions.open(
                            "release_order",
                            answer(k4711, "task-5", "alice", instance = "pi-2"),
                            engine,
                        )
                    }
                other.code shouldBe ErrorCode.ANSWER_UNMATCHED
                other.detail!!.jsonObject.let {
                    it["reason"] shouldBe JsonPrimitive("no-waiting-run")
                    it["instanceId"] shouldBe JsonPrimitive("pi-2")
                }
                fake.writes.size shouldBe sent
                state("4711") shouldBe "placed"
                f.ledger.findRun(original)!!.status shouldBe RunStatus.WAITING
                // Found 2 (planner 17:08): each refused answer leaves its own answer run `failed`, with the error.
                transaction {
                    Runs
                        .selectAll()
                        .where { Runs.kind eq RUN_KIND_ANSWER }
                        .map { it[Runs.status] to it[Runs.error]?.jsonObject?.get("code") }
                        .toSet()
                } shouldBe setOf("failed" to JsonPrimitive("answer-unmatched"))
            } finally {
                runtime.shutdown()
                f.close()
                dropWetRuntime()
            }
        }

        "(f) a moved deployment fingerprint is 409 superseded and the key stays held; once served again, it decides" {
            val (f, fake) = fixture()
            val runtime = f.start()
            try {
                val original = waiting(f, runtime)
                val sent = fake.writes.size
                f.refreshed =
                    f.served.copy(deploymentFps = f.served.deploymentFps + ("release_native_event" to "sha256:moved"))
                val stale = refusal { runtime.sessions.open("release_order", answer(k4711, "task-1", "alice"), engine) }
                stale.code shouldBe ErrorCode.SUPERSEDED
                stale.detail!!.jsonObject["differs"] shouldBe JsonArray(listOf(JsonPrimitive("deployment")))
                fake.writes.size shouldBe sent
                state("4711") shouldBe "placed"
                val batch = f.ledger.readRunBatch(original, f.caller.sub)
                batch.statuses.single { it.seq == batch.rows.single { r -> r.group == k4711 }.seq }.decided shouldBe
                    null
                // The refused answer's run failed and freed its key: the engine's retry of the same task decides.
                f.refreshed = null
                val retried = runtime.sessions.open("release_order", answer(k4711, "task-1", "alice"), engine)
                retried.httpStatus shouldBe 201
                state("4711") shouldBe "released"
            } finally {
                runtime.shutdown()
                f.close()
                dropWetRuntime()
            }
        }

        "(g)(h) an answer run refuses evaluate, part reads and close; status shows the decision on both runs" {
            val (f, _) = fixture()
            val runtime = f.start()
            try {
                val original = waiting(f, runtime)
                runtime.status.read(original, f.caller).let {
                    it.kind shouldBe RUN_KIND_RECIPE
                    it.batch!!.held shouldBe 2
                }
                val opened = runtime.sessions.open("release_order", answer(k4711, "task-1", "alice"), engine)
                val answerId = UUID.fromString(opened.response.runId)
                // (g) Nothing to evaluate, read or close: 409 close-not-ready · answer-run.
                for (attempt in listOf<suspend () -> Unit>(
                    { runtime.sessions.evaluate(answerId, engine) },
                    { runtime.sessions.read(answerId, engine, { Unit }) { _, _ -> Unit } },
                    { runtime.closer.close(answerId, engine) },
                )) {
                    val refused = refusal { attempt() }
                    refused.code shouldBe ErrorCode.CLOSE_NOT_READY
                    refused.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("answer-run")
                }
                // (h) The answer run's status, and the original's per-action decision and held count.
                runtime.status.read(answerId, engine).let {
                    it.kind shouldBe RUN_KIND_ANSWER
                    it.status shouldBe "closed"
                    it.answer shouldBe opened.response.answer
                    it.batch shouldBe null
                }
                runtime.status.read(original, f.caller).let { progress ->
                    progress.status shouldBe "waiting"
                    progress.batch!!.held shouldBe 1
                    progress.actions.single { it.key == k4711 }.decided!!.let {
                        it["decision"] shouldBe JsonPrimitive("commit")
                        it["by"] shouldBe JsonPrimitive("alice")
                        it["answer"] shouldBe JsonPrimitive(answerId.toString())
                    }
                    progress.actions.single { it.key == k4712 }.decided shouldBe null
                }
            } finally {
                runtime.shutdown()
                f.close()
                dropWetRuntime()
            }
        }

        "(i)(j) reject is reserved (400 engine), a bare key is 400 port, an unverified answer 403 — none claimed" {
            val (f, fake) = fixture()
            val runtime = f.start()
            try {
                waiting(f, runtime)
                val before = runs()
                val sent = fake.writes.size
                // (i) ⚑AU-S35-2: v1 is approve-only.
                val reject =
                    refusal {
                        runtime.sessions.open(
                            "release_order",
                            answer(k4711, "task-1", "alice", decision = "reject"),
                            engine,
                        )
                    }
                reject.code shouldBe ErrorCode.ENGINE
                reject.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("decision-unsupported")
                // Exporters S1.5.7 Found 7: the key is always the object, a single-column key included.
                val bare =
                    refusal {
                        runtime.sessions.open("release_order", answer(JsonPrimitive("4711"), "task-1", "alice"), engine)
                    }
                bare.code shouldBe ErrorCode.PORT
                bare.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("key-not-object")
                // (j) An answer from anyone but the engine client is refused before any lookup (✅R280-B, as today).
                val unverified =
                    refusal { runtime.sessions.open("release_order", answer(k4711, "task-1", "alice"), f.caller) }
                unverified.code shouldBe ErrorCode.IDENTITY
                unverified.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("unverified-answer")
                runs() shouldBe before
                fake.writes.size shouldBe sent
                state("4711") shouldBe "placed"
                // `approve` spelled out is the v1 default.
                runtime.sessions
                    .open("release_order", answer(k4711, "task-1", "alice", decision = "approve"), engine)
                    .httpStatus shouldBe 201
            } finally {
                runtime.shutdown()
                f.close()
                dropWetRuntime()
            }
        }

        // ---- review-354 F1 (✅R354-A): the original run is looked up by the key it held, whatever its status ----

        "(m) once its last key closed the original, an answer for a decided key replays (200), nothing applied twice" {
            val (f, fake) = fixture()
            val runtime = f.start()
            try {
                val original = waiting(f, runtime)
                runtime.sessions.open("release_order", answer(k4711, "task-1", "alice"), engine).httpStatus shouldBe 201
                runtime.sessions.open("release_order", answer(k4712, "task-2", "bob"), engine).httpStatus shouldBe 201
                f.ledger.findRun(original)!!.status shouldBe RunStatus.CLOSED
                val sent = fake.writes.size
                val late =
                    runtime.sessions.open(
                        "release_order",
                        answer(k4711, "task-9", "alice", idempotencyKey = "late-1"),
                        engine,
                    )
                late.httpStatus shouldBe 200
                late.response.answer!!.let {
                    it.replayed shouldBe true
                    it.decision shouldBe "commit"
                    it.original shouldBe original.toString()
                }
                fake.writes.size shouldBe sent
            } finally {
                runtime.shutdown()
                f.close()
                dropWetRuntime()
            }
        }

        "(n) the dev route decides the last key first; the engine's answer for it replays (200), not 409" {
            val (f, fake) = fixture()
            val runtime = f.start()
            try {
                val original = waiting(f, runtime)
                runtime.sessions.open("release_order", answer(k4711, "task-1", "alice"), engine).httpStatus shouldBe 201
                runtime.held.commitHeld(original, k4712, ops("olga")).runStatus shouldBe "closed"
                val sent = fake.writes.size
                val answered = runtime.sessions.open("release_order", answer(k4712, "task-2", "bob"), engine)
                answered.httpStatus shouldBe 200
                answered.response.answer!!.replayed shouldBe true
                fake.writes.size shouldBe sent
            } finally {
                runtime.shutdown()
                f.close()
                dropWetRuntime()
            }
        }

        "(o) two waiting runs in one instance: an answer for the older run's key decides the older run" {
            var candidates = listOf("4713")
            val (f, _) = fixture(keys = listOf("4711", "4712", "4713"), candidates = { candidates })
            val runtime = f.start()
            try {
                val older = waiting(f, runtime, keys = listOf("4713"))
                f.base.now = f.base.now.plusSeconds(60)
                candidates = listOf("4711", "4712")
                val newer = waiting(f, runtime)
                val k4713 = buildJsonObject { put("order_number", "4713") }
                val answered = runtime.sessions.open("release_order", answer(k4713, "task-3", "alice"), engine)
                answered.httpStatus shouldBe 201
                answered.response.answer!!.original shouldBe older.toString()
                state("4713") shouldBe "released"
                f.ledger.findRun(older)!!.status shouldBe RunStatus.CLOSED
                f.ledger.findRun(newer)!!.status shouldBe RunStatus.WAITING
                f.ledger
                    .readRunBatch(newer, f.caller.sub)
                    .statuses
                    .mapNotNull { it.decided } shouldBe emptyList()
            } finally {
                runtime.shutdown()
                f.close()
                dropWetRuntime()
            }
        }

        // ---- review-354 F2: the completion is inside the failure path; a reaped answer that decided still closes ----

        "(p) a failed completion leaves the answer run failed, not open; the engine's retry replays" {
            var failOnce = true
            val (f, _) =
                fixture(ledgerFor = { ledger ->
                    spyk(ledger).also { spy ->
                        every { spy.finishAnswer(any(), any()) } answers {
                            if (failOnce) {
                                failOnce = false
                                throw IllegalStateException("injected: the completion's transaction failed")
                            }
                            callOriginal()
                        }
                    }
                })
            val runtime = f.start()
            try {
                waiting(f, runtime)
                shouldThrow<IllegalStateException> {
                    runtime.sessions.open("release_order", answer(k4711, "task-1", "alice"), engine)
                }
                state("4711") shouldBe "released"
                transaction {
                    Runs.selectAll().where { Runs.kind eq RUN_KIND_ANSWER }.map { it[Runs.status] }
                } shouldBe listOf("failed")
                val retried = runtime.sessions.open("release_order", answer(k4711, "task-1", "alice"), engine)
                retried.httpStatus shouldBe 200
                retried.response.answer!!.replayed shouldBe true
            } finally {
                runtime.shutdown()
                f.close()
                dropWetRuntime()
            }
        }

        "(q) the sweep abandons the answer run mid-decision; the recorded decision still closes it (201)" {
            var sweep = true
            lateinit var fx: DurableRuntimeFixture
            val (f, _) =
                fixture(ledgerFor = { ledger ->
                    spyk(ledger).also { spy ->
                        every { spy.decideHeld(any(), any(), any(), any(), any()) } answers {
                            if (sweep) {
                                sweep = false
                                fx.base.now = fx.base.now.plusSeconds(3_600)
                                spy.abandonExpiredRuns().size shouldBe 1
                            }
                            callOriginal()
                        }
                    }
                })
            fx = f
            val runtime = f.start()
            try {
                waiting(f, runtime)
                val opened = runtime.sessions.open("release_order", answer(k4711, "task-1", "alice"), engine)
                opened.httpStatus shouldBe 201
                val answerId = UUID.fromString(opened.response.runId)
                f.ledger.findRun(answerId)!!.status shouldBe RunStatus.CLOSED
                f.ledger.answerOf(answerId)!!["applied"] shouldBe JsonPrimitive(1)
            } finally {
                runtime.shutdown()
                f.close()
                dropWetRuntime()
            }
        }

        "review-354 F4: a replayed answer names the recorded decider, not this answer's person" {
            val (f, _) = fixture()
            val runtime = f.start()
            try {
                val original = waiting(f, runtime)
                runtime.held.rejectHeld(original, k4711, ops("carol"))
                val replayed = runtime.sessions.open("release_order", answer(k4711, "task-1", "alice"), engine)
                replayed.httpStatus shouldBe 200
                replayed.response.answer!!.let {
                    it.by shouldBe "carol"
                    it.decision shouldBe "reject"
                    it.replayed shouldBe true
                    it.applied shouldBe 0
                    it.skipped shouldBe 1
                }
            } finally {
                runtime.shutdown()
                f.close()
                dropWetRuntime()
            }
        }
    })

/** `{"alg":"none"}.{"sub":"svc-sub","client_id":"svc-hartland-automation"}.` */
private const val DECIDER_TOKEN =
    "eyJhbGciOiJub25lIn0." +
        "eyJzdWIiOiJzdmMtc3ViIiwiY2xpZW50X2lkIjoic3ZjLWhhcnRsYW5kLWF1dG9tYXRpb24ifQ."
