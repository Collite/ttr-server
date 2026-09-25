// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.veles.grpc.MetadataServiceImpl
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.veles.parse.QueryParseState
import org.tatrman.veles.parse.QueryParseWorker
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.search.SearchAlgorithmRegistry
import org.tatrman.ttr.metadata.search.SearchIndexHolder
import org.tatrman.ttr.metadata.search.all.AllAlgorithm
import org.tatrman.ttr.metadata.search.keyword.KeywordAlgorithm
import org.tatrman.ttr.metadata.search.keyword.StopWords
import org.tatrman.ttr.metadata.search.keyword.Tokenizer
import org.tatrman.ttr.metadata.search.regex.RegexAlgorithm
import org.tatrman.ttr.metadata.search.substring.SubstringAlgorithm
import org.tatrman.ttr.metadata.source.ClasspathStorage
import org.tatrman.ttr.metadata.refresh.MetadataRefresher
import org.tatrman.veles.refresh.RefreshScheduler
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FallbackSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.git.GitArchiveStorage
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.logModelLoadIssues
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.ModelStorage
import org.tatrman.ttr.metadata.source.SourceSnapshot
import org.tatrman.veles.export.metadataExportRoutes
import org.tatrman.veles.read.velesReadRoutes
// YamlImportSource was removed in Phase 2.1 — kantheon is TTR-only (see buildSourceFromConfig).
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.util.concurrent.TimeUnit
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.Dispatchers
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.installKtorServerBase
import java.nio.file.Path

private val log = LoggerFactory.getLogger("org.tatrman.veles.Application")

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, "metadata", 7203)
    KtorServerBootstrap.createServer(serverConfig) { module(config) }.start(wait = true)
}

fun Application.module(config: Config) {
    installKtorServerBase(KtorConfigFactory.fromConfig(config, "metadata", 7203))

    // OTel SDK + tracer for the search RPC. Other RPCs gain spans incrementally;
    // for now only Search is instrumented (search-tuning is the immediate need).
    val otel: OpenTelemetry =
        createOpenTelemetrySdk(
            OtelEndpointConfig(
                serviceName = "metadata",
                protocol = System.getenv("METADATA_OTEL_PROTOCOL") ?: "grpc",
            ),
            enabled = config.hasPath("telemetry.enabled") && config.getBoolean("telemetry.enabled"),
        )
    val tracer = otel.getTracer("org.tatrman.veles")

    // Phase 09 B4 / DF-M16 — Prometheus meter registry. The metadata service was the only v1
    // service without `/metrics`; other services scrape from a comparable mount point.
    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    val registry = MetadataRegistry()
    val reconciler =
        ModelReconciler(
            descriptor =
                ModelDescriptor(
                    id = config.getString("metadata.descriptor.id"),
                    name = config.getString("metadata.descriptor.name"),
                    description = config.getString("metadata.descriptor.description"),
                ),
        )

    // Initial load — synchronous block on startup. The plan's polled refresher
    // (Section G follow-up) replaces this with a coroutine that re-runs the
    // load on schedule.
    val sourceSlots = buildSources(config)
    val refresher =
        MetadataRefresher(
            sources = sourceSlots.map { it.source },
            sourceIds = sourceSlots.map { it.id },
            reconciler = reconciler,
            registry = registry,
        )
    launch(Dispatchers.IO) {
        if (sourceSlots.isEmpty()) {
            log.warn("No sources configured — metadata service will report not-ready")
            return@launch
        }
        val snapshots = sourceSlots.map { runCatching { it.source.load() }.getOrElse { emptySnapshotForError(it) } }
        refresher.recordInitial(snapshots)
        val result = reconciler.reconcile(snapshots)
        val graph = withContext(Dispatchers.Default) { ModelGraph.build(result.model) }
        registry.swap(result.model, graph, lastWarnings = result.warnings + result.errors)
        log.info(
            "Metadata initial snapshot loaded: version={} objects={} warnings={} errors={}",
            result.model.version.value,
            graph.size(),
            result.warnings.size,
            result.errors.size,
        )
        log.logModelLoadIssues(result.errors, result.warnings)
    }

    // Search feature — algorithm registry + per-language indexes. Holder
    // listens for snapshot swaps and rebuilds indexes off the request path.
    val supportedLanguages = listOf("cs", "en", "de", "sk", "hu")
    val stopWords = StopWords(supportedLanguages)
    val tokenizer = Tokenizer(stopWords)
    val substring = SubstringAlgorithm()
    val keyword = KeywordAlgorithm(stopWords)
    val regex = RegexAlgorithm()
    val searchRegistry =
        SearchAlgorithmRegistry(
            mapOf(
                substring.name to substring,
                keyword.name to keyword,
                regex.name to regex,
            ),
        )
    val searchIndexHolder = SearchIndexHolder(searchRegistry, supportedLanguages)
    val all = AllAlgorithm(searchRegistry, searchIndexHolder)
    val searchRegistryWithAll =
        SearchAlgorithmRegistry(
            searchRegistry
                .all()
                .associateBy { it.name } + (all.name to all),
        )
    registry.addListener { snapshot -> searchIndexHolder.rebuild(snapshot) }

    // Section F — async query-parse worker. On every model swap, re-parse each stored query
    // against the new model in the background; the gRPC surface reads the live status from here.
    val parseState = QueryParseState()
    val parseWorker = QueryParseWorker()
    registry.addListener { snapshot ->
        parseState.reset(snapshot.model.version.value, snapshot.model.queries.keys)
        parseWorker.parseAll(snapshot.model, parseState)
    }

    val service =
        MetadataServiceImpl(
            registry = registry,
            searchRegistry = searchRegistryWithAll,
            searchIndexHolder = searchIndexHolder,
            tracer = tracer,
            parseState = parseState,
            refresher = refresher,
        )

    // Phase 07 B3 — RefreshScheduler (off by default; interval-seconds > 0 enables).
    val refreshIntervalSeconds =
        if (config.hasPath("metadata.refresh-scheduler.interval-seconds")) {
            config.getLong("metadata.refresh-scheduler.interval-seconds")
        } else {
            0L
        }
    val refreshSchedulerJob =
        if (refreshIntervalSeconds > 0) {
            RefreshScheduler(refresher, java.time.Duration.ofSeconds(refreshIntervalSeconds)).start()
        } else {
            null
        }
    val grpcPort = config.getInt("grpc.port")
    val maxMessageSize = config.getInt("grpc.max-message-size-bytes")
    val reflectionEnabled =
        config.hasPath("grpc.reflection-enabled") && config.getBoolean("grpc.reflection-enabled")
    val grpcServer =
        NettyServerBuilder
            .forPort(grpcPort)
            // Tolerate clients' keepalive (30s pings, incl. idle) instead of GOAWAY too_many_pings.
            .permitKeepAliveTime(20, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(true)
            .maxInboundMessageSize(maxMessageSize)
            .addService(service)
            .apply { if (reflectionEnabled) addService(ProtoReflectionServiceV1.newInstance()) }
            .build()
    launch {
        grpcServer.start()
        log.info("Metadata gRPC server started on port {} (reflection={})", grpcPort, reflectionEnabled)
        grpcServer.awaitTermination()
    }

    // Phase 09 B4 / DF-M19 — admin route gate. Honest v1 scope: a config-driven shared-secret
    // header check (`X-Admin-Token` must match `metadata.admin.token`). A full Keycloak JWT +
    // role-claim path needs Ktor `authentication-jwt` plus a JWKS-aware verifier setup that
    // doesn't yet exist on the Ktor side of the platform (Phase 04 B2's "admin role" lives at
    // the gRPC layer via PipelineContext.auth_roles, not HTTP). Tracked as `DF-M19-JWT-GATE`
    // for the upgrade when JWT-on-Ktor infra lands.
    //
    // If the config has no admin token set, the gate refuses ALL admin requests (fail-closed)
    // and logs a startup warning — better than accidentally serving the route unguarded.
    val configuredAdminToken: String? =
        if (config.hasPath("metadata.admin.token")) {
            config.getString("metadata.admin.token").ifBlank { null }
        } else {
            null
        }
    if (configuredAdminToken == null) {
        log.warn(
            "metadata.admin.token is not configured — `/admin/*` routes will reject every request " +
                "(fail-closed). Set the token (or use ${'$'}{?ENVVAR}) to enable admin operations.",
        )
    }

    routing {
        get("/health") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/ready") {
            val snap = registry.read()
            if (snap == null) {
                call.respond(
                    io.ktor.http.HttpStatusCode.ServiceUnavailable,
                    buildJsonObject { put("status", "NOT_READY") },
                )
            } else {
                call.respond(
                    buildJsonObject {
                        put("status", "UP")
                        put("model_version", snap.model.version.value)
                    },
                )
            }
        }
        get("/status") {
            val snap = registry.read()
            call.respond(
                buildJsonObject {
                    put("service", "metadata")
                    put("model_loaded", snap != null)
                    put("model_version", snap?.model?.version?.value ?: "")
                    put("object_count", snap?.graph?.size() ?: 0)
                    put("queries_total", snap?.model?.queries?.size ?: 0)
                },
            )
        }
        // Phase 09 B4 / DF-M16 — Prometheus scrape endpoint.
        get("/metrics") {
            call.respondText(meterRegistry.scrape(), ContentType.Text.Plain)
        }
        // DF-M19 — admin gate (see notes above the `routing` block).
        post("/admin/reload-stop-words") {
            val token = call.request.headers["X-Admin-Token"]
            if (configuredAdminToken == null || token != configuredAdminToken) {
                call.respond(
                    io.ktor.http.HttpStatusCode.Forbidden,
                    buildJsonObject {
                        put("status", "forbidden")
                        put("reason", "admin_token_required")
                    },
                )
                return@post
            }
            stopWords.reload()
            call.respond(
                buildJsonObject {
                    put("status", "ok")
                    putJsonArray("languages") { supportedLanguages.forEach { add(it) } }
                },
            )
        }
        // Unauthenticated, cluster-internal (see DF decision: /refresh has no auth). Forces a
        // full synchronous reload of every metadata source + the atomic model swap. Golem's
        // /v2/refresh orchestrator calls this FIRST, then fans out to the dependent services
        // (translator → query-runner, fuzzy-matcher) which rely on the new model version.
        post("/refresh") {
            val results = refresher.refresh(sourceId = "", force = true)
            val snap = registry.read()
            call.respond(
                buildJsonObject {
                    put("status", JsonPrimitive(if (results.all { it.success }) "ok" else "partial"))
                    put("model_version", JsonPrimitive(snap?.model?.version?.value ?: ""))
                    put("swapped", JsonPrimitive(results.any { it.snapshotSwapped }))
                    putJsonArray("sources") {
                        results.forEach { r ->
                            add(
                                buildJsonObject {
                                    put("source_id", JsonPrimitive(r.sourceId))
                                    put("success", JsonPrimitive(r.success))
                                    if (r.errorMessage.isNotEmpty()) {
                                        put("error", JsonPrimitive(r.errorMessage))
                                    }
                                },
                            )
                        }
                    }
                },
            )
        }
        metadataExportRoutes(registry)
        velesReadRoutes(registry, searchRegistryWithAll, searchIndexHolder)
    }

    monitor.subscribe(ApplicationStopping) {
        log.info("Shutting down metadata gRPC server")
        refreshSchedulerJob?.cancel()
        parseWorker.close()
        grpcServer.shutdown()
    }
}

/** Each configured source paired with its declared id. The id pairs source records to
 *  `RefreshRequest.source_id` and `SourceRefreshResult.source_id` (Phase 07 B2 / DF-M04). */
private data class SourceSlot(
    val id: String,
    val source: ModelSource,
)

private fun buildSources(config: Config): List<SourceSlot> {
    // Phase 2.2 — the built-in stock-roles source always loads first. Its
    // priority is Int.MAX_VALUE so it wins all merge collisions; protected
    // qnames are enforced separately by the reconciler regardless of priority.
    val builtins = listOf(SourceSlot(BuiltinStockSource.SOURCE_ID, BuiltinStockSource()))
    if (!config.hasPath("metadata.sources")) return builtins
    val sourcesConfig = config.getConfig("metadata.sources")
    val configured =
        sourcesConfig.root().keys.mapNotNull { key ->
            if (key == "builtin-stock") return@mapNotNull null
            val sub = sourcesConfig.getConfig(key)
            val id = if (sub.hasPath("id")) sub.getString("id") else key
            val enabled = if (sub.hasPath("enabled")) sub.getBoolean("enabled") else true
            if (!enabled) return@mapNotNull null

            // A source may name another (possibly `enabled = false`) source block as its
            // `fallback`. The two are served mutually-exclusively via FallbackSource — never
            // merged — so the bundled fallback can't resurrect repo-deleted defs. Used for the
            // GitHub model source (primary) with the bundled resources model as fallback.
            val fallbackId = if (sub.hasPath("fallback")) sub.getString("fallback").ifBlank { null } else null
            if (fallbackId == null) {
                return@mapNotNull buildSourceFromConfig(sub, id)?.let { SourceSlot(id, it) }
            }

            val fallbackSource = buildReferencedSource(sourcesConfig, fallbackId)
            val primary = buildSourceFromConfig(sub, id)
            when {
                fallbackSource == null -> {
                    log.warn(
                        "source '{}' references unknown/unbuildable fallback '{}' — using primary only",
                        id,
                        fallbackId,
                    )
                    primary?.let { SourceSlot(id, it) }
                }
                primary != null -> SourceSlot(id, FallbackSource(id, primary, fallbackSource))
                else -> {
                    // Primary couldn't be built (e.g. git `remote-uri` blank in local/CI) — load
                    // the bundled fallback directly so the service still has a model.
                    log.info(
                        "primary source '{}' unconfigured — loading bundled fallback '{}' directly",
                        id,
                        fallbackId,
                    )
                    SourceSlot(id, fallbackSource)
                }
            }
        }
    return builtins + configured
}

/** Build the source named [refId] (matched by block key or `id`), ignoring its `enabled` flag —
 *  used to materialise a block referenced only as another source's `fallback`. */
private fun buildReferencedSource(
    sourcesConfig: Config,
    refId: String,
): ModelSource? {
    val key =
        sourcesConfig.root().keys.firstOrNull { k ->
            val sub = sourcesConfig.getConfig(k)
            k == refId || (if (sub.hasPath("id")) sub.getString("id") else k) == refId
        }
    if (key == null) {
        log.warn("fallback source id '{}' not found among metadata.sources", refId)
        return null
    }
    val sub = sourcesConfig.getConfig(key)
    val id = if (sub.hasPath("id")) sub.getString("id") else key
    return buildSourceFromConfig(sub, id)
}

/** Construct a [ModelSource] from one `metadata.sources.<id>` block (storage × parser-kind),
 *  independent of its `enabled` flag. Returns null when the block is unbuildable (e.g. a git
 *  source with a blank `remote-uri`, or an unknown type/kind). */
private fun buildSourceFromConfig(
    sub: Config,
    id: String,
): ModelSource? {
    val type = if (sub.hasPath("type")) sub.getString("type") else id
    val kind = if (sub.hasPath("kind")) sub.getString("kind") else "ttr"
    val path = if (sub.hasPath("path")) sub.getString("path") else ""
    val priority = if (sub.hasPath("priority")) sub.getInt("priority") else 100

    val storage: ModelStorage =
        when (type) {
            "git" -> {
                // Phase 07 C1 — GitArchiveStorage. Requires `remote-uri`; optional
                // `local-checkout-dir` (defaults to /tmp/metadata-git/<id>), `branch`
                // (defaults to "main"), `subdirectory`, `username` / `password` for
                // HTTPS auth (PAT tokens use username="x-access-token").
                val remoteUri = if (sub.hasPath("remote-uri")) sub.getString("remote-uri") else ""
                if (remoteUri.isBlank()) {
                    log.warn("git source '{}' has no remote-uri; skipping", id)
                    return null
                }
                val checkout =
                    if (sub.hasPath("local-checkout-dir")) {
                        Path.of(sub.getString("local-checkout-dir"))
                    } else {
                        Path.of(System.getProperty("java.io.tmpdir"), "metadata-git", id)
                    }
                GitArchiveStorage(
                    id = id,
                    remoteUri = remoteUri,
                    localCheckoutDir = checkout,
                    branch = if (sub.hasPath("branch")) sub.getString("branch") else "main",
                    subdirectory = if (sub.hasPath("subdirectory")) sub.getString("subdirectory") else "",
                    username = if (sub.hasPath("username")) sub.getString("username") else null,
                    password = if (sub.hasPath("password")) sub.getString("password") else null,
                )
            }
            "filesystem" -> {
                if (path.isBlank()) {
                    log.warn("filesystem source '{}' has no path; skipping", id)
                    return null
                }
                LocalFsStorage(id = id, rootPath = Path.of(path))
            }
            "resources" -> {
                if (path.isBlank()) {
                    log.warn("resources source '{}' has no path; skipping", id)
                    return null
                }
                ClasspathStorage(id = id, resourcePrefix = path)
            }
            else -> {
                log.warn("Unknown source type '{}' for '{}' — skipping", type, id)
                return null
            }
        }

    return when (kind) {
        "ttr" -> FileBasedSource(sourceId = id, priority = priority, storage = storage)
        // Phase 2.1 (fork) — `yaml-legacy` source kind removed: kantheon is TTR-only
        // (`org.tatrman.ttr.*` modeler artifacts, no erp-sql legacy YAML parse path).
        // See docs/implementation/v1/fork/tasks-p2-s2.1-veles.md T2 note.
        else -> {
            log.warn("Unknown source kind '{}' for '{}' — skipping", kind, id)
            null
        }
    }
}

private fun emptySnapshotForError(t: Throwable): SourceSnapshot {
    log.error("Source load failed", t)
    return SourceSnapshot(sourceId = "<errored>", priority = 0, version = "errored")
}
