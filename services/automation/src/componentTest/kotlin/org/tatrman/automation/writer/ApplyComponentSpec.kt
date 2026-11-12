// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
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
                mapOf("w-erp" to "erp_fixture", "w-auto" to "wet_fixture"),
                tables = mapOf("order" to "sales_order"),
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
                "CREATE SCHEMA erp_fixture; " +
                    "CREATE TABLE erp_fixture.sales_order " +
                    "(order_number text PRIMARY KEY, state text NOT NULL, held_for text); " +
                    "CREATE TABLE erp_fixture.return_request_line (line_id integer PRIMARY KEY, state text NOT NULL, way text)",
            )
        }
        afterSpec { sql("DROP SCHEMA erp_fixture CASCADE; DROP SCHEMA wet_fixture CASCADE") }
        beforeEach {
            LedgerTestDb.reset()
            clock.now = Instant.parse("2026-11-11T10:00:00Z")
            fake.writes.clear()
            fake.refuse = { null }
            fake.loseCommit = { false }
            sql(
                "TRUNCATE wet_fixture.release_order_verdict, wet_fixture.release_order_verdict_detail, " +
                    "wet_fixture.release_order_verdict_current, wet_fixture.obligation, wet_fixture.state_history; " +
                    "TRUNCATE erp_fixture.sales_order, erp_fixture.return_request_line; " +
                    "INSERT INTO erp_fixture.sales_order VALUES ('4711', 'placed', NULL), ('4712', 'placed', NULL), " +
                    "('4713', 'held', 'AR'), ('4714', 'cancelled', NULL); " +
                    "INSERT INTO erp_fixture.return_request_line VALUES (1, 'received', NULL)",
            )
        }

        fun key(order: String) = buildJsonObject { put("order_number", order) }

        fun verdict(
            run: UUID,
            order: String,
            route: String,
            fp: JsonElement = JsonPrimitive("sha256:values"),
        ) = ApplyRow(
            1,
            "er.entity.release_order_verdict",
            "insert",
            key(order),
            row =
                buildJsonObject {
                    put("run", run.toString())
                    put("as_of", "2026-11-11T10:00:00")
                    put("order_number", order)
                    put("route", route)
                    put("verdict_reason", "because")
                    put("parameters_fp", fp)
                    put("run_as", principal)
                    put("written_at", "2026-11-11T10:00:00")
                },
        )

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
        fun prepare(build: (UUID) -> Planned): UUID {
            val id =
                ledger
                    .claimRun(
                        RunOpen(
                            "release_order",
                            runAs = principal,
                            dryRun = false,
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
                ),
                object : ApplyJournal {
                    override fun begin(seqs: List<Int>) {
                        ledger.beginApply(state.batch, seqs)
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
                "SELECT order_number, state, held_for FROM erp_fixture.sales_order WHERE order_number IN ('4711','4712') ORDER BY 1",
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
            rows("SELECT held_for FROM erp_fixture.sales_order WHERE order_number = '4711'") shouldBe
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
            rows("SELECT state, held_for FROM erp_fixture.sales_order WHERE order_number = '4711'") shouldBe
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
            rows("SELECT state, held_for FROM erp_fixture.sales_order WHERE order_number = '4714'") shouldBe
                listOf(listOf("cancelled", null))
            rows("SELECT count(*) FROM wet_fixture.state_history") shouldBe listOf(listOf("0"))
            (writesEntry(c.id)["staleState"] as JsonPrimitive).int shouldBe 2
        }

        "held → held applies set without history; an undeclared transition fails bad-transition with no door call" {
            val c = commit({ _ -> planned(hold("4713", "Sales"), listOf(listOf(0, 1)), keys = emptyList()) })
            c.batch.statuses.map { it.status } shouldBe listOf("applied", "applied")
            rows("SELECT state, held_for FROM erp_fixture.sales_order WHERE order_number = '4713'") shouldBe
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
            transactionCount("SELECT count(*) FROM pgmq.q_automation_signals") shouldBe 0L
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
            rows("SELECT state, way FROM erp_fixture.return_request_line") shouldBe
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
            rows("SELECT state FROM erp_fixture.sales_order WHERE order_number = '4711'") shouldBe
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
                    override fun begin(seqs: List<Int>) = ledger.beginApply(state.batch, seqs)

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
            rows("SELECT state, held_for FROM erp_fixture.sales_order WHERE order_number = '4711'") shouldBe
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
            rows("SELECT state FROM erp_fixture.sales_order WHERE order_number = '4711'") shouldBe
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
            rows("SELECT state FROM erp_fixture.sales_order WHERE order_number = '4711'") shouldBe
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
                rows("SELECT state FROM erp_fixture.sales_order WHERE order_number = '4711'") shouldBe
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
