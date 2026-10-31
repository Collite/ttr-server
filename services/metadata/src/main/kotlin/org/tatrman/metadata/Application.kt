// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.tatrman.metadata.read.GitLexiconSource
import org.tatrman.metadata.read.GitTemplateSource
import org.tatrman.metadata.grpc.MetadataServiceImpl
import org.tatrman.metadata.grpc.PackageIndex
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.metadata.parse.QueryParseState
import org.tatrman.metadata.parse.QueryParseWorker
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
import org.tatrman.metadata.refresh.putOps
import io.ktor.http.HttpStatusCode
import org.tatrman.metadata.refresh.HttpTreeStorage
import org.tatrman.metadata.refresh.sourceDeclarations
import org.tatrman.metadata.refresh.sourceSubdirectory
import org.tatrman.metadata.refresh.SourceDeclaration
import org.tatrman.metadata.refresh.BranchStorage
import org.tatrman.metadata.refresh.SourceOrigin
import org.tatrman.metadata.refresh.ManagedSource
import org.tatrman.metadata.refresh.GuardedRefresher
import org.tatrman.metadata.refresh.RefreshScheduler
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.git.GitArchiveStorage
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.ModelStorage
import org.tatrman.metadata.cn.CnSetup
import org.tatrman.metadata.cn.PromotionTarget
import org.tatrman.metadata.cn.VocabularySource
import org.tatrman.metadata.cn.candidateRoutes
import org.tatrman.metadata.export.metadataExportRoutes
import org.tatrman.metadata.health.SourceLoadBoard
import org.tatrman.metadata.health.NO_MODEL_SOURCE
import org.tatrman.metadata.health.NO_MODEL_SOURCE_HELP
import org.tatrman.metadata.health.modelHealthRoutes
import org.tatrman.metadata.health.redactCredentials
import org.tatrman.metadata.read.metadataReadRoutes
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
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("org.tatrman.metadata.Application")

internal class MissingContentSourceException(
    message: String,
) : IllegalStateException(message)

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, "metadata", 7203)
    try {
        KtorServerBootstrap.createServer(serverConfig) { module(config) }.start(wait = true)
    } catch (e: MissingContentSourceException) {
        log.error(e.message)
        exitProcess(1)
    }
}

fun Application.module(config: Config) {
    // MW-P1: missing configuration is fatal, unlike a configured source whose content is
    // temporarily unavailable. Check before installing telemetry, opening CN's database or
    // starting either serving listener. Internal stock roles and CN versions are not content.
    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val loadBoard = SourceLoadBoard(meterRegistry, strictSwap = strictSwap(config))
    val registry = MetadataRegistry()
    val lexiconSourceIds = mutableSetOf<String>()
    val skippedSources = mutableListOf<String>()
    val contentSlots =
        buildSources(config, loadBoard, lexiconSourceIds, skippedSources) { registry.read()?.exportSidecars.orEmpty() }
    if (loadBoard.states().isEmpty()) {
        val skipped = skippedSources.distinct().sorted().joinToString("; ")
        throw MissingContentSourceException(
            "$NO_MODEL_SOURCE: $NO_MODEL_SOURCE_HELP" + if (skipped.isEmpty()) "" else "; skipped: $skipped",
        )
    }

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
    val tracer = otel.getTracer("org.tatrman.metadata")

    val reconciler =
        ModelReconciler(
            descriptor =
                ModelDescriptor(
                    id = config.getString("metadata.descriptor.id"),
                    name = config.getString("metadata.descriptor.name"),
                    description = config.getString("metadata.descriptor.description"),
                ),
        )

    // Initial load shares the same serialized publication policy as every later refresh.
    // ttr-server#111 — every load also records which package each of its files belongs to; the
    // reconciled model keeps no such map, and package scoping must not guess it from the path.
    // The index serves a load once its model swaps in, so it is the registry's FIRST listener.
    val packageIndex = PackageIndex().commitOnSwap(registry)
    // CN-P5 S5.3 — the candidate store (cn contracts §6). Its registered vocabulary versions ride the refresher as one
    // more source (A-CN-94); it is not a configured model source, so it stays off the load board and out of /ready.
    val cn = CnSetup.fromConfig(config) { id -> promotionTarget(config, id) }
    val sourceSlots =
        contentSlots +
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
        GuardedRefresher(
            sources = sourceSlots.map { it.source },
            sourceIds = sourceSlots.map { it.id },
            reconciler = reconciler,
            registry = registry,
            strictSwap = strictSwap(config),
            configuredIds = loadBoard.states().map { it.id }.toSet(),
            board = loadBoard,
            packageIndex = packageIndex,
            allowNonDefaultBranch =
                config.hasPath("metadata.allow-non-default-branch") &&
                    config.getBoolean("metadata.allow-non-default-branch"),
        )

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
        refresher.addPublicationListener { outcome ->
            val version = refresher.promotionCommit(cn.promotionSourceId, outcome) ?: return@addPublicationListener
            val schema =
                outcome.snapshot
                    ?.model
                    ?.schemas
                    ?.get("cn") as? CnSchema ?: return@addPublicationListener
            launch(Dispatchers.IO) {
                runCatching { candidates.observe(schema, version) }
                    .onFailure { log.warn("cn candidates: merge check failed: {}", it.message) }
            }
        }
    }

    launch(Dispatchers.IO) {
        refresher.refresh(sourceId = "", force = true)
        retryUntilLoaded(loadBoard, refresher)
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
            loadBoard = loadBoard,
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
        modelHealthRoutes(registry, loadBoard, refresher)
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
            val outcome =
                refresher.refreshOutcome(
                    call.request.queryParameters["source_id"].orEmpty(),
                    true,
                    call.request.queryParameters["branch"].orEmpty(),
                )
            val results = outcome.results
            val snap = outcome.snapshot
            call.respond(
                HttpStatusCode.fromValue(outcome.httpStatus),
                buildJsonObject {
                    putOps(outcome)
                    put("status", outcome.status)
                    putJsonArray("errors") {
                        outcome.lastAttempt
                            ?.errors
                            .orEmpty()
                            .forEach { add(it) }
                    }
                    putJsonArray("warnings") {
                        outcome.lastAttempt
                            ?.warnings
                            .orEmpty()
                            .forEach { add(it) }
                    }
                    put("model_files", outcome.lastAttempt?.modelFiles ?: 0)
                    putJsonArray("ignored_files") {
                        outcome.lastAttempt
                            ?.ignoredFiles
                            .orEmpty()
                            .forEach { add(it) }
                    }
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
        metadataReadRoutes(registry, searchRegistryWithAll, searchIndexHolder, packageIndex, lexiconSourceIds.toSet())
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
 * The configured source slots, registered on [loadBoard] apart from built-in stock roles.
 * [GuardedRefresher] records health and package membership only after accepting the load.
 */
internal fun buildSources(
    config: Config,
    loadBoard: SourceLoadBoard = SourceLoadBoard(),
    lexiconSourceIds: MutableSet<String> = mutableSetOf(),
    skippedSources: MutableList<String> = mutableListOf(),
    servedSidecars: () -> Map<String, String> = { emptyMap() },
): List<SourceSlot> =
    configuredSources(config, lexiconSourceIds, servedSidecars, skippedSources).map { slot ->
        if (slot.id != BuiltinStockSource.SOURCE_ID) {
            log.info("Model source '{}': {}", slot.id, slot.description)
            loadBoard.register(
                slot.id,
                slot.description,
                (slot.source as? ManagedSource)?.origin ?: SourceOrigin("resources"),
            )
        }
        slot
    }

private fun configuredSources(
    config: Config,
    lexiconSourceIds: MutableSet<String>,
    servedSidecars: () -> Map<String, String>,
    skippedSources: MutableList<String>,
): List<SourceSlot> {
    // Phase 2.2 — the built-in stock-roles source always loads first. Its
    // priority is Int.MAX_VALUE so it wins all merge collisions; protected
    // qnames are enforced separately by the reconciler regardless of priority.
    val builtins = listOf(SourceSlot(BuiltinStockSource.SOURCE_ID, BuiltinStockSource(), "built-in stock roles"))
    val declarations = sourceDeclarations(config)
    val configured =
        declarations.mapNotNull { declaration ->
            val sub = declaration.config
            val id = declaration.id
            val enabled = if (sub.hasPath("enabled")) sub.getBoolean("enabled") else true
            if (!enabled) return@mapNotNull null

            // A source may name another (possibly `enabled = false`) source block as its
            // `fallback`. The two are served mutually-exclusively via FallbackSource — never
            // merged — so a stale fallback can't resurrect defs the primary has deleted.
            // ttr-server#180: no source has a fallback unless one is configured explicitly
            // (`METADATA_GIT_FALLBACK` for the git source); Metadata bundles no model of its own.
            val fallbackId = if (sub.hasPath("fallback")) sub.getString("fallback").ifBlank { null } else null
            if (fallbackId == null) {
                return@mapNotNull buildSourceFromConfig(
                    sub,
                    id,
                    lexiconSourceIds,
                    servedSidecars,
                    skippedSources,
                )?.let { SourceSlot(id, it, describeSource(sub)) }
            }

            val fallbackSub = referencedSourceConfig(declarations, fallbackId)
            val fallbackSource =
                fallbackSub?.let { fs ->
                    buildSourceFromConfig(
                        fs,
                        if (fs.hasPath("id")) fs.getString("id") else fallbackId,
                        lexiconSourceIds,
                        servedSidecars,
                        skippedSources,
                    )
                }
            val primary = buildSourceFromConfig(sub, id, lexiconSourceIds, servedSidecars, skippedSources)
            when {
                fallbackSource == null -> {
                    skipSource(id, "unbuildable fallback '$fallbackId'", skippedSources)
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
                        primary.apply { fallback = fallbackSource },
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
    declarations: List<SourceDeclaration>,
    refId: String,
): Config? =
    declarations.firstOrNull { it.key == refId || it.id == refId }?.config.also {
        if (it == null) log.warn("fallback source id '{}' not found among metadata.sources", refId)
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
        "http" ->
            "http " +
                runCatching {
                    java.net
                        .URI(
                            str("url"),
                        ).let { java.net.URI(it.scheme, null, it.host, it.port, it.path, null, null).toString() }
                }.getOrDefault("<invalid url>")
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
    refresher: GuardedRefresher,
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
    lexiconSourceIds: MutableSet<String>,
    servedSidecars: () -> Map<String, String>,
    skippedSources: MutableList<String>,
): ManagedSource? {
    val kind = if (sub.hasPath("kind")) sub.getString("kind") else "ttr"
    val priority = if (sub.hasPath("priority")) sub.getInt("priority") else 100
    val storage = buildStorage(sub, id, skippedSources = skippedSources) ?: return null

    val source =
        when (kind) {
            // VL V-37: Metadata serves the layouts committed next to the model — a source collects them only when asked
            "ttr" -> {
                val source = FileBasedSource(sourceId = id, priority = priority, storage = storage, layouts = true)
                if (storage is BranchStorage) {
                    lexiconSourceIds.add(id)
                    GitLexiconSource(
                        GitTemplateSource(source, gitCheckoutDir(sub, id, ""), gitSubdirectory(sub), id, priority) {
                            servedSidecars()[org.tatrman.metadata.read.TEMPLATE_SIDECAR_PREFIX + id]
                        },
                        gitCheckoutDir(sub, id, ""),
                        if (sub.hasPath("lexicon-path")) sub.getString("lexicon-path") else "generated/lexicon.tar.zst",
                    )
                } else {
                    source
                }
            }
            // Phase 2.1 (fork) — `yaml-legacy` source kind removed: kantheon is TTR-only
            // (`org.tatrman.ttr.*` modeler artifacts, no erp-sql legacy YAML parse path).
            // See docs/implementation/v1/fork/tasks-p2-s2.1-metadata.md T2 note.
            else -> {
                skipSource(id, "unknown kind '$kind'", skippedSources)
            }
        } ?: return null
    return ManagedSource(
        id,
        if (storage is BranchStorage) {
            "git"
        } else if (storage is HttpTreeStorage) {
            "http"
        } else if (storage is LocalFsStorage) {
            "filesystem"
        } else {
            "resources"
        },
        source,
        storage as? BranchStorage,
    )
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
    val sub = referencedSourceConfig(sourceDeclarations(config), sourceId) ?: return null
    if (sub.hasPath("type") && sub.getString("type") == "http") return null
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

/** Both model storage and template path mapping start with this exact configured value. */
private fun gitSubdirectory(sub: Config): String = sourceSubdirectory(sub)

/** The storage one `metadata.sources.<id>` block reads; null when the block is unbuildable. */
private fun buildStorage(
    sub: Config,
    id: String,
    checkoutSuffix: String = "",
    skippedSources: MutableList<String> = mutableListOf(),
): ModelStorage? {
    val type = if (sub.hasPath("type")) sub.getString("type") else id
    val path = if (sub.hasPath("path")) sub.getString("path") else ""
    return when (type) {
        "git" -> {
            // Service loads pin one Git commit per attempt; promotion keeps its independent storage.
            // Requires `remote-uri`; optional
            // `local-checkout-dir` (defaults to /tmp/metadata-git/<id>), `branch`
            // (defaults to "main"), `subdirectory`, `username` / `password` for
            // HTTPS auth (PAT tokens use username="x-access-token").
            val remoteUri = if (sub.hasPath("remote-uri")) sub.getString("remote-uri") else ""
            if (remoteUri.isBlank()) {
                return skipSource(id, "no remote-uri", skippedSources)
            }
            val branch = if (sub.hasPath("branch")) sub.getString("branch") else "main"
            if (branch.isBlank()) {
                return skipSource(id, "blank branch", skippedSources)
            }
            val checkout = gitCheckoutDir(sub, id, checkoutSuffix)
            if (checkoutSuffix.isNotEmpty()) {
                GitArchiveStorage(
                    id = id,
                    remoteUri = remoteUri,
                    localCheckoutDir = checkout,
                    branch = branch,
                    subdirectory = gitSubdirectory(sub),
                    username = sourceUsername(sub),
                    password = sourcePassword(sub),
                )
            } else {
                BranchStorage(
                    id,
                    remoteUri,
                    checkout,
                    branch,
                    gitSubdirectory(sub),
                    sourceUsername(sub),
                    sourcePassword(sub),
                )
            }
        }
        "filesystem" -> {
            if (path.isBlank()) {
                return skipSource(id, "filesystem source has no path", skippedSources)
            }
            LocalFsStorage(id = id, rootPath = Path.of(path).resolve(sourceSubdirectory(sub)))
        }
        "http" -> {
            val url = if (sub.hasPath("url")) sub.getString("url") else ""
            if (url.isBlank()) return skipSource(id, "http source has no url", skippedSources)
            HttpTreeStorage(
                id,
                url,
                if (sub.hasPath("token")) sub.getString("token") else null,
                sourceSubdirectory(sub),
                if (sub.hasPath("connect-timeout-ms")) sub.getInt("connect-timeout-ms") else 10_000,
                if (sub.hasPath("read-timeout-ms")) sub.getInt("read-timeout-ms") else 30_000,
                if (sub.hasPath("max-bytes")) sub.getInt("max-bytes") else 32 * 1024 * 1024,
            )
        }
        "resources" -> {
            if (path.isBlank()) {
                return skipSource(id, "resources source has no path", skippedSources)
            }
            ClasspathStorage(id = id, resourcePrefix = path)
        }
        else -> {
            return skipSource(id, "unknown type '$type'", skippedSources)
        }
    }
}

/** A declared Git token uses the same PAT username convention as the legacy env slots. */
private fun sourceUsername(sub: Config): String? =
    when {
        sub.hasPath("username") -> sub.getString("username")
        sourcePassword(sub) != null -> "x-access-token"
        else -> null
    }

/** Existing password/env routes win over the additive per-source token alias. */
private fun sourcePassword(sub: Config): String? =
    when {
        sub.hasPath("password") -> sub.getString("password")
        sub.hasPath("token") -> sub.getString("token")
        else -> null
    }

/** Configuration diagnostics name fields and source ids, never a remote URI or password. */
private fun skipSource(
    id: String,
    reason: String,
    skippedSources: MutableList<String>,
): Nothing? {
    val diagnostic = redactCredentials("$id ($reason)")
    log.warn("Skipping model source: {}", diagnostic)
    skippedSources += diagnostic
    return null
}

/** Strict by default even for programmatically supplied configurations. */
internal fun strictSwap(config: Config): Boolean =
    !config.hasPath("metadata.strict-swap") || config.getBoolean("metadata.strict-swap")
