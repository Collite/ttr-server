// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.mssql.connection

import com.typesafe.config.Config
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-`connection_id` JDBC connection pool registry. Pools open lazily on
 * the first request that targets them — a Worker boot with five configured
 * connections shouldn't pre-open five DB sessions if only one is in use.
 *
 * Configuration shape (HOCON `connections` block in application.conf):
 *
 *     connections {
 *       mssql-finance {
 *         host = "..."
 *         port = 1433
 *         database = "..."
 *         username = ${?MSSQL_FIN_USER}
 *         password = ${?MSSQL_FIN_PASSWORD}
 *         max-pool-size = 10
 *       }
 *     }
 *
 * Each pool builds a `jdbc:sqlserver://host:port;databaseName=db;...` URL and
 * reads username/password from the entry. v1 expects production overlays to
 * fill secrets via env vars (`${?MSSQL_FIN_PASSWORD}`).
 */
class ConnectionPoolManager(
    private val configs: Map<String, ConnectionConfig>,
) : AutoCloseable {
    private val pools = ConcurrentHashMap<String, HikariDataSource>()
    private val lastProbe = ConcurrentHashMap<String, ProbeResult>()

    val supportedConnections: Set<String> = configs.keys

    /**
     * Issue #57 Phase B — per-connection identity exposed for advertisement via the
     * worker's `GetCapabilities` response. The dispatcher uses this to map logical
     * TableScan qnames to the engine-side database / default schema each connection
     * is bound to.
     */
    fun connectionDetails(): List<ConnectionConfig> = configs.values.toList()

    /** Acquires a connection from the pool for [connectionId], opening the pool on first use. */
    fun acquire(connectionId: String): Connection {
        val cfg = configs[connectionId] ?: throw UnknownConnectionException(connectionId)
        val pool = pools.computeIfAbsent(connectionId) { id -> openPool(id, cfg) }
        return pool.connection
    }

    fun poolStats(): Map<String, PoolStats> =
        pools.mapValues { (_, ds) ->
            val mx = ds.hikariPoolMXBean
            if (mx == null) {
                PoolStats(active = 0, idle = 0, max = ds.maximumPoolSize, awaiting = 0)
            } else {
                PoolStats(
                    active = mx.activeConnections,
                    idle = mx.idleConnections,
                    max = ds.maximumPoolSize,
                    awaiting = mx.threadsAwaitingConnection,
                )
            }
        }

    /** True once at least one pool has been opened (i.e. served at least one acquire). */
    fun anyPoolOpen(): Boolean = pools.isNotEmpty()

    /**
     * Run `SELECT 1` against [connectionId]'s pool to verify the worker can actually reach
     * its DB. Opens the pool if not yet open. Probe failures are recorded in [lastProbe] but
     * never thrown — boot, GetStatus, and Execute all continue. The result is what surfaces
     * in GetStatus.connections so an operator can see "configured but unreachable" without
     * grepping logs.
     */
    fun probe(connectionId: String): ProbeResult {
        val cfg = configs[connectionId]
        val now = Instant.now()
        if (cfg == null) {
            val r = ProbeResult(connectionId, connected = false, lastError = "unknown connection_id", lastProbed = now)
            lastProbe[connectionId] = r
            return r
        }
        val result =
            try {
                acquire(connectionId).use { conn ->
                    conn.createStatement().use { st ->
                        st.queryTimeout = 5
                        st.executeQuery("SELECT 1").use { rs -> rs.next() }
                    }
                }
                ProbeResult(connectionId, connected = true, lastError = "", lastProbed = now)
            } catch (t: Throwable) {
                ProbeResult(
                    connectionId,
                    connected = false,
                    lastError = t.message ?: t.javaClass.simpleName,
                    lastProbed = now,
                )
            }
        lastProbe[connectionId] = result
        return result
    }

    /** Probe every configured connection. Returns one [ProbeResult] per `connection_id`. */
    fun probeAll(): List<ProbeResult> = configs.keys.map { probe(it) }

    /** Latest cached probe result, or null when [connectionId] has never been probed. */
    fun lastProbe(connectionId: String): ProbeResult? = lastProbe[connectionId]

    /**
     * Store a [ProbeResult] without actually running a probe. Used by tests (to inject a
     * "connected" state without a real DB) and reserved for future poller wiring.
     */
    fun recordProbeResult(result: ProbeResult) {
        lastProbe[result.connectionId] = result
    }

    data class ProbeResult(
        val connectionId: String,
        val connected: Boolean,
        val lastError: String,
        val lastProbed: Instant,
    )

    override fun close() {
        pools.values.forEach { runCatching { it.close() } }
        pools.clear()
    }

    private fun openPool(
        id: String,
        cfg: ConnectionConfig,
    ): HikariDataSource {
        log.info("Opening JDBC pool for connection_id={}", id)
        val hc =
            HikariConfig().apply {
                jdbcUrl = cfg.jdbcUrl
                username = cfg.username
                password = cfg.password
                maximumPoolSize = cfg.maxPoolSize
                connectionTimeout = cfg.connectionTimeoutMillis
                idleTimeout = cfg.idleTimeoutMillis
                maxLifetime = cfg.maxLifetimeMillis
                isReadOnly = cfg.readOnly
                poolName = "mssql-worker-$id"
            }
        return HikariDataSource(hc)
    }

    class UnknownConnectionException(
        val connectionId: String,
    ) : RuntimeException("No JDBC pool configured for connection_id='$connectionId'")

    companion object {
        private val log = LoggerFactory.getLogger(ConnectionPoolManager::class.java)

        fun fromConfig(config: Config): ConnectionPoolManager {
            if (!config.hasPath("connections")) return ConnectionPoolManager(emptyMap())
            val root = config.getConfig("connections")
            // A connection block whose `host` is absent/blank is INERT — skipped, not an error.
            // This lets a shipped, env-driven template block (e.g. `mssql-default { host = ${?MSSQL_DB_HOST} … }`)
            // stay dormant when the env is unset (fixture deployments boot with zero connections) and
            // activate only where an overlay supplies MSSQL_DB_HOST/… (charon/architecture §6).
            val map =
                buildMap {
                    for (id in root.root().keys) {
                        val entry = root.getConfig(id)
                        val host = if (entry.hasPath("host")) entry.getString("host").trim() else ""
                        if (host.isEmpty()) {
                            log.info(
                                "Skipping connection '{}' — no host configured (env-driven template left inert).",
                                id,
                            )
                            continue
                        }
                        put(id, ConnectionConfig.fromConfig(id, entry))
                    }
                }
            return ConnectionPoolManager(map)
        }
    }
}

data class ConnectionConfig(
    val id: String,
    val jdbcUrl: String,
    val username: String,
    val password: String,
    // Engine-side database the JDBC URL targets. Required for the dispatcher's
    // concretization pass (issue #57 Phase B) — workers advertise this via
    // GetCapabilities.connections so the dispatcher can rewrite logical
    // `<namespace>.<table>` refs to the worker's actual database namespace.
    val database: String,
    // Engine default schema (MSSQL: "dbo"). Empty = engine default.
    val defaultSchema: String = "dbo",
    val maxPoolSize: Int = 10,
    val connectionTimeoutMillis: Long = 10_000,
    val idleTimeoutMillis: Long = 600_000,
    val maxLifetimeMillis: Long = 1_800_000,
    val readOnly: Boolean = true,
) {
    companion object {
        fun fromConfig(
            id: String,
            entry: Config,
        ): ConnectionConfig {
            val host = entry.getString("host")
            val port = if (entry.hasPath("port")) entry.getInt("port") else 1433
            val database = entry.getString("database")
            val encrypt = if (entry.hasPath("encrypt")) entry.getBoolean("encrypt") else false
            val trust =
                if (entry.hasPath(
                        "trust-server-certificate",
                    )
                ) {
                    entry.getBoolean("trust-server-certificate")
                } else {
                    true
                }
            val builtJdbcUrl =
                "jdbc:sqlserver://$host:$port;databaseName=$database;" +
                    "encrypt=$encrypt;trustServerCertificate=$trust"
            val effectiveJdbcUrl = if (entry.hasPath("jdbc-url")) entry.getString("jdbc-url") else builtJdbcUrl
            // Issue #57 Phase B — workers advertise `database` so the dispatcher can
            // concretize logical qnames. If a user provides their own `jdbc-url` AND a
            // `database` config key, fail fast on a mismatch — silent drift here would
            // produce SQL that targets a different physical DB than the dispatcher
            // believes the worker serves.
            if (entry.hasPath("jdbc-url")) {
                val urlDb = extractDatabaseName(effectiveJdbcUrl)
                require(urlDb == null || urlDb.equals(database, ignoreCase = true)) {
                    "ConnectionConfig[$id]: jdbc-url databaseName=$urlDb does not match database=$database; " +
                        "align the two so the worker's advertised database matches the JDBC URL it connects with."
                }
            }
            return ConnectionConfig(
                id = id,
                jdbcUrl = effectiveJdbcUrl,
                username = entry.getString("username"),
                password = entry.getString("password"),
                database = database,
                defaultSchema = if (entry.hasPath("default-schema")) entry.getString("default-schema") else "dbo",
                maxPoolSize = if (entry.hasPath("max-pool-size")) entry.getInt("max-pool-size") else 10,
                connectionTimeoutMillis =
                    if (entry.hasPath("connection-timeout-millis")) {
                        entry.getLong("connection-timeout-millis")
                    } else {
                        10_000
                    },
                idleTimeoutMillis =
                    if (entry.hasPath(
                            "idle-timeout-millis",
                        )
                    ) {
                        entry.getLong("idle-timeout-millis")
                    } else {
                        600_000
                    },
                maxLifetimeMillis =
                    if (entry.hasPath(
                            "max-lifetime-millis",
                        )
                    ) {
                        entry.getLong("max-lifetime-millis")
                    } else {
                        1_800_000
                    },
                readOnly = if (entry.hasPath("read-only")) entry.getBoolean("read-only") else true,
            )
        }

        /**
         * Extract the `databaseName=…` value from an MSSQL JDBC URL, case-insensitively.
         * Returns null when the URL doesn't carry one (the driver then falls back to the
         * server's default DB).
         */
        private val databaseNamePattern = Regex("""(?i)\bdatabaseName=([^;]+)""")

        private fun extractDatabaseName(jdbcUrl: String): String? =
            databaseNamePattern
                .find(jdbcUrl)
                ?.groupValues
                ?.get(1)
    }
}

data class PoolStats(
    val active: Int,
    val idle: Int,
    val max: Int,
    val awaiting: Int,
)
