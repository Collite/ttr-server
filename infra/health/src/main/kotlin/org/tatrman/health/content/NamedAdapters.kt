// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.content

import org.tatrman.health.config.StatusConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

// The adapters whose figures need more than a path. Each starts from GenericJson (so the configured
// paths still apply) and adds what it computes.

/**
 * veles `GET /status`: one row per model source (`sources[]` — an estate may serve two repositories),
 * each with the commit it loaded, when, and its object count; a source that failed its last load is
 * red, one serving a fallback amber in the note. The content's commit is the FIRST source's (the one
 * the summary line compares with translate and validate); its time is the latest successful load.
 */
object VelesAdapter : StatusAdapter {
    override fun read(
        body: JsonElement,
        config: StatusConfig,
    ): Content {
        val base = GenericJson.read(body, config)
        val sources = (body.at("sources") as? JsonArray).orEmpty()
        if (sources.isEmpty()) return base
        val rows = sources.mapNotNull { source(it) }
        val failed = rows.filter { it.ok == false }.map { it.name }
        val fallback = sources.filter { it.text("serving_fallback") == "true" }.mapNotNull { it.text("id") }
        val loads =
            sources.mapNotNull { source ->
                isoTime(source.at("last_success_at"))?.let { runCatching { Instant.parse(it) }.getOrNull() }
            }
        val note =
            listOfNotNull(
                failed.takeIf { it.isNotEmpty() }?.let { "${it.joinToString(", ")}: the last load failed" },
                fallback.takeIf { it.isNotEmpty() }?.let { "${it.joinToString(", ")}: serving the fallback model" },
            ).joinToString(" · ").takeIf { it.isNotBlank() }
        return base.copy(
            ok = base.ok && failed.isEmpty(),
            at = base.at ?: loads.maxOrNull()?.toString(),
            commit = base.commit ?: sources.first().text("version")?.takeIf { it.isNotBlank() },
            note = base.note ?: note,
            sources = rows,
        )
    }

    private fun source(node: JsonElement): ContentSource? {
        val id = node.text("id") ?: return null
        val summary =
            listOfNotNull(
                node.text("version")?.takeIf { it.isNotBlank() }?.let { ContentPair("commit", it, "commit") },
                isoTime(node.at("last_success_at"))?.let { ContentPair("loaded", it, "time") },
                (
                    node.at(
                        "object_count",
                    ) as? JsonPrimitive
                )?.longOrNull?.let { ContentPair("objects", it.toString(), "count") },
            )
        // veles' own `source` line names the repository, branch and sub-path; `last_error` is free text
        // from a git client (hosts, credentials), so a failed source gets a fixed sentence.
        val where = node.text("source")?.removePrefix("git ")?.takeIf { it.isNotBlank() }
        return when {
            node.text(
                "loaded",
            ) != "true" -> ContentSource(id, ok = false, summary = summary, note = "not loaded: see the veles log")
            (node.at("consecutive_failures") as? JsonPrimitive)?.longOrNull?.let { it > 0 } == true ->
                ContentSource(id, ok = false, summary = summary, note = "the last refresh failed: see the veles log")
            else -> ContentSource(id, ok = true, summary = summary, note = where)
        }
    }
}

/**
 * nlp `GET /version`: what the NLP front can do, from its capability list — the languages, the engines
 * behind them and how many operations. The engines' own pods are the configured `probes`.
 */
object NlpAdapter : StatusAdapter {
    override fun read(
        body: JsonElement,
        config: StatusConfig,
    ): Content {
        val base = GenericJson.read(body, config)
        val capabilities = body.at("capabilities") as? JsonArray ?: return base
        val languages = capabilities.mapNotNull { it.text("language") }.distinct()
        val engines = capabilities.mapNotNull { it.text("engine") }.distinct()
        val summary =
            listOfNotNull(
                languages.takeIf { it.isNotEmpty() }?.let { ContentPair("languages", it.joinToString(" · "), "list") },
                engines.takeIf { it.isNotEmpty() }?.let { ContentPair("engines", it.joinToString(" · "), "list") },
                ContentPair("capabilities", capabilities.size.toString(), "count"),
            )
        return base.copy(ok = base.ok && capabilities.isNotEmpty(), summary = summary + base.summary)
    }
}

/** Prometheus `GET /api/v1/targets?state=active`: targets up of all, and which jobs are down. */
object PrometheusTargetsAdapter : StatusAdapter {
    override fun read(
        body: JsonElement,
        config: StatusConfig,
    ): Content {
        val targets = body.at("data.activeTargets") as? JsonArray ?: return Content.failed("no data.activeTargets")
        val down = targets.filter { it.text("health") != "up" }
        val downJobs = down.mapNotNull { it.text("labels.job") }.distinct()
        return Content(
            ok = down.isEmpty(),
            summary =
                listOf(
                    ContentPair("targetsUp", (targets.size - down.size).toString(), "count"),
                    ContentPair("targets", targets.size.toString(), "count"),
                ),
            note =
                if (downJobs.isEmpty()) {
                    null
                } else {
                    "down: " + downJobs.take(3).joinToString(", ") +
                        more(downJobs.size, 3)
                },
        )
    }
}

/**
 * Kubernetes `GET /apis/external-secrets.io/v1/externalsecrets` (every namespace): how many are synced
 * (condition Ready = True), the latest and the oldest refresh, and which are not synced. Status only:
 * the Secrets themselves are never read.
 */
object ExternalSecretsAdapter : StatusAdapter {
    override fun read(
        body: JsonElement,
        config: StatusConfig,
    ): Content {
        val items = body.at("items") as? JsonArray ?: return Content.failed("no items")
        val notSynced = items.filterNot { ready(it) }
        val refreshes =
            items.mapNotNull { item ->
                isoTime(item.at("status.refreshTime"))?.let { runCatching { Instant.parse(it) }.getOrNull() }
            }
        val names = notSynced.mapNotNull { name(it) }
        return Content(
            ok = notSynced.isEmpty(),
            at = refreshes.maxOrNull()?.toString(),
            summary =
                listOfNotNull(
                    ContentPair("synced", (items.size - notSynced.size).toString(), "count"),
                    ContentPair("secrets", items.size.toString(), "count"),
                    refreshes.minOrNull()?.let { ContentPair("oldestRefresh", it.toString(), "time") },
                ),
            note =
                if (names.isEmpty()) {
                    null
                } else {
                    "not synced: " + names.take(3).joinToString(", ") +
                        more(names.size, 3)
                },
        )
    }
}

/**
 * Kubernetes `GET /apis/cert-manager.io/v1/certificates` (every namespace): how many are ready, the
 * nearest expiry and which certificate it is, and which are not ready. A certificate within
 * [WARN_DAYS] of its expiry makes the content not ok even while it is ready: cert-manager renews well
 * before that, so one that close is one whose renewal is not happening. Status only: the keys and the
 * Secrets are never read.
 */
object CertificatesAdapter : StatusAdapter {
    const val WARN_DAYS = 14L

    override fun read(
        body: JsonElement,
        config: StatusConfig,
    ): Content = read(body, Instant.now())

    fun read(
        body: JsonElement,
        now: Instant,
    ): Content {
        val items = body.at("items") as? JsonArray ?: return Content.failed("no items")
        val notReady = items.filterNot { ready(it) }
        val expiries =
            items.mapNotNull { item ->
                val notAfter =
                    isoTime(
                        item.at("status.notAfter"),
                    )?.let { runCatching { Instant.parse(it) }.getOrNull() }
                notAfter?.let { item to it }
            }
        val nearest = expiries.minByOrNull { it.second }
        val soon = nearest?.second?.isBefore(now.plusSeconds(WARN_DAYS * 86_400)) == true
        val names = notReady.mapNotNull { name(it) }
        val note =
            when {
                names.isNotEmpty() -> "not ready: " + names.take(3).joinToString(", ") + more(names.size, 3)
                soon -> "${nearest?.first?.let { name(it) }} expires within $WARN_DAYS days and has not been renewed"
                else -> null
            }
        return Content(
            ok = notReady.isEmpty() && !soon,
            summary =
                listOfNotNull(
                    ContentPair("ready", (items.size - notReady.size).toString(), "count"),
                    ContentPair("certificates", items.size.toString(), "count"),
                    nearest?.let { ContentPair("nearestExpiry", it.second.toString(), "date") },
                    nearest?.first?.let { name(it) }?.let { ContentPair("expiresFirst", it, "text") },
                ),
            note = note,
        )
    }
}

/** A Kubernetes object whose condition `Ready` is `True`. */
private fun ready(item: JsonElement): Boolean =
    (item.at("status.conditions") as? JsonArray).orEmpty().any {
        it.text("type") == "Ready" && it.text("status") == "True"
    }

/** `namespace/name` of a Kubernetes object. */
private fun name(item: JsonElement): String? =
    item.text("metadata.name")?.let { "${item.text("metadata.namespace")}/$it" }

private fun more(
    total: Int,
    shown: Int,
): String = if (total > shown) " (+${total - shown})" else ""
