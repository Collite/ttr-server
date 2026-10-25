// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.arrow.flight.FlightServer
import org.apache.arrow.flight.FlightServerMiddleware
import org.apache.arrow.flight.Location
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.RootAllocator
import org.slf4j.LoggerFactory
import org.tatrman.query.world.Worlds
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A server middleware the edge installs, with the key its producer reads it back by. */
class MiddlewareBinding<T : FlightServerMiddleware>(
    val key: FlightServerMiddleware.Key<T>,
    val factory: FlightServerMiddleware.Factory<T>,
) {
    internal fun install(builder: FlightServer.Builder) {
        builder.middleware(key, factory)
    }
}

/**
 * BI (contracts C-1, C-2, C-8) — the Flight SQL edge's server: [TtrFlightSqlProducer] on [FlightConfig.port], bound
 * to [FlightConfig.bind], behind [middleware]. Started and stopped with the query service's Ktor application; owns
 * the producer and the Arrow allocator.
 *
 * Authentication is [IdentityWorldMiddleware]'s, installed by [create]: the edge built for production cannot be made
 * without it. The bare constructor is the test seam, where a spec stands a fixed session in for a verified one.
 */
class FlightEdge(
    private val config: FlightConfig,
    private val producer: TtrFlightSqlProducer,
    private val allocator: BufferAllocator,
    private val middleware: List<MiddlewareBinding<*>> = emptyList(),
    private val closeables: List<AutoCloseable> = emptyList(),
) : AutoCloseable {
    @Volatile private var server: FlightServer? = null

    /**
     * Binds and starts the listener. A listener that cannot bind (the port taken, an address the host does not have)
     * fails here, naming the address: the query service does not start without the edge it was configured with.
     */
    fun start(): FlightEdge {
        val built =
            FlightServer
                .builder(allocator, Location.forGrpcInsecure(config.bind, config.port), producer)
                .apply { middleware.forEach { it.install(this) } }
                .build()
        try {
            built.start()
        } catch (e: IOException) {
            runCatching { built.close() }
            throw IllegalStateException(
                "Flight SQL edge could not listen on ${config.bind}:${config.port}: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
        server = built
        log.info("Flight SQL edge started on {}:{}", config.bind, port)
        return this
    }

    /** The bound port (the configured one, or the OS's pick for port 0). */
    val port: Int get() = server?.port ?: config.port

    val running: Boolean get() = server != null

    /** `/status`'s `flight` block. */
    fun status(): JsonObject =
        buildJsonObject {
            put("enabled", true)
            put("running", running)
            put("port", port)
            put("bind", config.bind)
            put("statements", producer.statementCount)
        }

    override fun close() {
        val running = server
        server = null
        if (running != null) {
            running.shutdown()
            if (!running.awaitTermination(5, TimeUnit.SECONDS)) running.close()
            log.info("Flight SQL edge stopped")
        }
        runCatching { producer.close() }
        closeables.forEach { c -> runCatching { c.close() } }
        // A leak here is a bug worth seeing in the log, never a reason to fail the shutdown.
        runCatching {
            allocator.close()
        }.onFailure { log.warn("Flight edge: allocator closed with outstanding buffers: {}", it.message) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(FlightEdge::class.java)

        /** `/ready`: the door is ready, and an enabled edge ([edge] non-null) is listening (C-1). */
        fun ready(
            doorReady: Boolean,
            edge: FlightEdge?,
        ): Boolean = doorReady && (edge?.running ?: true)

        /** `/status`'s `flight` block when the edge is not configured. */
        val DISABLED_STATUS: JsonObject = buildJsonObject { put("enabled", false) }

        /**
         * The edge with its production collaborators: the loopback runner, the metadata service's catalog and the schema probe, every
         * call authenticated and placed in its world by [identity] — the door behind the edge trusts the identity it
         * is handed, so the edge never answers an unverified caller.
         */
        fun create(
            config: FlightConfig,
            runner: StatementRunner,
            metadata: MetadataCatalogClient,
            identity: IdentityWorldMiddleware.Factory,
            worlds: Worlds = Worlds.NONE,
            outcomes: StatementListener = StatementListener { },
            openTelemetry: OpenTelemetry = GlobalOpenTelemetry.get(),
            closeables: List<AutoCloseable> = emptyList(),
        ): FlightEdge {
            val allocator = RootAllocator()
            val catalog = CatalogSource(metadata, RunnerSchemaProbe(runner, allocator), config.catalogTtlSeconds)
            val producer =
                TtrFlightSqlProducer(
                    runner = runner,
                    catalog = catalog,
                    sessions = IdentityWorldMiddleware.SESSIONS,
                    allocator = allocator,
                    config = config,
                    outcomes = outcomes,
                    worlds = worlds,
                    openTelemetry = openTelemetry,
                )
            return FlightEdge(
                config,
                producer,
                allocator,
                listOf(IdentityWorldMiddleware.binding(identity)),
                closeables,
            )
        }
    }
}
