// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.queue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * The ledger's four queues (AU contracts §2; D-46). pgmq keeps every queue in its own `pgmq` schema, so the
 * contract's `automation.runs` is the queue `automation_runs` (V3__queues.sql).
 */
enum class LedgerQueue(
    val queueName: String,
) {
    RUNS("automation_runs"),
    TASKS("automation_tasks"),
    WINDOWS("automation_windows"),
    SIGNALS("automation_signals"),
    ;

    /** The contract's short name — the key of `GET /automation/health`'s `queues` and the metrics' `queue` label. */
    val key: String get() = name.lowercase()
}

data class PgmqMessage(
    val msgId: Long,
    val readCount: Int,
    val enqueuedAt: OffsetDateTime,
    val visibleAt: OffsetDateTime,
    val message: JsonElement,
)

data class QueueMetrics(
    val queueLength: Long,
    val totalMessages: Long,
    val oldestMessageAgeSeconds: Int?,
)

/**
 * pgmq over JDBC (D-130: the SQL-only install, so these are plain function calls into schema `pgmq`).
 *
 * Every call runs in the caller's Exposed transaction when there is one — a ledger write and the message that
 * announces it commit together or not at all (`markStatus` + `send`) — and in its own transaction otherwise.
 * Not [org.tatrman.automation.ledger.Ledger.claimRun] or `appendProtocol`: those must be the outermost transaction
 * and refuse to run inside one, so a message about a claim is sent after the claim commits.
 */
class Pgmq {
    /** Sends [message] to [queue], visible after [delaySeconds]; answers the message id. */
    fun send(
        queue: LedgerQueue,
        message: JsonElement,
        delaySeconds: Int = 0,
    ): Long =
        query(
            "SELECT * FROM pgmq.send(?, ?::jsonb, ?::integer)",
            listOf(
                TextColumnType() to queue.queueName,
                TextColumnType() to message.toString(),
                IntegerColumnType() to delaySeconds,
            ),
        ) { rs -> rs.next().also { check(it) { "pgmq.send answered no id" } }.let { rs.getLong(1) } }

    /** Reads up to [qty] visible messages and hides them for [visibilitySeconds] (a crashed reader's come back). */
    fun read(
        queue: LedgerQueue,
        visibilitySeconds: Int,
        qty: Int = 1,
        conditional: JsonObject = JsonObject(emptyMap()),
    ): List<PgmqMessage> =
        query(
            "SELECT msg_id, read_ct, enqueued_at, vt, message FROM pgmq.read(?, ?::integer, ?::integer, ?::jsonb)",
            listOf(
                TextColumnType() to queue.queueName,
                IntegerColumnType() to visibilitySeconds,
                IntegerColumnType() to qty,
                TextColumnType() to conditional.toString(),
            ),
        ) { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        PgmqMessage(
                            msgId = rs.getLong("msg_id"),
                            readCount = rs.getInt("read_ct"),
                            enqueuedAt = rs.getObject("enqueued_at", OffsetDateTime::class.java),
                            visibleAt = rs.getObject("vt", OffsetDateTime::class.java),
                            message = Json.parseToJsonElement(rs.getString("message")),
                        ),
                    )
                }
            }
        }

    /** Moves a handled message to the queue's archive (`pgmq.a_<queue>`); false when there was none. */
    fun archive(
        queue: LedgerQueue,
        msgId: Long,
    ): Boolean = booleanCall("SELECT pgmq.archive(?, ?::bigint)", queue, msgId)

    /** Deletes a message outright; false when there was none. */
    fun delete(
        queue: LedgerQueue,
        msgId: Long,
    ): Boolean = booleanCall("SELECT pgmq.delete(?, ?::bigint)", queue, msgId)

    fun metrics(queue: LedgerQueue): QueueMetrics =
        query(
            "SELECT queue_length, total_messages, oldest_msg_age_sec FROM pgmq.metrics(?)",
            listOf(TextColumnType() to queue.queueName),
        ) { rs ->
            check(rs.next()) { "pgmq.metrics answered no row for ${queue.queueName}" }
            QueueMetrics(
                queueLength = rs.getLong("queue_length"),
                totalMessages = rs.getLong("total_messages"),
                oldestMessageAgeSeconds = rs.getInt("oldest_msg_age_sec").takeUnless { rs.wasNull() },
            )
        }

    /** The queues pgmq knows — the startup check that V3 created all of [LedgerQueue]. */
    fun listQueues(): List<String> =
        query("SELECT queue_name FROM pgmq.list_queues()", emptyList()) { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }

    /** Creates the ledger queues V3 should have made and answers the ones it had to — none, normally. */
    fun ensure(): List<LedgerQueue> {
        val known = listQueues().toSet()
        return LedgerQueue.entries.filter { it.queueName !in known }.onEach { queue ->
            query("SELECT pgmq.create(?)", listOf(TextColumnType() to queue.queueName)) { }
        }
    }

    private fun booleanCall(
        sql: String,
        queue: LedgerQueue,
        msgId: Long,
    ): Boolean =
        query(sql, listOf(TextColumnType() to queue.queueName, LongColumnType() to msgId)) { rs ->
            rs.next() && rs.getBoolean(1)
        }

    private fun <T> query(
        sql: String,
        args: List<Pair<IColumnType<*>, Any?>>,
        read: (ResultSet) -> T,
    ): T =
        transaction {
            exec(sql, args, StatementType.SELECT) { read(it) } ?: error("pgmq: `$sql` answered nothing")
        }
}
