// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.MutableClock
import org.tatrman.automation.apply.*
import org.tatrman.automation.bundle.*
import org.tatrman.automation.identity.AuthorMap
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.RunAsRef
import org.tatrman.automation.identity.RunIdentity
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.automation.identity.TokenKind
import org.tatrman.automation.ledger.*
import org.tatrman.automation.runner.Door
import org.tatrman.automation.runner.DoorRead
import org.tatrman.automation.runner.DoorWrite
import org.tatrman.automation.runner.EvaluationResult
import org.tatrman.automation.runner.RunCloser
import org.tatrman.automation.runner.RunContext
import org.tatrman.automation.runner.QueryDoor
import org.tatrman.automation.runner.RouteSummary
import org.tatrman.automation.runner.VerdictSummary
import org.tatrman.automation.runner.WriteResult
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

/**
 * S2.2 T1/T2 — the apply through the door, on a real Postgres. Plans are built as `plan()` builds them; the
 * ledger journals, [Apply] sends each (storage, entity group) through [QueryDoor] to [RowWritesQueryFake]
 * (the pinned translator's unparser, one JDBC transaction per request), and the close commits and seals.
 */
class ApplyComponentSpec :
    StringSpec({
        LedgerTestDb.ready()
        val ds = LedgerTestDb.db.getDataSource()
        val clock = MutableClock(Instant.parse("2026-11-11T10:00:00Z"))
        val ledger = Ledger(Duration.ofMinutes(30), clock)
        val planner = ApplyPlanner(clock)
        val subject = "subject-wet"
        val principal = "svc-hartland-automation"
        lateinit var bundle: Bundle
        val schema =
            ManifestVerdictSchema(
                "release_order_verdict",
                detail = "release_order_verdict_detail",
                current = "release_order_verdict_current",
            )
        // Mirrors the fixture world's `writable:` (bundles/hartland/model/worlds/demo.ttrm, after ttr-demo#170).
        val writable =
            mapOf(
                "erp_db" to
                    listOf("order.state", "order.held_for", "return_request_line.state", "return_request_line.way"),
                "automation_db" to
                    listOf(
                        "obligation",
                        "state_history",
                        "*_verdict",
                        "*_verdict_detail",
                        "*_verdict_current",
                        "approvals",
                    ),
            )
        val source =
            PlanSource(
                schema,
                mapOf("er.entity.order" to "erp_db", "er.entity.return_request_line" to "erp_db"),
                "automation_db",
                obligationStorage = "automation_db",
                historyStorage = "automation_db",
                writable = writable,
            )
        val routes = listOf("hold_sales", "hold_ar", "release_confirmed", "release")
        val fake =
            RowWritesQueryFake(
                ds,
                mapOf("w-erp" to "public", "w-auto" to "wet_fixture"),
                bundle = { bundle },
            )
        val writeRoutes = mapOf("demo/erp_db" to "w-erp", "demo/automation_db" to "w-auto")

        fun door(routes: Map<String, String> = writeRoutes) =
            QueryDoor(
                fake,
                emptyMap(),
                ServiceTokens(emptyMap(), {
                    error("unused")
                }),
                routes,
            )

        val identity =
            RunIdentity(
                Caller(subject, principal, emptySet(), TokenKind.SERVICE, "not-stored"),
                RunAsRef.Caller,
                principal,
                null,
            )

        fun sql(text: String) =
            ds.connection.use { c ->
                c.autoCommit = true
                c.createStatement().use { it.execute(text) }
            }

        fun rows(query: String): List<List<String?>> =
            ds.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery(query).use { rs ->
                        buildList {
                            while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) })
                        }
                    }
                }
            }

        beforeSpec {
            val root = ApprovalFixture.copyTo(tempdir().toPath().resolve("wet"))
            completeWriterCatalogs(root)
            bundle =
                BundleLoader(AuthorMap(emptyMap())).load(
                    Checkout(root, "master", "0123456789abcdef", "po", "po@example.org"),
                    BundleKind.SERVED,
                )
            Ddl(ds, { listOf("wet_fixture") }).refresh(
                bundle.copy(
                    recipes =
                        bundle.recipes.filterKeys {
                            it ==
                                "release_order"
                        },
                ),
            )
            sql(
                "DROP TABLE IF EXISTS public.sales_order, public.return_request_line; " +
                    "CREATE TABLE public.sales_order " +
                    "(order_number text PRIMARY KEY, state text NOT NULL, held_for text); " +
                    "CREATE TABLE public.return_request_line (line_id integer PRIMARY KEY, state text NOT NULL, way text)",
            )
        }
        afterSpec { sql("DROP TABLE public.sales_order, public.return_request_line; DROP SCHEMA wet_fixture CASCADE") }
        beforeEach {
            LedgerTestDb.reset()
            clock.now = Instant.parse("2026-11-11T10:00:00Z")
            fake.writes.clear()
            fake.refuse = { null }
            fake.failRow = { null }
            fake.loseCommit = { false }
            sql(
                "TRUNCATE wet_fixture.release_order_verdict, wet_fixture.release_order_verdict_detail, " +
                    "wet_fixture.release_order_verdict_current, wet_fixture.obligation, wet_fixture.state_history; " +
                    "TRUNCATE public.sales_order, public.return_request_line; " +
                    "INSERT INTO public.sales_order VALUES ('4711', 'placed', NULL), ('4712', 'placed', NULL), " +
                    "('4713', 'held', 'AR'), ('4714', 'cancelled', NULL); " +
                    "INSERT INTO public.return_request_line VALUES (1, 'received', NULL)",
            )
        }

        fun key(order: String) = buildJsonObject { put("order_number", order) }

        /** The planner's verdict row: `run`, `as_of` and `written_at` are the run's own (review-293's fixture note). */
        fun verdict(
            run: UUID,
            order: String,
            route: String,
            fp: JsonElement = JsonPrimitive("sha256:values"),
            reason: String = "because",
        ): ApplyRow {
            val now =
                clock
                    .instant()
                    .atOffset(ZoneOffset.UTC)
                    .toLocalDateTime()
                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            return ApplyRow(
                1,
                "er.entity.release_order_verdict",
                "insert",
                key(order),
                row =
                    buildJsonObject {
                        put("run", run.toString())
                        put("as_of", now)
                        put("order_number", order)
                        put("route", route)
                        put("verdict_reason", reason)
                        put("parameters_fp", fp)
                        put("run_as", principal)
                        put("written_at", now)
                    },
            )
        }

        fun detail(
            run: UUID,
            order: String,
            route: String,
            no: Int,
        ) = ApplyRow(
            1,
            "er.entity.release_order_verdict_detail",
            "insert",
            key(order),
            row =
                buildJsonObject {
                    put("run", run.toString())
                    put("order_number", order)
                    put("route", route)
                    put("context", "shortfall")
                    put("row_no", no)
                    put("truncated", false)
                },
        )

        fun hold(
            order: String,
            department: String = "Sales",
        ) = listOf(
            ApplyRow(1, "er.entity.order", "update", key(order), set = buildJsonObject { put("held_for", department) }),
            ApplyRow(
                1,
                "er.entity.order",
                "transition",
                key(order),
                attribute = "state",
                from = listOf("placed"),
                to = JsonPrimitive("held"),
                history = true,
            ),
        )

        fun release(order: String) =
            ApplyRow(
                1,
                "er.entity.order",
                "transition",
                key(order),
                attribute = "state",
                from = listOf("placed", "held"),
                to = JsonPrimitive("released"),
                history = true,
            )

        /** Numbers rows in order; [actions] name 0-based row indices per action (an action's set + transition = one CAS). */
        class Planned(
            val rows: List<ApplyRow>,
            val actions: List<List<Int>>,
            val keys: List<String>,
        )

        fun planned(
            rows: List<ApplyRow>,
            actions: List<List<Int>> = emptyList(),
            keys: List<String> =
                rows
                    .mapNotNull {
                        (
                            it.key?.get(
                                "order_number",
                            ) as? JsonPrimitive
                        )?.content
                    }.distinct(),
        ) = Planned(rows.mapIndexed { i, r -> r.copy(seq = i + 1) }, actions, keys)

        data class Committed(
            val id: UUID,
            val batch: RunBatchPlan,
            val close: org.tatrman.automation.runner.CloseResult,
        )

        /** Claim a wet run and journal plan() at evaluate; answers the run, ready for its close. */
        fun prepare(
            build: (UUID) -> Planned,
            deployment: String? = null,
            dryRun: Boolean = false,
            program: String? = null,
            port: JsonElement = JsonObject(emptyMap()),
        ): UUID {
            val id =
                ledger
                    .claimRun(
                        RunOpen(
                            "release_order",
                            program = program,
                            port = port,
                            deployment = deployment,
                            runAs = principal,
                            dryRun = dryRun,
                            callerSubject = subject,
                            openingProtocol = buildJsonObject { put("event", "opened") },
                        ),
                    ).runId
            val p = build(id)
            val preview =
                planner.plan(
                    PreviewPlan(
                        UUID.randomUUID().toString(),
                        id.toString(),
                        p.rows,
                        p.actions.map { seqs ->
                            DryRunAction(
                                p.rows[seqs.first()].key!!,
                                "hold_sales",
                                "hold",
                                "transition",
                                JsonObject(emptyMap()),
                                seqs.map {
                                    it +
                                        1
                                },
                                dryRun = false,
                            )
                        },
                        source = source,
                        candidateKeys = p.keys.map(::key),
                    ),
                )
            ledger.completeEvaluation(
                id,
                EvaluationResult(VerdictSummary(mapOf("hold_sales" to RouteSummary(1, "arrow:x")), 0), emptyList()),
                preview,
            )
            return id
        }

        /** One close attempt's apply, from what the ledger holds now; [after] runs once each outcome list is journaled. */
        suspend fun applyOnce(
            id: UUID,
            door: Door = door(),
            afterBegin: () -> Unit = {},
            after: (List<RowOutcome>) -> Unit = {},
        ) {
            val state = ledger.applyState(id, subject)
            Apply(door, clock).apply(
                ApplyInput(
                    id,
                    "demo",
                    identity,
                    "model-fp",
                    state.deadline,
                    state.plan.rows,
                    state.actions,
                    source,
                    bundle.entities,
                    routes,
                    state.recorded,
                    settled = state.settled,
                ),
                object : ApplyJournal {
                    override fun begin(
                        seqs: List<Int>,
                        request: String,
                    ) {
                        ledger.beginApply(state.batch, seqs, request)
                        afterBegin()
                    }

                    override fun release(seqs: List<Int>) = ledger.releaseApply(state.batch, seqs)

                    override fun record(outcomes: List<RowOutcome>) {
                        ledger.recordApply(state.batch, outcomes)
                        after(outcomes)
                    }
                },
            )
        }

        /** Claim a wet run, journal plan() at evaluate, apply through the door, then commit and seal. */
        suspend fun commit(
            build: (UUID) -> Planned,
            door: QueryDoor = door(),
        ): Committed {
            val id = prepare(build)
            applyOnce(id, door)
            val close = ledger.closeCommitted(id, subject)
            return Committed(id, ledger.readRunBatch(id, subject), close)
        }

        fun RunBatchPlan.status(seq: Int) = statuses.single { it.seq == seq }

        fun RunBatchPlan.of(
            entity: String,
            op: String? = null,
        ) = rows.filter { it.entity == entity && (op == null || it.op == op) }.map { status(it.seq) }

        fun writesEntry(id: UUID): JsonObject =
            ledger
                .readProtocol(id, subject)
                .sections
                .getValue("writes")
                .last()
                .payload
                .jsonObject

        // ---- T1: inserts · failures ----

        "a committed H-1 run leaves one committed batch with verdict, detail and current rows; current = this run" {
            val c =
                commit({ run ->
                    planned(
                        listOf(
                            verdict(run, "4711", "hold_sales"),
                            detail(run, "4711", "hold_sales", 1),
                            detail(run, "4711", "hold_sales", 2),
                        ) +
                            hold("4711") + listOf(verdict(run, "4712", "release"), release("4712")),
                        actions = listOf(listOf(3, 4)),
                    )
                })
            c.close.status shouldBe "closed"
            c.close.batch.state shouldBe "committed"
            c.batch.mode shouldBe "auto"
            c.batch.statuses
                .map { it.status }
                .toSet() shouldBe setOf("applied")
            rows(
                "SELECT run, order_number, route FROM wet_fixture.release_order_verdict ORDER BY order_number",
            ) shouldBe
                listOf(listOf("${c.id}", "4711", "hold_sales"), listOf("${c.id}", "4712", "release"))
            rows("SELECT count(*) FROM wet_fixture.release_order_verdict_detail WHERE run = '${c.id}'") shouldBe
                listOf(listOf("2"))
            rows(
                "SELECT run, order_number, route FROM wet_fixture.release_order_verdict_current ORDER BY order_number",
            ) shouldBe
                listOf(listOf("${c.id}", "4711", "hold_sales"), listOf("${c.id}", "4712", "release"))
            rows(
                "SELECT order_number, state, held_for FROM public.sales_order WHERE order_number IN ('4711','4712') ORDER BY 1",
            ) shouldBe
                listOf(listOf("4711", "held", "Sales"), listOf("4712", "released", null))
            transactionCount(
                "SELECT count(*) FROM automation.batches WHERE run = '${c.id}' AND state = 'committed'",
            ) shouldBe
                1L
            // One request per (storage, entity group): the verdict group, the order group, then the history request.
            fake.writes.map { it.world } shouldBe listOf("w-auto", "w-erp", "w-auto")
            val writes = writesEntry(c.id)
            writes["state"] shouldBe JsonPrimitive("committed")
            writes["failed"] shouldBe JsonPrimitive(0)
            writes["staleState"] shouldBe JsonPrimitive(0)
            writes["entities"]!!.jsonObject["er.entity.order"] shouldBe
                buildJsonObject {
                    put("applied", 3)
                    put("failed", 0)
                    put("skipped", 0)
                    put("unknown", 0)
                    putJsonObject("history") {
                        put("applied", 2)
                        put("failed", 0)
                        put("unknown", 0)
                    }
                }
            writes["history"] shouldBe
                buildJsonObject {
                    put("applied", 2)
                    put("failed", 0)
                    put("unknown", 0)
                }
        }

        "a second run adds new verdict rows and current moves to it" {
            val first =
                commit(
                    { run -> planned(listOf(verdict(run, "4711", "hold_sales")) + hold("4711"), listOf(listOf(1, 2))) },
                )
            clock.now = clock.now.plusSeconds(60)
            val second =
                commit(
                    { run ->
                        planned(
                            listOf(verdict(run, "4711", "hold_ar")) + hold("4711", "AR"),
                            listOf(listOf(1, 2)),
                        )
                    },
                )
            rows(
                "SELECT run, route FROM wet_fixture.release_order_verdict ORDER BY written_at, run",
            ).map { it[0] }.toSet() shouldBe
                setOf("${first.id}", "${second.id}")
            rows("SELECT run, route FROM wet_fixture.release_order_verdict_current") shouldBe
                listOf(listOf("${second.id}", "hold_ar"))
            // held → held: `set:` applies, no second history row (D-85).
            rows("SELECT held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("AR"))
            rows("SELECT count(*) FROM wet_fixture.state_history") shouldBe listOf(listOf("1"))
        }

        "a row failing inside a group rolls the request back: it is failed, the others skipped, the batch committed" {
            val c =
                commit({ run ->
                    planned(
                        // The same (run, key, route) twice: the primary key refuses the second insert in Postgres.
                        listOf(verdict(run, "4711", "hold_sales"), verdict(run, "4711", "hold_sales")) + hold("4711"),
                        listOf(listOf(2, 3)),
                    )
                })
            c.close.batch.state shouldBe "committed"
            val verdicts = c.batch.of("er.entity.release_order_verdict")
            verdicts.map { it.status } shouldBe listOf("skipped", "failed")
            verdicts[1].error shouldBe "worker_execution_failed"
            c.batch
                .of("er.entity.release_order_verdict_current")
                .map { it.status }
                .toSet() shouldBe setOf("skipped")
            rows("SELECT count(*) FROM wet_fixture.release_order_verdict") shouldBe listOf(listOf("0"))
            rows("SELECT count(*) FROM wet_fixture.release_order_verdict_current") shouldBe listOf(listOf("0"))
            // Another group's request still applies.
            c.batch.of("er.entity.order").map { it.status } shouldBe listOf("applied", "applied")
            (writesEntry(c.id)["failed"] as JsonPrimitive).int shouldBe 1
        }

        "a row on a target that is not writable fails not-writable with no door call" {
            val c =
                commit({ run ->
                    planned(
                        listOf(
                            ApplyRow(
                                1,
                                "er.entity.order",
                                "update",
                                key("4711"),
                                set =
                                    buildJsonObject {
                                        put("customer", 7)
                                    },
                            ),
                        ),
                        keys = emptyList(),
                    )
                })
            c.batch.statuses
                .single()
                .status shouldBe "failed"
            c.batch.statuses
                .single()
                .error shouldBe "not-writable"
            fake.writes.size shouldBe 0
        }

        "a storage with no write route fails its rows storage-unmapped" {
            val c =
                commit(
                    { run -> planned(listOf(verdict(run, "4711", "hold_sales")) + hold("4711"), listOf(listOf(1, 2))) },
                    door(mapOf("demo/erp_db" to "w-erp")),
                )
            c.batch
                .of("er.entity.release_order_verdict")
                .single()
                .error shouldBe "storage-unmapped"
            c.batch
                .of("er.entity.release_order_verdict_current")
                .map { it.error }
                .toSet() shouldBe
                setOf("storage-unmapped")
            c.batch.of("er.entity.order").map { it.status } shouldBe listOf("applied", "applied")
            // The history request has no route either: the transition stands and names it.
            c.batch
                .of("er.entity.order", "transition")
                .single()
                .error shouldBe "history-failed: storage-unmapped"
            fake.writes.map { it.world } shouldBe listOf("w-erp")
        }

        // ---- T2: transitions · obligations ----

        "placed → held with set is one CAS answered 1, then one history row; batch_rows keeps the prior" {
            val c = commit({ _ -> planned(hold("4711"), listOf(listOf(0, 1)), keys = emptyList()) })
            fake.writes.size shouldBe 2
            fake.writes[0].world shouldBe "w-erp"
            rows("SELECT state, held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("held", "Sales"))
            rows(
                "SELECT entity, key, attribute, from_state, to_state, run, at, by FROM wet_fixture.state_history",
            ) shouldBe
                listOf(
                    listOf(
                        "er.entity.order",
                        """{"order_number":"4711"}""",
                        "state",
                        "placed",
                        "held",
                        "${c.id}",
                        "2026-11-11 10:00:00",
                        principal,
                    ),
                )
            val transition = c.batch.of("er.entity.order", "transition").single()
            transition.status shouldBe "applied"
            transition.prior shouldBe
                buildJsonObject {
                    put("state", "placed")
                    put("held_for", JsonNull)
                }
        }

        "S3.1 T6 (D-168): a typed perform hold on one order writes the transition + history, as its own perform run" {
            val name =
                bundle.deployments.keys.first {
                    bundle.programs[bundle.deployments.getValue(it).program]?.recipe ==
                        "release_order"
                }
            val served =
                bundle.copy(
                    deployments =
                        bundle.deployments +
                            (
                                name to
                                    bundle.deployments
                                        .getValue(
                                            name,
                                        ).copy(runAs = org.tatrman.ttr.metadata.model.RunAsRef.Caller)
                            ),
                )
            val caller = Caller(subject, principal, emptySet(), TokenKind.SERVICE, "not-stored")
            val perform =
                TypedPerform(
                    {
                        served
                    },
                    Apply(
                        door(),
                        clock,
                    ),
                    ledger,
                    ServiceTokens(emptyMap(), { error("unused") }),
                    Duration.ofSeconds(60),
                    clock,
                )
            val request =
                TypedPerformRequest(
                    key = buildJsonObject { put("order", "4711") },
                    params = buildJsonObject { put("department", "Sales") },
                    idempotencyKey = "timeout:4711",
                )
            val answer = perform.perform(name, "hold", request, caller)
            answer.outcome shouldBe "applied"
            answer.rows.map { it.op to it.status } shouldBe listOf("update" to "applied", "transition" to "applied")
            fake.writes.size shouldBe 2 // the guarded write, then its history row
            rows("SELECT state, held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("held", "Sales"))
            rows("SELECT entity, key, from_state, to_state, run, by FROM wet_fixture.state_history") shouldBe
                listOf(
                    listOf("er.entity.order", """{"order_number":"4711"}""", "placed", "held", answer.runId, principal),
                )
            val run = ledger.findRun(UUID.fromString(answer.runId))!!
            run.status shouldBe RunStatus.CLOSED
            run.open.kind shouldBe RUN_KIND_PERFORM
            val writes = ledger.readProtocol(UUID.fromString(answer.runId), subject).sections.getValue("writes")
            writes.last().payload.jsonObject["outcome"] shouldBe JsonPrimitive("applied")
            // The same key replays the recorded outcome and sends nothing again.
            val again = perform.perform(name, "hold", request, caller)
            again.replayed shouldBe true
            again.runId shouldBe answer.runId
            fake.writes.size shouldBe 2
            // An action needing approval refuses without `by`; an engine action has no typed perform; unknown columns 400.
            val recipe = served.recipes.getValue("release_order")
            val gated =
                served.copy(
                    recipes =
                        served.recipes +
                            (
                                "release_order" to
                                    recipe.copy(
                                        coverage =
                                            recipe.coverage.copy(
                                                actions =
                                                    recipe.coverage.actions +
                                                        (
                                                            "hold" to
                                                                recipe.coverage.actions
                                                                    .getValue(
                                                                        "hold",
                                                                    ).copy(approval = "required")
                                                        ),
                                            ),
                                    )
                            ),
                )
            val gatedPerform =
                TypedPerform(
                    {
                        gated
                    },
                    Apply(
                        door(),
                        clock,
                    ),
                    ledger,
                    ServiceTokens(emptyMap(), { error("unused") }),
                    Duration.ofSeconds(60),
                    clock,
                )
            shouldThrow<AutomationException> {
                gatedPerform.perform(name, "hold", request.copy(idempotencyKey = null), caller)
            }.code shouldBe ErrorCode.APPROVAL_REQUIRED
            shouldThrow<AutomationException> {
                perform.perform(
                    name,
                    "hold",
                    request.copy(
                        params =
                            buildJsonObject {
                                put("colour", "red")
                            },
                        idempotencyKey = null,
                    ),
                    caller,
                )
            }.code shouldBe ErrorCode.BAD_REQUEST
            shouldThrow<AutomationException> { perform.perform(name, "nope", request, caller) }.code shouldBe
                ErrorCode.ACTION
            fake.writes.size shouldBe 2
        }

        "review-273 F2/F3/F6: key vs params, paused, and a replay answers the key the run performed on" {
            val name =
                bundle.deployments.keys.first {
                    bundle.programs[bundle.deployments.getValue(it).program]?.recipe ==
                        "release_order"
                }
            val served =
                bundle.copy(
                    deployments =
                        bundle.deployments +
                            (
                                name to
                                    bundle.deployments
                                        .getValue(
                                            name,
                                        ).copy(runAs = org.tatrman.ttr.metadata.model.RunAsRef.Caller)
                            ),
                )
            val caller = Caller(subject, principal, emptySet(), TokenKind.SERVICE, "not-stored")
            var paused = false
            val perform =
                TypedPerform(
                    { served },
                    Apply(door(), clock),
                    ledger,
                    ServiceTokens(emptyMap(), { error("unused") }),
                    Duration.ofSeconds(60),
                    clock,
                    { paused },
                )

            fun runs() = transaction { Runs.selectAll().count() }
            // F2: a column in both key and params is refused before any run or door call — never overridden.
            shouldThrow<AutomationException> {
                perform.perform(
                    name,
                    "hold",
                    TypedPerformRequest(
                        key = buildJsonObject { put("order", "4711") },
                        params =
                            buildJsonObject {
                                put("order", "4712")
                                put("department", "Sales")
                            },
                    ),
                    caller,
                )
            }.code shouldBe ErrorCode.BAD_REQUEST
            runs() shouldBe 0L
            fake.writes.size shouldBe 0
            rows("SELECT state FROM public.sales_order WHERE order_number = '4712'") shouldBe
                listOf(listOf("placed"))
            // F3 (✅R273-A): paused refuses 503 before the claim; unpaused, the same request applies.
            val request =
                TypedPerformRequest(
                    key = buildJsonObject { put("order", "4711") },
                    params = buildJsonObject { put("department", "Sales") },
                    idempotencyKey = "timeout:4711",
                )
            paused = true
            shouldThrow<AutomationException> { perform.perform(name, "hold", request, caller) }.code shouldBe
                ErrorCode.PAUSED
            runs() shouldBe 0L
            fake.writes.size shouldBe 0
            paused = false
            perform.perform(name, "hold", request, caller).outcome shouldBe "applied"
            // F6: the same idempotency key with another key replays the run, and answers the key it performed on.
            val again =
                perform.perform(
                    name,
                    "hold",
                    request.copy(key = buildJsonObject { put("order", "4712") }),
                    caller,
                )
            again.replayed shouldBe true
            again.key shouldBe buildJsonObject { put("order", "4711") }
            fake.writes.size shouldBe 2
        }

        "a from mismatch answers 0: stale-state, the verdict stands, no history row" {
            val c =
                commit(
                    { run -> planned(listOf(verdict(run, "4714", "hold_sales")) + hold("4714"), listOf(listOf(1, 2))) },
                )
            c.batch.of("er.entity.order").map { it.status to it.error } shouldBe
                listOf("failed" to "stale-state", "failed" to "stale-state")
            c.batch
                .of("er.entity.release_order_verdict")
                .single()
                .status shouldBe "applied"
            rows("SELECT state, held_for FROM public.sales_order WHERE order_number = '4714'") shouldBe
                listOf(listOf("cancelled", null))
            rows("SELECT count(*) FROM wet_fixture.state_history") shouldBe listOf(listOf("0"))
            (writesEntry(c.id)["staleState"] as JsonPrimitive).int shouldBe 2
        }

        "held → held applies set without history; an undeclared transition fails bad-transition with no door call" {
            val c = commit({ _ -> planned(hold("4713", "Sales"), listOf(listOf(0, 1)), keys = emptyList()) })
            c.batch.statuses.map { it.status } shouldBe listOf("applied", "applied")
            rows("SELECT state, held_for FROM public.sales_order WHERE order_number = '4713'") shouldBe
                listOf(listOf("held", "Sales"))
            rows("SELECT count(*) FROM wet_fixture.state_history") shouldBe listOf(listOf("0"))
            fake.writes.size shouldBe 1
            fake.writes.clear()
            val bad =
                commit({ _ ->
                    planned(
                        listOf(
                            ApplyRow(
                                1,
                                "er.entity.order",
                                "transition",
                                key("4712"),
                                attribute = "state",
                                to = JsonPrimitive("placed"),
                                history = true,
                            ),
                        ),
                        keys = emptyList(),
                    )
                })
            bad.batch.statuses
                .single()
                .error shouldBe "bad-transition"
            fake.writes.size shouldBe 0
        }

        "expect twice for the same open (line, kind): the second answers 0 and is skipped (if_absent)" {
            fun expect(run: UUID) =
                ApplyRow(
                    1,
                    "er.entity.obligation",
                    "insert",
                    buildJsonObject { put("line_id", 1) },
                    row =
                        buildJsonObject {
                            put("id", UUID.nameUUIDFromBytes("$run/inbound".toByteArray()).toString())
                            put("recipe", "decide_return")
                            put("run", run.toString())
                            put("entity", "er.entity.return_request_line")
                            put("key", """{"line_id":1}""")
                            put("kind", "inbound")
                            put("due", "2026-11-25")
                            put("state", "open")
                            put("created_at", "2026-11-11T10:00:00")
                        },
                )
            commit({ run -> planned(listOf(expect(run)), keys = emptyList()) })
                .batch.statuses
                .single()
                .status shouldBe
                "applied"
            val second = commit({ run -> planned(listOf(expect(run)), keys = emptyList()) })
            second.batch.statuses
                .single()
                .let { (it.status to it.error) shouldBe ("skipped" to "if_absent") }
            rows("SELECT kind, state, due FROM wet_fixture.obligation") shouldBe
                listOf(listOf("inbound", "open", "2026-11-25"))
        }

        "an obligation transition to fulfilled writes history and no signal" {
            sql(
                "INSERT INTO wet_fixture.obligation " +
                    "(id, recipe, run, entity, key, kind, due, state, created_at) VALUES " +
                    "('ob-1', 'decide_return', 'r0', 'er.entity.return_request_line', '{\"line_id\":1}', 'inbound', '2026-11-25', 'open', '2026-11-01')",
            )
            val c =
                commit({ _ ->
                    planned(
                        listOf(
                            ApplyRow(
                                1,
                                "er.entity.obligation",
                                "transition",
                                buildJsonObject {
                                    put("id", "ob-1")
                                },
                                attribute = "state",
                                to = JsonPrimitive("fulfilled"),
                                history = true,
                            ),
                        ),
                        keys = emptyList(),
                    )
                })
            c.batch.statuses
                .single()
                .status shouldBe "applied"
            rows("SELECT state FROM wet_fixture.obligation WHERE id = 'ob-1'") shouldBe listOf(listOf("fulfilled"))
            rows("SELECT entity, from_state, to_state FROM wet_fixture.state_history") shouldBe
                listOf(listOf("er.entity.obligation", "open", "fulfilled"))
            ledger
                .readProtocol(c.id, subject)
                .sections["signals"]
                .orEmpty()
                .size shouldBe 0
        }

        "an R2a run: set_state transitions with history plus expect obligations; a second run is idempotent" {
            fun r2a(run: UUID) =
                planned(
                    listOf(
                        ApplyRow(
                            1,
                            "er.entity.return_request_line",
                            "update",
                            buildJsonObject {
                                put("line_id", 1)
                            },
                            set = buildJsonObject { put("way", "replacement") },
                        ),
                        ApplyRow(
                            1,
                            "er.entity.return_request_line",
                            "transition",
                            buildJsonObject { put("line_id", 1) },
                            attribute = "state",
                            to = JsonPrimitive("awaiting_goods"),
                            history = true,
                        ),
                        ApplyRow(
                            1,
                            "er.entity.obligation",
                            "insert",
                            buildJsonObject { put("line_id", 1) },
                            row =
                                buildJsonObject {
                                    put("id", UUID.nameUUIDFromBytes("$run/replacement".toByteArray()).toString())
                                    put("recipe", "decide_return")
                                    put("run", run.toString())
                                    put("entity", "er.entity.return_request_line")
                                    put("key", """{"line_id":1}""")
                                    put("kind", "replacement")
                                    put("due", "2026-11-18")
                                    put("state", "open")
                                    put("created_at", "2026-11-11T10:00:00")
                                },
                        ),
                    ),
                    listOf(listOf(0, 1)),
                    keys = emptyList(),
                )
            val first = commit(::r2a)
            first.batch.statuses.map { it.status } shouldBe listOf("applied", "applied", "applied")
            rows("SELECT state, way FROM public.return_request_line") shouldBe
                listOf(listOf("awaiting_goods", "replacement"))
            rows("SELECT entity, key, from_state, to_state FROM wet_fixture.state_history") shouldBe
                listOf(listOf("er.entity.return_request_line", """{"line_id":1}""", "received", "awaiting_goods"))
            rows("SELECT kind, state FROM wet_fixture.obligation") shouldBe listOf(listOf("replacement", "open"))
            val second = commit(::r2a)
            second.batch.statuses.map { it.status to it.error } shouldBe
                listOf("applied" to null, "applied" to null, "skipped" to "if_absent")
            rows("SELECT count(*) FROM wet_fixture.state_history") shouldBe listOf(listOf("1"))
            rows("SELECT count(*) FROM wet_fixture.obligation") shouldBe listOf(listOf("1"))
        }

        // ---- T3: refusals and the unknown outcome ----

        "a door refusal becomes the typed error on every row of the group" {
            fake.refuse = { if (it.world == "w-erp") "write_under_row_policy" else null }
            val c =
                commit(
                    { run -> planned(listOf(verdict(run, "4711", "hold_sales")) + hold("4711"), listOf(listOf(1, 2))) },
                )
            c.batch.of("er.entity.order").map { it.status to it.error } shouldBe
                listOf("failed" to "write_under_row_policy", "failed" to "write_under_row_policy")
            c.batch
                .of("er.entity.release_order_verdict")
                .single()
                .status shouldBe "applied"
        }

        "a lost commit is an unknown outcome: rows stay staged, and a retried close never resends the request" {
            fake.loseCommit = { it.world == "w-erp" }
            val c = commit({ _ -> planned(hold("4711"), listOf(listOf(0, 1)), keys = emptyList()) })
            c.batch.statuses.map { it.status to it.error } shouldBe
                listOf("staged" to RowOutcome.UNKNOWN, "staged" to RowOutcome.UNKNOWN)
            (writesEntry(c.id)["unknown"] as JsonPrimitive).int shouldBe 2
            // It did commit; no history row is guessed for it.
            rows("SELECT state FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("held"))
            rows("SELECT count(*) FROM wet_fixture.state_history") shouldBe listOf(listOf("0"))
            fake.writes.size shouldBe 1
        }

        "a close retried after a crash reads its in-flight marker as unknown and does not resend that group" {
            val id =
                ledger
                    .claimRun(
                        RunOpen(
                            "release_order",
                            runAs = principal,
                            callerSubject = subject,
                            openingProtocol =
                                buildJsonObject {
                                    put("event", "opened")
                                },
                        ),
                    ).runId
            val p = planned(listOf(verdict(id, "4711", "hold_sales")) + hold("4711"), listOf(listOf(1, 2)))
            val preview =
                planner.plan(
                    PreviewPlan(
                        UUID.randomUUID().toString(),
                        id.toString(),
                        p.rows,
                        listOf(
                            DryRunAction(
                                key("4711"),
                                "hold_sales",
                                "hold",
                                "transition",
                                JsonObject(emptyMap()),
                                listOf(2, 3),
                                dryRun = false,
                            ),
                        ),
                        source = source,
                        candidateKeys = listOf(key("4711")),
                    ),
                )
            ledger.completeEvaluation(id, EvaluationResult(VerdictSummary(emptyMap(), 0), emptyList()), preview)
            val before = ledger.applyState(id, subject)
            val orderSeqs =
                before.plan.rows
                    .filter { it.entity == "er.entity.order" }
                    .map { it.seq }
            ledger.beginApply(before.batch, orderSeqs) // the process died after this request left
            val state = ledger.applyState(id, subject)
            Apply(door(), clock).apply(
                ApplyInput(
                    id,
                    "demo",
                    identity,
                    "model-fp",
                    state.deadline,
                    state.plan.rows,
                    state.actions,
                    source,
                    bundle.entities,
                    routes,
                    state.recorded,
                ),
                object : ApplyJournal {
                    override fun begin(
                        seqs: List<Int>,
                        request: String,
                    ) = ledger.beginApply(state.batch, seqs, request)

                    override fun release(seqs: List<Int>) = ledger.releaseApply(state.batch, seqs)

                    override fun record(outcomes: List<RowOutcome>) = ledger.recordApply(state.batch, outcomes)
                },
            )
            ledger.closeCommitted(id, subject)
            val batch = ledger.readRunBatch(id, subject)
            batch.of("er.entity.order").map { it.error } shouldBe listOf(RowOutcome.UNKNOWN, RowOutcome.UNKNOWN)
            fake.writes.map { it.world } shouldBe listOf("w-auto")
        }

        // ---- review-244 (Amendment 2026-11-11: history sub-outcome; close-deadline-exceeded) ----

        "B1: a close that died once the transition was journaled sends only its history request on retry" {
            val id = prepare({ _ -> planned(hold("4711"), listOf(listOf(0, 1)), keys = emptyList()) })
            shouldThrow<IllegalStateException> {
                applyOnce(id) { outcomes ->
                    // The ledger committed the transition's outcome; its acknowledgement is lost.
                    check(outcomes.none { it.history == RowOutcome.HISTORY_PENDING }) { "acknowledgement lost" }
                }
            }
            fake.writes.map { it.world } shouldBe listOf("w-erp")
            val pending = ledger.readRunBatch(id, subject).of("er.entity.order", "transition").single()
            Triple(pending.status, pending.error, pending.history) shouldBe Triple("applied", null, "pending")
            // Not a clean close while the history outcome is unanswered.
            shouldThrow<IllegalStateException> { ledger.closeCommitted(id, subject) }
            applyOnce(id)
            fake.writes.map { it.world } shouldBe listOf("w-erp", "w-auto")
            rows("SELECT state, held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("held", "Sales"))
            rows("SELECT from_state, to_state FROM wet_fixture.state_history") shouldBe
                listOf(listOf("placed", "held"))
            ledger.closeCommitted(id, subject).batch.state shouldBe "committed"
            ledger
                .readRunBatch(
                    id,
                    subject,
                ).of("er.entity.order")
                .map { Triple(it.status, it.error, it.history) } shouldBe
                listOf(Triple("applied", null, null), Triple("applied", null, "applied"))
        }

        "S2.3 F-3 (R244-F): a history retry 20 min later stamps the transition's commit time, not its own" {
            val id = prepare({ _ -> planned(hold("4711"), listOf(listOf(0, 1)), keys = emptyList()) })
            val committedAt = clock.now
            shouldThrow<IllegalStateException> {
                applyOnce(id) { outcomes ->
                    check(outcomes.none { it.history == RowOutcome.HISTORY_PENDING }) { "acknowledgement lost" }
                }
            }
            ledger
                .readRunBatch(id, subject)
                .of("er.entity.order", "transition")
                .single()
                .historyAt shouldBe committedAt.toString()
            clock.now = committedAt.plusSeconds(20 * 60)
            // The run's budget is 30 min, so the retry still fits its original deadline.
            applyOnce(id)
            rows("SELECT to_char(at, 'YYYY-MM-DD\"T\"HH24:MI:SS') FROM wet_fixture.state_history") shouldBe
                listOf(listOf("2026-11-11T10:00:00"))
            committedAt shouldBe Instant.parse("2026-11-11T10:00:00Z")
        }

        // ---- S2.3 T3: revert · perform ----

        val ops = Caller("sub-ops", "olga", emptySet(), TokenKind.PERSON, "t", groups = setOf("hartland-ops"))
        val contexts =
            DeferredContexts { _, _, _ -> ApplyContext("demo", identity, "model-fp", source, bundle.entities, routes) }

        fun obligationFulfil() =
            ApplyRow(
                1,
                "er.entity.obligation",
                "transition",
                buildJsonObject { put("id", "ob-1") },
                attribute = "state",
                to = JsonPrimitive("fulfilled"),
                history = true,
            )

        "S2.3 T3: revert applies the inverse of the recorded prior as a rev_of batch; verdicts stay; twice is 409" {
            sql(
                "INSERT INTO wet_fixture.obligation " +
                    "(id, recipe, run, entity, key, kind, due, state, created_at) VALUES " +
                    "('ob-1', 'release_order', 'r0', 'er.entity.order', '{\"order_number\":\"4711\"}', 'confirm', " +
                    "'2026-11-25', 'open', '2026-11-01')",
            )
            val id =
                prepare(
                    { run ->
                        planned(
                            listOf(verdict(run, "4711", "hold_sales")) + hold("4711") + listOf(obligationFulfil()),
                            actions = listOf(listOf(1, 2)),
                            keys = listOf("4711"),
                        )
                    },
                    deployment = "release_native_event",
                )
            applyOnce(id)
            ledger.closeCommitted(id, subject).batch.state shouldBe "committed"
            rows("SELECT state, held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("held", "Sales"))
            val held = Held(ledger, Apply(door(), clock), contexts, { bundle }, clock)
            val revert = Revert(ledger, Apply(door(), clock), contexts, held, clock)
            val maya = Caller("sub-maya", "maya", setOf("hartland-sales-managers"), TokenKind.PERSON, "t")
            shouldThrow<AutomationException> { revert.revert(id, maya) }.code shouldBe ErrorCode.IDENTITY
            clock.now = clock.now.plusSeconds(3600)
            val original = ledger.readRunBatch(id, subject).batch
            val result = revert.revert(id, ops)
            result.revOf shouldBe original
            result.rows shouldBe 3
            result.applied shouldBe 3
            // The prior values are back; the obligation is reopened by an inverse transition with history.
            rows("SELECT state, held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("placed", null))
            rows("SELECT state FROM wet_fixture.obligation WHERE id = 'ob-1'") shouldBe listOf(listOf("open"))
            rows("SELECT entity, from_state, to_state FROM wet_fixture.state_history ORDER BY at, entity") shouldBe
                listOf(
                    listOf("er.entity.obligation", "open", "fulfilled"),
                    listOf("er.entity.order", "placed", "held"),
                    listOf("er.entity.obligation", "fulfilled", "open"),
                    listOf("er.entity.order", "held", "placed"),
                )
            // Verdicts are never reverted.
            rows("SELECT count(*) FROM wet_fixture.release_order_verdict WHERE run = '$id'") shouldBe
                listOf(listOf("1"))
            transactionCount(
                "SELECT count(*) FROM automation.batches WHERE run = '$id' AND state = 'reverted'",
            ) shouldBe
                1L
            transactionCount(
                "SELECT count(*) FROM automation.batches WHERE rev_of = '$original' AND state = 'committed'",
            ) shouldBe 1L
            writesEntry(id).let {
                it["event"] shouldBe JsonPrimitive("revert")
                it["after_seal"] shouldBe JsonPrimitive(true)
                it["rev_of"] shouldBe JsonPrimitive(original)
                it["by"] shouldBe JsonPrimitive("olga")
            }
            shouldThrow<AutomationException> { revert.revert(id, ops) }.code shouldBe ErrorCode.BATCH_REVERTED
            // A batch that never committed cannot be reverted.
            val open = prepare({ run -> planned(listOf(verdict(run, "4712", "release"))) }, "release_native_event")
            shouldThrow<AutomationException> { revert.revert(open, ops) }.code shouldBe ErrorCode.BATCH_NOT_COMMITTED
        }

        "S2.3 D-187: a revert's history rows are its own even when its seqs repeat the reverted batch's" {
            sql(
                "INSERT INTO wet_fixture.obligation " +
                    "(id, recipe, run, entity, key, kind, due, state, created_at) VALUES " +
                    "('ob-1', 'release_order', 'r0', 'er.entity.order', '{\"order_number\":\"4711\"}', 'confirm', " +
                    "'2026-11-25', 'open', '2026-11-01')",
            )
            // No verdict row: the inverse rows (update 1, transition 2, transition 3) reuse the original seqs exactly.
            val id =
                prepare({ planned(hold("4711") + listOf(obligationFulfil())) }, deployment = "release_native_event")
            applyOnce(id)
            ledger.closeCommitted(id, subject).batch.state shouldBe "committed"
            val held = Held(ledger, Apply(door(), clock), contexts, { bundle }, clock)
            clock.now = clock.now.plusSeconds(3600)
            Revert(ledger, Apply(door(), clock), contexts, held, clock).revert(id, ops).applied shouldBe 3
            rows("SELECT entity, from_state, to_state FROM wet_fixture.state_history ORDER BY at, entity") shouldBe
                listOf(
                    listOf("er.entity.obligation", "open", "fulfilled"),
                    listOf("er.entity.order", "placed", "held"),
                    listOf("er.entity.obligation", "fulfilled", "open"),
                    listOf("er.entity.order", "held", "placed"),
                )
            rows("SELECT count(DISTINCT id) FROM wet_fixture.state_history") shouldBe listOf(listOf("4"))
        }

        // ---- review-258 (the S2.3 repair round) ----

        fun reverter() =
            Revert(
                ledger,
                Apply(door(), clock),
                contexts,
                Held(ledger, Apply(door(), clock), contexts, { bundle }, clock),
                clock,
            )

        fun holdOnly(order: String) =
            ApplyRow(
                1,
                "er.entity.order",
                "transition",
                key(order),
                attribute = "state",
                from = listOf("placed"),
                to = JsonPrimitive("held"),
                history = true,
            )

        "review-258 B1: a revert's set and transition are one guarded write; a row that moved on is not touched" {
            val id =
                prepare(
                    { planned(hold("4711"), actions = listOf(listOf(0, 1)), keys = emptyList()) },
                    deployment = "release_native_event",
                )
            applyOnce(id)
            ledger.closeCommitted(id, subject).batch.state shouldBe "committed"
            // Another transaction moves the order on after the commit.
            sql("UPDATE public.sales_order SET state = 'cancelled', held_for = 'AR' WHERE order_number = '4711'")
            clock.now = clock.now.plusSeconds(3600)
            val result = reverter().revert(id, ops)
            result.rows shouldBe 2
            result.failed shouldBe 2
            result.applied shouldBe 0
            rows("SELECT state, held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("cancelled", "AR"))
            rows("SELECT count(*) FROM wet_fixture.state_history WHERE to_state = 'placed'") shouldBe
                listOf(listOf("0"))
        }

        "review-258 B2: successive writes to one order unwind last-first, to the values before the batch" {
            val id =
                prepare(
                    {
                        planned(
                            hold("4711") +
                                listOf(
                                    ApplyRow(
                                        1,
                                        "er.entity.order",
                                        "update",
                                        key("4711"),
                                        set = buildJsonObject { put("held_for", "AR") },
                                    ),
                                    release("4711"),
                                ),
                            actions = listOf(listOf(0, 1), listOf(2), listOf(3)),
                            keys = emptyList(),
                        )
                    },
                    deployment = "release_native_event",
                )
            applyOnce(id)
            ledger.closeCommitted(id, subject).batch.state shouldBe "committed"
            rows("SELECT state, held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("released", "AR"))
            clock.now = clock.now.plusSeconds(3600)
            val result = reverter().revert(id, ops)
            result.rows shouldBe 4
            result.applied shouldBe 4
            result.failed shouldBe 0
            rows("SELECT state, held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("placed", null))
            rows("SELECT from_state, to_state FROM wet_fixture.state_history ORDER BY at, from_state") shouldBe
                listOf(
                    listOf("held", "released"),
                    listOf("placed", "held"),
                    listOf("held", "placed"),
                    listOf("released", "held"),
                )
        }

        "review-258 B3: a lost acknowledgement is re-read before a revert; what cannot be restored is not certified" {
            val id =
                prepare(
                    { planned(hold("4711"), actions = listOf(listOf(0, 1)), keys = emptyList()) },
                    deployment = "release_native_event",
                )
            fake.loseCommit = { it.world == "w-erp" }
            applyOnce(id)
            fake.loseCommit = { false }
            ledger.closeCommitted(id, subject).batch.state shouldBe "committed"
            rows("SELECT state, held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("held", "Sales"))
            val sent = fake.writes.size
            clock.now = clock.now.plusSeconds(3600)
            val refused = shouldThrow<AutomationException> { reverter().revert(id, ops) }
            refused.code shouldBe ErrorCode.BATCH_NOT_COMMITTED
            refused.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("unresolved-outcome")
            refused.detail!!.jsonObject["rows"] shouldBe JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))
            // One re-read went to the ERP and changed nothing; the hold was not resent, nothing was reverted.
            fake.writes.size shouldBe sent + 1
            fake.writes
                .last()
                .context.correlationId shouldBe "$id/reconcile/erp_db/1"
            rows("SELECT state, held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("held", "Sales"))
            // The re-read settled the rows as applied, but the set's prior was never read: it cannot be restored.
            ledger.readRunBatch(id, subject).statuses.map { it.status to it.error } shouldBe
                listOf("applied" to RowOutcome.RECONCILED, "applied" to RowOutcome.RECONCILED)
            transactionCount(
                "SELECT count(*) FROM automation.batches WHERE run = '$id' AND state = 'reverted'",
            ) shouldBe
                0L
            transactionCount(
                "SELECT count(*) FROM automation.batches WHERE run = '$id' AND rev_of IS NOT NULL",
            ) shouldBe
                0L
        }

        // ⚑AU-P2-12: a lost request whose last effect is there is applied, but the state before it is not known — a
        // transition's CAS accepts its `to` as well, and a CAS miss commits too. The revert refuses; nothing moves.
        suspend fun lostThenRefused(
            plan: List<ApplyRow>,
            before: String,
            seqs: List<Int>,
        ) {
            sql("UPDATE public.sales_order SET state = '$before' WHERE order_number = '4711'")
            val id =
                prepare(
                    { planned(plan, keys = emptyList()) },
                    deployment = "release_native_event",
                )
            fake.loseCommit = { it.world == "w-erp" }
            applyOnce(id)
            fake.loseCommit = { false }
            ledger.closeCommitted(id, subject).batch.state shouldBe "committed"
            val after = rows("SELECT state FROM public.sales_order WHERE order_number = '4711'")
            val sent = fake.writes.size
            clock.now = clock.now.plusSeconds(3600)
            val refused = shouldThrow<AutomationException> { reverter().revert(id, ops) }
            refused.code shouldBe ErrorCode.BATCH_NOT_COMMITTED
            refused.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("unresolved-outcome")
            refused.detail!!.jsonObject["rows"] shouldBe JsonArray(seqs.map { JsonPrimitive(it) })
            // One re-read, nothing resent, no inverse applied, the state untouched.
            fake.writes.size shouldBe sent + 1
            rows("SELECT state FROM public.sales_order WHERE order_number = '4711'") shouldBe after
            ledger.readRunBatch(id, subject).statuses.map { Triple(it.status, it.error, it.prior) } shouldBe
                seqs.map { Triple("applied", RowOutcome.RECONCILED, null) }
            rows("SELECT count(*) FROM wet_fixture.state_history") shouldBe listOf(listOf("0"))
            transactionCount(
                "SELECT count(*) FROM automation.batches WHERE run = '$id' AND (state = 'reverted' OR rev_of IS NOT NULL)",
            ) shouldBe
                0L
        }

        "review-258 B3 (⚑AU-P2-12): a lost chain placed → held → released has no known first prior: refused" {
            lostThenRefused(listOf(holdOnly("4711"), release("4711")), before = "placed", seqs = listOf(1, 2))
            rows("SELECT state FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("released"))
        }

        "review-258 B3 (⚑AU-P2-12): a lost silent self-transition from held is never reverted to placed" {
            lostThenRefused(listOf(holdOnly("4711")), before = "held", seqs = listOf(1))
            rows("SELECT state FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("held"))
        }

        "review-258 B3 (⚑AU-P2-12): a lost chain on a key already released (CAS counts 0, 1) stays released" {
            lostThenRefused(listOf(holdOnly("4711"), release("4711")), before = "released", seqs = listOf(1, 2))
            rows("SELECT state FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("released"))
        }

        "review-258 B3 (D-188): a re-read value that differs proves nothing — the revert refuses and writes nothing" {
            val id =
                prepare({ planned(listOf(holdOnly("4711")), keys = emptyList()) }, deployment = "release_native_event")
            fake.loseCommit = { it.world == "w-erp" }
            applyOnce(id)
            fake.loseCommit = { false }
            ledger.closeCommitted(id, subject).batch.state shouldBe "committed"
            // The lost hold landed; another writer moved the order on since.
            sql("UPDATE public.sales_order SET state = 'cancelled' WHERE order_number = '4711'")
            val sent = fake.writes.size
            clock.now = clock.now.plusSeconds(3600)
            val refused = shouldThrow<AutomationException> { reverter().revert(id, ops) }
            refused.code shouldBe ErrorCode.BATCH_NOT_COMMITTED
            refused.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("unresolved-outcome")
            refused.detail!!.jsonObject["rows"] shouldBe JsonArray(listOf(JsonPrimitive(1)))
            fake.writes.size shouldBe sent + 1
            rows("SELECT state FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("cancelled"))
            ledger.readRunBatch(id, subject).statuses.map { it.status to it.error } shouldBe
                listOf("staged" to RowOutcome.UNKNOWN)
            transactionCount(
                "SELECT count(*) FROM automation.batches WHERE run = '$id' AND state = 'reverted'",
            ) shouldBe
                0L
            transactionCount(
                "SELECT count(*) FROM automation.batches WHERE run = '$id' AND rev_of IS NOT NULL",
            ) shouldBe
                0L
        }

        "R254-E: perform answers per key — a grouped key is held on a dry run too; the rest dry-run; nothing sent" {
            val id =
                prepare(
                    {
                        planned(
                            hold("4711").map { it.copy(group = key("4711")) } + hold("4712"),
                            actions = listOf(listOf(0, 1), listOf(2, 3)),
                            keys = emptyList(),
                        )
                    },
                    dryRun = true,
                )
            val perform =
                Perform(
                    ledger,
                    Apply(door(), clock),
                    { _, _ -> error("a dry run resolves no context") },
                    { _, b -> b() },
                )
            val answer = perform.perform(id, "hold", PerformRequest(), identity.caller)
            answer.performed shouldBe emptyList()
            answer.skipped shouldBe listOf(SkippedKey(key("4711"), "held"), SkippedKey(key("4712"), "dry-run"))
            fake.writes shouldBe emptyList()
        }

        "S2.3 T3: perform {keys} applies only those keys' rows of the action; an identical call answers the first" {
            val id =
                prepare({ run ->
                    planned(
                        listOf(verdict(run, "4711", "hold_sales"), verdict(run, "4712", "hold_sales")) +
                            hold("4711") + hold("4712"),
                        actions = listOf(listOf(2, 3), listOf(4, 5)),
                    )
                })
            val perform =
                Perform(
                    ledger,
                    Apply(door(), clock),
                    { _, _ -> contexts.resolve(id, RunNames("", null, null, null, null), identity.caller) },
                    { _, b -> b() },
                )
            val first = perform.perform(id, "hold", PerformRequest(keys = listOf(key("4711"))), identity.caller)
            first.performed.map { it.key } shouldBe listOf(key("4711"))
            first.skipped shouldBe emptyList()
            rows(
                "SELECT order_number, state FROM public.sales_order WHERE order_number IN ('4711','4712') ORDER BY 1",
            ) shouldBe
                listOf(listOf("4711", "held"), listOf("4712", "placed"))
            val sent = fake.writes.size
            perform.perform(id, "hold", PerformRequest(keys = listOf(key("4711"))), identity.caller) shouldBe first
            fake.writes.size shouldBe sent
            // The close applies the rest and never resends the performed key's rows.
            applyOnce(id)
            ledger.closeCommitted(id, subject).status shouldBe "closed"
            fake.writes.count { it.world == "w-erp" } shouldBe 2
            rows("SELECT state FROM public.sales_order WHERE order_number = '4712'") shouldBe
                listOf(listOf("held"))
            // outcome appends to the actions section of an open protocol only; the sealed one refuses.
            shouldThrow<AutomationException> {
                perform.outcome(
                    id,
                    "hold",
                    OutcomeRequest(listOf(OutcomeItem(key("4711"), "delivered", "2026-11-11T10:05:00Z"))),
                    identity.caller,
                )
            }.code shouldBe ErrorCode.RUN_CLOSED
        }

        "S2.3 T3: outcome appends the engine's per-key outcomes to the actions section; bad keys/status refuse" {
            val id =
                prepare({ run ->
                    planned(listOf(verdict(run, "4711", "hold_sales")) + hold("4711"), actions = listOf(listOf(1, 2)))
                })
            val perform = Perform(ledger, Apply(door(), clock), { _, _ -> null }, { _, b -> b() })
            perform.outcome(
                id,
                "hold",
                OutcomeRequest(listOf(OutcomeItem(key("4711"), "delivered", "2026-11-11T10:05:00Z"))),
                identity.caller,
            )["outcomes"] shouldBe JsonPrimitive(1)
            ledger
                .readProtocol(id, subject)
                .sections
                .getValue("actions")
                .last()
                .payload
                .jsonObject["event"] shouldBe JsonPrimitive("outcome")
            shouldThrow<AutomationException> {
                perform.outcome(
                    id,
                    "hold",
                    OutcomeRequest(listOf(OutcomeItem(key("4799"), "delivered", "x"))),
                    identity.caller,
                )
            }.code shouldBe ErrorCode.BAD_REQUEST
            shouldThrow<AutomationException> {
                perform.outcome(
                    id,
                    "hold",
                    OutcomeRequest(listOf(OutcomeItem(key("4711"), "sent", "x"))),
                    identity.caller,
                )
            }.code shouldBe ErrorCode.BAD_REQUEST
        }

        "B1: a history row that landed unacknowledged is found by the retry's re-read, not inserted again" {
            val id = prepare({ _ -> planned(hold("4711"), listOf(listOf(0, 1)), keys = emptyList()) })
            val inner = door()
            val dies =
                object : Door {
                    override suspend fun read(read: DoorRead) = inner.read(read)

                    override suspend fun write(write: DoorWrite): WriteResult {
                        val answer = inner.write(write)
                        check(!write.label.startsWith("history/")) { "process died after the history commit" }
                        return answer
                    }
                }
            shouldThrow<IllegalStateException> { applyOnce(id, dies) }
            rows("SELECT count(*) FROM wet_fixture.state_history") shouldBe listOf(listOf("1"))
            ledger
                .readRunBatch(id, subject)
                .of("er.entity.order", "transition")
                .single()
                .history shouldBe "pending"
            applyOnce(id)
            fake.writes.map { it.world } shouldBe listOf("w-erp", "w-auto", "w-auto")
            rows("SELECT count(*) FROM wet_fixture.state_history") shouldBe listOf(listOf("1"))
            rows("SELECT state FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("held"))
            ledger.closeCommitted(id, subject)
            ledger
                .readRunBatch(id, subject)
                .of("er.entity.order", "transition")
                .single()
                .history shouldBe "applied"
        }

        "B1: a history request whose commit was lost is history: unknown and never resent" {
            val id = prepare({ _ -> planned(hold("4711"), listOf(listOf(0, 1)), keys = emptyList()) })
            fake.loseCommit = { it.world == "w-auto" }
            applyOnce(id)
            fake.loseCommit = { false }
            applyOnce(id)
            fake.writes.map { it.world } shouldBe listOf("w-erp", "w-auto")
            ledger.closeCommitted(id, subject)
            val transition = ledger.readRunBatch(id, subject).of("er.entity.order", "transition").single()
            Triple(transition.status, transition.error?.substringBefore(":"), transition.history) shouldBe
                Triple("applied", "history-unknown-outcome", "unknown")
            val history =
                buildJsonObject {
                    put("applied", 0)
                    put("failed", 0)
                    put("unknown", 1)
                }
            writesEntry(id)["history"] shouldBe history
            writesEntry(id)
                .getValue("entities")
                .jsonObject
                .getValue("er.entity.order")
                .jsonObject["history"] shouldBe
                history
        }

        "B2: the original deadline holds through a multi-group apply; nothing leaves after it" {
            val id =
                prepare(
                    { run -> planned(listOf(verdict(run, "4711", "hold_sales")) + hold("4711"), listOf(listOf(1, 2))) },
                )
            val deadline = ledger.applyState(id, subject).deadline
            deadline shouldBe clock.now.plus(Duration.ofMinutes(30))
            clock.now = deadline.minusSeconds(10)
            val timeouts = mutableListOf<Duration>()
            val inner = door()
            val late =
                object : Door {
                    override suspend fun read(read: DoorRead) = inner.read(read)

                    override suspend fun write(write: DoorWrite): WriteResult {
                        timeouts += write.timeout
                        return inner.write(write).also { clock.now = clock.now.plusSeconds(11) }
                    }
                }
            shouldThrow<AutomationException> { applyOnce(id, late) }.code shouldBe ErrorCode.CLOSE_DEADLINE_EXCEEDED
            // One request, bounded by the 10 s left — not a fresh full budget; the next group never left.
            timeouts shouldBe listOf(Duration.ofSeconds(10))
            fake.writes.size shouldBe 1
            val batch = ledger.readRunBatch(id, subject)
            batch.mode shouldBe "preview"
            val sent = batch.statuses.filter { it.status != "staged" }
            sent.isNotEmpty() shouldBe true
            sent.all { it.status == "applied" } shouldBe true
            batch.of("er.entity.order").map { it.status to it.error } shouldBe
                listOf("staged" to null, "staged" to null)
            shouldThrow<AutomationException> { ledger.closeCommitted(id, subject) }.code shouldBe
                ErrorCode.CLOSE_DEADLINE_EXCEEDED
            ledger.findRun(id)?.status shouldBe RunStatus.EVALUATED
            rows("SELECT state FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("placed"))
        }

        "B2: a history request owed at the deadline is not sent and stays history: pending" {
            val id = prepare({ _ -> planned(hold("4711"), listOf(listOf(0, 1)), keys = emptyList()) })
            clock.now = ledger.applyState(id, subject).deadline.minusSeconds(5)
            val inner = door()
            val late =
                object : Door {
                    override suspend fun read(read: DoorRead) = inner.read(read)

                    override suspend fun write(write: DoorWrite): WriteResult =
                        inner.write(write).also { clock.now = clock.now.plusSeconds(6) }
                }
            shouldThrow<AutomationException> { applyOnce(id, late) }.code shouldBe ErrorCode.CLOSE_DEADLINE_EXCEEDED
            fake.writes.map { it.world } shouldBe listOf("w-erp")
            ledger
                .readRunBatch(id, subject)
                .of("er.entity.order", "transition")
                .single()
                .history shouldBe "pending"
        }

        "B2-r: a four-second journal write leaves only six seconds for the door" {
            val id = prepare({ _ -> planned(hold("4711"), listOf(listOf(0, 1)), keys = emptyList()) })
            clock.now = ledger.applyState(id, subject).deadline.minusSeconds(10)
            val timeouts = mutableListOf<Duration>()
            val inner = door()
            val recording =
                object : Door {
                    override suspend fun read(read: DoorRead) = inner.read(read)

                    override suspend fun write(write: DoorWrite): WriteResult {
                        timeouts += write.timeout
                        return inner.write(write)
                    }
                }
            applyOnce(id, recording, afterBegin = { clock.now = clock.now.plusSeconds(4) })
            timeouts shouldBe listOf(Duration.ofSeconds(6), Duration.ofSeconds(6))
            ledger.closeCommitted(id, subject).status shouldBe "closed"
            writesEntry(id)["history"] shouldBe
                buildJsonObject {
                    put("applied", 1)
                    put("failed", 0)
                    put("unknown", 0)
                }
        }

        for ((name, delay) in listOf(
            "expired" to Duration.ofSeconds(11),
            "sub-millisecond" to Duration.ofNanos(9_999_600_000),
        )) {
            "B2-r G1: a $name budget after journaling releases unsent rows without unknown outcomes" {
                val id = prepare({ _ -> planned(hold("4711"), listOf(listOf(0, 1)), keys = emptyList()) })
                val deadline = ledger.applyState(id, subject).deadline
                clock.now = deadline.minusSeconds(10)
                shouldThrow<AutomationException> {
                    applyOnce(id, afterBegin = { clock.now = clock.now.plus(delay) })
                }.code shouldBe ErrorCode.CLOSE_DEADLINE_EXCEEDED
                fake.writes.size shouldBe 0
                ledger.readRunBatch(id, subject).let { batch ->
                    batch.mode shouldBe "preview"
                    batch.statuses.map { it.status to it.error } shouldBe listOf("staged" to null, "staged" to null)
                }
                // An expired retry refuses before the lost-marker sweep, and no false unknown is stored.
                clock.now = deadline
                shouldThrow<AutomationException> { applyOnce(id) }.code shouldBe ErrorCode.CLOSE_DEADLINE_EXCEEDED
                ledger.readRunBatch(id, subject).statuses.map { it.error } shouldBe listOf(null, null)
                rows("SELECT state FROM public.sales_order WHERE order_number = '4711'") shouldBe
                    listOf(listOf("placed"))
            }
        }

        "G1: four hundred microseconds left refuses before marking or sending a group" {
            val id = prepare({ _ -> planned(hold("4711"), listOf(listOf(0, 1)), keys = emptyList()) })
            clock.now = ledger.applyState(id, subject).deadline.minusNanos(400_000)
            var begins = 0
            shouldThrow<AutomationException> { applyOnce(id, afterBegin = { begins++ }) }.code shouldBe
                ErrorCode.CLOSE_DEADLINE_EXCEEDED
            begins shouldBe 0
            fake.writes.size shouldBe 0
            ledger.readRunBatch(id, subject).statuses.map { it.status to it.error } shouldBe
                listOf("staged" to null, "staged" to null)
        }

        "G2: RunCloser carries the deadline recorded at open even when pin lookup finishes after expiry" {
            val openedAt = clock.now
            val id = prepare({ _ -> planned(hold("4711"), listOf(listOf(0, 1)), keys = emptyList()) })
            val context =
                RunContext(
                    id,
                    bundle.recipes.getValue("release_order"),
                    "demo",
                    "model-fp",
                    identity,
                    emptyMap(),
                    startedAt = openedAt,
                    previewMetadata = PreviewMetadata(emptyMap(), bundle.entities, planSource = source),
                    dryRun = false,
                )
            clock.now = openedAt.plus(Duration.ofMinutes(30)).minusSeconds(1)
            val closer =
                RunCloser(ledger, Apply(door(), clock), pinned = { _, _ ->
                    clock.now = openedAt.plus(Duration.ofMinutes(30)).plusSeconds(1)
                    context
                })
            shouldThrow<AutomationException> { closer.close(id, identity.caller) }.code shouldBe
                ErrorCode.CLOSE_DEADLINE_EXCEEDED
            fake.writes.size shouldBe 0
            ledger.readRunBatch(id, subject).statuses.map { it.status to it.error } shouldBe
                listOf("staged" to null, "staged" to null)
        }

        "G2: applyState refuses an expired retry with close-deadline-exceeded rather than budget" {
            val id = prepare({ _ -> planned(hold("4711"), listOf(listOf(0, 1)), keys = emptyList()) })
            val deadline = ledger.applyState(id, subject).deadline
            applyOnce(id)
            clock.now = deadline
            shouldThrow<AutomationException> { ledger.applyState(id, subject) }.code shouldBe
                ErrorCode.CLOSE_DEADLINE_EXCEEDED
            fake.writes.size shouldBe 2
            ledger.readRunBatch(id, subject).mode shouldBe "preview"
        }

        "G2: expiry during wet sealing rolls back the writes entry, batch commit and seal" {
            val id = prepare({ _ -> planned(hold("4711"), listOf(listOf(0, 1)), keys = emptyList()) })
            applyOnce(id)
            val deadline = ledger.applyState(id, subject).deadline
            val before = ledger.readProtocol(id, subject)
            val calls = AtomicInteger()
            val ticking =
                object : Clock() {
                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = this

                    override fun instant(): Instant = if (calls.incrementAndGet() >= 3) deadline else clock.now
                }
            shouldThrow<AutomationException> {
                Ledger(Duration.ofMinutes(30), ticking).closeCommitted(id, subject)
            }.code shouldBe ErrorCode.CLOSE_DEADLINE_EXCEEDED
            calls.get() shouldBe 3
            ledger.readProtocol(id, subject) shouldBe before
            ledger.readRunBatch(id, subject).mode shouldBe "preview"
            ledger.findRun(id)?.status shouldBe RunStatus.EVALUATED
            ledger.readRunProgress(id, subject).endedAt shouldBe null
            ledger.closeCommitted(id, subject).status shouldBe "closed"
        }

        "G2 G3: failed history is final across retry and visible in the sealed writes entry" {
            val id = prepare({ _ -> planned(hold("4711"), listOf(listOf(0, 1)), keys = emptyList()) })
            fake.refuse = { if (it.world == "w-auto") "connector-unavailable" else null }
            applyOnce(id)
            val failed = ledger.readRunBatch(id, subject).of("er.entity.order", "transition").single()
            Triple(failed.status, failed.error, failed.history) shouldBe
                Triple("applied", "history-failed: connector-unavailable", "failed")
            fake.refuse = { null }
            applyOnce(id)
            fake.writes.map { it.world } shouldBe listOf("w-erp", "w-auto")
            ledger.readRunBatch(id, subject).of("er.entity.order", "transition").single() shouldBe failed
            ledger.closeCommitted(id, subject).status shouldBe "closed"
            val history =
                buildJsonObject {
                    put("applied", 0)
                    put("failed", 1)
                    put("unknown", 0)
                }
            writesEntry(id)["history"] shouldBe history
            writesEntry(id)
                .getValue("entities")
                .jsonObject
                .getValue("er.entity.order")
                .jsonObject["history"] shouldBe
                history
        }

        "B2: an apply that finished just inside the deadline still cannot commit after it" {
            val id = prepare({ _ -> planned(hold("4711"), listOf(listOf(0, 1)), keys = emptyList()) })
            applyOnce(id)
            clock.now = ledger.applyState(id, subject).deadline
            shouldThrow<AutomationException> { ledger.closeCommitted(id, subject) }.code shouldBe
                ErrorCode.CLOSE_DEADLINE_EXCEEDED
            ledger.readRunBatch(id, subject).mode shouldBe "preview"
        }

        // ---- S3.2c (D-202 F2): a watermark re-read settles per row ----

        "D-202 (F2): a re-read skips what an earlier run of the program applied, resends the rest; per program" {
            val t0 = clock.instant()
            val held = Watermark.port(Watermark.EPOCH, t0, listOf("day"), watermark = true)

            fun context(id: UUID) =
                RunContext(
                    id,
                    bundle.recipes.getValue("release_order"),
                    "demo",
                    "model-fp",
                    identity,
                    emptyMap(),
                    startedAt = clock.instant(),
                    previewMetadata = PreviewMetadata(emptyMap(), bundle.entities, planSource = source),
                    dryRun = false,
                )
            // Run 1 of P over [epoch, t0): the verdict for 4711 applies; the release of 4712 meets a transient door fault.
            fake.refuse = { if (it.world == "w-erp") "worker_engine_unavailable" else null }
            val first =
                prepare(
                    { run -> planned(listOf(verdict(run, "4711", "release"), release("4712"))) },
                    program = "P",
                    port = held,
                )
            applyOnce(first)
            ledger.closeCommitted(first, subject)
            Watermark.lastTo("P", listOf("day")) shouldBe Watermark.EPOCH
            // Q, another program, applied the verdict for 4713 over the same range.
            fake.refuse = { null }
            val other = prepare({ run -> planned(listOf(verdict(run, "4713", "release"))) }, program = "Q", port = held)
            applyOnce(other)
            ledger.closeCommitted(other, subject)

            // Run 2 of P re-reads [epoch, t1), through the close.
            clock.now = t0.plus(Duration.ofHours(1))
            fake.writes.clear()
            val reread =
                prepare(
                    { run ->
                        planned(
                            listOf(verdict(run, "4711", "release"), release("4712"), verdict(run, "4713", "release")),
                        )
                    },
                    program = "P",
                    port = Watermark.port(Watermark.EPOCH, clock.instant(), listOf("day"), watermark = true),
                )
            RunCloser(ledger, Apply(door(), clock), pinned = { id, _ -> context(id) }).close(reread, identity.caller)

            val batch = ledger.readRunBatch(reread, subject)
            val outcome = batch.statuses.associate { it.seq to (it.status to it.error) }
            val byOrder =
                batch.rows.groupBy(
                    { (it.key!!["order_number"] as JsonPrimitive).content },
                    { outcome.getValue(it.seq) },
                )
            // Every row of 4711 (its verdict and the planner's rows beside it) is settled; 4713's are all sent (Q's run
            // does not settle P's). 4712's transition, which failed in run 1, is sent; its rows run 1 applied are settled.
            byOrder.getValue("4711").toSet() shouldBe setOf("skipped" to RowOutcome.SETTLED)
            byOrder.getValue("4713").toSet() shouldBe setOf("applied" to null)
            batch.rows.filter { it.entity == "er.entity.order" }.map { outcome.getValue(it.seq) } shouldBe
                listOf("applied" to null)
            batch.rows
                .filter {
                    it.entity != "er.entity.order" &&
                        (it.key!!["order_number"] as JsonPrimitive).content == "4712"
                }.map { outcome.getValue(it.seq) }
                .toSet() shouldBe setOf("skipped" to RowOutcome.SETTLED)
            // 4711's verdict was not sent again; 4713's was (Q's run does not settle P's); 4712 moved.
            rows("SELECT count(*) FROM wet_fixture.release_order_verdict WHERE order_number = '4711'") shouldBe
                listOf(listOf("1"))
            rows("SELECT count(*) FROM wet_fixture.release_order_verdict WHERE order_number = '4713'") shouldBe
                listOf(listOf("2"))
            rows("SELECT state FROM public.sales_order WHERE order_number = '4712'") shouldBe
                listOf(listOf("released"))
            // The door saw exactly the applied rows and 4712's state_history row — no settled row was sent.
            val sent =
                fake.writes.sumOf {
                    org.tatrman.plan.v1.PlanNode
                        .parseFrom(it.source.toByteArray(Charsets.ISO_8859_1))
                        .rowWrites.rowsCount
                }
            sent shouldBe batch.statuses.count { it.status == "applied" } + 1
            val settled = batch.statuses.count { it.error == RowOutcome.SETTLED }
            ledger
                .readProtocol(reread, subject)
                .sections
                .getValue("writes")
                .last()
                .payload.jsonObject
                .let {
                    it["skipped"] shouldBe JsonPrimitive(settled)
                    it["settled"] shouldBe JsonPrimitive(settled)
                }
            Watermark.lastTo("P", listOf("day")) shouldBe clock.instant()
        }

        // ---- review-293 F6 · F8: settlement counts any wet run; a held commit and perform settle too ----

        fun reread(
            id: UUID,
            recipe: String = "release_order",
        ) = RunContext(
            id,
            bundle.recipes.getValue(recipe),
            "demo",
            "model-fp",
            identity,
            emptyMap(),
            startedAt = clock.instant(),
            previewMetadata = PreviewMetadata(emptyMap(), bundle.entities, planSource = source),
            dryRun = false,
        )

        fun sentRows() =
            fake.writes.sumOf {
                org.tatrman.plan.v1.PlanNode
                    .parseFrom(it.source.toByteArray(Charsets.ISO_8859_1))
                    .rowWrites.rowsCount
            }

        /**
         * A re-read follows a held watermark only. Where a spec's first run sealed clean (and so advanced), this puts P's
         * watermark back, as if that run had held; the hold itself is spec'd in D-206 (F2)/(F10) and S3.2c's cases.
         */
        fun heldBack() = sql("DELETE FROM automation.program_windows WHERE program = 'P'")

        "review-293 F6: rows a run applied before it failed close-deadline-exceeded settle the re-read" {
            val t0 = clock.instant()
            val first =
                prepare(
                    { run -> planned(listOf(verdict(run, "4711", "release"))) },
                    program = "P",
                    port = Watermark.port(Watermark.EPOCH, t0, listOf("day"), watermark = true),
                )
            applyOnce(first)
            // The close comes too late: the run applied its rows, never sealed, and the sweep abandons it.
            clock.now = ledger.applyState(first, subject).deadline
            shouldThrow<AutomationException> { ledger.closeCommitted(first, subject) }.code shouldBe
                ErrorCode.CLOSE_DEADLINE_EXCEEDED
            clock.now = clock.now.plus(Duration.ofHours(1))
            ledger.abandonExpiredRuns() shouldContain first
            Watermark.lastTo("P", listOf("day")) shouldBe Watermark.EPOCH
            rows("SELECT count(*) FROM wet_fixture.release_order_verdict WHERE order_number = '4711'") shouldBe
                listOf(listOf("1"))

            fake.writes.clear()
            val again =
                prepare(
                    { run -> planned(listOf(verdict(run, "4711", "release"), release("4712"))) },
                    program = "P",
                    port = Watermark.port(Watermark.EPOCH, clock.instant(), listOf("day"), watermark = true),
                )
            RunCloser(ledger, Apply(door(), clock), pinned = { id, _ -> reread(id) }).close(again, identity.caller)
            val batch = ledger.readRunBatch(again, subject)
            val outcome = batch.statuses.associate { it.seq to (it.status to it.error) }
            batch.rows
                .filter { (it.key!!["order_number"] as JsonPrimitive).content == "4711" }
                .map { outcome.getValue(it.seq) }
                .toSet() shouldBe setOf("skipped" to RowOutcome.SETTLED)
            rows("SELECT count(*) FROM wet_fixture.release_order_verdict WHERE order_number = '4711'") shouldBe
                listOf(listOf("1"))
            // Only 4712's rows (and its state_history row) left.
            sentRows() shouldBe batch.statuses.count { it.status == "applied" } + 1
            Watermark.lastTo("P", listOf("day")) shouldBe clock.instant()
        }

        "review-293 F8: a held row another run applied after the close commits as settled, with no door request" {
            val t0 = clock.instant()
            // Run A of P holds 4711's verdict in an approval group; nothing applied it yet, so the run waits.
            // (D-206 F9: a held row an earlier run had already applied would settle at the close instead.)
            val again =
                prepare(
                    { run ->
                        planned(
                            listOf(verdict(run, "4711", "release").copy(group = key("4711")), release("4712")),
                        )
                    },
                    deployment = "release_native_event",
                    program = "P",
                    port = Watermark.port(Watermark.EPOCH, t0, listOf("day"), watermark = true),
                )
            RunCloser(ledger, Apply(door(), clock), pinned = { id, _ -> reread(id) }).close(again, identity.caller)
            ledger.findRun(again)?.status shouldBe RunStatus.WAITING
            // Before the group is decided, a debug replay of P over the range (an explicit window, `watermark: false`;
            // a watermark open is blocked while A waits, F3) applies 4711's verdict.
            clock.now = t0.plus(Duration.ofHours(1))
            val other =
                prepare(
                    { run -> planned(listOf(verdict(run, "4711", "release"))) },
                    program = "P",
                    port = Watermark.port(Watermark.EPOCH, clock.instant(), listOf("day"), watermark = false),
                )
            applyOnce(other)
            ledger.closeCommitted(other, subject)
            rows("SELECT count(*) FROM wet_fixture.release_order_verdict WHERE order_number = '4711'") shouldBe
                listOf(listOf("1"))
            // The run was opened under the served fingerprints (D-99); `prepare` claims without them.
            val fps =
                bundle.recipes
                    .getValue("release_order")
                    .recipe.fingerprints
            transaction {
                Runs.update({ Runs.id eq again }) {
                    it[fpText] = fps.text
                    it[fpValues] = fps.values
                    it[fpDeployment] = bundle.deploymentFps["release_native_event"]
                }
            }
            val grouped = ledger.readRunBatch(again, subject).rows.filter { it.group == key("4711") }
            grouped.shouldNotBeEmpty()

            fake.writes.clear()
            Held(ledger, Apply(door(), clock), contexts, { bundle }, clock).commitHeld(again, key("4711"), ops)
            fake.writes.size shouldBe 0
            val outcome = ledger.readRunBatch(again, subject).statuses.associate { it.seq to (it.status to it.error) }
            grouped.map { outcome.getValue(it.seq) }.toSet() shouldBe setOf("skipped" to RowOutcome.SETTLED)
            rows("SELECT count(*) FROM wet_fixture.release_order_verdict WHERE order_number = '4711'") shouldBe
                listOf(listOf("1"))
        }

        // ---- S3.2d (D-206): the settlement, second cut ----

        fun window(to: Instant) = Watermark.port(Watermark.EPOCH, to, listOf("day"), watermark = true)

        suspend fun closeRun(id: UUID) =
            RunCloser(ledger, Apply(door(), clock), pinned = { r, _ -> reread(r) }).close(id, identity.caller)

        /** (entity, order_number, op) → (status, error), in `seq` order. */
        fun outcomes(id: UUID): Map<Triple<String, String, String>, Pair<String, String?>> {
            val batch = ledger.readRunBatch(id, subject)
            return batch.rows.associate {
                Triple(it.entity, (it.key!!["order_number"] as JsonPrimitive).content, it.op) to
                    batch.status(it.seq).let { s -> s.status to s.error }
            }
        }

        fun erpRows() =
            fake.writes.filter { it.world == "w-erp" }.map {
                org.tatrman.plan.v1.PlanNode
                    .parseFrom(it.source.toByteArray(Charsets.ISO_8859_1))
                    .rowWrites.rowsCount
            }

        "D-206 (F2): a request the worker rolled back holds; the re-read resends only its rolled-back rows" {
            val t0 = clock.instant()
            // The worker refuses 4712's row whenever a request carries it (worker-postgres: the translator refuses the
            // row's unparse, `row_writes_shape`, a settled code) and the request rolls back.
            fake.failRow = { r ->
                if (r.world == "w-erp" &&
                    "\"4712\"" in r.context.parametersList.toString()
                ) {
                    1 to "row_writes_shape"
                } else {
                    null
                }
            }
            val plan = { _: UUID ->
                planned(listOf(release("4711"), release("4712"), release("4713")), keys = emptyList())
            }
            val first = prepare(plan, program = "P", port = window(t0))
            closeRun(first)
            outcomes(first).values.toList() shouldBe
                listOf(
                    "skipped" to RowOutcome.ROLLED_BACK,
                    "failed" to "row_writes_shape",
                    "skipped" to RowOutcome.ROLLED_BACK,
                )
            // The rolled-back rows hold: nothing of them was written.
            Watermark.lastTo("P", listOf("day")) shouldBe Watermark.EPOCH

            // The re-read: 4712's settled refusal is not resent, so the request does not roll back again.
            clock.now = t0.plus(Duration.ofHours(1))
            fake.writes.clear()
            val again = prepare(plan, program = "P", port = window(clock.instant()))
            closeRun(again)
            outcomes(again).values.toList() shouldBe
                listOf("applied" to null, "skipped" to RowOutcome.SETTLED, "applied" to null)
            erpRows() shouldBe listOf(2)
            rows("SELECT order_number, state FROM public.sales_order WHERE order_number < '4714' ORDER BY 1") shouldBe
                listOf(listOf("4711", "released"), listOf("4712", "placed"), listOf("4713", "released"))
            Watermark.lastTo("P", listOf("day")) shouldBe clock.instant()
        }

        "D-206 (F4): a changed value is re-applied, an unchanged one settles; what the run seeds does not count" {
            fun heldFor(
                order: String,
                department: String,
            ) = ApplyRow(
                1,
                "er.entity.order",
                "update",
                key(order),
                set = buildJsonObject { put("held_for", department) },
            )
            val t0 = clock.instant()
            val first =
                prepare(
                    { run ->
                        planned(
                            listOf(
                                verdict(run, "4711", "hold_sales"),
                                heldFor("4711", "Sales"),
                                heldFor("4712", "Sales"),
                            ),
                            keys = listOf("4711"),
                        )
                    },
                    program = "P",
                    port = window(t0),
                )
            closeRun(first)
            outcomes(first).values.toSet() shouldBe setOf("applied" to null)
            heldBack()

            // The re-read: 4711's department changed in the source (a new identity, sent); 4712's did not (settled).
            // The verdict and its current pair differ from run 1's only in run, as_of and written_at: settled.
            clock.now = t0.plus(Duration.ofHours(1))
            fake.writes.clear()
            val again =
                prepare(
                    { run ->
                        planned(
                            listOf(verdict(run, "4711", "hold_sales"), heldFor("4711", "AR"), heldFor("4712", "Sales")),
                            keys = listOf("4711"),
                        )
                    },
                    program = "P",
                    port = window(clock.instant()),
                )
            closeRun(again)
            val settled = "skipped" to RowOutcome.SETTLED
            outcomes(again) shouldBe
                mapOf(
                    Triple("er.entity.release_order_verdict", "4711", "insert") to settled,
                    Triple("er.entity.order", "4711", "update") to ("applied" to null),
                    Triple("er.entity.order", "4712", "update") to settled,
                    Triple("er.entity.release_order_verdict_current", "4711", "delete") to settled,
                    Triple("er.entity.release_order_verdict_current", "4711", "insert") to settled,
                )
            erpRows() shouldBe listOf(1)
            rows(
                "SELECT order_number, held_for FROM public.sales_order WHERE order_number < '4713' ORDER BY 1",
            ) shouldBe
                listOf(listOf("4711", "AR"), listOf("4712", "Sales"))

            // A changed verdict is a new identity, and so is the current delete before it: the pair is resent whole.
            heldBack()
            clock.now = clock.now.plus(Duration.ofHours(1))
            val third =
                prepare(
                    { run -> planned(listOf(verdict(run, "4711", "release"))) },
                    program = "P",
                    port = window(clock.instant()),
                )
            closeRun(third)
            outcomes(third).values.toSet() shouldBe setOf("applied" to null)
            rows("SELECT route FROM wet_fixture.release_order_verdict WHERE order_number = '4711' ORDER BY 1") shouldBe
                listOf(listOf("hold_sales"), listOf("release"))
        }

        "D-206 (F7): a reverted batch's rows and the revert's inverse rows settle nothing" {
            val plan = { _: UUID -> planned(hold("4711"), listOf(listOf(0, 1)), keys = emptyList()) }
            val first =
                prepare(plan, deployment = "release_native_event", program = "P", port = window(clock.instant()))
            closeRun(first)
            heldBack()
            clock.now = clock.now.plusSeconds(3600)
            val held = Held(ledger, Apply(door(), clock), contexts, { bundle }, clock)
            Revert(ledger, Apply(door(), clock), contexts, held, clock).revert(first, ops).applied shouldBe 2
            rows("SELECT state, held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("placed", null))

            // The re-read over the same range: the reverted rows (and the inverse rows that undid them) are not done.
            fake.writes.clear()
            val again =
                prepare(plan, deployment = "release_native_event", program = "P", port = window(clock.instant()))
            closeRun(again)
            outcomes(again).values.toSet() shouldBe setOf("applied" to null)
            rows("SELECT state, held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("held", "Sales"))
        }

        "D-206 (F9): a held group an operator rejected is not held, nor asked, again on the re-read" {
            val t0 = clock.instant()
            val plan = { _: UUID ->
                planned(
                    hold("4711").map { it.copy(group = key("4711")) } + release("4712"),
                    listOf(listOf(0, 1)),
                    keys = emptyList(),
                )
            }
            val first = prepare(plan, deployment = "release_native_event", program = "P", port = window(t0))
            closeRun(first)
            ledger.findRun(first)?.status shouldBe RunStatus.WAITING
            ledger.decideHeld(first, key("4711"), "reject", "olga")
            ledger.findRun(first)?.status shouldBe RunStatus.CLOSED
            heldBack()

            clock.now = t0.plus(Duration.ofHours(1))
            fake.writes.clear()
            val again =
                prepare(plan, deployment = "release_native_event", program = "P", port = window(clock.instant()))
            closeRun(again)
            // The rejected rows settle at the close (no group waits); 4712's release was applied by run 1.
            ledger.findRun(again)?.status shouldBe RunStatus.CLOSED
            outcomes(again).values.toSet() shouldBe setOf("skipped" to RowOutcome.SETTLED)
            fake.writes shouldBe emptyList()
            rows("SELECT state, held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe
                listOf(listOf("placed", null))
            writesEntry(again).let {
                it["held"] shouldBe null
                it["settled"] shouldBe JsonPrimitive(3)
            }
        }

        "D-206 (F10): an earlier run's unknown insert is read before it is resent — found settles, absent resends" {
            val t0 = clock.instant()
            // Run 1 commits 4711's verdict, but the answer is lost: its rows stay `staged · unknown-outcome`.
            fake.loseCommit = { it.world == "w-auto" }
            val landed =
                prepare({ run -> planned(listOf(verdict(run, "4711", "release"))) }, program = "P", port = window(t0))
            closeRun(landed)
            // Run 2 loses 4712's answer too — and its commit never landed (the rows are gone).
            val lost =
                prepare({ run -> planned(listOf(verdict(run, "4712", "release"))) }, program = "P", port = window(t0))
            closeRun(lost)
            fake.loseCommit = { false }
            sql(
                "DELETE FROM wet_fixture.release_order_verdict WHERE order_number = '4712'; " +
                    "DELETE FROM wet_fixture.release_order_verdict_current WHERE order_number = '4712'",
            )
            for (id in listOf(landed, lost)) {
                outcomes(id).values.toSet() shouldBe setOf("staged" to RowOutcome.UNKNOWN)
            }
            Watermark.lastTo("P", listOf("day")) shouldBe Watermark.EPOCH

            clock.now = t0.plus(Duration.ofHours(1))
            fake.writes.clear()
            val again =
                prepare(
                    { run -> planned(listOf(verdict(run, "4711", "release"), verdict(run, "4712", "release"))) },
                    program = "P",
                    port = window(clock.instant()),
                )
            closeRun(again)
            val o = outcomes(again)
            // 4711's verdict is found under run 1's key: reconciled, not sent. 4712's is absent: sent.
            o.getValue(Triple("er.entity.release_order_verdict", "4711", "insert")) shouldBe
                ("applied" to RowOutcome.RECONCILED)
            o.getValue(Triple("er.entity.release_order_verdict", "4712", "insert")) shouldBe ("applied" to null)
            // The current pairs are not read: each is resent whole, its delete clearing what a lost request left.
            o.filterKeys { it.first.endsWith("_current") }.values.toSet() shouldBe setOf("applied" to null)
            rows(
                "SELECT order_number, count(*) FROM wet_fixture.release_order_verdict GROUP BY 1 ORDER BY 1",
            ) shouldBe listOf(listOf("4711", "1"), listOf("4712", "1"))
            rows("SELECT order_number FROM wet_fixture.release_order_verdict_current ORDER BY 1") shouldBe
                listOf(listOf("4711"), listOf("4712"))
            writesEntry(again)["reconciled"] shouldBe JsonPrimitive(1)
            Watermark.lastTo("P", listOf("day")) shouldBe clock.instant()
        }

        // ---- review-303: the latest write wins (F1) · a current pair settles as a unit (F2) · settled carries (F3) ----

        "review-303 F1: a value that went A → B → A is re-applied — only the latest write of a key settles" {
            fun heldFor(department: String) =
                ApplyRow(
                    1,
                    "er.entity.order",
                    "update",
                    key("4711"),
                    set = buildJsonObject { put("held_for", department) },
                )
            for ((i, department) in listOf("Sales", "AR", "Sales").withIndex()) {
                clock.now = clock.now.plus(Duration.ofHours(1))
                fake.writes.clear()
                val id =
                    prepare(
                        { planned(listOf(heldFor(department)), keys = emptyList()) },
                        program = "P",
                        port = window(clock.instant()),
                    )
                closeRun(id)
                withClue("run ${i + 1}: $department") {
                    outcomes(id).values.single() shouldBe ("applied" to null)
                    erpRows() shouldBe listOf(1)
                }
                heldBack()
            }
            rows("SELECT held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe listOf(listOf("Sales"))
        }

        "review-303 F1: a current key whose verdict went A → B → A is rewritten — the pair is not settled on run 1's" {
            for (reason in listOf("A", "B", "A")) {
                clock.now = clock.now.plus(Duration.ofHours(1))
                val id =
                    prepare(
                        { run -> planned(listOf(verdict(run, "4711", "release", reason = reason))) },
                        program = "P",
                        port = window(clock.instant()),
                    )
                closeRun(id)
                withClue("reason $reason") { outcomes(id).values.toSet() shouldBe setOf("applied" to null) }
                heldBack()
            }
            rows(
                "SELECT verdict_reason FROM wet_fixture.release_order_verdict_current WHERE order_number = '4711'",
            ) shouldBe
                listOf(listOf("A"))
        }

        "review-303 F2: a two-route current key where one route changes keeps both rows — the pair is resent whole" {
            fun plan(holdReason: String) =
                { run: UUID ->
                    planned(
                        listOf(
                            verdict(run, "4711", "hold_sales", reason = holdReason),
                            verdict(run, "4711", "release", reason = "R"),
                        ),
                    )
                }
            val first = prepare(plan("A"), program = "P", port = window(clock.instant()))
            applyOnce(first)
            ledger.closeCommitted(first, subject)
            heldBack()
            clock.now = clock.now.plus(Duration.ofHours(1))
            fake.writes.clear()
            // Only hold_sales's row changes; release's is as run 1 wrote it.
            val again = prepare(plan("A2"), program = "P", port = window(clock.instant()))
            applyOnce(again)
            ledger.closeCommitted(again, subject)
            val current = "er.entity.release_order_verdict_current"
            ledger
                .readRunBatch(again, subject)
                .let { b -> b.rows.filter { it.entity == current }.map { b.status(it.seq).status } } shouldBe
                listOf("applied", "applied", "applied")
            rows(
                "SELECT route, verdict_reason FROM wet_fixture.release_order_verdict_current " +
                    "WHERE order_number = '4711' ORDER BY 1",
            ) shouldBe listOf(listOf("hold_sales", "A2"), listOf("release", "R"))
        }

        "review-303 F3: a settlement carries on — a run overlapping only the settling one asks no rejection again" {
            val t0 = clock.instant()
            val plan = { _: UUID ->
                planned(
                    hold("4711").map { it.copy(group = key("4711")) } + release("4712"),
                    listOf(listOf(0, 1)),
                    keys = emptyList(),
                )
            }
            val first = prepare(plan, deployment = "release_native_event", program = "P", port = window(t0))
            closeRun(first)
            ledger.decideHeld(first, key("4711"), "reject", "olga")
            heldBack()
            // Run 2 overlaps run 1 and settles all three rows (the group's by run 1's rejection).
            val t1 = t0.plus(Duration.ofHours(1))
            clock.now = t1
            val second = prepare(plan, deployment = "release_native_event", program = "P", port = window(t1))
            closeRun(second)
            outcomes(second).values.toSet() shouldBe setOf("skipped" to RowOutcome.SETTLED)
            // Run 3 reads [t0, t2): it overlaps run 2's [epoch, t1) but not run 1's [epoch, t0).
            sql("UPDATE automation.program_windows SET last_to = '$t0' WHERE program = 'P'")
            clock.now = t1.plus(Duration.ofHours(1))
            fake.writes.clear()
            val third =
                prepare(
                    plan,
                    deployment = "release_native_event",
                    program = "P",
                    port = Watermark.port(t0, clock.instant(), listOf("day"), watermark = true),
                )
            closeRun(third)
            ledger.findRun(third)?.status shouldBe RunStatus.CLOSED
            outcomes(third).values.toSet() shouldBe setOf("skipped" to RowOutcome.SETTLED)
            fake.writes shouldBe emptyList()
            // The original decision is carried, not just "settled": the group's rows name run 1's rejection.
            rows(
                "SELECT br.op, br.row -> 'settled_by' ->> 'run', br.row -> 'settled_by' ->> 'status', " +
                    "br.row -> 'settled_by' ->> 'error' FROM automation.batch_rows br " +
                    "JOIN automation.batches b ON b.id = br.batch WHERE b.run = '$third' ORDER BY br.seq",
            ) shouldBe
                listOf(
                    listOf("update", "$first", "skipped", RowOutcome.REJECTED),
                    listOf("transition", "$first", "skipped", RowOutcome.REJECTED),
                    listOf("transition", "$first", "applied", null),
                )
        }

        // ---- review-303 N1: a held row is written when it is decided, not when its run started ----

        fun heldFor(department: String) =
            ApplyRow(1, "er.entity.order", "update", key("4711"), set = buildJsonObject { put("held_for", department) })

        /**
         * Run A of P holds `held_for=Sales` and waits; a debug replay B (`watermark: false`, the F8 fixture) applies
         * `held_for=AR`; then the operator commits A's group, so Sales is sent last. P's watermark is put back for a re-read.
         */
        suspend fun lateCommit() {
            val t0 = clock.instant()
            val first =
                prepare(
                    { planned(listOf(heldFor("Sales").copy(group = key("4711"))), keys = emptyList()) },
                    deployment = "release_native_event",
                    program = "P",
                    port = window(t0),
                )
            closeRun(first)
            ledger.findRun(first)?.status shouldBe RunStatus.WAITING
            clock.now = t0.plus(Duration.ofHours(1))
            val replay =
                prepare(
                    { planned(listOf(heldFor("AR")), keys = emptyList()) },
                    program = "P",
                    port = Watermark.port(Watermark.EPOCH, clock.instant(), listOf("day"), watermark = false),
                )
            applyOnce(replay)
            ledger.closeCommitted(replay, subject)
            rows("SELECT held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe listOf(listOf("AR"))
            // The run was opened under the served fingerprints (D-99); `prepare` claims without them.
            val fps =
                bundle.recipes
                    .getValue("release_order")
                    .recipe.fingerprints
            transaction {
                Runs.update({ Runs.id eq first }) {
                    it[fpText] = fps.text
                    it[fpValues] = fps.values
                    it[fpDeployment] = bundle.deploymentFps["release_native_event"]
                }
            }
            clock.now = t0.plus(Duration.ofHours(2))
            fake.writes.clear()
            Held(ledger, Apply(door(), clock), contexts, { bundle }, clock).commitHeld(first, key("4711"), ops)
            erpRows() shouldBe listOf(1)
            rows("SELECT held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe listOf(listOf("Sales"))
            ledger.findRun(first)?.status shouldBe RunStatus.CLOSED
            heldBack()
            clock.now = t0.plus(Duration.ofHours(3))
            fake.writes.clear()
        }

        "review-303 N1: a held row committed after a debug replay is the later write — the replay's value is resent" {
            lateCommit()
            val again =
                prepare(
                    { planned(listOf(heldFor("AR")), keys = emptyList()) },
                    program = "P",
                    port = window(clock.instant()),
                )
            closeRun(again)
            outcomes(again).values.single() shouldBe ("applied" to null)
            erpRows() shouldBe listOf(1)
            rows("SELECT held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe listOf(listOf("AR"))
        }

        "review-303 N1 mirror: the late-committed held value is the one that settles the re-read" {
            lateCommit()
            val again =
                prepare(
                    { planned(listOf(heldFor("Sales")), keys = emptyList()) },
                    program = "P",
                    port = window(clock.instant()),
                )
            closeRun(again)
            outcomes(again).values.single() shouldBe ("skipped" to RowOutcome.SETTLED)
            fake.writes shouldBe emptyList()
            rows("SELECT held_for FROM public.sales_order WHERE order_number = '4711'") shouldBe listOf(listOf("Sales"))
        }

        "review-293 R1: perform on a watermark re-read records a row an earlier run applied as settled, sends nothing" {
            val t0 = clock.instant()
            val plan = { _: UUID -> planned(hold("4711"), listOf(listOf(0, 1)), keys = emptyList()) }
            closeRun(prepare(plan, program = "P", port = window(t0)))
            heldBack()
            clock.now = t0.plus(Duration.ofHours(1))
            fake.writes.clear()
            val again = prepare(plan, program = "P", port = window(clock.instant()))
            val perform =
                Perform(
                    ledger,
                    Apply(door(), clock),
                    { _, _ -> contexts.resolve(again, RunNames("", null, null, null, null), identity.caller) },
                    { _, b -> b() },
                )
            perform.perform(again, "hold", PerformRequest(), identity.caller)
            fake.writes shouldBe emptyList()
            outcomes(again).values.toSet() shouldBe setOf("skipped" to RowOutcome.SETTLED)
        }
    })

private fun transactionCount(query: String): Long =
    LedgerTestDb.db.getDataSource().connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery(query).use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }
