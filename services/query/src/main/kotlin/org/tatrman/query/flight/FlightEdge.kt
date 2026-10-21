// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.arrow.flight.auth2.CallHeaderAuthenticator
import org.apache.arrow.flight.CallStatus
import org.apache.arrow.flight.FlightServer
import org.apache.arrow.flight.FlightServerMiddleware
import org.apache.arrow.flight.Location
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.RootAllocator
import org.slf4j.LoggerFactory
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
 * BI (contracts C-1, C-8) — the Flight SQL edge's server: [TtrFlightSqlProducer] on [FlightConfig.port], bound to
 * [FlightConfig.bind], behind [authenticator]. Started and stopped with the query service's Ktor application; owns
 * the producer and the Arrow allocator.
 */
class FlightEdge(
    private val config: FlightConfig,
    private val producer: TtrFlightSqlProducer,
    private val allocator: BufferAllocator,
    private val authenticator: CallHeaderAuthenticator,
    private val middleware: List<MiddlewareBinding<*>> = emptyList(),
    private val closeables: List<AutoCloseable> = emptyList(),
) : AutoCloseable {
    @Volatile private var server: FlightServer? = null

    fun start(): FlightEdge {
        server =
            FlightServer
                .builder(allocator, Location.forGrpcInsecure(config.bind, config.port), producer)
                .headerAuthenticator(authenticator)
                .apply { middleware.forEach { it.install(this) } }
                .build()
                .start()
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

        /** `/status`'s `flight` block when the edge is not configured. */
        val DISABLED_STATUS: JsonObject = buildJsonObject { put("enabled", false) }

        /**
         * The authenticator the edge uses until a verifying one is configured: every call is refused. The edge must
         * never answer an unverified caller — the door behind it trusts the identity it is handed.
         */
        val REFUSE_ALL =
            CallHeaderAuthenticator {
                throw CallStatus.UNAUTHENTICATED
                    .withDescription("this Flight edge has no authenticator configured")
                    .toRuntimeException()
            }

        /** The edge with its production collaborators: the loopback runner, Veles' catalog and the schema probe. */
        fun create(
            config: FlightConfig,
            runner: StatementRunner,
            veles: VelesCatalogClient,
            sessions: SessionResolver,
            authenticator: CallHeaderAuthenticator,
            middleware: List<MiddlewareBinding<*>> = emptyList(),
            outcomes: StatementListener = StatementListener { },
            openTelemetry: OpenTelemetry = GlobalOpenTelemetry.get(),
            closeables: List<AutoCloseable> = emptyList(),
        ): FlightEdge {
            val allocator = RootAllocator()
            val catalog = CatalogSource(veles, RunnerSchemaProbe(runner, allocator), config.catalogTtlSeconds)
            val producer =
                TtrFlightSqlProducer(
                    runner = runner,
                    catalog = catalog,
                    sessions = sessions,
                    allocator = allocator,
                    config = config,
                    outcomes = outcomes,
                    openTelemetry = openTelemetry,
                )
            return FlightEdge(config, producer, allocator, authenticator, middleware, closeables)
        }
    }
}
