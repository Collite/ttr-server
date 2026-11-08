// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.postgres.connection

import com.typesafe.config.Config
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-`connection_id` JDBC connection pool registry for Postgres. Mirrors Mssql's
 * manager 1:1; the engine deltas are the `jdbc:postgresql://…` URL shape, the
 * `public` default schema, and the `requires-tenant-id` flag that gates the RLS
 * session contract (added behaviourally in Stage 1.3 — Stage 1.1 only parses it).
 *
 * Pools open lazily on the first request that targets them — a Worker boot with five
 * configured connections shouldn't pre-open five DB sessions if only one is in use.
 *
 * Configuration shape (HOCON `connections` block in application.conf):
 *
 *     connections {
 *       pg-midas {
 *         host = ${?POSTGRES_PG_MIDAS_HOST}
 *         port = 5432
 *         database = "midas"
 *         default-schema = "public"
 *         username = ${?POSTGRES_PG_MIDAS_USER}      # midas_app_readonly (non-owner → RLS applies)
 *         password = ${?POSTGRES_PG_MIDAS_PASSWORD}
 *         max-pool-size = 10
 *         read-only = true
 *         requires-tenant-id = true
 *       }
 *     }
 *
 * Credentials are NEVER in the request and NEVER hardcoded here — they arrive via env
 * vars from K8s sealed secrets.
 */
class ConnectionPoolManager(
    private val configs: Map<String, ConnectionConfig>,
) : AutoCloseable {
    private val pools = ConcurrentHashMap<String, HikariDataSource>()
    private val lastProbe = ConcurrentHashMap<String, ProbeResult>()

    val supportedConnections: Set<String> = configs.keys

    /**
     * Per-connection identity exposed for advertisement via the worker's `GetCapabilities`
     * response. The dispatcher uses this to map logical TableScan qnames to the engine-side
     * database / default schema each connection is bound to.
     */
    fun connectionDetails(): List<ConnectionConfig> = configs.values.toList()

    /**
     * Whether [connectionId] carries the RLS session contract (`requires-tenant-id = true`).
     * When true, [org.tatrman.worker.postgres.pipeline.ExecutePipeline] must bind the tenant via
     * `SET LOCAL app.tenant_id` inside a transaction and fail closed if no tenant is present.
     * Unknown connection ids return false (the connection_id validation rejects them first).
     */
    fun requiresTenantId(connectionId: String): Boolean = configs[connectionId]?.requiresTenantId ?: false

    /** Row DML is opt-in per declared connection; unknown ids fail closed. */
    fun isReadOnly(connectionId: String): Boolean = configs[connectionId]?.readOnly ?: true

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
        return HikariDataSource(poolConfig(id, cfg))
    }

    internal fun poolConfig(
        id: String,
        cfg: ConnectionConfig,
    ): HikariConfig =
        HikariConfig().apply {
            jdbcUrl = cfg.jdbcUrl
            username = cfg.username
            password = cfg.password
            maximumPoolSize = cfg.maxPoolSize
            connectionTimeout = cfg.connectionTimeoutMillis
            idleTimeout = cfg.idleTimeoutMillis
            maxLifetime = cfg.maxLifetimeMillis
            isReadOnly = cfg.readOnly
            if (!cfg.readOnly) schema = cfg.defaultSchema
            poolName = "postgres-worker-$id"
        }

    class UnknownConnectionException(
        val connectionId: String,
    ) : RuntimeException("No JDBC pool configured for connection_id='$connectionId'")

    companion object {
        private val log = LoggerFactory.getLogger(ConnectionPoolManager::class.java)

        fun fromConfig(config: Config): ConnectionPoolManager {
            if (!config.hasPath("connections")) return ConnectionPoolManager(emptyMap())
            val root = config.getConfig("connections")
            val map =
                root
                    .root()
                    .keys
                    // Pure-env connections (`host = ${?POSTGRES_PG_<NAME>_HOST}` with no default) are
                    // declared in the base conf but ACTIVATED per deployment: an entry whose `host`
                    // resolves to absent/blank (the deployment didn't set that env var) is skipped, so
                    // the base conf can register optional named connections without failing boot in
                    // ConnectionConfig.fromConfig's getString("host"). Connections that carry a default
                    // host (e.g. pg-tpcds) are always present, unchanged.
                    .filter { id ->
                        val entry = root.getConfig(id)
                        entry.hasPath("host") && entry.getString("host").isNotBlank()
                    }.associateWith { id ->
                        ConnectionConfig.fromConfig(id, root.getConfig(id))
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
    // Engine-side database the JDBC URL targets. Advertised via GetCapabilities.connections so
    // the dispatcher can concretize logical `<namespace>.<table>` refs to this database.
    val database: String,
    // Engine default schema (Postgres: "public"). Empty = engine default.
    val defaultSchema: String = "public",
    // Gate for the RLS session contract: when true, every Execute must bind a tenant via
    // `SET LOCAL app.tenant_id` (contracts §2.1; enforced in Stage 1.3). Missing tenant on
    // such a connection fails closed.
    val requiresTenantId: Boolean = false,
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
            val port = if (entry.hasPath("port")) entry.getInt("port") else 5432
            val database = entry.getString("database")
            val builtJdbcUrl = "jdbc:postgresql://$host:$port/$database"
            val effectiveJdbcUrl = if (entry.hasPath("jdbc-url")) entry.getString("jdbc-url") else builtJdbcUrl
            // Workers advertise `database` so the dispatcher can concretize logical qnames. If a
            // user supplies their own `jdbc-url` AND a `database` key, fail fast on a mismatch —
            // silent drift would produce SQL that targets a different physical DB than the
            // dispatcher believes the worker serves.
            if (entry.hasPath("jdbc-url")) {
                val urlDb = extractDatabaseName(effectiveJdbcUrl)
                require(urlDb == null || urlDb.equals(database, ignoreCase = true)) {
                    "ConnectionConfig[$id]: jdbc-url database=$urlDb does not match database=$database; " +
                        "align the two so the worker's advertised database matches the JDBC URL it connects with."
                }
            }
            return ConnectionConfig(
                id = id,
                jdbcUrl = effectiveJdbcUrl,
                username = entry.getString("username"),
                password = entry.getString("password"),
                database = database,
                defaultSchema = if (entry.hasPath("default-schema")) entry.getString("default-schema") else "public",
                requiresTenantId = entry.hasPath("requires-tenant-id") && entry.getBoolean("requires-tenant-id"),
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
         * Extract the database name (the path segment) from a `jdbc:postgresql://host:port/db?…`
         * URL. Returns null when the URL carries no path database (the driver then falls back to
         * the connection's default DB).
         */
        private val databaseNamePattern = Regex("""jdbc:postgresql://[^/]+/([^?;/]+)""")

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
