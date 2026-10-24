// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.veles.grpc.MetadataServiceImpl
import org.tatrman.veles.grpc.PackageIndex
import org.tatrman.ttr.metadata.model.CnSchema
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
import org.tatrman.ttr.metadata.layout.LayoutIndex
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
import org.tatrman.veles.cn.CnSetup
import org.tatrman.veles.cn.PromotionTarget
import org.tatrman.veles.cn.VocabularySource
import org.tatrman.veles.cn.candidateRoutes
import org.tatrman.veles.export.metadataExportRoutes
import org.tatrman.veles.health.SourceLoadBoard
import org.tatrman.veles.health.modelHealthRoutes
import org.tatrman.veles.health.redactCredentials
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
import kotlinx.coroutines.delay
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
    // ttr-server#180 — every configured source's loads, for /ready, /status and /metrics.
    val loadBoard = SourceLoadBoard(meterRegistry)

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
    // ttr-server#111 — every load also records which package each of its files belongs to; the
    // reconciled model keeps no such map, and package scoping must not guess it from the path.
    // The index serves a load once its model swaps in, so it is the registry's FIRST listener.
    val packageIndex = PackageIndex().commitOnSwap(registry)
    // CN-P5 S5.3 — the candidate store (cn contracts §6). Its registered vocabulary versions ride the refresher as one
    // more source (A-CN-94); it is not a configured model source, so it stays off the load board and out of /ready.
    val cn = CnSetup.fromConfig(config) { id -> promotionTarget(config, id) }
    val sourceSlots =
        buildSources(config, packageIndex, loadBoard) +
            listOfNotNull(
                cn.vocabularySource?.let {
                    SourceSlot(
                        VocabularySource.SOURCE_ID,
                        it,
                        "registered vocabulary versions",
                    )
                },
            )
    val refresher =
        MetadataRefresher(
            sources = sourceSlots.map { it.source },
            sourceIds = sourceSlots.map { it.id },
            reconciler = reconciler,
            registry = registry,
        )
    launch(Dispatchers.IO) {
        if (loadBoard.states().isEmpty()) {
            log.error(
                "No model source configured: METADATA_GIT_REMOTE_URI is empty and no other source is enabled. " +
                    "Veles serves no model and /ready answers 503 until one is configured — set " +
                    "METADATA_GIT_REMOTE_URI, METADATA_GIT_BRANCH and METADATA_GIT_SUBDIR.",
            )
        }
        val snapshots = sourceSlots.map { runCatching { it.source.load() }.getOrElse { emptySnapshotForError(it) } }
        refresher.recordInitial(snapshots)
        val result = reconciler.reconcile(snapshots)
        val graph = withContext(Dispatchers.Default) { ModelGraph.build(result.model) }
        // VL V-37: the layouts of this model, from the same loads, in the same swap (the refresher does likewise)
        val layouts = LayoutIndex.build(snapshots, result.model)
        registry.swap(result.model, graph, lastWarnings = result.warnings + result.errors, layouts = layouts)
        log.info(
            "Metadata initial snapshot loaded: version={} objects={} warnings={} errors={}",
            result.model.version.value,
            graph.size(),
            result.warnings.size,
            result.errors.size,
        )
        log.logModelLoadIssues(result.errors, result.warnings)
        retryUntilLoaded(loadBoard, refresher)
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

    // CN-P5 S5.3 T4 — a pending promotion is merged once a load of the promotion source carries its def; that load's
    // version (the commit) is its canon_ref. Off the swap thread: the store is a database.
    cn.service?.let { candidates ->
        registry.addListener { snapshot ->
            val version =
                loadBoard
                    .states()
                    .firstOrNull { it.id == cn.promotionSourceId }
                    ?.version
                    .orEmpty()
            val schema = snapshot.model.schemas["cn"] as? CnSchema ?: return@addListener
            launch(Dispatchers.IO) {
                runCatching { candidates.observe(schema, version) }
                    .onFailure { log.warn("cn candidates: merge check failed: {}", it.message) }
            }
        }
    }

    val service =
        MetadataServiceImpl(
            registry = registry,
            searchRegistry = searchRegistryWithAll,
            searchIndexHolder = searchIndexHolder,
            tracer = tracer,
            parseState = parseState,
            refresher = refresher,
            packageIndex = packageIndex,
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
        // ttr-server#180 — /ready waits for the configured source; /status says what each source did.
        modelHealthRoutes(registry, loadBoard)
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
        velesReadRoutes(registry, searchRegistryWithAll, searchIndexHolder, packageIndex)
        // CN-P5 S5.3 — the candidate store and the vocabulary versions; a registration is in the model at once.
        candidateRoutes(cn.surface) {
            withContext(Dispatchers.IO) { refresher.refresh(sourceId = VocabularySource.SOURCE_ID, force = true) }
        }
    }

    monitor.subscribe(ApplicationStopping) {
        log.info("Shutting down metadata gRPC server")
        refreshSchedulerJob?.cancel()
        parseWorker.close()
        cn.store?.close()
        grpcServer.shutdown()
    }
}

/** Each configured source paired with its declared id. The id pairs source records to
 *  `RefreshRequest.source_id` and `SourceRefreshResult.source_id` (Phase 07 B2 / DF-M04). */
internal data class SourceSlot(
    val id: String,
    val source: ModelSource,
    /** What the source reads, credentials redacted — logged at startup and shown in `/status`. */
    val description: String = "",
)

/**
 * The configured source slots, each recording its loads into [packageIndex] (ttr-server#111) and,
 * apart from the built-in stock roles, into [loadBoard] (ttr-server#180). The wrapping lives here,
 * not at the call site, so no slot can reach the refresher unrecorded.
 */
internal fun buildSources(
    config: Config,
    packageIndex: PackageIndex,
    loadBoard: SourceLoadBoard = SourceLoadBoard(),
): List<SourceSlot> =
    configuredSources(config).map { slot ->
        val recorded =
            if (slot.id == BuiltinStockSource.SOURCE_ID) {
                slot.source
            } else {
                log.info("Model source '{}': {}", slot.id, slot.description)
                loadBoard.recording(slot.id, slot.description, slot.source)
            }
        slot.copy(source = packageIndex.recording(slot.id, recorded))
    }

private fun configuredSources(config: Config): List<SourceSlot> {
    // Phase 2.2 — the built-in stock-roles source always loads first. Its
    // priority is Int.MAX_VALUE so it wins all merge collisions; protected
    // qnames are enforced separately by the reconciler regardless of priority.
    val builtins = listOf(SourceSlot(BuiltinStockSource.SOURCE_ID, BuiltinStockSource(), "built-in stock roles"))
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
            // merged — so a stale fallback can't resurrect defs the primary has deleted.
            // ttr-server#180: no source has a fallback unless one is configured explicitly
            // (`METADATA_GIT_FALLBACK` for the git source); Veles bundles no model of its own.
            val fallbackId = if (sub.hasPath("fallback")) sub.getString("fallback").ifBlank { null } else null
            if (fallbackId == null) {
                return@mapNotNull buildSourceFromConfig(sub, id)?.let { SourceSlot(id, it, describeSource(sub)) }
            }

            val fallbackSub = referencedSourceConfig(sourcesConfig, fallbackId)
            val fallbackSource =
                fallbackSub?.let { fs ->
                    buildSourceFromConfig(fs, if (fs.hasPath("id")) fs.getString("id") else fallbackId)
                }
            val primary = buildSourceFromConfig(sub, id)
            when {
                fallbackSource == null -> {
                    log.warn(
                        "source '{}' references unknown/unbuildable fallback '{}' — using primary only",
                        id,
                        fallbackId,
                    )
                    primary?.let { SourceSlot(id, it, describeSource(sub)) }
                }
                primary != null ->
                    SourceSlot(
                        id,
                        FallbackSource(id, primary, fallbackSource),
                        "${describeSource(sub)}; fallback '$fallbackId': ${describeSource(fallbackSub)}",
                    )
                else -> {
                    // Primary unbuildable (e.g. a blank git `remote-uri`) and a fallback was asked
                    // for explicitly — serve the fallback as the source.
                    log.info(
                        "primary source '{}' unconfigured — loading its configured fallback '{}' directly",
                        id,
                        fallbackId,
                    )
                    SourceSlot(id, fallbackSource, "fallback '$fallbackId': ${describeSource(fallbackSub)}")
                }
            }
        }
    return builtins + configured
}

/** The block named [refId] (matched by block key or `id`) — used to materialise a block
 *  referenced only as another source's `fallback`, whatever its own `enabled` flag says. */
private fun referencedSourceConfig(
    sourcesConfig: Config,
    refId: String,
): Config? {
    val key =
        sourcesConfig.root().keys.firstOrNull { k ->
            val sub = sourcesConfig.getConfig(k)
            k == refId || (if (sub.hasPath("id")) sub.getString("id") else k) == refId
        }
    if (key == null) {
        log.warn("fallback source id '{}' not found among metadata.sources", refId)
        return null
    }
    return sourcesConfig.getConfig(key)
}

/** One line saying what a `metadata.sources.<id>` block reads, with any URI credentials redacted. */
private fun describeSource(sub: Config): String {
    fun str(path: String) = if (sub.hasPath(path)) sub.getString(path) else ""
    return when (val type = str("type")) {
        "git" -> {
            val branch = str("branch").ifBlank { "main" }
            val subdirectory = str("subdirectory").ifBlank { "/" }
            "git ${redactCredentials(str("remote-uri"))} (branch $branch, sub-path $subdirectory)"
        }
        "filesystem" -> "filesystem ${str("path")}"
        "resources" -> "classpath ${str("path")}"
        else -> type
    }
}

/**
 * ttr-server#180 — until every configured source has loaded once, retry the ones that have not,
 * backing off from 5 s to 60 s. Without this a source that failed at boot (the network not up
 * yet, a token not mounted yet) would wait for the next scheduled refresh — five minutes by
 * default, or never with the scheduler off — while `/ready` answers 503.
 */
private suspend fun retryUntilLoaded(
    loadBoard: SourceLoadBoard,
    refresher: MetadataRefresher,
) {
    var backoffSeconds = 5L
    while (loadBoard.notLoaded().isNotEmpty()) {
        val pending = loadBoard.notLoaded()
        log.warn(
            "Model source(s) {} not loaded yet — retrying in {}s (see /status for the last error)",
            pending,
            backoffSeconds,
        )
        delay(backoffSeconds * 1000)
        pending.forEach { id -> runCatching { refresher.refresh(sourceId = id, force = true) } }
        backoffSeconds = minOf(backoffSeconds * 2, 60L)
    }
}

/** Construct a [ModelSource] from one `metadata.sources.<id>` block (storage × parser-kind),
 *  independent of its `enabled` flag. Returns null when the block is unbuildable (e.g. a git
 *  source with a blank `remote-uri`, or an unknown type/kind). */
private fun buildSourceFromConfig(
    sub: Config,
    id: String,
): ModelSource? {
    val kind = if (sub.hasPath("kind")) sub.getString("kind") else "ttr"
    val priority = if (sub.hasPath("priority")) sub.getInt("priority") else 100
    val storage = buildStorage(sub, id) ?: return null

    return when (kind) {
        // VL V-37: Veles serves the layouts committed next to the model — a source collects them only when asked
        "ttr" -> FileBasedSource(sourceId = id, priority = priority, storage = storage, layouts = true)
        // Phase 2.1 (fork) — `yaml-legacy` source kind removed: kantheon is TTR-only
        // (`org.tatrman.ttr.*` modeler artifacts, no erp-sql legacy YAML parse path).
        // See docs/implementation/v1/fork/tasks-p2-s2.1-veles.md T2 note.
        else -> {
            log.warn("Unknown source kind '{}' for '{}' — skipping", kind, id)
            null
        }
    }
}

/**
 * CN-P5 S5.3 (A-CN-93) — the promotion source: the `metadata.sources` block named [sourceId], read through a storage
 * of its own (a git source clones into `<checkout>-promotion`), so a promotion never reads a tree the refresher is
 * pulling. Null when no such block builds.
 */
internal fun promotionTarget(
    config: Config,
    sourceId: String,
): PromotionTarget? {
    if (!config.hasPath("metadata.sources")) return null
    val sub = referencedSourceConfig(config.getConfig("metadata.sources"), sourceId) ?: return null
    val storage = buildStorage(sub, sourceId, checkoutSuffix = "-promotion") ?: return null

    fun str(path: String) = if (sub.hasPath(path)) sub.getString(path) else ""
    val subdirectory = str("subdirectory")
    return PromotionTarget(
        storage = storage,
        remoteUri = str("remote-uri"),
        branch = str("branch").ifBlank { "main" },
        subdirectory = subdirectory,
        localRoot =
            (storage as? GitArchiveStorage)?.let {
                gitCheckoutDir(sub, sourceId, "-promotion").let { dir ->
                    if (subdirectory.isBlank()) dir else dir.resolve(subdirectory.trim('/'))
                }
            },
    )
}

/** Where a git source block clones: `local-checkout-dir` (plus [suffix]), else `<tmp>/metadata-git/<id><suffix>`. */
private fun gitCheckoutDir(
    sub: Config,
    id: String,
    suffix: String,
): Path =
    if (sub.hasPath("local-checkout-dir")) {
        Path.of(sub.getString("local-checkout-dir") + suffix)
    } else {
        Path.of(System.getProperty("java.io.tmpdir"), "metadata-git", id + suffix)
    }

/** The storage one `metadata.sources.<id>` block reads; null when the block is unbuildable. */
private fun buildStorage(
    sub: Config,
    id: String,
    checkoutSuffix: String = "",
): ModelStorage? {
    val type = if (sub.hasPath("type")) sub.getString("type") else id
    val path = if (sub.hasPath("path")) sub.getString("path") else ""
    return when (type) {
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
            val checkout = gitCheckoutDir(sub, id, checkoutSuffix)
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
}

private fun emptySnapshotForError(t: Throwable): SourceSnapshot {
    log.error("Source load failed", t)
    return SourceSnapshot(sourceId = "<errored>", priority = 0, version = "errored")
}
