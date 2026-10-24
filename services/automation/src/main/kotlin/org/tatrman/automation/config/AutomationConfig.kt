// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.config

import com.typesafe.config.Config
import java.time.Duration
import java.time.ZoneId

data class DoorConfig(
    val queryUrl: String,
    val velesUrl: String,
    val sidecarUrl: String?,
    /** Exact logical world/storage → Query world (also configured in Query's QUERY_WORLDS). */
    val routes: Map<String, String> = emptyMap(),
)

/**
 * Bearer verification and the identities' token exchange (§7). [tokenUrl] defaults to the JWKS URL's
 * sibling — Keycloak serves `…/protocol/openid-connect/certs` and `…/token` side by side. [authorMap] is
 * `AUTOMATION_AUTHOR_MAP` parsed: commit author (e-mail or name) → principal. [refreshRoles] are the realm roles
 * that may call `POST /automation/refresh` (the CI's service principal and operators: `role.ops`, S4.3).
 */
data class AuthConfig(
    val jwksUrl: String,
    val issuer: String,
    val audience: String?,
    val tokenUrl: String,
    val authorMap: Map<String, String>,
    val refreshRoles: Set<String> = setOf(DEFAULT_REFRESH_ROLE),
) {
    companion object {
        const val DEFAULT_REFRESH_ROLE = "ops"
    }
}

/**
 * The automation repo (§8): [path] = where `model/` and `automation/` sit in the work tree; [pollSeconds] = the
 * refresh poll (0 = off; the CI call and the manual `POST /refresh` remain); [checkoutDir] = the clones' root;
 * [velesSource] = the Veles source id serving this repo's model, for the drift handshake (unset = any source).
 */
data class BundleGitConfig(
    val url: String?,
    val branch: String,
    val path: String,
    val token: String?,
    val pollSeconds: Long = 0,
    val checkoutDir: String = "/tmp/automation-bundles",
    val velesSource: String? = null,
)

data class PreviewsConfig(
    val allowedBranches: List<String>,
    val idleEvictHours: Long,
)

/** Raw immutable staging; credentials stay in the Redis URL or the AWS provider chain. */
data class StagingConfig(
    val kind: String,
    val path: String?,
    val url: String?,
    val bucket: String? = null,
    val prefix: String = "automation/staging",
    val region: String = "us-east-1",
    val anonymous: Boolean = false,
) {
    init {
        require(
            kind in setOf("local", "redis", "seaweed"),
        ) { "automation.staging.kind must be local, redis or seaweed" }
        require(prefix.split('/').all { it.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")) && it != ".." }) {
            "automation.staging.prefix must be a nonempty relative namespace"
        }
        if (kind == "local") {
            require(!path.isNullOrBlank()) { "automation.staging.path is required for local storage" }
        } else {
            val uri = runCatching { java.net.URI(requireNotNull(url)) }.getOrNull()
            require(
                uri != null &&
                    uri.host != null &&
                    uri.fragment == null &&
                    uri.query == null &&
                    (uri.port == -1 || uri.port in 1..65535),
            ) { "invalid automation.staging.url" }
            if (kind == "redis") {
                require(
                    uri.scheme in setOf("redis", "rediss") &&
                        uri.path.orEmpty().matches(Regex("(/[0-9]+)?")),
                ) {
                    "automation.staging.url must be a redis/rediss URL with an optional database number"
                }
            } else {
                require(
                    uri.scheme in
                        setOf(
                            "http",
                            "https",
                        ) &&
                        uri.userInfo == null &&
                        uri.path.orEmpty() in setOf("", "/"),
                ) {
                    "automation.staging.url must be an HTTP(S) S3 endpoint without credentials or a path"
                }
                require(
                    bucket != null && bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) && ".." !in bucket,
                ) {
                    "automation.staging.bucket is required for seaweed storage"
                }
                require(region.matches(Regex("[a-z0-9-]+"))) { "invalid automation.staging.region" }
            }
        }
    }

    // Redis URLs may contain credentials. Never include the URL in bootstrap diagnostics.
    override fun toString(): String =
        "StagingConfig(kind=$kind, path=$path, url=${if (url == null) null else "<configured>"}, " +
            "bucket=$bucket, prefix=$prefix, region=$region, anonymous=$anonymous)"
}

data class SchedulerConfig(
    val tz: ZoneId,
    val enabled: Boolean,
)

data class RetentionConfig(
    val stagingHours: Long,
    val graceHours: Long,
    val protocolDays: Long,
)

data class RunnerConfig(
    val workers: Int = 4,
) {
    init {
        require(workers in 1..256) { "automation.runner.workers must be in 1..256" }
    }
}

data class BudgetsConfig(
    val syncDefault: Duration,
    val batchDefault: Duration,
    val displayRows: Int,
    val detailLimit: Int,
)

/**
 * The service's configuration (AU contracts §8), typed from `automation.*` of HOCON. [load] fails on the first
 * read when a required key is missing — `db.host`, `door.queryUrl`, `door.velesUrl`, `auth.jwksUrl`,
 * `auth.issuer` — and
 * names every missing key at once, so a misconfigured pod dies at start with the whole list, not one key per
 * restart. [db] stays a raw [Config]: it is `DatabaseConnection`'s shape (type/host/port/database/user/password).
 *
 * [connections] are the named identities' and connectors' credentials (`automation.connections.<NAME>`, the
 * `TTR_CONN_<NAME>` env) — values never logged.
 */
data class AutomationConfig(
    val db: Config,
    val door: DoorConfig,
    val auth: AuthConfig,
    val bundleGit: BundleGitConfig,
    val previews: PreviewsConfig,
    val staging: StagingConfig,
    val scheduler: SchedulerConfig,
    val retention: RetentionConfig,
    val budgets: BudgetsConfig,
    val paused: Boolean,
    val policiesFragment: String?,
    val clockSkew: Duration,
    val connections: Map<String, String>,
    val approvalsOff: Boolean = false,
    val runner: RunnerConfig = RunnerConfig(),
) {
    companion object {
        const val ROOT = "automation"
        val REQUIRED = listOf("db.host", "door.queryUrl", "door.velesUrl", "auth.jwksUrl", "auth.issuer")
        const val CONNECTION_ENV_PREFIX = "TTR_CONN_"

        fun load(
            root: Config,
            env: Map<String, String> = System.getenv(),
        ): AutomationConfig {
            val c = root.getConfig(ROOT)
            val missing = REQUIRED.filter { !c.hasPath(it) || c.getString(it).isBlank() }
            require(missing.isEmpty()) {
                "automation config: missing required keys ${missing.joinToString { "$ROOT.$it" }}"
            }
            return AutomationConfig(
                db = c.getConfig("db"),
                door =
                    DoorConfig(
                        queryUrl = c.getString("door.queryUrl"),
                        velesUrl = c.getString("door.velesUrl"),
                        sidecarUrl = c.optString("door.sidecarUrl"),
                        routes = doorRoutes(c.optString("door.routes")),
                    ),
                auth =
                    AuthConfig(
                        jwksUrl = c.getString("auth.jwksUrl"),
                        issuer = c.getString("auth.issuer"),
                        audience = c.optString("auth.audience"),
                        tokenUrl = c.optString("auth.tokenUrl") ?: siblingTokenUrl(c.getString("auth.jwksUrl")),
                        authorMap = authorMap(c.optString("auth.authorMap")),
                        refreshRoles = refreshRoles(c.optString("auth.refreshRoles")),
                    ),
                bundleGit =
                    BundleGitConfig(
                        url = c.optString("bundle.git.url"),
                        branch = c.getString("bundle.git.branch"),
                        path = c.getString("bundle.git.path"),
                        token = c.optString("bundle.git.token"),
                        pollSeconds =
                            if (c.hasPath(
                                    "bundle.git.pollSeconds",
                                )
                            ) {
                                c.getLong("bundle.git.pollSeconds")
                            } else {
                                0
                            },
                        checkoutDir = c.optString("bundle.git.checkoutDir") ?: "/tmp/automation-bundles",
                        velesSource = c.optString("bundle.velesSource"),
                    ),
                previews =
                    PreviewsConfig(
                        allowedBranches = c.getStringList("bundle.previews.allowedBranches"),
                        idleEvictHours = c.getLong("bundle.previews.idleEvictHours"),
                    ),
                staging =
                    StagingConfig(
                        kind = c.getString("staging.kind"),
                        path = c.optString("staging.path"),
                        url = c.optString("staging.url"),
                        bucket = c.optString("staging.bucket"),
                        prefix = c.optString("staging.prefix") ?: "automation/staging",
                        region = c.optString("staging.region") ?: "us-east-1",
                        anonymous = c.hasPath("staging.anonymous") && c.getBoolean("staging.anonymous"),
                    ),
                scheduler =
                    SchedulerConfig(
                        tz = ZoneId.of(c.getString("scheduler.tz")),
                        enabled = c.getBoolean("scheduler.enabled"),
                    ),
                retention =
                    RetentionConfig(
                        stagingHours = c.getLong("retention.stagingHours"),
                        graceHours = c.getLong("retention.graceHours"),
                        protocolDays = c.getLong("retention.protocolDays"),
                    ),
                budgets =
                    BudgetsConfig(
                        syncDefault = c.getDuration("budgets.syncDefault"),
                        batchDefault = c.getDuration("budgets.batchDefault"),
                        displayRows = c.getInt("budgets.displayRows"),
                        detailLimit = c.getInt("budgets.detailLimit"),
                    ),
                paused = c.getBoolean("paused"),
                policiesFragment = c.optString("policies.fragment"),
                clockSkew = if (c.hasPath("clock.skew")) c.getDuration("clock.skew") else Duration.ZERO,
                connections = connections(c, env),
                approvalsOff = approvalsOff(c),
                runner = RunnerConfig(if (c.hasPath("runner.workers")) c.getInt("runner.workers") else 4),
            )
        }

        /** Explicit aliases only; duplicate keys must not silently redirect a storage to another database. */
        internal fun doorRoutes(raw: String?): Map<String, String> {
            val routes = linkedMapOf<String, String>()
            val name = Regex("[A-Za-z_][A-Za-z_0-9.-]*")
            raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { pair ->
                val parts = pair.split('=')
                require(parts.size == 2) { "automation.door.routes requires world/storage=query-world pairs" }
                val key = parts[0].trim()
                val alias = parts[1].trim()
                val path = key.split('/')
                require(path.size == 2 && path.all { name.matches(it) } && name.matches(alias)) {
                    "automation.door.routes requires world/storage=query-world pairs"
                }
                require(key !in routes) { "automation.door.routes repeats $key" }
                routes[key] = alias
            }
            return routes
        }

        /** Keycloak's token endpoint beside its JWKS: `…/openid-connect/certs` → `…/openid-connect/token`. */
        internal fun siblingTokenUrl(jwksUrl: String): String {
            require(jwksUrl.endsWith("/certs")) {
                "automation config: auth.tokenUrl is not set and auth.jwksUrl ($jwksUrl) does not end in /certs"
            }
            return jwksUrl.removeSuffix("/certs") + "/token"
        }

        /** `a@x.org=svc-a, Jana Nová=jana` → the map; a pair without `=` is a configuration error, named. */
        internal fun authorMap(raw: String?): Map<String, String> =
            raw
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .associate { pair ->
                    val at = pair.indexOf('=')
                    require(at > 0 && at < pair.length - 1) {
                        "automation config: auth.authorMap entry '$pair' is not author=principal"
                    }
                    pair.substring(0, at).trim() to pair.substring(at + 1).trim()
                }

        /** `ops, release-ci` → the set; unset or blank → `ops`. An empty set would let nobody refresh by hand. */
        internal fun refreshRoles(raw: String?): Set<String> =
            raw
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
                .ifEmpty { setOf(AuthConfig.DEFAULT_REFRESH_ROLE) }

        /** HOCON's `automation.connections` first, the `TTR_CONN_<NAME>` env over it. */
        private fun connections(
            c: Config,
            env: Map<String, String>,
        ): Map<String, String> {
            val fromHocon =
                if (c.hasPath("connections")) {
                    c.getConfig("connections").entrySet().associate { it.key to it.value.unwrapped().toString() }
                } else {
                    emptyMap()
                }
            val fromEnv =
                env
                    .filterKeys { it.startsWith(CONNECTION_ENV_PREFIX) && it.length > CONNECTION_ENV_PREFIX.length }
                    .mapKeys { it.key.removePrefix(CONNECTION_ENV_PREFIX) }
            return fromHocon + fromEnv
        }

        private fun Config.optString(path: String): String? =
            if (hasPath(path)) getString(path).ifBlank { null } else null

        private fun approvalsOff(c: Config): Boolean {
            val mode = if (c.hasPath("approvals")) c.getString("approvals") else "required"
            require(mode in setOf("required", "off")) { "automation.approvals must be required or off" }
            require(mode != "off" || c.hasPath("development") && c.getBoolean("development")) {
                "automation.approvals=off requires automation.development=true; development use only"
            }
            return mode == "off"
        }
    }

    /** Never prints [connections]' values or the bundle token. */
    override fun toString(): String =
        "AutomationConfig(door=$door, auth=$auth, bundleGit=${bundleGit.copy(
            token = bundleGit.token?.let { "***" },
        )}, " +
            "previews=$previews, staging=$staging, scheduler=$scheduler, retention=$retention, budgets=$budgets, " +
            "paused=$paused, runner=$runner, policiesFragment=$policiesFragment, clockSkew=$clockSkew, approvalsOff=$approvalsOff, connections=${connections.keys})"
}
