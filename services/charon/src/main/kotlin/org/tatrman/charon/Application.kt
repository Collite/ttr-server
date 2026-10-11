// SPDX-License-Identifier: Apache-2.0
package org.tatrman.charon

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.tatrman.charon.core.CharonMoveExecutor
import org.tatrman.charon.core.ConnectionRegistry
import org.tatrman.charon.core.ConnectionSecretResolver
import org.tatrman.charon.core.HikariConnectionProvider
import org.tatrman.charon.core.MovePlanner
import org.tatrman.charon.endpoints.GrpcWorkerGatewayFactory
import org.tatrman.charon.endpoints.RedisEndpoint
import org.tatrman.charon.grpc.CharonServiceImpl
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.io.File
import java.nio.file.Path
import java.net.URI
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger("org.tatrman.charon.Application")

/**
 * Bootstrap. Per `EXAMPLES.md` §1b the `App.kt` does only wiring; business
 * logic lives under `core/` and `grpc/`.
 *
 * Stage 1.3 fills in the Redis endpoint (charon/plan.md §3.3 T1–T6):
 *  - [CharonMoveExecutor] now wires both Seaweed (Stage 1.2) and Redis
 *    (Stage 1.3) into the move-pipe seam.
 *  - The Lettuce [RedisClient] / [StatefulRedisConnection] are built at
 *    startup and shut down on `ApplicationStopping`.
 *  - `/metrics` route serves the LLM gateway scrape.
 *  - `/ready` returns a static 200 once the process is up. It does NOT
 *    probe S3/Redis reachability — that is checked lazily at the first
 *    move (per the architecture §7 readiness contract: endpoints
 *    validated lazily rather than gating the pod). The gRPC server
 *    binds before the HTTP server starts, so a bind failure crashes the
 *    process before `/ready` can answer. (review-006 R8.8: KDoc trimmed
 *    to match what the route actually checks.)
 */
fun main() {
    val config = ConfigFactory.load()
    val httpPort = config.getInt("charon.http.port")
    val grpcPort = config.getInt("charon.grpc.port")

    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val s3Client = buildS3Client(config)
    val redisConnection = buildRedisConnection(config)
    Runtime.getRuntime().addShutdownHook(Thread { redisConnection.close() })
    val redisMaxValueBytes = config.getLong("charon.redis.max-value-bytes")
    val redisDefaultTtlSeconds =
        if (config.hasPath("charon.redis.default-ttl-s")) {
            config.getLong("charon.redis.default-ttl-s")
        } else {
            null
        }
    // Phase 2 — the named-connection registry + JDBC provider. The registry is
    // loaded lazily-validated (a broken DB connection does NOT gate the pod —
    // architecture §7 + plan §4 Stage 2.3); a missing file ⇒ blob-only pod.
    val connectionsFile = File(config.getString("charon.connections.path"))
    // CH-D3: connections resolve their `${VAR}` credential tokens through the open
    // ConnectionSecretResolver port — env-var binding by default; a deployment binds a real
    // secret-store adapter (the platform's cz.tatrman:secrets-spi adapter). CH-D8: the adapter
    // is MOUNTED as a jar into charon.connections.plugin-dir (env CHARON_PLUGIN_DIR), loaded via
    // an isolated child classloader — the open image carries no commercial bytes. Empty ⇒ the
    // app classloader only (today's behaviour). A missing/empty connections file ⇒ a blob-only pod.
    // F5 (review-074) require-half: a deployment that MUST use a real store sets
    // require-secret-resolver so a missing/mis-mounted adapter fails the pod closed instead of
    // silently falling back to env binding.
    val pluginDir = config.getString("charon.connections.plugin-dir").takeIf { it.isNotBlank() }?.let { Path.of(it) }
    val requireResolver = config.getBoolean("charon.connections.require-secret-resolver")
    val connectionRegistry =
        ConnectionRegistry.fromFile(
            connectionsFile,
            ConnectionSecretResolver.discover(pluginDir = pluginDir, requireProvider = requireResolver),
        )
    val dbProvider = HikariConnectionProvider()
    Runtime.getRuntime().addShutdownHook(Thread { dbProvider.close() })
    log.info("Charon connection registry: {} connection(s) {}", connectionRegistry.ids().size, connectionRegistry.ids())

    // Phase 3 — worker gateways (POLARS = Polars worker.v1; METIS = metis.v1).
    // Targets are optional; an unset engine isn't wired on this pod.
    val workerFactory =
        GrpcWorkerGatewayFactory(
            polarsTarget =
                if (config.hasPath(
                        "charon.worker.polars-target",
                    )
                ) {
                    config.getString("charon.worker.polars-target")
                } else {
                    null
                },
            metisTarget =
                if (config.hasPath(
                        "charon.worker.metis-target",
                    )
                ) {
                    config.getString("charon.worker.metis-target")
                } else {
                    null
                },
        )
    Runtime.getRuntime().addShutdownHook(Thread { workerFactory.close() })

    val executor =
        CharonMoveExecutor(
            s3Client = s3Client,
            redisConnection = redisConnection,
            redisMaxValueBytes = redisMaxValueBytes,
            registry = meterRegistry,
            redisDefaultTtlSeconds = redisDefaultTtlSeconds,
            connectionRegistry = connectionRegistry,
            dbProvider = dbProvider,
            workerGatewayFactory = workerFactory,
        )
    val service =
        CharonServiceImpl(
            planner = MovePlanner(),
            executor = executor,
        )
    val grpcServer =
        NettyServerBuilder
            .forPort(grpcPort)
            .permitKeepAliveTime(20, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(true)
            .maxInboundMessageSize(33554432) // 32 MiB; matches veles
            .addService(service)
            .build()
    Runtime.getRuntime().addShutdownHook(Thread { grpcServer.shutdownNow() })
    grpcServer.start()
    log.info(
        "Charon gRPC server started on :{} (Stage 1.3; Seaweed + Redis live, others stubbed)",
        grpcPort,
    )

    val serverConfig = KtorConfigFactory.fromConfig(config, "charon", httpPort)
    embeddedServer(
        Netty,
        port = httpPort,
        host = "0.0.0.0",
        module = { module(meterRegistry, connectionRegistry, connectionsFile, serverConfig) },
    ).start(wait = true)
}

private fun buildS3Client(config: Config): S3Client {
    val endpoint = config.getString("charon.s3.endpoint")
    val region = config.getString("charon.s3.region")
    val accessKey =
        if (config.hasPath("charon.s3.access-key")) config.getString("charon.s3.access-key") else ""
    val secretKey =
        if (config.hasPath("charon.s3.secret-key")) config.getString("charon.s3.secret-key") else ""
    val builder =
        S3Client
            .builder()
            .region(Region.of(region))
            .endpointOverride(URI.create(endpoint))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
    if (accessKey.isNotBlank() && secretKey.isNotBlank()) {
        builder.credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)),
        )
    }
    return builder.build()
}

private fun buildRedisConnection(config: Config): StatefulRedisConnection<ByteArray, ByteArray> {
    val url = config.getString("charon.redis.url")
    val client = RedisEndpoint.clientFromUrl(url)
    // `ByteArrayCodec` for binary-safe value access. The Arrow IPC
    // stream lives in the value bytes; the schema fingerprint sidecar
    // is a UTF-8 hex string. `ByteArrayCodec` gives us `<byte[], byte[]>`
    // for K/V — keys are ASCII strings (encoded via [RedisEndpoint.keyOf]);
    // values are raw bytes. This is the only clean way to carry the
    // Arrow IPC stream through Redis.
    val connection =
        client.connect(
            io.lettuce.core.codec
                .ByteArrayCodec(),
        )
    Runtime.getRuntime().addShutdownHook(Thread { client.shutdown() })
    return connection
}

fun Application.module(
    meterRegistry: PrometheusMeterRegistry,
    connectionRegistry: ConnectionRegistry = ConnectionRegistry.of(emptyList()),
    connectionsFile: File? = null,
    serverConfig: KtorServerConfig = KtorConfigFactory.fromConfig(ConfigFactory.load(), "charon", 7250),
) {
    // CH-D4: the shared ktor base installs ContentNegotiation(json) + CORS + CallLogging +
    // ForwardedHeaders — the 9-sibling pattern, replacing charon's hand-rolled install.
    // ContentNegotiation is still the load-bearing piece: without it the JSON probe routes
    // answer HTTP 406 and the kubelet reads a liveness failure → CrashLoopBackOff
    // (HealthRoutesSpec is the regression guard). See EXAMPLES.md §2a.
    installKtorServerBase(serverConfig)
    routing {
        get("/health") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/ready") {
            // Lazily-validated connections: a broken DB connection does NOT
            // make the pod unready (architecture §7 / plan §4 Stage 2.3 —
            // "one broken DB ≠ unready pod"). The registered set is reported
            // as informational degraded-set context.
            call.respond(
                buildJsonObject {
                    put("status", "UP")
                    put("stage", "2.3")
                    putJsonArray("connections") { connectionRegistry.ids().sorted().forEach { add(it) } }
                },
            )
        }
        get("/status") {
            call.respond(
                buildJsonObject {
                    put("service", "charon")
                    put("stage", "2.3")
                    put("endpoints", "seaweed (live), redis (live), db_table (live); worker_df stubbed")
                    putJsonArray("connections") { connectionRegistry.ids().sorted().forEach { add(it) } }
                },
            )
        }
        // Registry reload (cluster-internal; contracts §4). Re-reads the
        // connections file and atomically swaps the live set.
        post("/refresh") {
            val file = connectionsFile
            if (file == null || !file.isFile) {
                call.respond(
                    buildJsonObject {
                        put("status", "no-op")
                        put("reason", "no connections file")
                    },
                )
            } else {
                connectionRegistry.refresh(file.readText())
                call.respond(
                    buildJsonObject {
                        put("status", "reloaded")
                        putJsonArray("connections") { connectionRegistry.ids().sorted().forEach { add(it) } }
                    },
                )
            }
        }
        get("/metrics") {
            call.respondText(meterRegistry.scrape(), ContentType.Text.Plain)
        }
    }
    monitor.subscribe(ApplicationStopping) {
        log.info("Charon shutting down")
    }
}
