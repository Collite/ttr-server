// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

data class HealthConfig(
    val prometheus: PrometheusConfig = PrometheusConfig(),
    val technologies: Map<String, TechnologyConfig> = emptyMap(),
)

data class PrometheusConfig(
    val url: String = "http://prometheus:9090",
    val timeout: Long = 5000,
    val queryInterval: Long = 60,
)

data class TechnologyConfig(
    val type: String,
    val url: String? = null,
    val healthEndpoint: String? = null,
    val job: String? = null,
    val prometheusUrl: String? = null,
    val timeout: Long = 5000,
    val host: String? = null,
    val port: Int? = null,
    /** Where the service reports its content, and how to read it (`content` on the landing). */
    val status: StatusConfig? = null,
)

/**
 * `technologies.<key>.status`: the service's own status endpoint and the adapter that turns its body
 * into a [org.tatrman.health.content.Content]. The paths are dotted JSON paths into the body (see
 * `content.at`); the named adapters read these too and add what needs code.
 */
data class StatusConfig(
    val url: String,
    val adapter: String = "generic-json",
    val timeout: Long = 10_000,
    /** Path of the content's load/refresh time. */
    val at: String? = null,
    /** Path of the model commit the service serves. */
    val commit: String? = null,
    /**
     * A regular expression with one group that takes the commit out of the value at [commit], for a
     * service that reports it inside a longer version string (`stock-v1|<sha>:<hash>`). No match is
     * no commit.
     */
    val commitPattern: String? = null,
    /**
     * Path whose value says whether the content is fine: [okValues] are the accepted values (any if
     * empty), [failValues] the rejected ones (a boot fixture's version, a `degraded: true`).
     */
    val ok: String? = null,
    val okValues: List<String> = emptyList(),
    val failValues: List<String> = emptyList(),
    /** Path of a sentence to show as it is. */
    val note: String? = null,
    /** A fixed sentence shown when the content is not fine (rather than the service's own free text). */
    val failNote: String? = null,
    val summary: List<FieldConfig> = emptyList(),
    /** Path of an object of booleans, one sub-row each (`{"metadata": true}`). */
    val children: String? = null,
    /** Extra request headers (e.g. a Loki tenant). */
    val headers: Map<String, String> = emptyMap(),
    /** Parts checked on their own, one sub-row each (the NLP engines behind the NLP front). */
    val probes: List<ProbeConfig> = emptyList(),
)

/**
 * One sub-row checked by the health service itself: `http://…` is ready on a 2xx, not ready on any
 * other answer, down on none; `tcp://host:port` is ready when it connects (a part with no HTTP
 * readiness path the health service may rely on), down when it does not.
 */
data class ProbeConfig(
    val name: String,
    val url: String,
    val tech: String? = null,
    val timeout: Long = 3_000,
)

/** One summary figure: its key on the page, the path of its value, and how to format it. */
data class FieldConfig(
    val k: String,
    val path: String,
    val kind: String = "text",
)

object ConfigLoader {
    /**
     * The `health-check-service` configuration. When it carries a `roster` block, the roster's
     * `technologies` REPLACE the bundled ones rather than merging into them: it is the per-cluster
     * roster a deployment supplies (each estate runs a different set of services, and a roster change
     * must not need an image). A merge would keep every bundled target the estate does not run, as a
     * red tile. The chart supplies it through its config fragment (`configFragment.content`, README
     * "Per-cluster roster"); anything else in the roster (`prometheus`) replaces the bundled value too.
     */
    fun load(config: Config = ConfigFactory.load()): HealthConfig {
        val healthConfig = config.getConfig("health-check-service")
        if (!healthConfig.hasPath("roster")) return parseHealthConfig(healthConfig)
        val roster = healthConfig.getConfig("roster")
        require(roster.hasPath("technologies")) { "health-check-service.roster has no `technologies` block" }
        return parseHealthConfig(roster.withFallback(healthConfig.withoutPath("technologies").withoutPath("roster")))
    }

    private fun parseHealthConfig(config: Config): HealthConfig {
        val prometheusConfig =
            if (config.hasPath("prometheus")) {
                val prom = config.getConfig("prometheus")
                PrometheusConfig(
                    url = prom.getString("url"),
                    timeout = prom.getLong("timeout"),
                    queryInterval = prom.getLong("queryInterval"),
                )
            } else {
                PrometheusConfig()
            }
        val technologies =
            if (config.hasPath("technologies")) {
                val techConfig = config.getConfig("technologies")
                val keys = techConfig.root().keys
                keys.associateWith { key ->
                    val tc = techConfig.getConfig(key)
                    TechnologyConfig(
                        type = tc.getString("type"),
                        url = tc.getStringOrNull("url"),
                        healthEndpoint = tc.getStringOrNull("healthEndpoint"),
                        job = tc.getStringOrNull("job"),
                        prometheusUrl = tc.getStringOrNull("prometheusUrl"),
                        timeout = if (tc.hasPath("timeout")) tc.getLong("timeout") else 5000,
                        host = tc.getStringOrNull("host"),
                        port = if (tc.hasPath("port")) tc.getInt("port") else null,
                        status = if (tc.hasPath("status")) parseStatus(tc.getConfig("status")) else null,
                    )
                }
            } else {
                emptyMap()
            }
        return HealthConfig(
            prometheus = prometheusConfig,
            technologies = technologies,
        )
    }

    private fun parseStatus(sc: Config): StatusConfig =
        StatusConfig(
            url = sc.getString("url"),
            adapter = sc.getStringOrNull("adapter") ?: "generic-json",
            timeout = if (sc.hasPath("timeout")) sc.getLong("timeout") else 10_000,
            at = sc.getStringOrNull("at"),
            commit = sc.getStringOrNull("commit"),
            commitPattern = sc.getStringOrNull("commitPattern"),
            ok = sc.getStringOrNull("ok"),
            okValues = if (sc.hasPath("okValues")) sc.getStringList("okValues") else emptyList(),
            failValues = if (sc.hasPath("failValues")) sc.getStringList("failValues") else emptyList(),
            note = sc.getStringOrNull("note"),
            failNote = sc.getStringOrNull("failNote"),
            children = sc.getStringOrNull("children"),
            summary =
                if (sc.hasPath("summary")) {
                    sc.getConfigList("summary").map { f ->
                        FieldConfig(
                            k = f.getString("k"),
                            path = f.getString("path"),
                            kind = f.getStringOrNull("kind") ?: "text",
                        )
                    }
                } else {
                    emptyList()
                },
            headers =
                if (sc.hasPath("headers")) {
                    sc.getObject("headers").unwrapped().mapValues { (_, value) -> value.toString() }
                } else {
                    emptyMap()
                },
            probes =
                if (sc.hasPath("probes")) {
                    sc.getConfigList("probes").map { p ->
                        ProbeConfig(
                            name = p.getString("name"),
                            url = p.getString("url"),
                            tech = p.getStringOrNull("tech"),
                            timeout = if (p.hasPath("timeout")) p.getLong("timeout") else 3_000,
                        )
                    }
                } else {
                    emptyList()
                },
        )

    private fun Config.getStringOrNull(path: String): String? = if (hasPath(path)) getString(path) else null
}
