// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.config

import com.typesafe.config.Config
import java.time.Duration
import java.time.ZoneId

data class DoorConfig(
    val queryUrl: String,
    val velesUrl: String,
    val sidecarUrl: String?,
)

data class AuthConfig(
    val jwksUrl: String,
    val issuer: String?,
    val audience: String?,
)

data class BundleGitConfig(
    val url: String?,
    val branch: String,
    val path: String,
    val token: String?,
)

data class PreviewsConfig(
    val allowedBranches: List<String>,
    val idleEvictHours: Long,
)

/** Where island outputs are staged: `local` with a [path], or a store at a [url] (Seaweed/Redis via transfer-core). */
data class StagingConfig(
    val kind: String,
    val path: String?,
    val url: String?,
)

data class SchedulerConfig(
    val tz: ZoneId,
    val enabled: Boolean,
)

data class RetentionConfig(
    val stagingHours: Long,
    val graceHours: Long,
    val protocolDays: Long,
)

data class BudgetsConfig(
    val syncDefault: Duration,
    val batchDefault: Duration,
    val displayRows: Int,
    val detailLimit: Int,
)

/**
 * The service's configuration (AU contracts §8), typed from `automation.*` of HOCON. [load] fails on the first
 * read when a required key is missing — `db.host`, `door.queryUrl`, `door.velesUrl`, `auth.jwksUrl` — and
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
) {
    companion object {
        const val ROOT = "automation"
        val REQUIRED = listOf("db.host", "door.queryUrl", "door.velesUrl", "auth.jwksUrl")
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
                    ),
                auth =
                    AuthConfig(
                        jwksUrl = c.getString("auth.jwksUrl"),
                        issuer = c.optString("auth.issuer"),
                        audience = c.optString("auth.audience"),
                    ),
                bundleGit =
                    BundleGitConfig(
                        url = c.optString("bundle.git.url"),
                        branch = c.getString("bundle.git.branch"),
                        path = c.getString("bundle.git.path"),
                        token = c.optString("bundle.git.token"),
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
            )
        }

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
    }

    /** Never prints [connections]' values or the bundle token. */
    override fun toString(): String =
        "AutomationConfig(door=$door, auth=$auth, bundleGit=${bundleGit.copy(
            token = bundleGit.token?.let { "***" },
        )}, " +
            "previews=$previews, staging=$staging, scheduler=$scheduler, retention=$retention, budgets=$budgets, " +
            "paused=$paused, policiesFragment=$policiesFragment, clockSkew=$clockSkew, connections=${connections.keys})"
}
