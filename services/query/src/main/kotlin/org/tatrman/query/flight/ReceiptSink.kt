// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import com.typesafe.config.Config
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * BI (contracts C-6) — one statement's receipt, `bi_receipts`' row: who asked what, in which world, what the door ran
 * and what it applied, how it ended. `executed_sql` and `security_applied` are the ES receipt's (the door's last
 * batch); a statement refused before it ran has neither.
 */
data class Receipt(
    val correlationId: String,
    val ts: Instant,
    val userId: String,
    val roles: List<String>,
    val world: String,
    val connectionId: String,
    val client: String,
    val statement: String,
    val executedSql: String,
    /** The ES receipt's `security_applied`, as JSON: `[{"rule_id", "predicate_summary", "table"}]`. */
    val securityApplied: JsonArray,
    val rowsTotal: Long,
    val topNApplied: Boolean,
    val status: String,
    val errorCode: String,
    /**
     * The statement's notices, as its closing metadata carried them (C-5): `[{"code", "severity", "message"}]`, with
     * `requested` / `applied` on a typed one (`top_n_applied`).
     */
    val notices: JsonArray = JsonArray(emptyList()),
) {
    companion object {
        fun of(
            outcome: StatementOutcome,
            ts: Instant,
        ): Receipt {
            val es = outcome.receipt
            return Receipt(
                correlationId = outcome.correlationId,
                ts = ts,
                userId = outcome.session.userId,
                roles = outcome.session.roles,
                world = outcome.session.world,
                connectionId = es?.connectionId.orEmpty(),
                client = outcome.session.client,
                statement = outcome.statement,
                executedSql = es?.statement?.ifEmpty { es.statementRef }.orEmpty(),
                securityApplied =
                    buildJsonArray {
                        es?.securityAppliedList?.forEach { rule ->
                            addJsonObject {
                                put("rule_id", rule.ruleId)
                                put("predicate_summary", rule.predicateSummary)
                                put("table", rule.table)
                            }
                        }
                    },
                rowsTotal = outcome.rowsStreamed,
                topNApplied = outcome.topNApplied,
                status = outcome.status.label,
                errorCode = outcome.errorCode,
                notices = outcome.noticesJson,
            )
        }
    }
}

/** `GET /receipts`' question: by [correlationId], or by [userId] (optionally [since]); newest first, at most [limit]. */
data class ReceiptQuery(
    val correlationId: String? = null,
    val userId: String? = null,
    val since: Instant? = null,
    val limit: Int = 100,
)

/** Where receipts are kept. Implementations may throw; [ReceiptSink] is what makes a failure harmless. */
interface ReceiptStore : AutoCloseable {
    fun insert(receipt: Receipt)

    fun find(query: ReceiptQuery): List<Receipt>

    override fun close() = Unit
}

/**
 * C-6 — the edge's [StatementListener] that writes every statement's [Receipt], after its stream: handed to one
 * writer thread through a bounded queue, so the edge never waits on the database. Best effort, by contract: a full
 * queue drops the receipt and a failed write loses it, both logged — neither ever fails a statement.
 */
class ReceiptSink(
    private val store: ReceiptStore,
    capacity: Int = 1000,
    private val clock: Clock = Clock.systemUTC(),
) : StatementListener,
    AutoCloseable {
    private val queue = ArrayBlockingQueue<Receipt>(capacity)

    @Volatile private var open = true
    private val writer =
        Thread({ drain() }, "bi-receipt-writer").apply {
            isDaemon = true
            start()
        }

    override fun onStatementDone(outcome: StatementOutcome) {
        val receipt = Receipt.of(outcome, clock.instant())
        if (!queue.offer(receipt)) {
            log.warn("BI receipts: queue full, receipt {} dropped", receipt.correlationId)
        }
    }

    private fun drain() {
        while (open || queue.isNotEmpty()) {
            val receipt = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            try {
                store.insert(receipt)
            } catch (e: Exception) {
                log.warn("BI receipts: receipt {} not written: {}", receipt.correlationId, e.message)
            }
        }
    }

    /** Writes what is queued (for up to [drainMillis]), then stops. */
    fun close(drainMillis: Long) {
        open = false
        writer.join(drainMillis)
    }

    override fun close() = close(5_000)

    companion object {
        private val log = LoggerFactory.getLogger(ReceiptSink::class.java)
    }
}

/**
 * `bi_receipts` in schema `query` of the service's CNPG database, migrated by Flyway on first use (its history in the
 * same schema): a database that is down at boot costs receipts until it is back, never the query service's start.
 * The migration is retried at most once per [retryMillis].
 */
class PostgresReceiptStore(
    private val dataSource: DataSource,
    private val retryMillis: Long = 30_000,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ReceiptStore {
    @Volatile private var migrated = false
    private var lastAttempt: Long? = null

    @Synchronized
    private fun ensureMigrated() {
        if (migrated) return
        val now = nowMillis()
        lastAttempt?.let { check(now - it >= retryMillis) { "bi_receipts is not migrated yet (last attempt failed)" } }
        lastAttempt = now
        val ran = migrate(dataSource)
        migrated = true
        log.info("BI receipts: bi_receipts ready ({} migration(s) applied)", ran)
    }

    override fun insert(receipt: Receipt) {
        ensureMigrated()
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    """
                    INSERT INTO query.bi_receipts (correlation_id, ts, user_id, roles, world, connection_id, client,
                        statement, executed_sql, security_applied, rows_total, top_n_applied, status, error_code,
                        notices)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (correlation_id) DO NOTHING
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, receipt.correlationId)
                    s.setTimestamp(2, Timestamp.from(receipt.ts))
                    s.setString(3, receipt.userId)
                    s.setArray(4, c.createArrayOf("text", receipt.roles.toTypedArray()))
                    s.setString(5, receipt.world)
                    s.setString(6, receipt.connectionId)
                    s.setString(7, receipt.client)
                    s.setString(8, receipt.statement)
                    s.setString(9, receipt.executedSql)
                    s.setString(10, receipt.securityApplied.toString())
                    s.setLong(11, receipt.rowsTotal)
                    s.setBoolean(12, receipt.topNApplied)
                    s.setString(13, receipt.status)
                    s.setString(14, receipt.errorCode)
                    s.setString(15, receipt.notices.toString())
                    s.executeUpdate()
                }
        }
    }

    override fun find(query: ReceiptQuery): List<Receipt> {
        ensureMigrated()
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        query.correlationId?.let {
            where += "correlation_id = ?"
            args += it
        }
        query.userId?.let {
            where += "user_id = ?"
            args += it
        }
        query.since?.let {
            where += "ts >= ?"
            args += Timestamp.from(it)
        }
        val sql =
            "SELECT correlation_id, ts, user_id, roles, world, connection_id, client, statement, executed_sql, " +
                "security_applied::text, rows_total, top_n_applied, status, error_code, notices::text " +
                "FROM query.bi_receipts" +
                (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
                " ORDER BY ts DESC LIMIT ?"
        return dataSource.connection.use { c ->
            c.prepareStatement(sql).use { s ->
                args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
                s.setInt(args.size + 1, query.limit)
                s.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            @Suppress("UNCHECKED_CAST")
                            add(
                                Receipt(
                                    correlationId = rs.getString(1),
                                    ts = rs.getTimestamp(2).toInstant(),
                                    userId = rs.getString(3),
                                    roles = (rs.getArray(4).array as Array<String>).toList(),
                                    world = rs.getString(5),
                                    connectionId = rs.getString(6),
                                    client = rs.getString(7),
                                    statement = rs.getString(8),
                                    executedSql = rs.getString(9),
                                    securityApplied =
                                        kotlinx.serialization.json.Json
                                            .parseToJsonElement(rs.getString(10)) as JsonArray,
                                    rowsTotal = rs.getLong(11),
                                    topNApplied = rs.getBoolean(12),
                                    status = rs.getString(13),
                                    errorCode = rs.getString(14),
                                    notices =
                                        kotlinx.serialization.json.Json
                                            .parseToJsonElement(rs.getString(15)) as JsonArray,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        (dataSource as? AutoCloseable)?.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(PostgresReceiptStore::class.java)

        /** Flyway over schema `query` (`classpath:db/receipts`); answers how many migrations ran. */
        fun migrate(dataSource: DataSource): Int =
            Flyway
                .configure()
                .dataSource(dataSource)
                .schemas("query")
                .locations("classpath:db/receipts")
                .load()
                .migrate()
                .migrationsExecuted

        /**
         * The store from `flight.receipts.db`, or null when no host is set (receipts off). The pool does not connect
         * until the first receipt: the database is not a precondition of the query service.
         */
        fun fromConfig(config: Config): PostgresReceiptStore? {
            val path = "flight.receipts.db"
            if (!config.hasPath("$path.host") || config.getString("$path.host").isBlank()) return null
            val db = config.getConfig(path)
            val hikari =
                HikariConfig().apply {
                    poolName = "bi-receipts"
                    driverClassName = "org.postgresql.Driver"
                    jdbcUrl =
                        "jdbc:postgresql://${db.getString("host")}:${db.getString("port")}/${db.getString("database")}"
                    username = db.getString("user")
                    password = db.getString("password")
                    maximumPoolSize = 2
                    isAutoCommit = true
                    initializationFailTimeout = -1
                    connectionTimeout = 5_000
                }
            return PostgresReceiptStore(HikariDataSource(hikari))
        }
    }
}
