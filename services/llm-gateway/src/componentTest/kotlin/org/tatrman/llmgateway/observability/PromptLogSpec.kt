// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.observability

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.tatrman.llmgateway.governance.CallAttribution
import org.tatrman.llmgateway.governance.Settle
import org.tatrman.llmgateway.governance.Usage
import org.tatrman.llmgateway.store.Pg
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection

/**
 * LG-P5·S2·T1/T2/T3 — the prompt-log sink over real Postgres (Testcontainers). A settled request writes ONE
 * row with the §3 attribution columns populated (fallback / cached / estimated variants); the V1 TSVECTOR
 * trigger fires on the new-schema insert (FTS finds the row); and V3 is additive — an existing 1.x-shape row
 * survives with the new columns null/default. The writer is async, so the assertions poll for the row.
 */
class PromptLogSpec :
    StringSpec({

        val pgc =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("llmgateway")
                .withUsername("tatrman")
                .withPassword("tatrman")

        lateinit var pg: Pg
        lateinit var scope: CoroutineScope
        lateinit var writer: PromptLogWriter

        beforeSpec {
            pgc.start()
            pg =
                Pg
                    .fromConfig(
                        ConfigFactory.parseString(
                            """db { type = "POSTGRES", enabled = true, host = "${pgc.host}", port = "${pgc.firstMappedPort}", database = "${pgc.databaseName}", user = "${pgc.username}", password = "${pgc.password}" }""",
                        ),
                    ).also { it.migrate() } // V1 + V2 + V3
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            writer = PromptLogWriter(pg.db, scope)
        }
        afterSpec {
            scope.cancel()
            pg.close()
            pgc.stop()
        }

        fun <T> query(
            sql: String,
            read: (java.sql.ResultSet) -> T,
        ): T =
            pg.db.getDataSource().connection.use { c: Connection ->
                c.createStatement().use { st -> st.executeQuery(sql).use { rs -> read(rs) } }
            }

        /** True once a backend is waiting on a lock inside an INSERT into prompt_logs (the parked drain). */
        suspend fun awaitBlockedInsert(): Boolean {
            repeat(100) {
                val n =
                    query(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
                            "AND query LIKE 'INSERT INTO prompt_logs%'",
                    ) {
                        it.next()
                        it.getInt(1)
                    }
                if (n > 0) return true
                delay(50)
            }
            return false
        }

        suspend fun awaitRow(where: String): Boolean {
            repeat(60) {
                val n =
                    query("SELECT count(*) FROM prompt_logs WHERE $where") {
                        it.next()
                        it.getInt(1)
                    }
                if (n > 0) return true
                delay(50)
            }
            return false
        }

        fun settle(
            keyId: String,
            fallbackFrom: String? = null,
            stripped: List<String> = emptyList(),
            cached: Boolean = false,
            estimated: Boolean = false,
            attribution: CallAttribution = CallAttribution(turnRef = "turn-99"),
        ) = Settle(
            keyId = keyId,
            teamId = "golem",
            costCenter = "golem/analytics",
            attribution = attribution,
            requestedModel = "gpt-4o",
            servedProvider = "anthropic",
            servedModel = "claude-sonnet-4-6",
            fallbackFrom = fallbackFrom,
            strippedParams = stripped,
            usage = Usage(11, 7),
            costUsd = 0.0034,
            estimated = estimated,
            cached = cached,
            ttfbMs = 42,
            durationMs = 123,
            traceId = "0af7651916cd43dd8448eb211c80319c",
        )

        "a settled request writes one row with the §3 columns populated (fallback + stripped_params JSONB)" {
            writer.enqueue(
                PromptLogRecord(
                    settle("vk_a", fallbackFrom = "gpt-4o", stripped = listOf("logprobs", "top_logprobs")),
                    promptText = "what is the fiscal quarter",
                    responseText = "Q3",
                    status = "SUCCESS",
                ),
            )
            awaitRow("key_id = 'vk_a'") shouldBe true
            query(
                "SELECT team_id, cost_center, turn_ref, requested_model, served_provider, served_model," +
                    " fallback_from, estimated, cached, cost_usd, ttfb_ms, trace_id, stripped_params::text," +
                    " tokens_prompt, tokens_completion FROM prompt_logs WHERE key_id = 'vk_a'",
            ) { rs ->
                rs.next()
                rs.getString("team_id") shouldBe "golem"
                rs.getString("cost_center") shouldBe "golem/analytics"
                rs.getString("turn_ref") shouldBe "turn-99"
                rs.getString("requested_model") shouldBe "gpt-4o"
                rs.getString("served_provider") shouldBe "anthropic"
                rs.getString("served_model") shouldBe "claude-sonnet-4-6"
                rs.getString("fallback_from") shouldBe "gpt-4o"
                rs.getBoolean("estimated") shouldBe false
                rs.getBoolean("cached") shouldBe false
                rs.getBigDecimal("cost_usd").toDouble() shouldBe 0.0034
                rs.getLong("ttfb_ms") shouldBe 42L
                rs.getString("trace_id") shouldBe "0af7651916cd43dd8448eb211c80319c"
                rs.getString("stripped_params") shouldBe """["logprobs", "top_logprobs"]"""
                rs.getInt("tokens_prompt") shouldBe 11
                rs.getInt("tokens_completion") shouldBe 7
            }
        }

        "the V1 TSVECTOR trigger fires on the new-schema insert — FTS finds the row by prompt text" {
            writer.enqueue(
                PromptLogRecord(
                    settle("vk_fts"),
                    promptText = "supercalifragilistic prompt marker",
                    responseText = "ok",
                    status = "SUCCESS",
                ),
            )
            awaitRow("key_id = 'vk_fts'") shouldBe true
            query("SELECT count(*) FROM prompt_logs WHERE tsv @@ to_tsquery('english', 'supercalifragilistic')") {
                it.next()
                it.getInt(1)
            } shouldBe 1
        }

        "cached + estimated variants persist their flags" {
            writer.enqueue(PromptLogRecord(settle("vk_cached", cached = true), "p", "r", "SUCCESS"))
            writer.enqueue(PromptLogRecord(settle("vk_est", estimated = true), "p", "r", "SUCCESS"))
            awaitRow("key_id = 'vk_cached'") shouldBe true
            awaitRow("key_id = 'vk_est'") shouldBe true
            query("SELECT cached FROM prompt_logs WHERE key_id='vk_cached'") {
                it.next()
                it.getBoolean(1)
            } shouldBe
                true
            query("SELECT estimated FROM prompt_logs WHERE key_id='vk_est'") {
                it.next()
                it.getBoolean(1)
            } shouldBe
                true
        }

        "V3 is additive — a 1.x-shape insert (old columns only) still works, new columns default" {
            pg.db.getDataSource().connection.use { c ->
                c.createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO prompt_logs (user_id, model_name, provider, prompt_text, response_text, status)" +
                            " VALUES ('legacy', 'gpt-4o', 'azure', 'old row', 'old resp', 'SUCCESS')",
                    )
                }
                c.commit() // the shared pool runs autoCommit=false — commit the raw insert so the SELECT below sees it
            }
            query("SELECT cached, estimated, team_id FROM prompt_logs WHERE user_id='legacy'") { rs ->
                rs.next()
                rs.getBoolean("cached") shouldBe false // NOT NULL DEFAULT FALSE
                rs.getBoolean("estimated") shouldBe false
                rs.getString("team_id") shouldBe null // new nullable column
            }
        }

        // ── LC-P0·S0.2 (LC contracts §2.1/§2.2) ─────────────────────────────────────────────────────

        "the V5 attribution columns land on the row; a settle without them writes NULLs" {
            writer.enqueue(
                PromptLogRecord(
                    settle(
                        "vk_lc_attr",
                        attribution = CallAttribution("turn-lc", "compose-plan", "sub-dan", "golem-hartland"),
                    ),
                    "p",
                    "r",
                    "SUCCESS",
                ),
            )
            writer.enqueue(PromptLogRecord(settle("vk_lc_none", attribution = CallAttribution()), "p", "r", "SUCCESS"))
            awaitRow("key_id = 'vk_lc_attr'") shouldBe true
            awaitRow("key_id = 'vk_lc_none'") shouldBe true

            fun attributionOf(keyId: String): List<String?> =
                query(
                    "SELECT turn_ref, purpose, end_user_subject, agent_id FROM prompt_logs WHERE key_id = '$keyId'",
                ) { rs ->
                    rs.next()
                    listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
                }
            attributionOf("vk_lc_attr") shouldBe listOf("turn-lc", "compose-plan", "sub-dan", "golem-hartland")
            attributionOf("vk_lc_none") shouldBe listOf(null, null, null, null)
        }

        "allocateId hands out the id the row is then written under; a record without one takes the default" {
            val a = writer.allocateId().shouldNotBeNull()
            val b = writer.allocateId().shouldNotBeNull()
            b shouldNotBe a
            writer.enqueue(PromptLogRecord(settle("vk_lc_id"), "p", "r", "SUCCESS", id = b))
            writer.enqueue(PromptLogRecord(settle("vk_lc_default"), "p", "r", "SUCCESS")) // id = null
            awaitRow("key_id = 'vk_lc_id'") shouldBe true
            awaitRow("key_id = 'vk_lc_default'") shouldBe true
            query("SELECT id FROM prompt_logs WHERE key_id = 'vk_lc_id'") {
                it.next()
                it.getLong(1)
            } shouldBe b
            // the default comes off the SAME sequence, so it never collides with a handed-out id
            val default =
                query("SELECT id FROM prompt_logs WHERE key_id = 'vk_lc_default'") {
                    it.next()
                    it.getLong(1)
                }
            (default > b) shouldBe true
        }

        // The whole reason the id is allocated BEFORE the enqueue: a dropped row still had a real, unique
        // id, so the reader holding it can say "the gateway holds no row for this call" (LC §4, A-LC-2).
        // Made deterministic with a REAL stall (review-100 F21 — the first cut cancelled the writer's scope,
        // so even the kept row was never written and "the dropped id has no row" held vacuously): a table
        // lock parks the drain inside its first INSERT, the one-slot queue then holds the second row, the
        // third is dropped; releasing the lock proves the kept rows land under their ids and the dropped
        // id never does.
        "a row the writer DROPS (queue full) still had its id; the rows it kept land under theirs" {
            val registry = SimpleMeterRegistry()
            val own = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val full = PromptLogWriter(pg.db, own, registry, capacity = 1)
            val ids = List(3) { full.allocateId().shouldNotBeNull() }
            ids.toSet().size shouldBe 3

            val lock = pg.db.getDataSource().connection
            try {
                lock.autoCommit = false
                lock.createStatement().use { it.execute("LOCK TABLE prompt_logs IN ACCESS EXCLUSIVE MODE") }

                full.enqueue(PromptLogRecord(settle("vk_lc_first"), "p", "r", "SUCCESS", id = ids[0]))
                awaitBlockedInsert() shouldBe true // the drain has taken row 1 and waits on the lock
                full.enqueue(PromptLogRecord(settle("vk_lc_queued"), "p", "r", "SUCCESS", id = ids[1])) // the slot
                full.enqueue(PromptLogRecord(settle("vk_lc_dropped"), "p", "r", "SUCCESS", id = ids[2])) // dropped
                registry.counter("llm_gateway_promptlog_dropped_total").count() shouldBe 1.0
            } finally {
                lock.rollback()
                lock.close()
            }

            awaitRow("key_id = 'vk_lc_queued'") shouldBe true

            fun idOf(key: String): Long =
                query("SELECT id FROM prompt_logs WHERE key_id = '$key'") {
                    it.next()
                    it.getLong(1)
                }
            idOf("vk_lc_first") shouldBe ids[0]
            idOf("vk_lc_queued") shouldBe ids[1]
            query("SELECT count(*) FROM prompt_logs WHERE id = ${ids[2]} OR key_id = 'vk_lc_dropped'") {
                it.next()
                it.getInt(1)
            } shouldBe 0
            own.cancel()
        }

        // review-100 F3 — the id is handed out from memory, never read from PG on the call path. With the
        // sequence locked (every nextval blocks), allocateId still answers at once: the remaining reserved
        // ids, then null (no header) — never a wait. Once PG answers again, the pool refills.
        "allocateId never waits on PG: a stalled sequence costs the header, not the call" {
            val registry = SimpleMeterRegistry()
            val own = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val w = PromptLogWriter(pg.db, own, registry) // warmed synchronously at construction

            val lock = pg.db.getDataSource().connection
            try {
                lock.autoCommit = false
                // ALTER SEQUENCE holds the sequence's ACCESS EXCLUSIVE lock until the transaction ends.
                lock.createStatement().use { it.execute("ALTER SEQUENCE prompt_logs_id_seq INCREMENT BY 1") }
                // Drain the pool. Its low-water refill now parks on the lock, so the pool cannot grow back.
                while (w.reservedIds() > 0) w.allocateId().shouldNotBeNull()

                val started = System.nanoTime()
                repeat(20) { w.allocateId() shouldBe null }
                val tookMs = (System.nanoTime() - started) / 1_000_000
                (tookMs < 100) shouldBe true
                registry.counter("llm_gateway_promptlog_id_unavailable_total").count() shouldBe 20.0
            } finally {
                lock.rollback()
                lock.close()
            }

            var refilled: Long? = null
            repeat(100) {
                if (refilled == null) {
                    refilled = w.allocateId()
                    if (refilled == null) delay(50)
                }
            }
            refilled.shouldNotBeNull()
            own.cancel()
        }
    })
