// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.apply.DryRunAction
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.NoSuchRun
import org.tatrman.ttr.metadata.model.AutomationAction
import java.util.UUID

@Serializable
data class DigestGroup(
    val who: String,
    val entries: Int,
    val rows: Int,
    val text: String,
    val html: String,
)

@Serializable
data class DigestResponse(
    val run: String,
    val action: String,
    val language: String,
    val groups: List<DigestGroup>,
)

/**
 * `GET /automation/runs/{id}/digest?group=<who>` (D-148): the run's `notify` entries — the engine's send task —
 * grouped by `who`, rendered as text and HTML in the requested language. Rendered, never sent (D-54): the engine's send
 * task delivers it. Reads the run's own protocol (`actions` preview entries), owner-scoped like every run read.
 */
class Digests(
    private val ledger: Ledger,
    private val served: () -> Bundle?,
) {
    suspend fun digest(
        id: UUID,
        caller: Caller,
        group: String?,
        action: String,
        language: String,
    ): DigestResponse {
        if (language !in LANGUAGES) throw AutomationException(ErrorCode.BAD_REQUEST, "language is one of $LANGUAGES")
        val document =
            withContext(Dispatchers.IO) {
                try {
                    ledger.readProtocol(id, caller.sub)
                } catch (_: NoSuchRun) {
                    throw AutomationException(ErrorCode.IDENTITY, "the run is not available to this caller")
                }
            }
        val entries =
            document.sections["actions"]
                .orEmpty()
                .mapNotNull { it.payload as? JsonObject }
                .filter { it["event"] == JsonPrimitive("preview") && it["action"] == JsonPrimitive(action) }
                .map { Json.decodeFromJsonElement<DryRunAction>(it) }
        val definition = served()?.recipes?.values?.firstNotNullOfOrNull { it.coverage.actions[action] }
        val title = label(definition, action, language)
        val groups =
            entries
                .groupBy { (it.arguments["who"] as? JsonPrimitive)?.contentOrNull ?: "" }
                .filterKeys { group == null || it == group }
                .toSortedMap()
                .map { (who, list) -> render(id, title, who, list, language) }
        if (group != null && groups.isEmpty()) {
            throw AutomationException(ErrorCode.BAD_REQUEST, "the run has no $action entries for $group")
        }
        return DigestResponse(id.toString(), action, language, groups)
    }

    private fun render(
        run: UUID,
        title: String,
        who: String,
        entries: List<DryRunAction>,
        language: String,
    ): DigestGroup {
        val lines = mutableListOf<String>()
        val html = StringBuilder()
        val heading = "$title — $who"
        lines += heading
        lines += "${words(language).run} $run"
        html.append("<section class=\"digest\"><h2>").append(escape(heading)).append("</h2>")
        html.append("<p>").append(escape("${words(language).run} $run")).append("</p><ul>")
        var rows = 0
        for (entry in entries) {
            val key =
                entry.key.entries.joinToString(
                    ", ",
                ) { (k, v) -> "$k ${(v as? JsonPrimitive)?.contentOrNull ?: v}" }
            val detail = entry.arguments["detail"] as? JsonObject
            val count = (detail?.get("count") as? JsonPrimitive)?.intOrNull ?: 0
            val truncated = (detail?.get("truncated") as? JsonPrimitive)?.contentOrNull == "true"
            rows += count
            val refs =
                (detail?.get("refs") as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.get("ref")?.let { r -> (r as? JsonPrimitive)?.contentOrNull } }
                    .orEmpty()
            val counted = if (detail == null) "" else " (${words(language).rows(count, truncated)})"
            val line = "- $key · ${entry.route}" + (entry.would?.let { ": $it" } ?: "") + counted
            lines += line
            html
                .append("<li><strong>")
                .append(escape(key))
                .append("</strong> · ")
                .append(escape(entry.route))
            entry.would?.let { html.append(": ").append(escape(it)) }
            if (detail != null) html.append(" (").append(escape(words(language).rows(count, truncated))).append(")")
            refs.forEach {
                html
                    .append(
                        " <a href=\"",
                    ).append(escape(it))
                    .append("\">")
                    .append(escape(words(language).detail))
                    .append("</a>")
            }
            html.append("</li>")
        }
        html.append("</ul></section>")
        return DigestGroup(who, entries.size, rows, lines.joinToString("\n"), html.toString())
    }

    private fun label(
        action: AutomationAction?,
        name: String,
        language: String,
    ): String =
        action
            ?.label
            ?.byLanguage
            ?.let { it[language] ?: it["en"] }
            ?.takeIf { it.isNotBlank() }
            ?: name

    private class Words(
        val run: String,
        val detail: String,
        val rows: (Int, Boolean) -> String,
    )

    private fun words(language: String): Words =
        if (language == "cs") {
            Words("Běh", "detail") { n, cut -> "$n řádků" + if (cut) ", zkráceno" else "" }
        } else {
            Words("Run", "detail") { n, cut -> "$n rows" + if (cut) ", truncated" else "" }
        }

    private fun escape(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    companion object {
        val LANGUAGES = setOf("en", "cs")
    }
}
