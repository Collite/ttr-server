// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.content

import org.tatrman.health.config.FieldConfig
import org.tatrman.health.config.StatusConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Turns one service's status body into [Content]. Pure: the fetching is [ContentService]'s. */
fun interface StatusAdapter {
    fun read(
        body: JsonElement,
        config: StatusConfig,
    ): Content

    /** Whether this technology's content names the model commit — the commit summary expects to hear from it. */
    fun reportsCommit(config: StatusConfig): Boolean = config.commit != null
}

/**
 * The adapter most services need: every figure is a path in the body, named in the configuration
 * (`at`, `commit` + `commitPattern`, `ok` + `okValues` / `failValues`, `note`, `failNote`, `summary`,
 * `children`). The named adapters start from it.
 */
object GenericJson : StatusAdapter {
    const val NO_COMMIT = "names no model commit: a boot fixture or no model loaded"

    override fun read(
        body: JsonElement,
        config: StatusConfig,
    ): Content {
        val fine = okOf(body, config)
        val commit = config.commit?.let { body.text(it) }?.let { commitOf(it, config.commitPattern) }
        // A service configured to name the model commit that names none serves no loaded model (its boot
        // fixture's version matches no pattern) — whatever its own ready flag says.
        val noCommit = config.commit != null && commit == null
        return Content(
            ok = fine && !noCommit,
            at = config.at?.let { isoTime(body.at(it)) },
            commit = commit,
            summary = config.summary.mapNotNull { pair(body, it) },
            note =
                when {
                    !fine -> config.failNote
                    noCommit -> NO_COMMIT
                    else -> null
                } ?: config.note?.let { body.text(it) }?.takeIf { it.isNotBlank() },
            children = config.children?.let { childrenOf(body.at(it)) },
        )
    }

    private fun okOf(
        body: JsonElement,
        config: StatusConfig,
    ): Boolean {
        val path = config.ok ?: return true
        val value = body.text(path) ?: return false
        if (config.failValues.any { it.equals(value, ignoreCase = true) }) return false
        return config.okValues.isEmpty() || config.okValues.any { it.equals(value, ignoreCase = true) }
    }

    /** `{"veles": true, "metadata": false}` → one sub-row each, ready or down. */
    private fun childrenOf(node: JsonElement?): List<ContentChild>? =
        (node as? JsonObject)?.mapNotNull { (name, up) ->
            (up as? JsonPrimitive)?.booleanOrNull?.let {
                ContentChild(
                    name = name,
                    state = if (it) "ready" else "down",
                )
            }
        }
}

/**
 * The commit in a reported value: the value itself, or [pattern]'s first group when one is configured
 * (`stock-v1|3d54d5e8…:b33a900b` → `3d54d5e8…`). A blank value, or one the pattern does not match (a
 * boot fixture's version), is no commit.
 */
fun commitOf(
    value: String,
    pattern: String?,
): String? {
    if (value.isBlank()) return null
    if (pattern == null) return value
    return Regex(pattern)
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
}

/** One configured figure, or nothing when the body does not carry it or carries it empty. */
fun pair(
    body: JsonElement,
    field: FieldConfig,
): ContentPair? {
    val node = body.at(field.path) ?: return null
    val value =
        when (field.kind) {
            "time" -> isoTime(node)
            // An array of names, shown joined; an empty one is no figure.
            "list" ->
                (node as? JsonArray)
                    ?.mapNotNull {
                        (it as? JsonPrimitive)?.content
                    }?.takeIf { it.isNotEmpty() }
                    ?.joinToString(" · ")
            else -> (node as? JsonPrimitive)?.content ?: node.toString()
        } ?: return null
    // An empty string is no figure: the page would show the label alone (query's
    // `cache.current_model_version` before its first plan).
    if (value.isBlank()) return null
    return ContentPair(field.k, value, field.kind)
}

object Adapters {
    val byName: Map<String, StatusAdapter> =
        mapOf(
            "generic-json" to GenericJson,
            "veles" to VelesAdapter,
            "nlp" to NlpAdapter,
            "prometheus-targets" to PrometheusTargetsAdapter,
            "k8s-externalsecrets" to ExternalSecretsAdapter,
            "k8s-certificates" to CertificatesAdapter,
        )
}
