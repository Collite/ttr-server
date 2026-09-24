// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.observability

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.DecimalColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import org.tatrman.llmgateway.governance.Settle
import shared.libs.db.common.DatabaseConnection
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One prompt-log row: the [Settle] facts (§3 attribution columns) plus the prompt/response text (V1 columns).
 * [id] is the row id handed out by [PromptLogWriter.allocateId] and already echoed to the caller as
 * `X-Prompt-Log-Id` (LC §2.2); `null` ⇒ the column's own sequence default, exactly as before LC.
 */
data class PromptLogRecord(
    val settle: Settle,
    val promptText: String,
    val responseText: String,
    val status: String, // SUCCESS | ERROR (1.x column)
    val id: Long? = null,
)

/**
 * PG prompt-log sink (F-1, contracts §3), consuming the [Settle] record — the second of its three sinks
 * (budget, prompt-log, metrics). **Async write-behind**: rows are offered to a bounded channel and drained
 * by a single writer coroutine, so a request NEVER waits on PG. On a full queue the row is **dropped + a
 * metric ticks** (F-1/P-2 tension: log loss is survivable, blocking the data plane is not). The V1 TSVECTOR
 * trigger fires on these inserts, so FTS keeps working on the new-schema rows.
 */
class PromptLogWriter(
    private val db: DatabaseConnection,
    scope: CoroutineScope,
    private val metrics: MeterRegistry? = null,
    capacity: Int = 1024,
) {
    private val channel = Channel<PromptLogRecord>(capacity)

    // Drain on Dispatchers.IO: the writer runs blocking Exposed/JDBC per row, which must not sit on the
    // request-serving dispatcher (it would park a data-plane thread for the length of each insert).
    private val writer =
        scope.launch(Dispatchers.IO) {
            for (rec in channel) {
                runCatching { insert(rec) }.onFailure {
                    log.warn("prompt-log write failed", it)
                    metrics?.counter("llm_gateway_promptlog_write_error_total")?.increment()
                }
            }
        }

    // Row ids reserved ahead of the calls that will carry them (review-100 F3). Guarded by `ids`.
    private val ids = ArrayDeque<Long>()
    private val refilling = AtomicBoolean(false)
    private val refillScope = scope

    init {
        // Warm the pool once, synchronously, at boot — PG is already required here (Flyway just migrated),
        // and a cold pool would leave the first calls after every start without a ref. A failure only
        // logs: the on-demand refill below retries.
        runCatching { synchronized(ids) { ids.addAll(reserveBlock()) } }
            .onFailure { reserveFailed(it) }
    }

    /**
     * The id the row for this call WILL carry — taken from the column's own sequence BEFORE the row is
     * enqueued, so the caller can be told it (`X-Prompt-Log-Id`, LC §2.2) even when [enqueue] later drops
     * the row. That is the point: a reader holding a ref with no row knows the writer dropped it (*"golem
     * reported 3 calls; the gateway holds 2"*), instead of silently reporting 2.
     *
     * **Never touches PG, never suspends** (review-100 F3). Ids are reserved from the sequence in blocks of
     * [ID_BLOCK] by a background refill and handed out here from memory. The first cut bounded a per-call
     * `nextval` with `withTimeoutOrNull(250 ms)`, which cannot interrupt blocking JDBC — so a stalled PG sat
     * in front of every SSE first byte and cache hit, and a client cancel during the wait skipped the settle
     * (F10). An empty pool answers `null`: the call goes on WITHOUT an id (no header; the row, if written,
     * takes the sequence default) — attribution loss is survivable, stalling the data plane is not (F-1).
     *
     * Ids are unique but, across replicas, not in call order (each holds its own block) — the inspect read
     * orders by `created_at`, not by id.
     */
    fun allocateId(): Long? {
        val id: Long?
        val low: Boolean
        synchronized(ids) {
            id = ids.removeFirstOrNull()
            low = ids.size <= ID_LOW_WATER
        }
        if (low) refill()
        if (id == null) metrics?.counter("llm_gateway_promptlog_id_unavailable_total")?.increment()
        return id
    }

    /** One background reservation at a time; a failure leaves the pool as it was and is retried on demand. */
    private fun refill() {
        if (!refilling.compareAndSet(false, true)) return
        refillScope.launch(Dispatchers.IO) {
            try {
                val block = reserveBlock()
                synchronized(ids) { ids.addAll(block) }
            } catch (e: Exception) {
                reserveFailed(e)
            } finally {
                refilling.set(false)
            }
        }
    }

    /** [ID_BLOCK] ids off the column's own sequence — the one blocking PG read behind [allocateId]. */
    private fun reserveBlock(): List<Long> =
        transaction {
            exec(RESERVE_IDS_SQL) { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
        }.orEmpty()

    private fun reserveFailed(e: Throwable) {
        log.warn("prompt-log id reservation failed — calls go on without X-Prompt-Log-Id until it succeeds", e)
        metrics?.counter("llm_gateway_promptlog_id_error_total")?.increment()
    }

    /** Row ids reserved and not yet handed out (diagnostics; the component specs drain it). */
    fun reservedIds(): Int = synchronized(ids) { ids.size }

    /** Non-blocking offer. A full queue drops the row (never blocks the caller). */
    fun enqueue(rec: PromptLogRecord) {
        if (!channel.trySend(rec).isSuccess) {
            metrics?.counter("llm_gateway_promptlog_dropped_total")?.increment()
            log.warn(
                "prompt-log queue full — dropping a row (F-1: log loss survivable, blocking the data plane is not)",
            )
        }
    }

    /**
     * Close the queue and briefly wait for the writer to drain already-queued rows, so a graceful stop
     * flushes in-flight logs BEFORE the caller closes the PG pool. Bounded, so a stuck insert can't hang
     * shutdown — anything past the deadline is abandoned (log loss is survivable, F-1).
     */
    fun close() {
        channel.close()
        runBlocking { withTimeoutOrNull(DRAIN_TIMEOUT_MS) { writer.join() } }
    }

    private fun insert(r: PromptLogRecord) {
        val s = r.settle
        val strippedJson = JsonArray(s.strippedParams.map { JsonPrimitive(it) }).toString()
        transaction {
            exec(
                INSERT_SQL,
                listOf(
                    LongColumnType() to r.id, // pre-allocated (LC §2.2) or null → the sequence default
                    TextColumnType() to s.keyId, // user_id (1.x) = the key id
                    TextColumnType() to s.servedModel, // model_name (1.x)
                    TextColumnType() to s.servedProvider, // provider (1.x)
                    TextColumnType() to r.promptText,
                    TextColumnType() to r.responseText,
                    IntegerColumnType() to s.usage.promptTokens.toInt(),
                    IntegerColumnType() to s.usage.completionTokens.toInt(),
                    LongColumnType() to s.durationMs,
                    TextColumnType() to r.status,
                    TextColumnType() to s.keyId,
                    TextColumnType() to s.teamId,
                    TextColumnType() to s.costCenter,
                    TextColumnType() to s.attribution.turnRef,
                    TextColumnType() to s.requestedModel,
                    TextColumnType() to s.servedProvider,
                    TextColumnType() to s.servedModel,
                    TextColumnType() to s.fallbackFrom,
                    TextColumnType() to strippedJson, // stripped_params — bound as text, cast ?::jsonb in SQL
                    BooleanColumnType() to s.estimated,
                    BooleanColumnType() to s.cached,
                    DecimalColumnType(12, 6) to BigDecimal.valueOf(s.costUsd),
                    LongColumnType() to s.ttfbMs,
                    TextColumnType() to s.traceId,
                    TextColumnType() to s.attribution.purpose,
                    TextColumnType() to s.attribution.endUserSubject,
                    TextColumnType() to s.attribution.agentId,
                ),
            )
        }
    }

    private companion object {
        const val DRAIN_TIMEOUT_MS = 5_000L
        const val ID_BLOCK = 64
        const val ID_LOW_WATER = 16
        const val RESERVE_IDS_SQL = "SELECT nextval('prompt_logs_id_seq') FROM generate_series(1, $ID_BLOCK)"
        val log = LoggerFactory.getLogger(PromptLogWriter::class.java)
        val INSERT_SQL =
            """
            INSERT INTO prompt_logs
              (id, user_id, model_name, provider, prompt_text, response_text, tokens_prompt, tokens_completion,
               duration_ms, status, key_id, team_id, cost_center, turn_ref, requested_model, served_provider,
               served_model, fallback_from, stripped_params, estimated, cached, cost_usd, ttfb_ms, trace_id,
               purpose, end_user_subject, agent_id)
            VALUES (COALESCE(?::bigint, nextval('prompt_logs_id_seq')),
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
    }
}
