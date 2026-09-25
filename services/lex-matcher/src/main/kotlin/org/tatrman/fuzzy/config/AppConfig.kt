// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.config

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.*
import org.tatrman.fuzzy.core.MatchVersion
import org.tatrman.fuzzy.core.MethodDispatcher
import org.tatrman.fuzzy.core.ProfileScorer
import org.tatrman.fuzzy.core.RetrievalMode
import org.tatrman.fuzzy.core.TokenBasedMatcherV2
import org.tatrman.fuzzy.core.V2Normalization

data class AppConfig(
    val serverPort: Int,
    val grpcPort: Int,
    val grpcReflectionEnabled: Boolean,
    val refreshIntervalSeconds: Long,
    val tokenBasedConfig: TokenBasedConfig = TokenBasedConfig(),
    val nlp: NlpConfig = NlpConfig(),
    val loaderSource: LoaderSourceConfig = LoaderSourceConfig(),
    val metadata: MetadataConfig = MetadataConfig(),
    val lexicon: LexiconConfig = LexiconConfig(),
    /**
     * Warehouse connection for the `metadata` loader source. Null for the
     * default `static` (in-repo JSON catalog) source, which needs no DB.
     * Required (non-null) when `loaderSource.source = "metadata"` — the loader
     * runs each member vocabulary's read plan (rendered by Veles) here.
     */
    val database: DatabaseConfig? = null,
)

/**
 * Warehouse dialect + connection for the metadata loader. The sealed pair
 * (`PostgresConfig` / `MssqlConfig`) drives both the JDBC URL in
 * [org.tatrman.fuzzy.db.DatabaseFactory] and the identifier quoting in
 * the SQL composer (`"x"` for Postgres, `[x]` for MSSQL).
 */
sealed interface DatabaseConfig {
    val user: String
    val pass: String
}

data class PostgresConfig(
    val host: String,
    val port: Int,
    val database: String,
    override val user: String,
    override val pass: String,
) : DatabaseConfig

data class MssqlConfig(
    val host: String,
    val port: Int,
    val database: String,
    override val user: String,
    override val pass: String,
) : DatabaseConfig

data class LoaderSourceConfig(
    val source: String = "static",
)

/**
 * RV-P1.4 — the compiled-lexicon layer (RV-39). Absent path = no declared layer, which is the
 * pre-RV service: member vocabulary only. Optional on purpose, so an estate that has not authored
 * a lexicon yet is a supported deployment rather than a broken one.
 *
 * A local path, not a URL: the estate build writes the archive and the deployment mounts it.
 * Pulling it over the snapshot channel from veles is the RO-13 step and is deliberately not this.
 */
data class LexiconConfig(
    val archivePath: String = "",
    /**
     * RV-32 (T4) — the uniqueness floor a TOKENS match must clear to be auto-bindable. Lives here
     * rather than under `token-based` because it is an authoring rule, not an engine tuning knob:
     * `token-based` shapes how things score, this decides what a score is allowed to mean.
     */
    val uniquenessMarginFloor: Double = MethodDispatcher.DEFAULT_UNIQUENESS_FLOOR,
    /**
     * RV-44 ⚑M-5 — a global minimum on the DECLARED (within-class) score: below it a
     * profile-scored candidate is dropped entirely.
     *
     * **Default 0 = off**, and deliberately so: RV-14's evidence classes, WEAK-never-binds and the
     * uniqueness margin above already do the safety work here. The knob exists for an estate that
     * wants a blunt cut and for nobody else. It is deliberately different from the legacy default,
     * where no class system exists — recorded so nobody "harmonizes" the two later.
     */
    val minInClassScore: Double = ProfileScorer.DEFAULT_MIN_IN_CLASS_SCORE,
    /**
     * RV-P7.3 — the LEARNED overlay archive, written by the estate's Golem. Blank = no overlay,
     * which is every estate that has not learned anything and every deployment with no learning
     * store at all: `overlay_version` stays absent from the RV-39 tuple, exactly as the contract
     * says it must for a pre-P7 estate.
     *
     * A local path for the same reason [archivePath] is one, and it is the reason the transport
     * ruling costs this service nothing: lex-matcher reads a file. **Who writes it is not its
     * business** — an open-runtime service that had to call the commercial constellation to serve
     * a layer would be a dependency the licence boundary does not allow.
     */
    val overlayArchivePath: String = "",
)

data class MetadataConfig(
    val host: String = "veles",
    val port: Int = 7261,
    val timeoutMs: Long = 10_000,
    /**
     * Source-identifier guard for the v1 single-source assumption. When
     * set, the loader skips any member vocabulary whose ATTRIBUTE's namespace
     * differs from this value and records `member_vocabulary_skipped_total{reason="wrong_source"}`.
     * Empty string disables the check — the historical v1 "single source,
     * asserted not solved" behaviour. Multi-source deployments should set
     * this explicitly.
     *
     * (MV-T2: `schema` / `FUZZY_METADATA_SCHEMA` is gone — Veles lists member vocabularies
     * by attribute, whatever schema their rows live in.)
     */
    val namespace: String = "",
)

/**
 * The TATRMAN engine settings. ⚑ The data-class defaults below are the LIBRARY's (V1 + LEGACY — what a
 * test or an embedder building this by hand gets). What the SERVICE runs when a key is absent or blank
 * is [ConfigLoader]'s shipped fallback ([ConfigLoader.SHIPPED_MATCH_VERSION] +
 * [ConfigLoader.SHIPPED_RETRIEVAL]) — review-103 L1: an operator's own `-Dconfig.file` without a
 * `fuzzy.match` block must not silently select the engine the service stopped shipping.
 */
data class TokenBasedConfig(
    val distanceThreshold: Double = 0.20,
    val orderBonusMultiplier: Double = 1.05,
    val maxOrderBonus: Double = 1.5,
    // A v1 switch: v2 always weighs by IDF (contracts §4.3), and the service WARNs at startup when
    // this is off under v2 (review-103 L2) rather than ignoring it silently.
    val idfEnabled: Boolean = true,
    // FZ-P2 — retrieval path selector (`fuzzy.token-based.retrieval`). Defaults LEGACY; flipped to
    // INDEX_FIRST in application.conf at the FZ-P2 DoD.
    val retrieval: RetrievalMode = RetrievalMode.LEGACY,
    // LP-P0 — the TATRMAN scorer (`fuzzy.match.version`, env FUZZY_MATCH_VERSION). Defaulted V1
    // here and flipped to v2 in application.conf at LP-P3 (ruling LPA-2), the same shape
    // `retrieval` above uses: the CODE default stays the pinned engine, the SHIPPED SERVICE moves.
    // V2 with LEGACY retrieval is a startup error (contracts §4.1).
    val matchVersion: MatchVersion = MatchVersion.V1,
    // review-103 F3 (ruling 2, D3) — `fuzzy.match.v2.normalize.{mode,ceiling}` (env
    // FUZZY_V2_NORMALIZE_MODE / FUZZY_V2_NORMALIZE_CEILING): how v2 keeps a row that is not
    // all-exact under 1.0. Default scale/0.99 — the library's and the shipped value alike. Inert
    // under v1.
    val v2Normalization: V2Normalization = V2Normalization.DEFAULT,
)

/**
 * `nlp` integration for Czech lemmatisation via gRPC `BatchLemmatize`
 * (RG-P2.S1.T4). Disabled → folded-surface matching only. `port` is the nlp
 * **gRPC** port (7271; gRPC is nlp's contract, REST is its dev mirror).
 */
data class NlpConfig(
    val enabled: Boolean = false,
    val host: String = "nlp",
    val port: Int = 7271,
    val timeoutMs: Long = 5_000,
    val lang: String = "cs",
)

object ConfigLoader {
    /** review-103 L1 — the engine the service runs when `fuzzy.match.version` is absent or blank. */
    val SHIPPED_MATCH_VERSION: MatchVersion = MatchVersion.V2

    /** review-103 L1/L2 — the retrieval the service runs when `fuzzy.token-based.retrieval` is absent or blank. */
    val SHIPPED_RETRIEVAL: RetrievalMode = RetrievalMode.INDEX_FIRST

    fun load(): AppConfig {
        val config = ConfigFactory.load()
        val fuzzyConfig = config.getConfig("fuzzy")

        return AppConfig(
            serverPort = config.getString("ktor.deployment.port").toInt(),
            grpcPort = fuzzyConfig.getString("grpc.port").toInt(),
            grpcReflectionEnabled =
                fuzzyConfig.hasPath("grpc.reflection-enabled") &&
                    fuzzyConfig.getBoolean("grpc.reflection-enabled"),
            refreshIntervalSeconds = fuzzyConfig.getLong("refreshIntervalSeconds"),
            tokenBasedConfig =
                withV2Normalization(withMatchVersion(loadTokenBasedConfig(fuzzyConfig), fuzzyConfig), fuzzyConfig),
            nlp = loadNlpConfig(fuzzyConfig),
            loaderSource = loadLoaderSourceConfig(fuzzyConfig),
            metadata = loadMetadataConfig(fuzzyConfig),
            lexicon = loadLexiconConfig(fuzzyConfig),
            database = loadDatabaseConfig(fuzzyConfig),
        )
    }

    /**
     * Reads the warehouse connection from `fuzzy.type` + `fuzzy.{postgres,mssql}`.
     * Returns null when `fuzzy.type` is absent — the `static` (JSON catalog)
     * source needs no DB, so a DB-less config is valid. When the `metadata`
     * source is selected but this is null, `Application.module` fails fast.
     */
    private fun loadDatabaseConfig(fuzzyConfig: com.typesafe.config.Config): DatabaseConfig? {
        if (!fuzzyConfig.hasPath("type")) return null
        return when (fuzzyConfig.getString("type").uppercase()) {
            "POSTGRES" -> {
                val pg = fuzzyConfig.getConfig("postgres")
                PostgresConfig(
                    host = pg.getString("host"),
                    port = pg.getString("port").toInt(),
                    database = pg.getString("database"),
                    user = pg.getString("user"),
                    pass = pg.getString("password"),
                )
            }
            "MSSQL" -> {
                val ms = fuzzyConfig.getConfig("mssql")
                MssqlConfig(
                    host = ms.getString("host"),
                    port = ms.getString("port").toInt(),
                    database = ms.getString("database"),
                    user = ms.getString("user"),
                    pass = ms.getString("password"),
                )
            }
            else -> throw IllegalArgumentException(
                "Unknown fuzzy.type: '${fuzzyConfig.getString("type")}' (expected postgres|mssql)",
            )
        }
    }

    private fun loadNlpConfig(fuzzyConfig: com.typesafe.config.Config): NlpConfig =
        try {
            val nlp = fuzzyConfig.getConfig("nlp")
            val defaults = NlpConfig()
            NlpConfig(
                enabled = if (nlp.hasPath("enabled")) nlp.getBoolean("enabled") else defaults.enabled,
                host = if (nlp.hasPath("host")) nlp.getString("host") else defaults.host,
                port = if (nlp.hasPath("port")) nlp.getString("port").toInt() else defaults.port,
                timeoutMs = if (nlp.hasPath("timeout-ms")) nlp.getLong("timeout-ms") else defaults.timeoutMs,
                lang = if (nlp.hasPath("lang")) nlp.getString("lang") else defaults.lang,
            )
        } catch (e: com.typesafe.config.ConfigException) {
            NlpConfig()
        }

    /**
     * LP-P0 — `fuzzy.match.version` layered onto the token-based block, then validated against the
     * retrieval mode: a bad version or an incompatible pair stops the service.
     *
     * review-103 L1 — absent or blank (`FUZZY_MATCH_VERSION=""`, a config file with no `fuzzy.match`
     * block) is UNSET and runs [SHIPPED_MATCH_VERSION], never the library's V1. Rolling back is an
     * explicit `FUZZY_MATCH_VERSION=v1`.
     */
    internal fun withMatchVersion(
        tokenBased: TokenBasedConfig,
        fuzzyConfig: com.typesafe.config.Config,
    ): TokenBasedConfig {
        val version = MatchVersion.fromString(fuzzyConfig.optionalString("match.version"), SHIPPED_MATCH_VERSION)
        version.requireCompatible(tokenBased.retrieval)
        return tokenBased.copy(matchVersion = version)
    }

    /**
     * Operator-facing warnings about settings that parse but do nothing — logged at WARN on startup.
     *
     * review-103 L2 — `idf-enabled=false` under v2: v2 always weighs tokens by IDF (contracts §4.3),
     * so the flag is ignored there. It used to be ignored silently, while documented as the A/B
     * switch.
     */
    fun startupWarnings(config: AppConfig): List<String> =
        buildList {
            val tokenBased = config.tokenBasedConfig
            if (tokenBased.matchVersion == MatchVersion.V2 && !tokenBased.idfEnabled) {
                add(
                    "fuzzy.token-based.idf-enabled=false (FUZZY_TOKEN_BASED_IDF_ENABLED) has NO effect under " +
                        "fuzzy.match.version=v2: v2 always weighs tokens by IDF (contracts §4.3). It is a v1 " +
                        "switch — set FUZZY_MATCH_VERSION=v1 as well to compare unweighted v1.",
                )
            }
        }

    /**
     * review-103 F3 (ruling 2, D3) — `fuzzy.match.v2.normalize.{mode,ceiling}` layered onto the
     * token-based block. Blank (an env var exported empty) is unset and takes the default; a mode
     * outside scale|cap|off, a ceiling that is not a number, or one outside (0, 1) is a STARTUP
     * error naming the key — calibration is what this knob is for, so a typo must not quietly
     * calibrate something else.
     */
    internal fun withV2Normalization(
        tokenBased: TokenBasedConfig,
        fuzzyConfig: com.typesafe.config.Config,
    ): TokenBasedConfig {
        val mode = V2Normalization.Mode.fromString(fuzzyConfig.optionalString("match.v2.normalize.mode"))
        val ceilingText = fuzzyConfig.optionalString("match.v2.normalize.ceiling")
        val ceiling =
            if (ceilingText == null) {
                V2Normalization.DEFAULT_CEILING
            } else {
                ceilingText.trim().toDoubleOrNull()
                    ?: throw IllegalArgumentException(
                        "fuzzy.match.v2.normalize.ceiling must be a number, got '$ceilingText'",
                    )
            }
        // The constructor range-checks the ceiling (> 0, < 1.0) and names the key.
        return tokenBased.copy(v2Normalization = V2Normalization(mode, ceiling))
    }

    /** The value at [path] as a string, or null when absent or blank (blank = unset). */
    private fun com.typesafe.config.Config.optionalString(path: String): String? =
        if (hasPath(path)) getString(path).takeIf { it.isNotBlank() } else null

    /**
     * `fuzzy.token-based`, key by key. review-103 L2 — this used to be one `try` whose catch-all
     * turned ANY malformed key into `TokenBasedConfig()` (LEGACY retrieval), so a typo surfaced as
     * the misleading "v2 requires index-first … got legacy". Now:
     *  - an absent block, or an absent or blank key, takes its default — the SHIPPED retrieval
     *    ([SHIPPED_RETRIEVAL]) for `retrieval`;
     *  - a value that does not parse — including a retrieval other than `index-first` · `legacy`,
     *    which [RetrievalMode.fromString] would quietly read as LEGACY — stops startup with a message
     *    naming the key.
     */
    internal fun loadTokenBasedConfig(fuzzyConfig: com.typesafe.config.Config): TokenBasedConfig {
        val defaults = TokenBasedConfig(retrieval = SHIPPED_RETRIEVAL)
        if (!fuzzyConfig.hasPath("token-based")) return defaults
        // Not an object (`token-based = 5`) ⇒ ConfigException naming `token-based`.
        val block = fuzzyConfig.getConfig("token-based")
        return TokenBasedConfig(
            distanceThreshold = block.doubleOr("distance-threshold", defaults.distanceThreshold),
            orderBonusMultiplier = block.doubleOr("order-bonus-multiplier", defaults.orderBonusMultiplier),
            maxOrderBonus = block.doubleOr("max-order-bonus", defaults.maxOrderBonus),
            idfEnabled = block.booleanOr("idf-enabled", defaults.idfEnabled),
            retrieval = retrievalOf(block.optionalString("retrieval")),
        )
    }

    private fun retrievalOf(text: String?): RetrievalMode {
        if (text == null) return SHIPPED_RETRIEVAL
        return RetrievalMode.entries.firstOrNull { it.name == text.trim().uppercase().replace('-', '_') }
            ?: throw IllegalArgumentException(
                "fuzzy.token-based.retrieval must be index-first or legacy, got '$text'",
            )
    }

    private fun com.typesafe.config.Config.doubleOr(
        key: String,
        default: Double,
    ): Double {
        val text = optionalString(key) ?: return default
        return text.trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("fuzzy.token-based.$key must be a number, got '$text'")
    }

    private fun com.typesafe.config.Config.booleanOr(
        key: String,
        default: Boolean,
    ): Boolean {
        val text = optionalString(key) ?: return default
        return when (text.trim().lowercase()) {
            "true", "yes", "on" -> true
            "false", "no", "off" -> false
            else -> throw IllegalArgumentException("fuzzy.token-based.$key must be true or false, got '$text'")
        }
    }

    private fun loadLoaderSourceConfig(fuzzyConfig: com.typesafe.config.Config): LoaderSourceConfig =
        try {
            val loader = fuzzyConfig.getConfig("loader")
            LoaderSourceConfig(
                source = if (loader.hasPath("source")) loader.getString("source") else "static",
            )
        } catch (e: com.typesafe.config.ConfigException) {
            LoaderSourceConfig()
        }

    /**
     * RV-P1.4 — absent block or absent path both mean "no declared layer", not a failure.
     *
     * review-103 L3 — a `uniqueness-margin-floor` at or under v2's ε (0.01) stops startup. Contracts
     * §4.5's "two candidates at equal P differ by ≤ ε ⇒ never auto-bind" is only true while the floor
     * sits above ε; at 0.005 a coverage tie-break auto-binds one of two equal-P targets.
     */
    internal fun loadLexiconConfig(fuzzyConfig: com.typesafe.config.Config): LexiconConfig =
        readLexiconConfig(fuzzyConfig).also { lexicon ->
            require(lexicon.uniquenessMarginFloor > TokenBasedMatcherV2.EPSILON) {
                "fuzzy.lexicon.uniqueness-margin-floor must be > ${TokenBasedMatcherV2.EPSILON} (v2's coverage " +
                    "tie-break ε; at or under it two equal-P targets auto-bind — contracts §4.5), " +
                    "got ${lexicon.uniquenessMarginFloor}"
            }
        }

    private fun readLexiconConfig(fuzzyConfig: com.typesafe.config.Config): LexiconConfig =
        try {
            val lexicon = fuzzyConfig.getConfig("lexicon")
            val defaults = LexiconConfig()
            LexiconConfig(
                archivePath =
                    if (lexicon.hasPath("archive-path")) lexicon.getString("archive-path") else defaults.archivePath,
                uniquenessMarginFloor =
                    if (lexicon.hasPath("uniqueness-margin-floor")) {
                        lexicon.getDouble("uniqueness-margin-floor")
                    } else {
                        defaults.uniquenessMarginFloor
                    },
                minInClassScore =
                    if (lexicon.hasPath("min-in-class-score")) {
                        lexicon.getDouble("min-in-class-score")
                    } else {
                        defaults.minInClassScore
                    },
                overlayArchivePath =
                    if (lexicon.hasPath("overlay-archive-path")) {
                        lexicon.getString("overlay-archive-path")
                    } else {
                        defaults.overlayArchivePath
                    },
            )
        } catch (e: com.typesafe.config.ConfigException) {
            LexiconConfig()
        }

    private fun loadMetadataConfig(fuzzyConfig: com.typesafe.config.Config): MetadataConfig =
        try {
            val metadata = fuzzyConfig.getConfig("metadata")
            val defaults = MetadataConfig()
            MetadataConfig(
                host = if (metadata.hasPath("host")) metadata.getString("host") else defaults.host,
                port = if (metadata.hasPath("port")) metadata.getInt("port") else defaults.port,
                timeoutMs = if (metadata.hasPath("timeout-ms")) metadata.getLong("timeout-ms") else defaults.timeoutMs,
                namespace = if (metadata.hasPath("namespace")) metadata.getString("namespace") else defaults.namespace,
            )
        } catch (e: com.typesafe.config.ConfigException) {
            MetadataConfig()
        }
}
