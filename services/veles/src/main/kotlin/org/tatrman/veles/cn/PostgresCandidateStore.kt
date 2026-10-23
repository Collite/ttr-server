// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.cn

import com.typesafe.config.Config
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.tatrman.concept.candidate.CandidateLoad
import org.tatrman.concept.candidate.CnCandidates
import org.tatrman.ttr.metadata.model.VocabularyVersion
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The candidate store in schema `metadata` of the service's database (A-CN-90), migrated by Flyway on first use
 * (`classpath:db/cn`, its history in the same schema) — the BI receipts pattern: a database that is down at boot costs
 * the candidate API until it is back, never Veles's start. The migration is retried at most once per [retryMillis].
 * The schema is `metadata`, the name the service takes at MR-P1, so the rename moves no table.
 */
class PostgresCandidateStore(
    private val dataSource: DataSource,
    private val retryMillis: Long = 30_000,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : CandidateStore {
    @Volatile private var migrated = false
    private var lastAttempt: Long? = null

    @Synchronized
    private fun ensureMigrated() {
        if (migrated) return
        val now = nowMillis()
        lastAttempt?.let {
            check(now - it >= retryMillis) { "the cn candidate store is not migrated yet (last attempt failed)" }
        }
        lastAttempt = now
        val ran = migrate(dataSource)
        migrated = true
        log.info("cn candidates: store ready ({} migration(s) applied)", ran)
    }

    private fun <T> db(block: (Connection) -> T): T {
        ensureMigrated()
        return dataSource.connection.use(block)
    }

    override fun insert(c: StoredCandidate): Inserted =
        db { conn ->
            val inserted =
                conn
                    .prepareStatement(
                        """
                        INSERT INTO metadata.cn_candidate (id, content_hash, proposal, provenance, status, resolution,
                            state, producer, kind, question_count, document_count, submitted_by, submitted_at,
                            updated_at, promotion)
                        VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        ON CONFLICT DO NOTHING
                        """.trimIndent(),
                    ).use { s ->
                        val doc = CnCandidates.toJson(c.candidate)
                        s.setObject(1, UUID.fromString(c.id))
                        s.setString(2, c.contentHash)
                        s.setString(3, doc.getValue("proposal").toString())
                        s.setString(4, doc.getValue("provenance").toString())
                        s.setString(5, c.candidate.status.word)
                        s.setString(6, doc["resolution"]?.toString())
                        s.setString(7, c.state.word)
                        s.setString(8, c.candidate.provenance.producer)
                        s.setString(9, c.kind)
                        s.setObject(10, c.questionCount)
                        s.setObject(11, c.documentCount)
                        s.setString(12, c.submittedBy)
                        s.setTimestamp(13, Timestamp.from(c.submittedAt))
                        s.setTimestamp(14, Timestamp.from(c.updatedAt))
                        s.setString(15, c.promotion?.let(::promotionJson)?.toString())
                        s.executeUpdate()
                    }
            if (inserted == 1) return@db Inserted.Created(c)
            selectOne(conn, "content_hash = ?", c.contentHash)?.let { return@db Inserted.Duplicate(it) }
            Inserted.IdTaken(
                checkNotNull(selectOne(conn, "id = ?", UUID.fromString(c.id))) { "conflict on ${c.id}, row gone" },
            )
        }

    override fun get(id: String): StoredCandidate? {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
        return db { selectOne(it, "id = ?", uuid) }
    }

    override fun find(q: CandidateQuery): List<StoredCandidate> =
        db { conn ->
            val where = mutableListOf<String>()
            val args = mutableListOf<Any>()
            q.state?.let {
                where += "state = ?"
                args += it.word
            }
            q.producer?.let {
                where += "producer = ?"
                args += it
            }
            q.kind?.let {
                where += "kind = ?"
                args += it
            }
            val order =
                when (q.sort) {
                    CandidateSort.NEWEST -> "submitted_at DESC, id"
                    CandidateSort.EVIDENCE ->
                        "question_count DESC NULLS LAST, document_count DESC NULLS LAST, submitted_at, id"
                }
            val sql =
                "SELECT $COLUMNS FROM metadata.cn_candidate" +
                    (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
                    " ORDER BY $order LIMIT ?"
            conn.prepareStatement(sql).use { s ->
                args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
                s.setInt(args.size + 1, q.limit)
                s.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
            }
        }

    override fun replace(
        c: StoredCandidate,
        expected: CandidateState,
    ): Boolean =
        db { conn ->
            conn
                .prepareStatement(
                    """
                    UPDATE metadata.cn_candidate
                    SET status = ?, resolution = ?::jsonb, state = ?, updated_at = ?, promotion = ?::jsonb
                    WHERE id = ? AND state = ?
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, c.candidate.status.word)
                    s.setString(2, CnCandidates.toJson(c.candidate)["resolution"]?.toString())
                    s.setString(3, c.state.word)
                    s.setTimestamp(4, Timestamp.from(c.updatedAt))
                    s.setString(5, c.promotion?.let(::promotionJson)?.toString())
                    s.setObject(6, UUID.fromString(c.id))
                    s.setString(7, expected.word)
                    s.executeUpdate() == 1
                }
        }

    override fun record(e: CandidateEvent) {
        db { conn ->
            conn
                .prepareStatement(
                    "INSERT INTO metadata.cn_candidate_event (candidate_id, action, actor, at, detail) VALUES (?, ?, ?, ?, ?)",
                ).use { s ->
                    s.setObject(1, UUID.fromString(e.candidateId))
                    s.setString(2, e.action)
                    s.setString(3, e.actor)
                    s.setTimestamp(4, Timestamp.from(e.at))
                    s.setString(5, e.detail)
                    s.executeUpdate()
                }
        }
    }

    override fun events(id: String): List<CandidateEvent> {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return emptyList()
        return db { conn ->
            conn
                .prepareStatement(
                    "SELECT action, actor, at, detail FROM metadata.cn_candidate_event WHERE candidate_id = ? ORDER BY seq",
                ).use { s ->
                    s.setObject(1, uuid)
                    s.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    CandidateEvent(
                                        id,
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getTimestamp(3).toInstant(),
                                        rs.getString(4),
                                    ),
                                )
                            }
                        }
                    }
                }
        }
    }

    override fun register(
        v: VocabularyVersion,
        by: String,
    ): Boolean =
        db { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO metadata.cn_vocabulary_version (vocabulary, version, bundle, registered_at, registered_by)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, v.vocabulary)
                    s.setString(2, v.version)
                    s.setString(3, v.bundle)
                    s.setTimestamp(4, Timestamp.from(v.registeredAt))
                    s.setString(5, by)
                    s.executeUpdate() == 1
                }
        }

    override fun vocabularyVersions(): List<VocabularyVersion> =
        db { conn ->
            conn
                .prepareStatement(
                    "SELECT vocabulary, version, bundle, registered_at FROM metadata.cn_vocabulary_version ORDER BY seq",
                ).use { s ->
                    s.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    VocabularyVersion(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getTimestamp(4).toInstant(),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    override fun close() {
        (dataSource as? AutoCloseable)?.close()
    }

    private fun selectOne(
        conn: Connection,
        where: String,
        arg: Any,
    ): StoredCandidate? =
        conn.prepareStatement("SELECT $COLUMNS FROM metadata.cn_candidate WHERE $where").use { s ->
            s.setObject(1, arg)
            s.executeQuery().use { rs -> if (rs.next()) row(rs) else null }
        }

    /** A row back to the document — read by the same reader `submit` used, so a row the reader refuses is loud. */
    private fun row(rs: ResultSet): StoredCandidate {
        val id = rs.getString("id")
        val doc =
            buildJsonObject {
                put("id", id)
                put("proposal", Json.parseToJsonElement(rs.getString("proposal")))
                put("provenance", Json.parseToJsonElement(rs.getString("provenance")))
                put("status", rs.getString("status"))
                rs.getString("resolution")?.let { put("resolution", Json.parseToJsonElement(it)) }
            }
        val candidate =
            when (val load = CnCandidates.read(doc)) {
                is CandidateLoad.Ok -> load.candidate
                is CandidateLoad.Rejected ->
                    error(
                        "stored candidate $id no longer reads: ${load.violations.first().message}",
                    )
            }
        return StoredCandidate(
            candidate = candidate,
            state =
                checkNotNull(
                    CandidateState.of(rs.getString("state")),
                ) { "candidate $id has state ${rs.getString("state")}" },
            contentHash = rs.getString("content_hash"),
            submittedBy = rs.getString("submitted_by"),
            submittedAt = rs.getTimestamp("submitted_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            promotion = rs.getString("promotion")?.let { promotionOf(Json.parseToJsonElement(it).jsonObject) },
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(PostgresCandidateStore::class.java)

        private const val COLUMNS =
            "id, content_hash, proposal::text AS proposal, provenance::text AS provenance, status, " +
                "resolution::text AS resolution, state, submitted_by, submitted_at, updated_at, promotion::text AS promotion"

        /** Flyway over schema `metadata` (`classpath:db/cn`); answers how many migrations ran. */
        fun migrate(dataSource: DataSource): Int =
            Flyway
                .configure()
                .dataSource(dataSource)
                .schemas("metadata")
                .table("flyway_cn_history")
                .locations("classpath:db/cn")
                .load()
                .migrate()
                .migrationsExecuted

        private const val PATH = "metadata.cn.db"

        const val OFF_NO_HOST = "no METADATA_CN_DB_HOST"

        /** Why the store is off for [config], or null when it is on: no host, or a host with no password. */
        fun offReason(config: Config): String? =
            when {
                !config.hasPath("$PATH.host") || config.getString("$PATH.host").isBlank() -> OFF_NO_HOST
                config.getString("$PATH.password").isBlank() -> "no METADATA_CN_DB_PASSWORD (is its Secret missing?)"
                else -> null
            }

        /** The store from `metadata.cn.db`, or null when it is off. The pool connects on first use. */
        fun fromConfig(config: Config): PostgresCandidateStore? {
            if (offReason(config) != null) return null
            val db = config.getConfig(PATH)
            val hikari =
                HikariConfig().apply {
                    poolName = "cn-candidates"
                    driverClassName = "org.postgresql.Driver"
                    jdbcUrl =
                        "jdbc:postgresql://${db.getString("host")}:${db.getString("port")}/${db.getString("database")}"
                    username = db.getString("user")
                    password = db.getString("password")
                    maximumPoolSize = 4
                    isAutoCommit = true
                    initializationFailTimeout = -1
                    connectionTimeout = 5_000
                }
            return PostgresCandidateStore(HikariDataSource(hikari))
        }

        internal fun promotionJson(p: PromotionRecord): JsonObject =
            buildJsonObject {
                put("qname", p.qname)
                put("diff", p.diff)
                put("title", p.title)
                put("body", p.body)
                put("base", p.base)
                put("by", p.by)
                put("at", p.at.toString())
                p.pullRequest?.let { put("pull_request", it) }
                p.inbox?.let { put("inbox", it) }
                putJsonArray("findings") { p.findings.forEach { add(JsonPrimitive(it)) } }
            }

        internal fun promotionOf(o: JsonObject): PromotionRecord {
            fun s(key: String) = (o[key] as? JsonPrimitive)?.content
            return PromotionRecord(
                qname = s("qname").orEmpty(),
                diff = s("diff").orEmpty(),
                title = s("title").orEmpty(),
                body = s("body").orEmpty(),
                base = s("base").orEmpty(),
                by = s("by").orEmpty(),
                at = Instant.parse(s("at")),
                pullRequest = s("pull_request"),
                inbox = s("inbox"),
                findings = (o["findings"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
            )
        }
    }
}
